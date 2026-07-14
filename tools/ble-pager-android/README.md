# CrossPoint Pager for Android

This is an experimental Android companion for the opt-in BLE Pager firmware. It
does two local-only jobs:

- relays new Android notifications to `CrossPoint Pager` while the relay is
  explicitly running; and
- provides a test page with the same **Title**, **Message**, and **Footer**
  fields, policy read, and identical-payload warning as
  [`../ble-pager-test`](../ble-pager-test/README.md).

It scans for the Pager's custom GATT service, connects, writes the latest
payload, then disconnects by default. For X3 battery measurements in
**Always Available** mode, the app can instead hold the BLE connection while
the relay is active. It does not use Android's normal Bluetooth pairing, does
not retain notification content, and does not use a network service.

## Payload compatibility

The app matches the firmware and PC test-page protocol exactly:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- read-only policy/status characteristic:
  `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001`
- encoding: UTF-8 `title\nmessage\nfooter`
- maximum payload: 320 bytes

The test page refuses an oversized payload, like the browser test page.
Notification delivery preserves the title and source-app footer, truncating the
message at UTF-8 character boundaries when necessary.

The policy/status read is device-owned and read-only. It reports availability,
receive-window timing, normal power profile, and the latest BLE link timing.
The Android relay still writes notifications directly after connecting so
periodic availability windows are not spent on a status read.

## Connection mode for battery testing

The app has two Android-side connection modes:

- **Connect per message** scans, connects, sends or reads, then disconnects.
  This is the default and works with both Always Available and periodic
  availability.
- **Keep connected while relay is active** opens and holds the GATT connection
  after **Start notification relay**. Use this only with X3 **Always Available**
  mode when measuring whether X3 consumes less current while advertising idle
  or connected idle. Periodic availability still disconnects from the X3 side
  after the receive window.

## Build a debug APK

Install Android SDK Platform 35 and a JDK 17, set `ANDROID_HOME` to the SDK,
then run from this directory:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install on a connected Android device with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Use it

1. On the Xteink, select **Settings → Display → Sleep Screen → Pager**, then
   enter Pager standby.
2. Open the app and grant its nearby-device Bluetooth permission.
3. To compare X3 power modes, choose **Connect per message** or **Keep
   connected while relay is active** before starting the relay.
4. To relay notifications, open Android's **Notification access** screen from
   the app, allow *CrossPoint Pager*, then select **Start notification
   relay**. The persistent Android notification means the relay is active.
5. To test directly, enter Title, Message, and Footer and select **Send pager
   update**. The byte counter must remain at or below 320. Select **Refresh
   pager policy** to read the X3-owned availability and link status.

The app forwards all non-ongoing notifications while the relay is running. Do
not enable it where nearby unbonded BLE delivery is inappropriate, and do not
send sensitive notification content until Pager adds authentication.

## Hardware validation still needed

An APK build verifies only the Android package. Validate on a physical Android
phone and Xteink X3/X4: permission prompts, notification access, scanner
discovery, repeated delivery, reconnect after range loss, power-button exit,
and the Pager battery/current behaviour described in
[`../../docs/ble-pager-power-plan.md`](../../docs/ble-pager-power-plan.md).
