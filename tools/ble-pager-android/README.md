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
not retain notification content, and does not use a network service. It stores
only X3's app-level Pager enrollment token.

## Payload compatibility

The app matches the firmware and PC test-page protocol exactly:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- read-only policy/status characteristic:
  `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001`
- encoding: UTF-8 `XPAGER1\nDATA\n<16-hex-token>\n<title>\nmessage\nfooter`
- maximum payload: 320 bytes total; display text is limited to 290 bytes after
  the token header

The test page refuses an oversized payload, like the browser test page.
Notification delivery preserves the title and source-app footer, truncating the
message at UTF-8 character boundaries when necessary.

The policy/status read is device-owned and read-only. It reports availability,
receive-window timing, Always Available profile, enrollment state, and the
latest BLE link timing. If X3 setup is open, **Refresh pager policy** stores the
setup token locally. The first valid write enrolls the phone. After that, the
Android relay writes notifications directly after connecting so periodic
availability windows are not spent on a status read.

## Connection mode for battery testing

The app has two Android-side connection modes:

- **Connect per message** scans, connects, sends or reads, then disconnects.
  This is the default. After the app has seen X3 report
  `availability=mailbox`, it keeps only the latest pending update and schedules
  bounded scans around the expected receive windows instead of scanning
  continuously. If a window is missed, the pending update remains queued and
  the next scan realigns with X3's following mailbox interval.
- **Keep connected while relay is active** opens and holds the GATT connection
  after **Start notification relay**. Use this only with X3 **Always Available**
  mode when measuring whether X3 consumes less current while advertising idle
  or connected idle. Periodic availability still disconnects from the X3 side
  after the receive window.

## Beat mode for diagnostics

**Start beat mode** runs a bounded periodic BLE status read and appends each
result to the in-app event log. Use it when validating whether X3 ever exposes
the Pager service. If X3 is in Always Available mode, the log should show
regular `Beat: X3 online` entries. If X3 is in periodic availability, the beat
will report misses between receive windows and online entries when Android
catches a window. Stop beat mode when you finish debugging; it scans
periodically and is intentionally not a battery-saving phone mode.

The **Enable event log** switch controls whether completed operations and
diagnostic results are retained in the on-screen log. It does not disable the
current status shown below **Send pager update**. Mailbox wait status counts
down there once per second without adding every countdown tick to the log.

## Build a debug APK

Install Android SDK Platform 35 and a JDK 17, set `ANDROID_HOME` to the SDK,
then run from this directory:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to:

```text
app\build\outputs\apk\debug\crosspoint-pager.apk
```

Install on a connected Android device with:

```powershell
adb install -r app\build\outputs\apk\debug\crosspoint-pager.apk
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
   update**. The byte counter must remain at or below 290. Select **Refresh
   pager policy** to read the X3-owned availability, enrollment, and link
   status. If X3 says setup is open, this stores the setup token locally.
6. To diagnose discovery, select **Start beat mode** and watch the event log
   for online/missed checks. Disable **Enable event log** when the history is
   not needed; the current operation and mailbox countdown remain visible.

For periodic availability, Android can schedule around mailbox windows only
after it has learned the mailbox interval from X3 status. If schedule is not
known yet, the first send still performs an immediate bounded scan; once a
mailbox status read or delivery succeeds while X3 reports `availability=mailbox`,
later test sends and forwarded notifications use the mailbox queue.

If the phone loses its token or X3 is reset, use **Settings → System → Pager →
Enrolled Device → Reset** on X3, re-enter Pager standby, then select **Refresh
pager policy** in the app. The app forwards all non-ongoing notifications while
the relay is running. Pager enrollment is not Bluetooth bonding or strong
cryptographic authentication, so do not enable it where nearby unbonded BLE
delivery is inappropriate.

## Hardware validation still needed

An APK build verifies only the Android package. Validate on a physical Android
phone and Xteink X3/X4: permission prompts, notification access, scanner
discovery, repeated delivery, reconnect after range loss, power-button exit,
and the Pager battery/current behaviour described in
[`../../docs/ble-pager-power-plan.md`](../../docs/ble-pager-power-plan.md).
