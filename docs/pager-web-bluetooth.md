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
flashable file is `.pio\build\pager_power\firmware.bin`. The experimental
`pager_power` environment enables BLE modem sleep and automatic light sleep;
use `-e default` only when you specifically want the normal reader build.

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
2. For a visible e-ink update, choose **Settings → Display → Sleep Screen →
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
3. Enter a title, message, and footer, then select **Send pager update**.

The browser writes a compact UTF-8 GATT payload to the device. Pager refreshes
the e-ink screen only when that payload changes. It uses fast e-ink refreshes
for message updates and performs its cleanup refresh according to **Settings >
Display > Refresh Frequency**.

## If the browser cannot find Pager

- Wake the Xteink, then enter Pager standby again with **Sleep Screen → Pager** selected.
- Make sure Wi-Fi is not active on the device; Pager uses BLE only.
- Use Edge or Chrome and `http://localhost:8080`; unsupported browsers will not
  expose the Web Bluetooth chooser.
- Move the PC closer to the Xteink, then choose **Connect Bluetooth** again.

Pager is an unbonded development service. Use it only with trusted nearby PCs
and do not send sensitive information.
