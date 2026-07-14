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
        void onStatus(String status, long countdownAtMs);
    }

    private static final long SCAN_TIMEOUT_MS = 12_000;
    private static final long MAILBOX_SCAN_LEAD_MS = 10_000;
    private static final long MAILBOX_MAX_PENDING_MS = 75L * 60L * 1000L;
    private static final long BEAT_SCAN_INTERVAL_MS = 30_000;
    private static final long BEAT_BUSY_RETRY_MS = 3_000;
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
    private boolean beatEnabled;
    private boolean beatAttemptActive;

    PagerGattClient(Context context, StatusCallback statusCallback) {
        this.context = context.getApplicationContext();
        this.statusCallback = statusCallback;
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
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
        if (!keepConnected && mailboxScheduleKnown) {
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
        if (isHoldingLink()) {
            beginConnected(Operation.READ_STATUS, null);
            return;
        }
        if (isBusy()) {
            status("Pager is busy. Try reading the policy again after delivery finishes.");
            return;
        }
        begin(Operation.READ_STATUS, null);
    }

    void startBeat() {
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
        status("Beat mode stopped.");
    }

    private void scheduleBeat(long delayMs, String message) {
        if (!beatEnabled) {
            return;
        }
        handler.removeCallbacks(beatRunnable);
        handler.postDelayed(beatRunnable, delayMs);
        status(delayMs <= 0L ? message + " Checking now." : message + " Next check " + mailboxDelayText(delayMs));
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
            status("Queued latest pager update for the mailbox window.");
            return;
        }
        scheduleMailboxAttempt(mailboxScanDelayMs(), "Queued latest pager update for the mailbox window.");
    }

    private long mailboxScanDelayMs() {
        long now = SystemClock.elapsedRealtime();
        if (nextMailboxWindowAtMs > 0L && mailboxIntervalMs > 0L
                && now >= nextMailboxWindowAtMs + mailboxWindowMs) {
            long elapsedAfterWindowMs = now - (nextMailboxWindowAtMs + mailboxWindowMs);
            long elapsedIntervals = elapsedAfterWindowMs / mailboxIntervalMs + 1L;
            nextMailboxWindowAtMs += elapsedIntervals * mailboxIntervalMs;
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
            status("Mailbox delivery expired before X3 was reachable.");
            return;
        }
        if (isBusy()) {
            scheduleMailboxAttempt(1000L, "Pager is busy; keeping latest update for mailbox delivery.");
            return;
        }
        mailboxAttemptActive = true;
        begin(Operation.SEND, mailboxPayload);
    };

    private boolean hasMailboxPayloadExpired() {
        return mailboxPayloadQueuedAtMs > 0L
                && SystemClock.elapsedRealtime() - mailboxPayloadQueuedAtMs > MAILBOX_MAX_PENDING_MS;
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
        if (!mailboxAttemptActive || mailboxPayload == null || !mailboxScheduleKnown) {
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
        scheduleMailboxAttempt(mailboxScanDelayMs(), reason + " Keeping latest update queued.");
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
        status(reason);
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
    }

    private void updateNextMailboxWindowFromNow() {
        if (!mailboxScheduleKnown || mailboxIntervalMs <= 0L) {
            return;
        }
        lastMailboxWindowSeenAtMs = SystemClock.elapsedRealtime();
        nextMailboxWindowAtMs = lastMailboxWindowSeenAtMs + mailboxIntervalMs;
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
            if (retryBeatAfterMiss("Beat: X3 not found.")) {
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
                    ? "Pager update sent.\nMessage identical; X3 will not update content."
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
                ? "Beat: checking X3..."
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
                complete("Beat: X3 online\n" + PagerProtocol.formatStatus(rawStatus));
                return;
            }
            if (readingStatusForSend) {
                readingStatusForSend = false;
                if (PagerProtocol.isValidClientToken(RelayPreferences.clientToken(context))) {
                    writePayload();
                } else if (PagerProtocol.isEnrolled(rawStatus)) {
                    fail("X3 is already enrolled. Reset Enrolled Device on X3, then refresh pager policy.");
                } else {
                    fail("Pager setup token was unavailable. Re-enter Pager standby and refresh policy.");
                }
                return;
            }
            String note = !PagerProtocol.isEnrolled(rawStatus) && PagerProtocol.isValidClientToken(setupToken)
                    ? "\nSetup token saved locally. Send one pager update to enroll this phone."
                    : "";
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
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        if (!keepConnected) {
            closeConnection();
        }
        status(finalStatus);
        if (completedBeat) {
            scheduleNextBeat();
            return;
        }
        if (mailboxPayload != null && mailboxScheduleKnown && !keepConnected) {
            scheduleMailboxAttempt(mailboxScanDelayMs(), "Queued latest pager update for the next mailbox window.");
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
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        status(finalStatus);
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
            status("Beat: X3 disconnected during check.");
            scheduleNextBeat();
            return;
        }
        if (mailboxAttemptActive && mailboxPayload != null && mailboxScheduleKnown && !hasMailboxPayloadExpired()) {
            currentOperation = null;
            currentPayload = null;
            readingStatusForSend = false;
            mailboxAttemptActive = false;
            scheduleMailboxAttempt(mailboxScanDelayMs(), finalStatus + " Keeping latest update queued.");
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
        beatEnabled = false;
        handler.removeCallbacks(beatRunnable);
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        readingStatusForSend = false;
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        status("Pager relay stopped.");
    }

    private void status(String value) {
        statusCallback.onStatus(value, 0L);
    }

    private void countdown(String value, long targetAtMs) {
        statusCallback.onStatus(value, targetAtMs);
    }

    private enum Operation {
        HOLD_LINK,
        SEND,
        READ_STATUS,
        BEAT_STATUS
    }
}
