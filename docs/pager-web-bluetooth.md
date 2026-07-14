# Connect to Pager from a PC browser

Use this guide to send test messages from a PC to the Xteink Pager screen.

> **Important:** Pager uses a custom BLE GATT service. Do **not** try to pair it
> from Windows **Add a device**, macOS Bluetooth settings, or a phone's normal
> Bluetooth pairing screen. Connect through the Web Bluetooth chooser in the
> Pager test page instead.

## Build the firmware

The build uses a project-local Conda environment in `.conda`. It needs Conda's
`python` (version 3.11) and `pip` packages, plus the `platformio` Python
package. Create it once from the repository root:

```powershell
conda create --prefix .conda python=3.11 pip -y
conda run --prefix .conda python -m pip install platformio==6.1.19
```

`bleak` is not required: the Pager test page uses the browser's Web Bluetooth
API and Python's built-in HTTP server.

Build the validated low-power Pager firmware with:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
.\.conda\Scripts\pio.exe run -e pager_power
```

The first build downloads the ESP32 platform and toolchain. On success, the
flashable file is `.pio\build\pager_power\firmware.bin`. The `pager_power`
environment enables BLE modem sleep and automatic light sleep and disables
serial logging for battery tests. For serial diagnosis, build
`pager_power_debug`; use `-e default` only when you specifically want the normal
reader build.

## Flash the firmware

With the Xteink connected by USB and no serial monitor open, flash the same
environment directly from PowerShell:

```powershell
.\.conda\Scripts\pio.exe run -e pager_power -t upload
```

Alternatively, open [CrossPoint Flash Tools](https://crosspointreader.com/#flash-tools)
and select `.pio\build\pager_power\firmware.bin`.

## Before starting

1. Flash the firmware with one of the methods above.
2. In **Settings → System → Pager → Availability**, choose **Always Available**
   for continuously available PC debugging, or **Every 1/5/15/30/60 min** for
   timer-based delivery. The default periodic interval is 5 minutes. The
   separate **Always Available Profile** chooses Responsive, Balanced, or Battery
   Saver link preferences when Availability is Always Available.
3. If you need to enroll a new browser or phone, use **Settings → System →
   Pager → Enrolled Device → Reset** before entering Pager. With no enrolled
   client, X3 opens setup in Always Available mode even if periodic availability
   is selected.
4. For a visible e-ink update, choose **Settings → Display → Sleep Screen →
   Pager**, then put the device into sleep/Pager standby.

## Start the local web server

Open PowerShell at the repository root and run:

```powershell
.\.conda\python.exe -m http.server 8080 --directory tools\ble-pager-test
```

Keep that PowerShell window open while testing. Then open this address in Edge
or Chrome:

```text
http://localhost:8080
```

Do not open `index.html` directly from File Explorer: Web Bluetooth requires a
secure context such as `localhost` or HTTPS.

## Connect and send a message

1. Select **Connect Bluetooth** in the browser page.
2. In the browser's device chooser, select **CrossPoint Pager**.
3. Check **X3 Pager policy** to see the device's Availability, Always Available
   profile, enrollment state, and latest negotiated BLE link timing. If setup
   is open, the policy read stores X3's setup token in this browser.
4. Enter a title, message, and footer, then select **Send pager update**.

The browser writes a compact authenticated UTF-8 GATT payload to the device.
The first valid write after reset enrolls this browser. Pager refreshes the
e-ink screen only when the display payload changes. It uses fast e-ink
refreshes for message updates and performs its cleanup refresh according to
**Settings > Display > Refresh Frequency**.

**Always Available** is the X3-controlled continuous policy: the browser
connection stays open until the browser or user disconnects. Its Normal Power
Profile asks the central for a link interval and latency; the browser does not
schedule this itself, and the central may adjust X3's request. **Periodic
availability** is also X3-controlled: it fully deinitializes BLE between
windows, advertises for two seconds, and closes a delivered or idle connection
after 250 ms or three seconds respectively. To save scan-response traffic, a
periodic window may appear without the `CrossPoint Pager` name in a chooser; it
is still selected by the service UUID. The browser cannot retry in the
background, so use Always Available for interactive PC testing.

The test page reads policy and effective link status from X3. It intentionally
cannot write those settings: X3 remains the battery-policy authority.

## If the browser cannot find Pager

- Wake the Xteink, then enter Pager standby again with **Sleep Screen → Pager** selected.
- Make sure Wi-Fi is not active on the device; Pager uses BLE only.
- Use Edge or Chrome and `http://localhost:8080`; unsupported browsers will not
  expose the Web Bluetooth chooser.
- Move the PC closer to the Xteink, then choose **Connect Bluetooth** again.

Pager is not Bluetooth-bonded. The setup token is app-level enrollment, not
strong cryptographic pairing. Use it only with trusted nearby PCs and do not
send sensitive information.
