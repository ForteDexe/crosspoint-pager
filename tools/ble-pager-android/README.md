# CrossPoint Pager for Android

This is an experimental Android companion for the opt-in BLE Pager firmware. It
does two local-only jobs:

It supports Xteink models X3 and X4 running CrossPoint Pager firmware.

- relays new Android notifications to `CrossPoint Pager` while the relay is
  explicitly running; and
- provides a test page with the same **Title**, **Message**, and **Footer**
  fields, policy read, and identical-payload warning as
  [`../ble-pager-test`](../ble-pager-test/README.md).

It scans for the Pager's custom GATT service, connects, writes the latest
payload, then disconnects by default. For Xteink battery measurements in
**Always Available** mode, the app can instead hold the BLE connection while
the relay is active. It does not use Android's normal Bluetooth pairing, does
not retain notification content, and does not use a network service. It stores
only Xteink's app-level Pager enrollment token.

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

The policy/status read is device-owned and read-only. It reports effective and
configured availability, receive-window timing, enrollment state, and the
latest BLE link timing. The Always Available profile is present only while
effective availability is Always Available. If Xteink setup is open,
**Refresh pager policy** stores the setup token locally. The first valid write
enrolls the phone; Android derives the first expected mailbox window while Xteink
acknowledges the write and completes the setup-to-mailbox handoff. After that,
the relay writes notifications directly after connecting so periodic
availability windows are not spent on a status read.

## Connection mode for battery testing

The app has two Android-side connection modes:

- **Connect per message** scans, connects, sends or reads, then disconnects.
  This is the default. After the app has seen Xteink report
  `availability=mailbox`, it keeps only the latest pending update and schedules
  bounded scans around the expected receive windows instead of scanning
  continuously. If a window is missed, the pending update remains queued and
  the next scan realigns with Xteink's following mailbox interval. Learned timing
  is retained across service and app restarts. Until timing is known, a pending
  latest update retries every 30 seconds instead of stopping after one scan.
  The app's **Learned mailbox timing** section shows the persisted interval,
  receive-window duration, and next estimated window. It refreshes whenever
  Xteink status or a successful delivery updates the schedule. **Refresh pager
  policy** is located in this section and continuously runs bounded scans with
  a short retry gap instead of waiting for the learned mailbox window. Its
  progress appears directly below the button and keeps retrying after scan,
  connection, or policy-read failures until Xteink responds, the user selects
  **Stop policy retry**, or the 75-minute safety limit expires.
- **Keep connected while relay is active** opens and holds the GATT connection
  while the **Notification relay** switch is enabled. A manual test send also
  simulates this mode by leaving its link open after delivery, even when the
  relay is off; selecting **Connect per message** closes that test link. Use
  keep-connected mode only with Xteink **Always Available** when measuring
  whether Xteink consumes less current while advertising idle or connected
  idle. Periodic availability still disconnects from the Xteink side after the
  receive window.

## Beat mode for diagnostics

Beat Mode, learned mailbox timing, the test sender, live status, and the Event
log are grouped under the collapsed **Debug** row. Collapsing this row only
hides the controls; it does not stop an active relay, Beat Mode, or retry.

The **Beat mode** switch runs a bounded periodic BLE status read and appends
each result to the in-app event log. Use it when validating whether Xteink ever
exposes the Pager service. If Xteink is in Always Available mode, the log should
show regular `Beat: Xteink online` entries. If Xteink is in periodic
availability, the beat
will report misses between receive windows and online entries when Android
catches a window. Disable Beat mode when you finish debugging; it scans
periodically and is intentionally not a battery-saving phone mode.

The Event log has separate **Notification relay** and **Beat mode** switches to
filter its bounded 40-entry history by source. They do not disable the current
status shown below **Send pager update**. Mailbox wait status counts down there
once per second without adding every countdown tick to the log. **Clear log**
removes the current history.

Notification Relay and Beat Mode switch states are persisted. If Android or an
APK update stops the service, reopening the app resumes whichever modes remain
enabled, so an ON switch always corresponds to an active foreground service.

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
3. To compare Xteink power modes, choose **Connect per message** or **Keep
   connected while relay is active** before starting the relay.
4. To relay notifications, open Android's **Notification access** screen from
   the app, allow *CrossPoint Pager*, then enable **Notification relay**. The
   persistent Android notification means the relay is active.
5. To test directly, enter Title, Message, and Footer and select **Send pager
   update**. The mode summary above the fields confirms whether the test will
   disconnect after delivery or keep the link open. The byte counter must
   remain at or below 290. Select **Refresh pager policy** under **Learned
   mailbox timing** to read the Xteink-owned availability, enrollment, and link
   status. Policy progress stays beneath its own button. If Xteink says setup is
   open, this stores the setup token locally. While either operation is queued
   or scanning, its button
   changes to **Stop pager update retry** or **Stop policy retry**. Select it to
   cancel immediately; otherwise the 75-minute safety expiry still applies.
6. To diagnose discovery, enable **Beat mode** and watch its event-log category
   for online/missed checks. Disable either Event log category when that history
   is not useful, or select **Clear log** to remove it.

For periodic availability, Android schedules around mailbox windows after it
learns `configured_availability=mailbox` and the interval from Xteink status. The
first enrollment send still connects immediately through effective Always
Available setup; its acknowledged write establishes the first mailbox estimate.
Later test sends and forwarded notifications use the mailbox queue.

If the phone loses its token or Xteink is reset, use **Settings → System → Pager
→ Enrolled Device → Reset** on Xteink, re-enter Pager standby, then select **Refresh
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
