# BLE Dashboard experiment

This fork adds an opt-in BLE dashboard for Xteink X3/X4. It is a local
experiment, not an upstream CrossPoint feature.

## What it does

- **Settings → System → Bluetooth Pairing** starts a small custom GATT
  peripheral named `CrossPoint Dashboard`.
- **Settings → Display → Sleep Screen → Dashboard** makes the sleep screen
  receive dashboard updates from that GATT peripheral.
- The writable characteristic accepts UTF-8 `title\nmessage\nfooter` data,
  capped at 320 bytes.
- The display is refreshed only when the received payload differs from the
  previous one.

The protocol identifiers are:

| Item | UUID |
| --- | --- |
| Dashboard service | `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Writable payload characteristic | `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001` |

Use the [PC Web Bluetooth test page](../tools/ble-dashboard-test/README.md)
to exercise the protocol from Edge or Chrome.

## Connection and power model

BLE is completely off outside the pairing page and Dashboard sleep mode. It
is BLE-only: starting it turns Wi-Fi off rather than attempting unmeasured
Wi-Fi/BLE coexistence.

Dashboard is powered-on standby, not ESP32 deep sleep. Deep sleep powers the
radio off, so a truly periodic wake-and-receive design needs a later hardware
current-draw and reconnect study. While the dashboard service is running, the
firmware keeps the ESP32-C3 at normal CPU frequency; the BLE controller is not
reliable at the 10 MHz idle frequency.

The service is not bonded or authenticated yet. Treat it as a nearby,
development-only receiver and do not send sensitive notifications.

## Discovery notes and fixes

The first implementation could log that it had started advertising yet remain
undiscoverable. Two independent constraints caused that result:

1. A full 128-bit service UUID and the readable `CrossPoint Dashboard` name do
   not both fit in the 31-byte primary BLE advertising packet. The service UUID
   now stays in the primary advertisement, while the device name is returned
   in the active-scan response.
2. The normal firmware idles the ESP32-C3 at 10 MHz after a short period of
   inactivity. NimBLE may initialize at that clock but its controller is not
   dependable there. The main loop now holds normal CPU frequency for the
   lifetime of the opt-in dashboard service.

For diagnosis, serial output should include:

```text
[INF] [BLE] Dashboard advertising started
```

The regular Windows/macOS Bluetooth pairing UI may not present an unbonded
custom GATT peripheral as a normal pairing target. Use the Web Bluetooth page
or a BLE GATT scanner and filter by the service UUID instead.
