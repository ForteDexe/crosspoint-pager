# BLE Pager PC test page

This is a no-build Web Bluetooth harness for the first BLE pager firmware.
It writes UTF-8 text to the device's custom GATT service:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- read-only policy/status characteristic: `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001`
- payload: `title\nmessage\nfooter`, maximum 320 UTF-8 bytes

## Run it

1. Build and flash the firmware. On the Xteink, select **Settings → Display → Sleep Screen → Pager**, then put the device to sleep. Pager standby starts advertising and keeps the MCU powered so it can receive BLE.
2. In PowerShell, serve this directory from the repository root:

   ```powershell
   .\.conda\python.exe -m http.server 8080 --directory tools\ble-pager-test
   ```

3. Open `http://localhost:8080` in a Web Bluetooth-capable Chromium browser such as Edge or Chrome. Use **Connect Bluetooth**, choose `CrossPoint Pager`, then send an update.

The browser must use `localhost` or HTTPS; do not open `index.html` directly as a file. The test service does not create a bonded or authenticated relationship yet, so use it only with trusted nearby PCs during development. Always Available responds to active scans with the `CrossPoint Pager` name. Periodic availability omits that optional response to save radio work, so it may appear unnamed. The normal operating-system pairing dialog may not list this unbonded custom GATT peripheral; use the Web Bluetooth chooser instead.

## Choose the policy on X3

Pager policy belongs to the device, not to a nearby client. Before entering
Pager standby, select **Settings → System → Pager**:

- **Availability → Always Available** is the continuously available debugging
  mode. The test page may stay connected until it or the user disconnects.
- **Availability → Every 1/5/15/30/60 min** deinitializes BLE between receive
  windows (5 minutes is the default). X3 advertises for two seconds, accepts a
  valid payload, acknowledges its GATT write, then closes the connection after
  250 ms. An idle client is closed after three seconds.
- **Always Available Profile** selects Responsive, Balanced, or Battery Saver BLE
  connection preferences for Always Available. The central may adjust the
  requested parameters.

After connecting, the page reads and displays X3's policy plus the latest BLE
link timing. This status is read-only; policy remains controlled on X3. The
browser cannot scan and reconnect in the background, so use **Always Available**
for interactive PC debugging. With periodic availability, connect only while
the X3's short advertising window is visible.

## Current power behavior

Pager is intentionally not true deep sleep: ESP32-C3 deep sleep powers the BLE radio off. With periodic availability, X3 uses timer light sleep with BLE fully deinitialized between receive windows; its battery latch remains held so it resumes without rebooting. It performs an e-ink refresh only after a changed payload arrives. Message updates use fast refresh; the cleanup refresh follows **Settings > Display > Refresh Frequency**. Measure current before making battery-life claims.

For architecture and the advertising/CPU-clock fixes that made the service discoverable, see [BLE Pager experiment](../../docs/ble-pager.md).
