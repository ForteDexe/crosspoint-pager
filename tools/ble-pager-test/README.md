# BLE Pager PC test page

This is a no-build Web Bluetooth harness for the first BLE pager firmware.
It writes UTF-8 text to the device's custom GATT service:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- payload: `title\nmessage\nfooter`, maximum 320 UTF-8 bytes

## Run it

1. Build and flash the firmware, then on the Xteink choose **Settings → System → Bluetooth Pairing** to confirm that it advertises. Press Back when finished.
2. Select **Settings → Display → Sleep Screen → Pager**, then put the device to sleep. Pager standby starts advertising and keeps the MCU powered so it can receive BLE.
3. In PowerShell, serve this directory from the repository root:

   ```powershell
   .\.conda\python.exe -m http.server 8080 --directory tools\ble-pager-test
   ```

4. Open `http://localhost:8080` in a Web Bluetooth-capable Chromium browser such as Edge or Chrome. Use **Connect Bluetooth**, choose `CrossPoint Pager`, then send an update.

The browser must use `localhost` or HTTPS; do not open `index.html` directly as a file. The test service does not create a bonded or authenticated relationship yet, so use it only with trusted nearby PCs during development. The device advertises its custom service and responds to active scans with the `CrossPoint Pager` name. The normal operating-system pairing dialog may not list this unbonded custom GATT peripheral; use the Web Bluetooth chooser instead.

## Current power behavior

Pager is intentionally not true deep sleep: ESP32-C3 deep sleep powers the BLE radio off. In this first implementation it is a powered-on standby screen and performs an e-ink refresh only after a changed payload arrives. Message updates use fast refresh; the cleanup refresh follows **Settings > Display > Refresh Frequency**. Measuring light-sleep/periodic-wake power and designing reconnect behavior are follow-up hardware work.

For architecture and the advertising/CPU-clock fixes that made the service discoverable, see [BLE Pager experiment](../../docs/ble-pager.md).
