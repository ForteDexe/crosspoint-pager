package org.crosspointreader.pagerrelay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Foreground-only discovery for the explicit Xteink chooser. */
final class PagerDeviceScanner {
    interface Listener {
        void onScanStarted();
        void onDevicesChanged(List<DeviceCandidate> devices);
        void onScanStopped(String message);
    }

    private static final long SCAN_DURATION_MS = 12_000L;
    private static final ParcelUuid SERVICE_UUID = new ParcelUuid(UUID.fromString(PagerProtocol.SERVICE_UUID));

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DeviceCandidate> devices = new ArrayList<>();
    private boolean scanning;

    PagerDeviceScanner(Context context, Listener listener) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    void start() {
        stop(false);
        devices.clear();
        listener.onDevicesChanged(Collections.emptyList());
        if (adapter == null || !adapter.isEnabled()) {
            listener.onScanStopped("Bluetooth is off.");
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            listener.onScanStopped("BLE scanning is unavailable.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        listener.onScanStarted();
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(filter), settings, scanCallback);
        handler.postDelayed(scanTimeout, SCAN_DURATION_MS);
    }

    void stop() {
        stop(false);
    }

    @SuppressLint("MissingPermission")
    private void stop(boolean timedOut) {
        handler.removeCallbacks(scanTimeout);
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
        scanning = false;
        if (timedOut) {
            listener.onScanStopped(devices.isEmpty()
                    ? "No CrossPoint Pager found. Put the Xteink in Pager standby and scan again."
                    : "Scan complete. Select the Xteink shown on the reader screen.");
        }
    }

    private final Runnable scanTimeout = () -> stop(true);

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            remember(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                remember(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            handler.removeCallbacks(scanTimeout);
            listener.onScanStopped("BLE scan failed (" + errorCode + ").");
        }
    };

    @SuppressLint("MissingPermission")
    private void remember(ScanResult result) {
        if (!scanning || result == null || result.getDevice() == null) {
            return;
        }
        String address = result.getDevice().getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        DeviceCandidate candidate = new DeviceCandidate(address, name, result.getRssi());
        for (int index = 0; index < devices.size(); index++) {
            if (devices.get(index).address.equalsIgnoreCase(address)) {
                devices.set(index, candidate);
                listener.onDevicesChanged(new ArrayList<>(devices));
                return;
            }
        }
        devices.add(candidate);
        listener.onDevicesChanged(new ArrayList<>(devices));
    }

    static final class DeviceCandidate {
        final String address;
        final String advertisedName;
        final int rssi;

        DeviceCandidate(String address, String advertisedName, int rssi) {
            this.address = address;
            this.advertisedName = advertisedName == null ? "" : advertisedName.trim();
            this.rssi = rssi;
        }

        String displayLabel(boolean selected) {
            String name = advertisedName.startsWith("CrossPoint ")
                    ? advertisedName
                    : PagerProtocol.DEVICE_NAME + " · " + addressSuffix();
            return name + "\nSignal: " + rssi + " dBm" + (selected ? " · Selected" : "");
        }

        String storedLabel() {
            return advertisedName.startsWith("CrossPoint ")
                    ? advertisedName
                    : PagerProtocol.DEVICE_NAME + " · " + addressSuffix();
        }

        private String addressSuffix() {
            String compact = address.replace(":", "");
            return compact.substring(Math.max(0, compact.length() - 6)).toUpperCase(Locale.US);
        }
    }
}
