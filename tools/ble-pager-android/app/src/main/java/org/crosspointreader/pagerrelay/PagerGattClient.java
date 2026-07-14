package org.crosspointreader.pagerrelay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/** One bounded, latest-message-wins GATT delivery pipeline. */
final class PagerGattClient {
    interface StatusCallback {
        void onStatus(String status, long countdownAtMs, EventLogCategory category,
                      boolean sendRetryActive, boolean policyRetryActive, boolean policyStatus);
    }

    private static final long SCAN_TIMEOUT_MS = 12_000;
    private static final long MAILBOX_SCAN_LEAD_MS = 10_000;
    private static final long MAILBOX_MAX_PENDING_MS = 75L * 60L * 1000L;
    private static final long BEAT_SCAN_INTERVAL_MS = 30_000;
    private static final long BEAT_BUSY_RETRY_MS = 3_000;
    private static final long POLICY_SCAN_RETRY_MS = 1_000;
    private static final long UNKNOWN_SCHEDULE_RETRY_MS = 30_000;
    private static final UUID SERVICE_UUID = UUID.fromString(PagerProtocol.SERVICE_UUID);
    private static final UUID PAYLOAD_UUID = UUID.fromString(PagerProtocol.PAYLOAD_UUID);
    private static final UUID STATUS_UUID = UUID.fromString(PagerProtocol.STATUS_UUID);

    private final Context context;
    private final StatusCallback statusCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattService pagerService;
    private boolean scanning;
    private boolean keepConnected;
    private Operation currentOperation;
    private String currentPayload;
    private String queuedPayload;
    private String lastAcknowledgedPayload;
    private boolean readingStatusForSend;
    private boolean mailboxScheduleKnown;
    private boolean mailboxConfigured;
    private boolean mailboxAttemptActive;
    private long mailboxIntervalMs;
    private long mailboxWindowMs;
    private long lastMailboxWindowSeenAtMs;
    private long nextMailboxWindowAtMs;
    private long mailboxPayloadQueuedAtMs;
    private String mailboxPayload;
    private boolean policyReadPending;
    private boolean policyAttemptActive;
    private long policyReadQueuedAtMs;
    private boolean beatEnabled;
    private boolean beatAttemptActive;

    PagerGattClient(Context context, StatusCallback statusCallback) {
        this.context = context.getApplicationContext();
        this.statusCallback = statusCallback;
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        RelayPreferences.setSendRetryActive(this.context, false);
        RelayPreferences.setPolicyRetryActive(this.context, false);
        restoreMailboxSchedule();
    }

    void setKeepConnected(boolean enabled) {
        keepConnected = enabled;
        if (enabled) {
            cancelMailboxAttempt();
            if (mailboxPayload != null) {
                queuedPayload = mailboxPayload;
                mailboxPayload = null;
                mailboxPayloadQueuedAtMs = 0L;
            }
        }
        if (!enabled && isHoldingLink()) {
            closeConnection();
            status("Connection mode: connect per message.");
        }
    }

    void holdConnection() {
        if (!keepConnected) {
            status("Connection mode: connect per message.");
            return;
        }
        if (isHoldingLink()) {
            status("Pager link is held for battery testing.");
            return;
        }
        if (isBusy()) {
            status("Pager is busy. Keep-connected mode will use the next idle link.");
            return;
        }
        begin(Operation.HOLD_LINK, null);
    }

    void send(String payload) {
        if (!keepConnected) {
            queueForMailbox(payload);
            return;
        }
        if (isHoldingLink()) {
            beginConnected(Operation.SEND, payload);
            return;
        }
        if (isBusy()) {
            queuedPayload = payload;
            status("Queued the latest pager update.");
            return;
        }
        begin(Operation.SEND, payload);
    }

    void readStatus() {
        policyReadPending = true;
        policyReadQueuedAtMs = SystemClock.elapsedRealtime();
        if (isHoldingLink()) {
            policyAttemptActive = true;
            beginConnected(Operation.READ_STATUS, null);
            return;
        }
        if (isBusy()) {
            schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                    "Pager is busy; policy scan will continue.");
            return;
        }
        schedulePolicyAttempt(0L, "Policy refresh started; scanning until Xteink appears.");
    }

    void cancelSend() {
        boolean cancelActiveOperation = currentOperation == Operation.SEND;
        cancelMailboxAttempt();
        mailboxPayload = null;
        mailboxPayloadQueuedAtMs = 0L;
        mailboxAttemptActive = false;
        queuedPayload = null;
        if (cancelActiveOperation) {
            closeConnection();
            currentOperation = null;
            currentPayload = null;
            readingStatusForSend = false;
        }
        status("Pager update retry stopped.");
    }

    void cancelPolicyRead() {
        boolean cancelActiveOperation = currentOperation == Operation.READ_STATUS;
        handler.removeCallbacks(policyAttemptRunnable);
        policyReadPending = false;
        policyReadQueuedAtMs = 0L;
        policyAttemptActive = false;
        if (cancelActiveOperation) {
            closeConnection();
            currentOperation = null;
            readingStatusForSend = false;
        }
        policyStatus("Policy refresh retry stopped.");
    }

    void startBeat() {
        if (beatEnabled) {
            return;
        }
        beatEnabled = true;
        scheduleBeat(0L, "Beat mode started.");
    }

    void stopBeat() {
        beatEnabled = false;
        handler.removeCallbacks(beatRunnable);
        if (beatAttemptActive) {
            closeConnection();
            currentOperation = null;
            currentPayload = null;
            readingStatusForSend = false;
            beatAttemptActive = false;
        }
        beatStatus("Beat mode stopped.");
    }

    private void scheduleBeat(long delayMs, String message) {
        if (!beatEnabled) {
            return;
        }
        handler.removeCallbacks(beatRunnable);
        handler.postDelayed(beatRunnable, delayMs);
        beatStatus(delayMs <= 0L ? message + " Checking now." : message + " Next check " + mailboxDelayText(delayMs));
    }

    private final Runnable beatRunnable = () -> {
        if (!beatEnabled) {
            return;
        }
        if (isBusy()) {
            scheduleBeat(BEAT_BUSY_RETRY_MS, "Beat waits for the current Pager operation.");
            return;
        }
        beatAttemptActive = true;
        begin(Operation.BEAT_STATUS, null);
    };

    private void scheduleNextBeat() {
        if (!beatEnabled) {
            return;
        }
        long delayMs = mailboxScheduleKnown ? mailboxScanDelayMs() : BEAT_SCAN_INTERVAL_MS;
        scheduleBeat(delayMs, "Beat mode armed.");
    }

    private void queueForMailbox(String payload) {
        mailboxPayload = payload;
        mailboxPayloadQueuedAtMs = SystemClock.elapsedRealtime();
        if (isBusy()) {
            status("Queued latest pager update.");
            return;
        }
        long delayMs = mailboxScheduleKnown ? mailboxScanDelayMs() : 0L;
        scheduleMailboxAttempt(delayMs, mailboxScheduleKnown
                ? "Queued latest pager update for the mailbox window."
                : "Queued latest pager update; mailbox timing is not known yet.");
    }

    private long mailboxScanDelayMs() {
        long now = SystemClock.elapsedRealtime();
        if (nextMailboxWindowAtMs > 0L && mailboxIntervalMs > 0L
                && now >= nextMailboxWindowAtMs + mailboxWindowMs) {
            long elapsedAfterWindowMs = now - (nextMailboxWindowAtMs + mailboxWindowMs);
            long elapsedIntervals = elapsedAfterWindowMs / mailboxIntervalMs + 1L;
            nextMailboxWindowAtMs += elapsedIntervals * mailboxIntervalMs;
            persistMailboxSchedule();
        }
        long scanStartAt = nextMailboxWindowAtMs - MAILBOX_SCAN_LEAD_MS;
        return Math.max(0L, scanStartAt - now);
    }

    private void scheduleMailboxAttempt(long delayMs, String message) {
        if (mailboxPayload == null) {
            return;
        }
        cancelMailboxAttempt();
        handler.postDelayed(mailboxAttemptRunnable, delayMs);
        countdown(message, SystemClock.elapsedRealtime() + delayMs);
    }

    private final Runnable mailboxAttemptRunnable = () -> {
        if (mailboxPayload == null) {
            return;
        }
        if (hasMailboxPayloadExpired()) {
            mailboxPayload = null;
            mailboxPayloadQueuedAtMs = 0L;
            status("Mailbox delivery expired before Xteink was reachable.");
            return;
        }
        if (isBusy()) {
            scheduleMailboxAttempt(1000L, "Pager is busy; keeping latest update for mailbox delivery.");
            return;
        }
        mailboxAttemptActive = true;
        begin(Operation.SEND, mailboxPayload);
    };

    private void schedulePolicyAttempt(long delayMs, String message) {
        if (!policyReadPending) {
            return;
        }
        handler.removeCallbacks(policyAttemptRunnable);
        handler.postDelayed(policyAttemptRunnable, delayMs);
        policyCountdown(message, SystemClock.elapsedRealtime() + delayMs);
    }

    private final Runnable policyAttemptRunnable = () -> {
        if (!policyReadPending) {
            return;
        }
        if (hasPolicyReadExpired()) {
            policyReadPending = false;
            policyReadQueuedAtMs = 0L;
            policyStatus("Policy refresh retry expired before Xteink was reachable.");
            return;
        }
        if (isBusy()) {
            schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                    "Pager is busy; policy scan will continue.");
            return;
        }
        policyAttemptActive = true;
        begin(Operation.READ_STATUS, null);
    };

    private boolean hasMailboxPayloadExpired() {
        return mailboxPayloadQueuedAtMs > 0L
                && SystemClock.elapsedRealtime() - mailboxPayloadQueuedAtMs > MAILBOX_MAX_PENDING_MS;
    }

    private boolean hasPolicyReadExpired() {
        return policyReadQueuedAtMs > 0L
                && SystemClock.elapsedRealtime() - policyReadQueuedAtMs > MAILBOX_MAX_PENDING_MS;
    }

    private void cancelMailboxAttempt() {
        handler.removeCallbacks(mailboxAttemptRunnable);
    }

    private String mailboxDelayText(long delayMs) {
        if (delayMs <= 0L) {
            return "Scanning now.";
        }
        long seconds = Math.max(1L, Math.round(delayMs / 1000.0));
        if (seconds < 60L) {
            return "Scanning in " + seconds + " s.";
        }
        long minutes = seconds / 60L;
        long remainderSeconds = seconds % 60L;
        return remainderSeconds == 0L
                ? "Scanning in " + minutes + " min."
                : "Scanning in " + minutes + " min " + remainderSeconds + " s.";
    }

    private boolean retryMailboxAfterMiss(String reason) {
        if (!mailboxAttemptActive || mailboxPayload == null) {
            return false;
        }
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        if (hasMailboxPayloadExpired()) {
            mailboxPayload = null;
            mailboxPayloadQueuedAtMs = 0L;
            status(reason + " Mailbox delivery expired.");
            return true;
        }
        scheduleMailboxAttempt(nextMailboxRetryDelayMs(), reason + " Keeping latest update queued.");
        return true;
    }

    private boolean retryPolicyAfterMiss(String reason) {
        if (!policyAttemptActive || !policyReadPending) {
            return false;
        }
        closeConnection();
        currentOperation = null;
        readingStatusForSend = false;
        policyAttemptActive = false;
        if (hasPolicyReadExpired()) {
            policyReadPending = false;
            policyReadQueuedAtMs = 0L;
            policyStatus(reason + " Policy refresh retry expired.");
            return true;
        }
        schedulePolicyAttempt(POLICY_SCAN_RETRY_MS, reason + " Continuing policy scan.");
        return true;
    }

    private boolean retryBeatAfterMiss(String reason) {
        if (!beatAttemptActive) {
            return false;
        }
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        beatAttemptActive = false;
        beatStatus(reason);
        scheduleNextBeat();
        return true;
    }

    private void rememberMailboxStatus(PagerProtocol.PagerStatus status) {
        mailboxConfigured = status.configuredMailbox;
        mailboxIntervalMs = status.intervalMs;
        mailboxWindowMs = status.windowMs;
        if (!status.canScheduleMailbox()) {
            mailboxScheduleKnown = false;
            lastMailboxWindowSeenAtMs = 0L;
            nextMailboxWindowAtMs = 0L;
            RelayPreferences.clearMailboxSchedule(context);
            cancelMailboxAttempt();
            return;
        }
        mailboxScheduleKnown = true;
        long now = SystemClock.elapsedRealtime();
        if (status.nextWindowMs > 0L) {
            nextMailboxWindowAtMs = now + status.nextWindowMs;
        } else {
            lastMailboxWindowSeenAtMs = now;
            nextMailboxWindowAtMs = now;
        }
        persistMailboxSchedule();
    }

    private void updateNextMailboxWindowFromNow() {
        if (!mailboxScheduleKnown || mailboxIntervalMs <= 0L) {
            return;
        }
        lastMailboxWindowSeenAtMs = SystemClock.elapsedRealtime();
        nextMailboxWindowAtMs = lastMailboxWindowSeenAtMs + mailboxIntervalMs;
        persistMailboxSchedule();
    }

    private void restoreMailboxSchedule() {
        long savedIntervalMs = RelayPreferences.mailboxIntervalMs(context);
        long savedWindowMs = RelayPreferences.mailboxWindowMs(context);
        long savedNextWindowWallClockMs = RelayPreferences.mailboxNextWindowWallClockMs(context);
        if (savedIntervalMs <= 0L || savedWindowMs <= 0L || savedNextWindowWallClockMs <= 0L) {
            return;
        }
        mailboxConfigured = true;
        mailboxScheduleKnown = true;
        mailboxIntervalMs = savedIntervalMs;
        mailboxWindowMs = savedWindowMs;
        long nowWallClockMs = System.currentTimeMillis();
        if (nowWallClockMs >= savedNextWindowWallClockMs + savedWindowMs) {
            long elapsedAfterWindowMs = nowWallClockMs - (savedNextWindowWallClockMs + savedWindowMs);
            long elapsedIntervals = elapsedAfterWindowMs / savedIntervalMs + 1L;
            savedNextWindowWallClockMs += elapsedIntervals * savedIntervalMs;
        }
        long delayMs = savedNextWindowWallClockMs - nowWallClockMs;
        nextMailboxWindowAtMs = SystemClock.elapsedRealtime()
                + (delayMs >= 0L && delayMs <= savedIntervalMs ? delayMs : 0L);
        persistMailboxSchedule();
    }

    private void persistMailboxSchedule() {
        if (!mailboxScheduleKnown || mailboxIntervalMs <= 0L || mailboxWindowMs <= 0L
                || nextMailboxWindowAtMs <= 0L) {
            return;
        }
        long nextWindowWallClockMs = System.currentTimeMillis()
                + nextMailboxWindowAtMs - SystemClock.elapsedRealtime();
        RelayPreferences.setMailboxSchedule(context, mailboxIntervalMs, mailboxWindowMs, nextWindowWallClockMs);
    }

    private long nextMailboxRetryDelayMs() {
        return mailboxScheduleKnown ? mailboxScanDelayMs() : UNKNOWN_SCHEDULE_RETRY_MS;
    }

    private boolean isBusy() {
        return currentOperation != null || scanning || gatt != null;
    }

    private boolean isHoldingLink() {
        return keepConnected && currentOperation == null && gatt != null && pagerService != null;
    }

    private void begin(Operation operation, String payload) {
        currentOperation = operation;
        currentPayload = payload;
        scanAndConnect();
    }

    private void beginConnected(Operation operation, String payload) {
        currentOperation = operation;
        currentPayload = payload;
        runOperation();
    }

    @SuppressLint("MissingPermission")
    private void scanAndConnect() {
        if (adapter == null || !adapter.isEnabled()) {
            fail("Bluetooth is off.");
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            fail("BLE scanning is unavailable.");
            return;
        }
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        status("Searching for " + PagerProtocol.DEVICE_NAME + "...");
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(filter), settings, scanCallback);
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
        scanning = false;
    }

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            stopScan();
            if (retryBeatAfterMiss("Beat: Xteink not found.")) {
                return;
            }
            if (retryPolicyAfterMiss("CrossPoint Pager was not found.")) {
                return;
            }
            if (retryMailboxAfterMiss("Mailbox window was not found.")) {
                return;
            }
            fail("CrossPoint Pager was not found. Put it in Pager standby and try again.");
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) {
                return;
            }
            stopScan();
            status("Connecting to " + PagerProtocol.DEVICE_NAME + "...");
            gatt = result.getDevice().connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                fail("Could not connect to CrossPoint Pager.");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            if (retryBeatAfterMiss("Beat: BLE scan failed (" + errorCode + ").")) {
                return;
            }
            if (retryPolicyAfterMiss("BLE scan failed (" + errorCode + ").")) {
                return;
            }
            if (retryMailboxAfterMiss("BLE scan failed (" + errorCode + ").")) {
                return;
            }
            fail("BLE scan failed (" + errorCode + ").");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Pager connection failed (" + status + ").");
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                status("Discovering Pager service...");
                if (!connection.discoverServices()) {
                    fail("Could not discover Pager service.");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED && gatt != null) {
                boolean wasActive = currentOperation != null;
                closeConnection();
                finishAfterDisconnect(wasActive ? "Pager disconnected before delivery." : "Pager disconnected.");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Pager service discovery failed (" + status + ").");
                return;
            }
            pagerService = connection.getService(SERVICE_UUID);
            if (pagerService == null) {
                fail("Pager service was not found.");
                return;
            }
            runOperation();
        }

        @Override
        @SuppressWarnings("deprecation") // Android 12 and earlier use this callback signature.
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Pager write failed (" + status + ").");
                return;
            }
            boolean identical = currentPayload != null && currentPayload.equals(lastAcknowledgedPayload);
            lastAcknowledgedPayload = currentPayload;
            boolean startedMailboxHandoff = !mailboxScheduleKnown && mailboxConfigured
                    && mailboxIntervalMs > 0L && mailboxWindowMs > 0L;
            if (startedMailboxHandoff) {
                mailboxScheduleKnown = true;
                lastMailboxWindowSeenAtMs = SystemClock.elapsedRealtime();
                nextMailboxWindowAtMs = lastMailboxWindowSeenAtMs + mailboxWindowMs + mailboxIntervalMs;
                persistMailboxSchedule();
            }
            if (mailboxAttemptActive) {
                if (currentPayload != null && currentPayload.equals(mailboxPayload)) {
                    mailboxPayload = null;
                    mailboxPayloadQueuedAtMs = 0L;
                }
                mailboxAttemptActive = false;
                if (!startedMailboxHandoff) {
                    updateNextMailboxWindowFromNow();
                }
            }
            complete(identical
                    ? "Pager update sent.\nMessage identical; Xteink will not update content."
                    : "Pager update sent.");
        }

        @Override
        @SuppressWarnings("deprecation") // Android 12 and earlier use this callback signature.
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            handleStatusRead(characteristic.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            handleStatusRead(value, status);
        }
    };

    private void runOperation() {
        if (currentOperation == Operation.HOLD_LINK) {
            complete("Pager link held for battery testing.");
        } else if (currentOperation == Operation.READ_STATUS) {
            readPagerStatus(false);
        } else if (currentOperation == Operation.BEAT_STATUS) {
            readPagerStatus(false);
        } else if (currentOperation == Operation.SEND) {
            if (PagerProtocol.isValidClientToken(RelayPreferences.clientToken(context))) {
                if (!keepConnected && !mailboxScheduleKnown) {
                    readPagerStatus(true);
                } else {
                    writePayload();
                }
            } else {
                readPagerStatus(true);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void readPagerStatus(boolean forSend) {
        BluetoothGattCharacteristic characteristic = pagerService == null ? null : pagerService.getCharacteristic(STATUS_UUID);
        if (characteristic == null) {
            fail("Pager policy status is unavailable on this firmware.");
            return;
        }
        readingStatusForSend = forSend;
        status(currentOperation == Operation.BEAT_STATUS
                ? "Beat: checking Xteink..."
                : forSend ? "Reading Pager setup token..." : "Reading Pager policy...");
        if (gatt == null || !gatt.readCharacteristic(characteristic)) {
            readingStatusForSend = false;
            fail("Pager policy read could not start.");
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation") // Required for Android 12 and earlier GATT writes.
    private void writePayload() {
        BluetoothGattCharacteristic characteristic = pagerService == null ? null : pagerService.getCharacteristic(PAYLOAD_UUID);
        if (characteristic == null || currentPayload == null) {
            fail("Pager write characteristic was not found.");
            return;
        }
        String token = RelayPreferences.clientToken(context);
        if (!PagerProtocol.isValidAuthenticatedPayload(currentPayload, token)) {
            fail("Pager setup token is missing or the payload is too large. Refresh pager policy first.");
            return;
        }
        byte[] bytes = PagerProtocol.authenticatedPayload(currentPayload, token).getBytes(StandardCharsets.UTF_8);
        status("Sending pager update...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = gatt == null
                    ? BluetoothStatusCodes.ERROR_UNKNOWN
                    : gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (result != BluetoothStatusCodes.SUCCESS) {
                fail("Pager write could not start (" + result + ").");
            }
        } else {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(bytes);
            if (gatt == null || !gatt.writeCharacteristic(characteristic)) {
                fail("Pager write could not start.");
            }
        }
    }

    private void handleStatusRead(byte[] value, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            String rawStatus = new String(value, StandardCharsets.UTF_8);
            rememberMailboxStatus(PagerProtocol.parseStatus(rawStatus));
            String setupToken = PagerProtocol.enrollmentToken(rawStatus);
            if (!PagerProtocol.isEnrolled(rawStatus) && PagerProtocol.isValidClientToken(setupToken)) {
                RelayPreferences.setClientToken(context, setupToken);
            }
            if (currentOperation == Operation.BEAT_STATUS) {
                beatAttemptActive = false;
                complete("Beat: Xteink online\n" + PagerProtocol.formatStatus(rawStatus));
                return;
            }
            if (readingStatusForSend) {
                readingStatusForSend = false;
                if (PagerProtocol.isValidClientToken(RelayPreferences.clientToken(context))) {
                    writePayload();
                } else if (PagerProtocol.isEnrolled(rawStatus)) {
                    fail("Xteink is already enrolled. Reset Enrolled Device on Xteink, then refresh pager policy.");
                } else {
                    fail("Pager setup token was unavailable. Re-enter Pager standby and refresh policy.");
                }
                return;
            }
            String note = !PagerProtocol.isEnrolled(rawStatus) && PagerProtocol.isValidClientToken(setupToken)
                    ? "\nSetup token saved locally. Send one pager update to enroll this phone."
                    : "";
            policyReadPending = false;
            policyReadQueuedAtMs = 0L;
            policyAttemptActive = false;
            complete("Pager policy\n" + PagerProtocol.formatStatus(rawStatus) + note);
        } else {
            readingStatusForSend = false;
            if (currentOperation == Operation.BEAT_STATUS) {
                retryBeatAfterMiss("Beat: policy read failed (" + status + ").");
                return;
            }
            fail("Pager policy read failed (" + status + ").");
        }
    }

    private void complete(String finalStatus) {
        boolean completedBeat = currentOperation == Operation.BEAT_STATUS;
        boolean completedPolicyRead = currentOperation == Operation.READ_STATUS;
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        if (!keepConnected) {
            closeConnection();
        }
        status(finalStatus, completedBeat ? EventLogCategory.BEAT_MODE : EventLogCategory.NOTIFICATION_RELAY,
                completedPolicyRead);
        if (completedBeat) {
            scheduleNextBeat();
            return;
        }
        if (mailboxPayload != null && !keepConnected) {
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), "Queued latest pager update for retry.");
            return;
        }
        runQueuedPayload();
    }

    private void fail(String finalStatus) {
        closeConnection();
        if (mailboxAttemptActive) {
            mailboxPayload = null;
            mailboxPayloadQueuedAtMs = 0L;
        }
        boolean failedBeat = currentOperation == Operation.BEAT_STATUS || beatAttemptActive;
        boolean failedPolicyRead = currentOperation == Operation.READ_STATUS || policyAttemptActive;
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        if (failedPolicyRead) {
            policyAttemptActive = false;
            if (hasPolicyReadExpired()) {
                handler.removeCallbacks(policyAttemptRunnable);
                policyReadPending = false;
                policyReadQueuedAtMs = 0L;
                policyStatus(finalStatus + " Policy refresh retry expired.");
            } else {
                schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                        finalStatus + " Continuing policy scan.");
            }
            return;
        }
        status(finalStatus, failedBeat ? EventLogCategory.BEAT_MODE : EventLogCategory.NOTIFICATION_RELAY, false);
        if (failedBeat) {
            scheduleNextBeat();
            return;
        }
        runQueuedPayload();
    }

    private void finishAfterDisconnect(String finalStatus) {
        if (beatAttemptActive) {
            currentOperation = null;
            currentPayload = null;
            readingStatusForSend = false;
            beatAttemptActive = false;
            beatStatus("Beat: Xteink disconnected during check.");
            scheduleNextBeat();
            return;
        }
        if (policyAttemptActive && policyReadPending) {
            currentOperation = null;
            readingStatusForSend = false;
            policyAttemptActive = false;
            if (hasPolicyReadExpired()) {
                policyReadPending = false;
                policyReadQueuedAtMs = 0L;
                policyStatus(finalStatus + " Policy refresh retry expired.");
            } else {
                schedulePolicyAttempt(POLICY_SCAN_RETRY_MS, finalStatus + " Continuing policy scan.");
            }
            return;
        }
        if (mailboxAttemptActive && mailboxPayload != null && !hasMailboxPayloadExpired()) {
            currentOperation = null;
            currentPayload = null;
            readingStatusForSend = false;
            mailboxAttemptActive = false;
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), finalStatus + " Keeping latest update queued.");
            return;
        }
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        status(finalStatus);
        runQueuedPayload();
    }

    private void runQueuedPayload() {
        if (queuedPayload != null) {
            String nextPayload = queuedPayload;
            queuedPayload = null;
            handler.postDelayed(() -> send(nextPayload), 300);
        }
    }

    @SuppressLint("MissingPermission")
    private void closeConnection() {
        stopScan();
        pagerService = null;
        if (gatt != null) {
            BluetoothGatt oldGatt = gatt;
            gatt = null;
            oldGatt.disconnect();
            oldGatt.close();
        }
    }

    void close() {
        queuedPayload = null;
        mailboxPayload = null;
        mailboxPayloadQueuedAtMs = 0L;
        cancelMailboxAttempt();
        handler.removeCallbacks(policyAttemptRunnable);
        beatEnabled = false;
        handler.removeCallbacks(beatRunnable);
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        policyReadPending = false;
        policyReadQueuedAtMs = 0L;
        policyAttemptActive = false;
        updateRetryPreferences();
    }

    private void status(String value) {
        status(value, currentOperation == Operation.BEAT_STATUS || beatAttemptActive
                ? EventLogCategory.BEAT_MODE
                : EventLogCategory.NOTIFICATION_RELAY);
    }

    private void beatStatus(String value) {
        status(value, EventLogCategory.BEAT_MODE, false);
    }

    private void status(String value, EventLogCategory category) {
        publishStatus(value, 0L, category, isPolicyStatus());
    }

    private void status(String value, EventLogCategory category, boolean policyMessage) {
        publishStatus(value, 0L, category, policyMessage);
    }

    private void policyStatus(String value) {
        publishStatus(value, 0L, EventLogCategory.NOTIFICATION_RELAY, true);
    }

    private void countdown(String value, long targetAtMs) {
        publishStatus(value, targetAtMs, EventLogCategory.NOTIFICATION_RELAY, false);
    }

    private void policyCountdown(String value, long targetAtMs) {
        publishStatus(value, targetAtMs, EventLogCategory.NOTIFICATION_RELAY, true);
    }

    private boolean isPolicyStatus() {
        return currentOperation == Operation.READ_STATUS || policyAttemptActive;
    }

    private void publishStatus(String value, long targetAtMs, EventLogCategory category,
                               boolean policyStatus) {
        boolean sendRetryActive = hasSendRetryActive();
        boolean policyRetryActive = hasPolicyRetryActive();
        RelayPreferences.setSendRetryActive(context, sendRetryActive);
        RelayPreferences.setPolicyRetryActive(context, policyRetryActive);
        statusCallback.onStatus(value, targetAtMs, category, sendRetryActive, policyRetryActive, policyStatus);
    }

    boolean hasSendRetryActive() {
        return mailboxPayload != null || queuedPayload != null || currentOperation == Operation.SEND;
    }

    boolean hasPolicyRetryActive() {
        return policyReadPending || policyAttemptActive || currentOperation == Operation.READ_STATUS;
    }

    boolean hasHeldConnection() {
        return isHoldingLink();
    }

    private void updateRetryPreferences() {
        RelayPreferences.setSendRetryActive(context, hasSendRetryActive());
        RelayPreferences.setPolicyRetryActive(context, hasPolicyRetryActive());
    }

    private enum Operation {
        HOLD_LINK,
        SEND,
        READ_STATUS,
        BEAT_STATUS
    }
}
