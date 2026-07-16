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
import java.util.List;
import java.util.UUID;

/** One serialized GATT pipeline for policy reads and bounded event batches. */
final class PagerGattClient {
    interface StatusCallback {
        void onStatus(String status, long countdownAtMs, EventLogCategory category,
                      boolean testRetryActive, boolean policyRetryActive, UiStatusChannel channel);
    }

    private static final long DEFAULT_SCAN_TIMEOUT_MS = 12_000;
    private static final long MAILBOX_SCAN_LEAD_MS = 10_000;
    private static final long MAILBOX_LATE_TOLERANCE_MS = 10_000;
    private static final long MAILBOX_MAX_PENDING_MS = 75L * 60L * 1000L;
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
    private UiStatusChannel currentStatusChannel = UiStatusChannel.NONE;
    private String currentPayload;
    private List<PagerProtocol.WriteCommand> currentBatch;
    private int currentCommandIndex;
    private String queuedPayload;
    private UiStatusChannel queuedStatusChannel = UiStatusChannel.NONE;
    private String pendingPolicyRawStatus;
    private String activeDeviceAddress;
    private boolean mailboxScheduleKnown;
    private boolean mailboxConfigured;
    private boolean mailboxAttemptActive;
    private long mailboxIntervalMs;
    private long mailboxWindowMs;
    private long nextMailboxWindowAtMs;
    private long mailboxPayloadQueuedAtMs;
    private String mailboxPayload;
    private UiStatusChannel mailboxStatusChannel = UiStatusChannel.NONE;
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
                queuedStatusChannel = mailboxStatusChannel;
                mailboxPayload = null;
                mailboxStatusChannel = UiStatusChannel.NONE;
                mailboxPayloadQueuedAtMs = 0L;
            }
        }
        if (!enabled && isHoldingLink()) {
            closeConnection();
            status("Connection mode: connect per message.", UiStatusChannel.NONE);
        }
    }

    void holdConnection() {
        if (!RelayPreferences.isPagerReadyForUse(context)) {
            status("Choose an Xteink and refresh Pager policy before starting a connection.", UiStatusChannel.RELAY);
            return;
        }
        if (!keepConnected) {
            status("Connection mode: connect per message.", UiStatusChannel.RELAY);
            return;
        }
        if (isHoldingLink()) {
            status("Pager link is held for battery testing.", UiStatusChannel.RELAY);
            return;
        }
        if (isBusy()) {
            status("Pager is busy. Keep-connected mode will use the next idle link.", UiStatusChannel.RELAY);
            return;
        }
        begin(Operation.HOLD_LINK, null, UiStatusChannel.RELAY);
    }

    void sendPending(UiStatusChannel channel) {
        send("pending", channel);
    }

    private void send(String payload, UiStatusChannel channel) {
        UiStatusChannel sendChannel = channel == UiStatusChannel.RELAY
                ? UiStatusChannel.RELAY : UiStatusChannel.TEST;
        if (!RelayPreferences.isPagerReadyForUse(context)) {
            status("Pager status is not ready. Choose an Xteink, then refresh Pager policy.", sendChannel);
            return;
        }
        if (!keepConnected) {
            queueForMailbox(payload, sendChannel);
            return;
        }
        if (isHoldingLink()) {
            beginConnected(Operation.SEND, payload, sendChannel);
            return;
        }
        if (isBusy()) {
            queuedPayload = payload;
            queuedStatusChannel = sendChannel;
            status("Queued pending Pager notifications.", sendChannel);
            return;
        }
        begin(Operation.SEND, payload, sendChannel);
    }

    void readStatus() {
        if (!RelayPreferences.hasPagerSelection(context)) {
            policyStatus("Choose an Xteink before refreshing Pager policy.");
            return;
        }
        policyReadPending = true;
        policyReadQueuedAtMs = SystemClock.elapsedRealtime();
        if (isHoldingLink()) {
            policyAttemptActive = true;
            beginConnected(Operation.READ_STATUS, null, UiStatusChannel.POLICY);
            return;
        }
        if (isBusy()) {
            schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                    "Pager is busy; policy scan will continue.");
            return;
        }
        schedulePolicyAttempt(0L, "Pager policy refresh started; scanning for the selected Xteink.");
    }

    void cancelSend() {
        boolean cancelActiveOperation = currentStatusChannel == UiStatusChannel.TEST
                && currentOperation == Operation.SEND;
        if (mailboxStatusChannel == UiStatusChannel.TEST) {
            cancelMailboxAttempt();
            mailboxPayload = null;
            mailboxStatusChannel = UiStatusChannel.NONE;
            mailboxPayloadQueuedAtMs = 0L;
            mailboxAttemptActive = false;
        }
        if (queuedStatusChannel == UiStatusChannel.TEST) {
            queuedPayload = null;
            queuedStatusChannel = UiStatusChannel.NONE;
        }
        if (cancelActiveOperation) {
            closeConnection();
            currentOperation = null;
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
            clearPendingWriteState();
        }
        status("Pager update retry stopped.", UiStatusChannel.TEST);
        if (mailboxPayload != null && !keepConnected) {
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), "Queued pending Pager notifications for retry.");
            return;
        }
        runQueuedPayload();
    }

    void cancelPolicyRead() {
        boolean cancelActiveOperation = isPolicyOperation();
        handler.removeCallbacks(policyAttemptRunnable);
        policyReadPending = false;
        policyReadQueuedAtMs = 0L;
        policyAttemptActive = false;
        clearPendingWriteState();
        if (cancelActiveOperation) {
            closeConnection();
            currentOperation = null;
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
        }
        policyStatus("Pager policy refresh stopped.");
    }

    void startBeat() {
        if (beatEnabled) {
            return;
        }
        if (!RelayPreferences.isPagerReadyForUse(context)) {
            RelayPreferences.setBeatEnabled(context, false);
            beatStatus("Beat mode requires a selected Xteink with stored Pager status.");
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
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
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
        begin(Operation.BEAT_STATUS, null, UiStatusChannel.BEAT);
    };

    private void scheduleNextBeat() {
        if (!beatEnabled) {
            return;
        }
        scheduleBeat(0L, "Beat mode scanning continuously.");
    }

    private void queueForMailbox(String payload, UiStatusChannel channel) {
        mailboxPayload = payload;
        mailboxStatusChannel = channel;
        mailboxPayloadQueuedAtMs = SystemClock.elapsedRealtime();
        if (isBusy()) {
            status("Queued pending Pager notifications.", channel);
            return;
        }
        long delayMs = mailboxScheduleKnown ? mailboxScanDelayMs() : 0L;
        scheduleMailboxAttempt(delayMs, mailboxScheduleKnown
                ? "Queued pending Pager notifications for the mailbox window."
                : "Queued pending Pager notifications; mailbox timing is not known yet.");
    }

    private long mailboxScanDelayMs() {
        long nowWallClockMs = System.currentTimeMillis();
        long nextWindowWallClockMs = MailboxSchedule.currentOrNextUtcWindow(
                nowWallClockMs, mailboxIntervalMs, mailboxWindowMs);
        long delayMs = MailboxSchedule.scanDelay(
                nowWallClockMs, mailboxIntervalMs, mailboxWindowMs, MAILBOX_SCAN_LEAD_MS);
        nextMailboxWindowAtMs = SystemClock.elapsedRealtime() + Math.max(0L, nextWindowWallClockMs - nowWallClockMs);
        persistMailboxSchedule();
        return delayMs;
    }

    private void scheduleMailboxAttempt(long delayMs, String message) {
        if (mailboxPayload == null) {
            return;
        }
        cancelMailboxAttempt();
        handler.postDelayed(mailboxAttemptRunnable, delayMs);
        countdown(message, SystemClock.elapsedRealtime() + delayMs, mailboxStatusChannel);
    }

    private final Runnable mailboxAttemptRunnable = () -> {
        if (mailboxPayload == null) {
            return;
        }
        if (hasMailboxPayloadExpired()) {
            mailboxPayload = null;
            UiStatusChannel expiredChannel = mailboxStatusChannel;
            mailboxStatusChannel = UiStatusChannel.NONE;
            mailboxPayloadQueuedAtMs = 0L;
            status("Mailbox delivery expired before Xteink was reachable.", expiredChannel);
            return;
        }
        if (isBusy()) {
            scheduleMailboxAttempt(1000L, "Pager is busy; keeping pending notifications for mailbox delivery.");
            return;
        }
        mailboxAttemptActive = true;
        begin(Operation.SEND, mailboxPayload, mailboxStatusChannel);
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
            policyStatus("Pager policy refresh expired before the selected Xteink was reachable.");
            return;
        }
        if (isBusy()) {
            schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                    "Pager is busy; policy scan will continue.");
            return;
        }
        policyAttemptActive = true;
        begin(Operation.READ_STATUS, null, UiStatusChannel.POLICY);
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
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        mailboxAttemptActive = false;
        if (hasMailboxPayloadExpired()) {
            mailboxPayload = null;
            UiStatusChannel expiredChannel = mailboxStatusChannel;
            mailboxStatusChannel = UiStatusChannel.NONE;
            mailboxPayloadQueuedAtMs = 0L;
            status(reason + " Mailbox delivery expired.", expiredChannel);
            return true;
        }
        scheduleMailboxAttempt(nextMailboxRetryDelayMs(), reason + " Keeping pending notifications queued.");
        return true;
    }

    private boolean retryPolicyAfterMiss(String reason) {
        if (!policyAttemptActive || !policyReadPending) {
            return false;
        }
        closeConnection();
        currentOperation = null;
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        clearPendingWriteState();
        policyAttemptActive = false;
        if (hasPolicyReadExpired()) {
            policyReadPending = false;
            policyReadQueuedAtMs = 0L;
            policyStatus(reason + " Pager policy refresh expired.");
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
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
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
            nextMailboxWindowAtMs = 0L;
            RelayPreferences.clearMailboxSchedule(context);
            cancelMailboxAttempt();
            return;
        }
        mailboxScheduleKnown = true;
        long nowWallClockMs = System.currentTimeMillis();
        long nextWindowWallClockMs = MailboxSchedule.currentOrNextUtcWindow(
                nowWallClockMs, mailboxIntervalMs, mailboxWindowMs);
        nextMailboxWindowAtMs = SystemClock.elapsedRealtime() + nextWindowWallClockMs - nowWallClockMs;
        persistMailboxSchedule();
    }

    private void updateNextMailboxWindowFromNow() {
        if (!mailboxScheduleKnown || mailboxIntervalMs <= 0L) {
            return;
        }
        long nowWallClockMs = System.currentTimeMillis();
        long nextWindowWallClockMs = MailboxSchedule.currentOrNextUtcWindow(
                nowWallClockMs, mailboxIntervalMs, mailboxWindowMs);
        nextMailboxWindowAtMs = SystemClock.elapsedRealtime() + nextWindowWallClockMs - nowWallClockMs;
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
        savedNextWindowWallClockMs = MailboxSchedule.currentOrNextUtcWindow(
                nowWallClockMs, savedIntervalMs, savedWindowMs);
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

    private void begin(Operation operation, String payload, UiStatusChannel channel) {
        currentOperation = operation;
        currentStatusChannel = channel;
        currentPayload = payload;
        prepareCurrentBatch(operation);
        scanAndConnect();
    }

    private void beginConnected(Operation operation, String payload, UiStatusChannel channel) {
        currentOperation = operation;
        currentStatusChannel = channel;
        currentPayload = payload;
        prepareCurrentBatch(operation);
        runOperation();
    }

    private void prepareCurrentBatch(Operation operation) {
        currentBatch = null;
        currentCommandIndex = 0;
        if (operation != Operation.SEND) {
            return;
        }
        List<PagerProtocol.NotificationItem> pending = RelayPreferences.pendingEvents(context);
        currentBatch = PagerProtocol.notificationBatch(pending, RelayPreferences.maxNotifications(context),
                System.currentTimeMillis() / 1000L);
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
        String storedAddress = RelayPreferences.pagerBluetoothAddress(context);
        if (!BluetoothAdapter.checkBluetoothAddress(storedAddress)) {
            fail("Choose an Xteink before starting a Pager connection.");
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID))
                .setDeviceAddress(storedAddress)
                .build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        status("Searching for the selected Xteink...");
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(filter), settings, scanCallback);
        long scanTimeoutMs = DEFAULT_SCAN_TIMEOUT_MS;
        if (mailboxAttemptActive && mailboxScheduleKnown) {
            scanTimeoutMs = Math.max(scanTimeoutMs,
                    MAILBOX_SCAN_LEAD_MS + mailboxWindowMs + MAILBOX_LATE_TOLERANCE_MS);
        }
        handler.postDelayed(scanTimeout, scanTimeoutMs);
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
            if (retryMailboxAfterMiss("The selected Xteink was not seen during the scheduled mailbox window.")) {
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
            activeDeviceAddress = result.getDevice().getAddress();
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
            if (connection != gatt) {
                connection.close();
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Pager connection failed (" + status + ").");
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                activeDeviceAddress = connection.getDevice().getAddress();
                status("Discovering Pager service...");
                if (!connection.discoverServices()) {
                    fail("Could not discover Pager service.");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                boolean wasActive = currentOperation != null;
                closeConnection();
                finishAfterDisconnect(wasActive ? "Pager disconnected before delivery." : "Pager disconnected.");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            if (connection != gatt) {
                return;
            }
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
            if (connection != gatt) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Pager write failed (" + status + ").");
                return;
            }
            if (currentOperation == Operation.CONFIRM_POLICY) {
                completePolicyConfirmation();
                return;
            }
            if (currentOperation == Operation.SEND) {
                currentCommandIndex++;
                if (currentBatch != null && currentCommandIndex < currentBatch.size()) {
                    writePayload();
                    return;
                }
                RelayPreferences.markBatchSent(context, currentBatch);
                recordAcknowledgedWrite(false);
                complete("Pager update sent.");
                return;
            }
            complete("Pager update sent.");
        }

        @Override
        @SuppressWarnings("deprecation") // Android 12 and earlier use this callback signature.
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            if (connection != gatt) {
                return;
            }
            handleStatusRead(characteristic.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (connection != gatt) {
                return;
            }
            handleStatusRead(value, status);
        }
    };

    private void runOperation() {
        if (currentOperation == null) {
            return;
        }
        switch (currentOperation) {
            case HOLD_LINK:
                complete("Pager link held for battery testing.");
                return;
            case READ_STATUS:
            case BEAT_STATUS:
                readPagerStatus();
                return;
            case SEND:
                writePayload();
                return;
            case CONFIRM_POLICY:
                return;
        }
    }

    @SuppressLint("MissingPermission")
    private void readPagerStatus() {
        BluetoothGattCharacteristic characteristic = pagerService == null ? null : pagerService.getCharacteristic(STATUS_UUID);
        if (characteristic == null) {
            fail("Pager policy status is unavailable on this firmware.");
            return;
        }
        String readStatus;
        if (currentOperation == Operation.BEAT_STATUS) {
            readStatus = "Beat: checking Xteink...";
        } else {
            readStatus = "Reading Pager policy...";
        }
        status(readStatus);
        if (gatt == null || !gatt.readCharacteristic(characteristic)) {
            fail("Pager policy read could not start.");
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation") // Required for Android 12 and earlier GATT writes.
    private void writePayload() {
        BluetoothGattCharacteristic characteristic = pagerService == null ? null : pagerService.getCharacteristic(PAYLOAD_UUID);
        PagerProtocol.WriteCommand command = currentOperation == Operation.CONFIRM_POLICY
                ? PagerProtocol.policyConfirmationCommand()
                : currentBatch == null || currentCommandIndex >= currentBatch.size()
                        ? null : currentBatch.get(currentCommandIndex);
        if (characteristic == null || command == null) {
            fail("Pager write characteristic was not found.");
            return;
        }
        String token = RelayPreferences.clientToken(context);
        if (!PagerProtocol.isValidAuthenticatedCommand(command, token)) {
            fail("Pager setup token is missing or the payload is too large. Refresh Pager policy first.");
            return;
        }
        byte[] bytes = PagerProtocol.authenticatedCommand(command, token).getBytes(StandardCharsets.UTF_8);
        status(currentOperation == Operation.CONFIRM_POLICY
                ? "Confirming Pager connection..."
                : "Sending Pager command " + (currentCommandIndex + 1) + "/" + currentBatch.size() + "...");
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
            PagerProtocol.PagerStatus pagerStatus = PagerProtocol.parseStatus(rawStatus);
            if (isUnexpectedDevice(pagerStatus)) {
                fail("A different CrossPoint Pager answered. Forget the stored Xteink before changing devices.");
                return;
            }
            if (!pagerStatus.usablePolicy) {
                if (currentOperation == Operation.BEAT_STATUS) {
                    retryBeatAfterMiss("Beat: Xteink returned an incomplete Pager policy.");
                } else if (!retryPolicyAfterMiss("Xteink returned an incomplete Pager policy.")) {
                    fail("Xteink returned an incomplete Pager policy.");
                }
                return;
            }
            if (currentOperation == Operation.BEAT_STATUS) {
                storeObservedPolicyIfAllowed(pagerStatus, rawStatus, false, false);
                beatAttemptActive = false;
                complete("Beat: Xteink online\n" + PagerProtocol.formatStatus(rawStatus));
                return;
            }

            rememberPagerIdentity(pagerStatus);
            storeObservedPolicyIfAllowed(pagerStatus, rawStatus, true, false);
            String setupToken = PagerProtocol.enrollmentToken(rawStatus);
            if (!PagerProtocol.isEnrolled(rawStatus) && PagerProtocol.isValidClientToken(setupToken)) {
                RelayPreferences.setClientToken(context, setupToken);
            }
            String clientToken = RelayPreferences.clientToken(context);
            if (PagerProtocol.isValidClientToken(clientToken)) {
                pendingPolicyRawStatus = rawStatus;
                currentOperation = Operation.CONFIRM_POLICY;
                currentPayload = PagerProtocol.policyConfirmationPayload();
                writePayload();
                return;
            }
            finishPolicyRefreshState();
            String tokenNote = PagerProtocol.isEnrolled(rawStatus)
                    ? "\nXteink is enrolled, but this app has no matching token. Reset Enrolled Device to reconnect it."
                    : "\nPager setup token was unavailable. Re-enter Pager standby and refresh policy.";
            complete("Pager policy\n" + PagerProtocol.formatStatus(rawStatus) + tokenNote);
        } else {
            if (currentOperation == Operation.BEAT_STATUS) {
                retryBeatAfterMiss("Beat: policy read failed (" + status + ").");
                return;
            }
            fail("Pager policy read failed (" + status + ").");
        }
    }

    private boolean isUnexpectedDevice(PagerProtocol.PagerStatus status) {
        String storedDeviceId = RelayPreferences.pagerDeviceId(context);
        return PagerProtocol.isValidDeviceId(storedDeviceId)
                && PagerProtocol.isValidDeviceId(status.deviceId)
                && !storedDeviceId.equalsIgnoreCase(status.deviceId);
    }

    private void completePolicyConfirmation() {
        if (pendingPolicyRawStatus == null) {
            fail("Pager policy confirmation state was lost. Refresh Pager policy again.");
            return;
        }
        recordAcknowledgedWrite(true);
        RelayPreferences.setPagerEnrollmentConfirmed(context);
        finishPolicyRefreshState();
        complete("Pager policy updated.\nPager connection confirmed.");
    }

    private void rememberPagerIdentity(PagerProtocol.PagerStatus status) {
        if (!status.usablePolicy || activeDeviceAddress == null
                || !BluetoothAdapter.checkBluetoothAddress(activeDeviceAddress)) {
            return;
        }
        RelayPreferences.setPagerIdentity(context, status, activeDeviceAddress);
    }

    private void finishPolicyRefreshState() {
        handler.removeCallbacks(policyAttemptRunnable);
        policyReadPending = false;
        policyReadQueuedAtMs = 0L;
        policyAttemptActive = false;
    }

    private void recordAcknowledgedWrite(boolean policyWrite) {
        if (mailboxConfigured && mailboxIntervalMs > 0L && mailboxWindowMs > 0L) {
            mailboxScheduleKnown = true;
            updateNextMailboxWindowFromNow();
        }
        if (policyWrite) {
            return;
        }
        if (!mailboxAttemptActive) {
            return;
        }
        mailboxAttemptActive = false;
        if (RelayPreferences.pendingEvents(context).isEmpty()) {
            mailboxPayload = null;
            mailboxStatusChannel = UiStatusChannel.NONE;
            mailboxPayloadQueuedAtMs = 0L;
        } else {
            mailboxPayload = "pending";
        }
    }

    private void storeObservedPolicyIfAllowed(PagerProtocol.PagerStatus status, String rawStatus,
                                               boolean manualRefresh, boolean preserveMailboxHandoff) {
        if (!manualRefresh && !RelayPreferences.isAutoUpdatePagerPolicy(context)) {
            return;
        }
        RelayPreferences.setPagerPolicy(context, status, PagerProtocol.formatTechnicalStatus(rawStatus));
        if (preserveMailboxHandoff && status.configuredMailbox && !status.canScheduleMailbox()) {
            return;
        }
        rememberMailboxStatus(status);
    }

    private void complete(String finalStatus) {
        boolean completedBeat = currentOperation == Operation.BEAT_STATUS;
        boolean completedPolicyRead = isPolicyOperation();
        UiStatusChannel completedChannel = completedPolicyRead ? UiStatusChannel.POLICY
                : completedBeat ? UiStatusChannel.BEAT : currentStatusChannel;
        currentOperation = null;
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        currentBatch = null;
        currentCommandIndex = 0;
        clearPendingWriteState();
        if (!keepConnected) {
            closeConnection();
        }
        status(finalStatus, completedBeat ? EventLogCategory.BEAT_MODE : EventLogCategory.NOTIFICATION_RELAY,
                completedChannel);
        if (completedBeat) {
            scheduleNextBeat();
            return;
        }
        if (mailboxPayload != null && !keepConnected) {
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), "Queued pending Pager notifications for retry.");
            return;
        }
        runQueuedPayload();
    }

    private void fail(String finalStatus) {
        UiStatusChannel failedChannel = currentStatusChannel;
        boolean failedMailbox = mailboxAttemptActive && mailboxPayload != null;
        closeConnection();
        boolean failedBeat = currentOperation == Operation.BEAT_STATUS || beatAttemptActive;
        boolean failedPolicyRead = isPolicyOperation() || policyAttemptActive;
        currentOperation = null;
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        currentBatch = null;
        currentCommandIndex = 0;
        clearPendingWriteState();
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        if (failedPolicyRead) {
            policyAttemptActive = false;
            if (hasPolicyReadExpired()) {
                handler.removeCallbacks(policyAttemptRunnable);
                policyReadPending = false;
                policyReadQueuedAtMs = 0L;
                policyStatus(finalStatus + " Pager policy refresh expired.");
            } else {
                schedulePolicyAttempt(POLICY_SCAN_RETRY_MS,
                        finalStatus + " Continuing policy scan.");
            }
            return;
        }
        status(finalStatus, failedBeat ? EventLogCategory.BEAT_MODE : EventLogCategory.NOTIFICATION_RELAY,
                failedBeat ? UiStatusChannel.BEAT : failedChannel);
        if (failedBeat) {
            scheduleNextBeat();
            return;
        }
        if (failedMailbox && !RelayPreferences.pendingEvents(context).isEmpty()) {
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), finalStatus + " Keeping pending events queued.");
            return;
        }
        runQueuedPayload();
    }

    private void finishAfterDisconnect(String finalStatus) {
        UiStatusChannel disconnectedChannel = currentStatusChannel;
        if (beatAttemptActive) {
            currentOperation = null;
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
            beatAttemptActive = false;
            beatStatus("Beat: Xteink disconnected during check.");
            scheduleNextBeat();
            return;
        }
        if (policyAttemptActive && policyReadPending) {
            currentOperation = null;
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
            clearPendingWriteState();
            policyAttemptActive = false;
            if (hasPolicyReadExpired()) {
                policyReadPending = false;
                policyReadQueuedAtMs = 0L;
                policyStatus(finalStatus + " Pager policy refresh expired.");
            } else {
                schedulePolicyAttempt(POLICY_SCAN_RETRY_MS, finalStatus + " Continuing policy scan.");
            }
            return;
        }
        if (mailboxAttemptActive && mailboxPayload != null && !hasMailboxPayloadExpired()) {
            currentOperation = null;
            currentStatusChannel = UiStatusChannel.NONE;
            currentPayload = null;
            mailboxAttemptActive = false;
            scheduleMailboxAttempt(nextMailboxRetryDelayMs(), finalStatus + " Keeping pending notifications queued.");
            return;
        }
        currentOperation = null;
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        clearPendingWriteState();
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        status(finalStatus, disconnectedChannel);
        runQueuedPayload();
    }

    private void runQueuedPayload() {
        if (queuedPayload != null) {
            String nextPayload = queuedPayload;
            UiStatusChannel nextChannel = queuedStatusChannel;
            queuedPayload = null;
            queuedStatusChannel = UiStatusChannel.NONE;
            handler.postDelayed(() -> send(nextPayload, nextChannel), 300);
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
        activeDeviceAddress = null;
    }

    private void clearPendingWriteState() {
        pendingPolicyRawStatus = null;
        currentBatch = null;
        currentCommandIndex = 0;
    }

    void close() {
        queuedPayload = null;
        queuedStatusChannel = UiStatusChannel.NONE;
        mailboxPayload = null;
        mailboxStatusChannel = UiStatusChannel.NONE;
        mailboxPayloadQueuedAtMs = 0L;
        cancelMailboxAttempt();
        handler.removeCallbacks(policyAttemptRunnable);
        beatEnabled = false;
        handler.removeCallbacks(beatRunnable);
        closeConnection();
        currentOperation = null;
        currentStatusChannel = UiStatusChannel.NONE;
        currentPayload = null;
        clearPendingWriteState();
        mailboxAttemptActive = false;
        beatAttemptActive = false;
        policyReadPending = false;
        policyReadQueuedAtMs = 0L;
        policyAttemptActive = false;
        updateRetryPreferences();
    }

    private void status(String value) {
        UiStatusChannel channel = isPolicyOperation() || policyAttemptActive
                ? UiStatusChannel.POLICY
                : currentOperation == Operation.BEAT_STATUS || beatAttemptActive
                        ? UiStatusChannel.BEAT : currentStatusChannel;
        status(value, channel);
    }

    private void status(String value, UiStatusChannel channel) {
        status(value, channel == UiStatusChannel.BEAT
                ? EventLogCategory.BEAT_MODE : EventLogCategory.NOTIFICATION_RELAY, channel);
    }

    private void beatStatus(String value) {
        status(value, EventLogCategory.BEAT_MODE, UiStatusChannel.BEAT);
    }

    private void status(String value, EventLogCategory category, UiStatusChannel channel) {
        publishStatus(value, 0L, category, channel);
    }

    private void policyStatus(String value) {
        publishStatus(value, 0L, EventLogCategory.NOTIFICATION_RELAY, UiStatusChannel.POLICY);
    }

    private void countdown(String value, long targetAtMs, UiStatusChannel channel) {
        publishStatus(value, targetAtMs, EventLogCategory.NOTIFICATION_RELAY, channel);
    }

    private void policyCountdown(String value, long targetAtMs) {
        publishStatus(value, targetAtMs, EventLogCategory.NOTIFICATION_RELAY, UiStatusChannel.POLICY);
    }

    private boolean isPolicyOperation() {
        return currentOperation == Operation.READ_STATUS || currentOperation == Operation.CONFIRM_POLICY;
    }

    private void publishStatus(String value, long targetAtMs, EventLogCategory category,
                               UiStatusChannel channel) {
        boolean testRetryActive = hasTestRetryActive();
        boolean policyRetryActive = hasPolicyRetryActive();
        RelayPreferences.setSendRetryActive(context, testRetryActive);
        RelayPreferences.setPolicyRetryActive(context, policyRetryActive);
        statusCallback.onStatus(value, targetAtMs, category, testRetryActive, policyRetryActive, channel);
    }

    private boolean hasTestRetryActive() {
        return mailboxPayload != null && mailboxStatusChannel == UiStatusChannel.TEST
                || queuedPayload != null && queuedStatusChannel == UiStatusChannel.TEST
                || currentStatusChannel == UiStatusChannel.TEST
                        && currentOperation == Operation.SEND;
    }

    boolean hasSendRetryActive() {
        return mailboxPayload != null || queuedPayload != null || currentOperation == Operation.SEND;
    }

    boolean hasPolicyRetryActive() {
        return policyReadPending || policyAttemptActive || isPolicyOperation();
    }

    boolean hasHeldConnection() {
        return isHoldingLink();
    }

    void forgetPager() {
        resetPagerRuntime();
        RelayPreferences.forgetPager(context);
        policyStatus("Stored Xteink forgotten. Choose a reader before refreshing Pager policy.");
    }

    void selectPager(String address, String label) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            policyStatus("The selected Xteink address is invalid.");
            return;
        }
        resetPagerRuntime();
        RelayPreferences.selectPager(context, address, label);
        policyStatus("Selected " + (label == null || label.isEmpty() ? "Xteink" : label)
                + ". Refresh Pager policy to enroll it.");
    }

    private void resetPagerRuntime() {
        close();
        mailboxScheduleKnown = false;
        mailboxConfigured = false;
        mailboxIntervalMs = 0L;
        mailboxWindowMs = 0L;
        nextMailboxWindowAtMs = 0L;
    }

    private void updateRetryPreferences() {
        RelayPreferences.setSendRetryActive(context, hasTestRetryActive());
        RelayPreferences.setPolicyRetryActive(context, hasPolicyRetryActive());
    }

    private enum Operation {
        HOLD_LINK,
        SEND,
        READ_STATUS,
        CONFIRM_POLICY,
        BEAT_STATUS
    }
}
