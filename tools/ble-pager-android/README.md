# CrossPoint Pager for Android

This is an experimental Android companion for the opt-in BLE Pager firmware. It
does two local-only jobs:

- relays new Android notifications to `CrossPoint Pager` while the relay is
  explicitly running; and
- provides a test page with the same **Title**, **Message**, and **Footer**
  fields as [`../ble-pager-test`](../ble-pager-test/README.md).

It scans for the Pager's custom GATT service, connects, writes the latest
payload, then disconnects. It does not use Android's normal Bluetooth pairing,
does not retain notification content, and does not use a network service.

## Payload compatibility

The app matches the firmware and PC test-page protocol exactly:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- encoding: UTF-8 `title\nmessage\nfooter`
- maximum payload: 320 bytes

The test page refuses an oversized payload, like the browser test page.
Notification delivery preserves the title and source-app footer, truncating the
message at UTF-8 character boundaries when necessary.

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
3. To relay notifications, open Android's **Notification access** screen from
   the app, allow *CrossPoint Pager*, then select **Start notification
   relay**. The persistent Android notification means the relay is active.
4. To test directly, enter Title, Message, and Footer and select **Send pager
   update**. The byte counter must remain at or below 320.

The app forwards all non-ongoing notifications while the relay is running. Do
not enable it where nearby unbonded BLE delivery is inappropriate, and do not
send sensitive notification content until Pager adds authentication.

## Hardware validation still needed

An APK build verifies only the Android package. Validate on a physical Android
phone and Xteink X3/X4: permission prompts, notification access, scanner
discovery, repeated delivery, reconnect after range loss, power-button exit,
and the Pager battery/current behaviour described in
[`../../docs/ble-pager-power-plan.md`](../../docs/ble-pager-power-plan.md).
