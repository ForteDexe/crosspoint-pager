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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/** One bounded, latest-message-wins GATT delivery pipeline. */
final class PagerGattClient {
    interface StatusCallback {
        void onStatus(String status);
    }

    private static final long SCAN_TIMEOUT_MS = 12_000;
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

    PagerGattClient(Context context, StatusCallback statusCallback) {
        this.context = context.getApplicationContext();
        this.statusCallback = statusCallback;
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    void setKeepConnected(boolean enabled) {
        keepConnected = enabled;
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
            readPagerStatus();
        } else if (currentOperation == Operation.SEND) {
            writePayload();
        }
    }

    @SuppressLint("MissingPermission")
    private void readPagerStatus() {
        BluetoothGattCharacteristic characteristic = pagerService == null ? null : pagerService.getCharacteristic(STATUS_UUID);
        if (characteristic == null) {
            fail("Pager policy status is unavailable on this firmware.");
            return;
        }
        status("Reading Pager policy...");
        if (gatt == null || !gatt.readCharacteristic(characteristic)) {
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
        byte[] bytes = currentPayload.getBytes(StandardCharsets.UTF_8);
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
            complete("Pager policy\n" + PagerProtocol.formatStatus(new String(value, StandardCharsets.UTF_8)));
        } else {
            fail("Pager policy read failed (" + status + ").");
        }
    }

    private void complete(String finalStatus) {
        currentOperation = null;
        currentPayload = null;
        if (!keepConnected) {
            closeConnection();
        }
        status(finalStatus);
        runQueuedPayload();
    }

    private void fail(String finalStatus) {
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        status(finalStatus);
        runQueuedPayload();
    }

    private void finishAfterDisconnect(String finalStatus) {
        currentOperation = null;
        currentPayload = null;
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
        closeConnection();
        currentOperation = null;
        currentPayload = null;
        status("Pager relay stopped.");
    }

    private void status(String value) {
        statusCallback.onStatus(value);
    }

    private enum Operation {
        HOLD_LINK,
        SEND,
        READ_STATUS
    }
}
