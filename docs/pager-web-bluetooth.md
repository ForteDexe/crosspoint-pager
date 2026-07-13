# Connect to Pager from a PC browser

Use this guide to send test messages from a PC to the Xteink Pager screen.

> **Important:** Pager uses a custom BLE GATT service. Do **not** try to pair it
> from Windows **Add a device**, macOS Bluetooth settings, or a phone's normal
> Bluetooth pairing screen. Connect through the Web Bluetooth chooser in the
> Pager test page instead.

## Before starting

1. Build the firmware, then open
   [CrossPoint Flash Tools](https://crosspointreader.com/#flash-tools).
2. In the flash tool, select and flash the built
   `.pio\build\default\firmware.bin` file from this repository.
3. On the device, open **Settings → System → Bluetooth Pairing** to confirm it
   advertises as `CrossPoint Pager`.
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
3. Enter a title, message, and footer, then select **Send pager update**.

The browser writes a compact UTF-8 GATT payload to the device. Pager refreshes
the e-ink screen only when that payload changes. It uses fast e-ink refreshes
for message updates and performs its cleanup refresh according to **Settings >
Display > Refresh Frequency**.

## If the browser cannot find Pager

- Reopen **Bluetooth Pairing** on the Xteink and keep it on that screen.
- Make sure Wi-Fi is not active on the device; Pager uses BLE only.
- Use Edge or Chrome and `http://localhost:8080`; unsupported browsers will not
  expose the Web Bluetooth chooser.
- Move the PC closer to the Xteink, then choose **Connect Bluetooth** again.

Pager is an unbonded development service. Use it only with trusted nearby PCs
and do not send sensitive information.
