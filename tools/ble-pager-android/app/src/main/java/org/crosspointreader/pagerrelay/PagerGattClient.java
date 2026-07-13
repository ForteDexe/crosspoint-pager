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

    private final Context context;
    private final StatusCallback statusCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private boolean scanning;
    private String currentPayload;
    private String queuedPayload;

    PagerGattClient(Context context, StatusCallback statusCallback) {
        this.context = context.getApplicationContext();
        this.statusCallback = statusCallback;
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    void send(String payload) {
        if (currentPayload != null || scanning || gatt != null) {
            queuedPayload = payload;
            status("Queued the latest pager update.");
            return;
        }
        begin(payload);
    }

    @SuppressLint("MissingPermission")
    private void begin(String payload) {
        currentPayload = payload;
        if (adapter == null || !adapter.isEnabled()) {
            finish("Bluetooth is off.");
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            finish("BLE scanning is unavailable.");
            return;
        }
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        status("Searching for " + PagerProtocol.DEVICE_NAME + "…");
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
            finish("CrossPoint Pager was not found. Put it in Pager standby and try again.");
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
            status("Connecting to " + PagerProtocol.DEVICE_NAME + "…");
            gatt = result.getDevice().connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                finish("Could not connect to CrossPoint Pager.");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            finish("BLE scan failed (" + errorCode + ").");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("Pager connection failed (" + status + ").");
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                status("Discovering Pager service…");
                if (!connection.discoverServices()) {
                    finish("Could not discover Pager service.");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED && gatt != null) {
                finish("Pager disconnected before delivery.");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation") // Required for Android 12 and earlier GATT writes.
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("Pager service discovery failed (" + status + ").");
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(PAYLOAD_UUID);
            if (characteristic == null || currentPayload == null) {
                finish("Pager write characteristic was not found.");
                return;
            }
            byte[] bytes = currentPayload.getBytes(StandardCharsets.UTF_8);
            status("Sending pager update…");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = connection.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (result != BluetoothStatusCodes.SUCCESS) {
                    finish("Pager write could not start (" + result + ").");
                }
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(bytes);
                if (!connection.writeCharacteristic(characteristic)) {
                    finish("Pager write could not start.");
                }
            }
        }

        @Override
        @SuppressWarnings("deprecation") // Android 12 and earlier use this callback signature.
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            finish(status == BluetoothGatt.GATT_SUCCESS ? "Pager update sent." : "Pager write failed (" + status + ").");
        }

    };

    @SuppressLint("MissingPermission")
    private void finish(String finalStatus) {
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        currentPayload = null;
        status(finalStatus);
        if (queuedPayload != null) {
            String nextPayload = queuedPayload;
            queuedPayload = null;
            handler.postDelayed(() -> begin(nextPayload), 300);
        }
    }

    void close() {
        queuedPayload = null;
        finish("Pager relay stopped.");
    }

    private void status(String value) {
        statusCallback.onStatus(value);
    }
}
