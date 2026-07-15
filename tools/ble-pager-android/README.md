# CrossPoint Pager for Android

This is an experimental Android companion for the opt-in BLE Pager firmware. It
does two local-only jobs:

It supports Xteink models X3 and X4 running CrossPoint Pager firmware.

- relays new or updated Android notification events to
  `CrossPoint Pager` while the relay is explicitly running; and
- provides a test page with the same **Title**, **Message**, and **Footer**
  fields, plus a multi-notification stack editor, policy read, and
  one-line overflow preview as
  [`../ble-pager-test`](../ble-pager-test/README.md).

The user first chooses one nearby Xteink from a foreground scan. The app then
targets only that BLE address, connects, writes the pending event batch, and
disconnects by default. For Xteink battery measurements in
**Always Available** mode, the app can instead hold the BLE connection while
the relay is active. It does not use Android's normal Bluetooth pairing or a
network service. It stores bounded pending events until their
individual writes succeed, plus a short sent-ID ledger. It also stores
one selected Xteink, its app-level Pager enrollment token, and its latest saved
policy. Choosing a different Xteink clears the previous device completely; the
app does not maintain multiple device profiles.

## Payload compatibility

The app matches the firmware protocol exactly; the PC page keeps the legacy
`DATA` command for simple manual messages:

- service: `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001`
- writable characteristic: `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001`
- read-only policy/status characteristic:
  `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001`
- envelope: UTF-8 authenticated `DATA`, `BEGIN`, `ADD`, and `END` commands
- maximum authenticated write: 216 bytes
- `ADD` field limits: 11 bytes for time, 48 for title, and 92 for body

Each delivery writes `BEGIN`, one bounded `ADD` per pending event, then `END`,
waiting for each Android GATT write callback before advancing. Complete-stack
packets are not used. The phone measures each title beside its time and each
body against the content width, then adds trailing `...` without splitting a
Unicode code point. Firmware applies an exact-font bounds check as a fallback.

**Maximum notifications** accepts 1–10 and is stored across app restarts. The
default is four. A stable event ID prevents an unchanged active notification
from being queued again. Updated notifications receive a new event ID. Pending
events and sent IDs survive app restarts; Xteink independently ignores event IDs
already present in its fixed ring and discards the oldest entry when full.

The policy/status read is device-owned and read-only. It reports effective and
configured availability, receive-window timing, enrollment state, and the
latest BLE link timing. During enrollment, the app presents the configured
availability as the main policy and labels temporary Always Available radio
access separately. If Xteink setup is open, **Refresh pager policy** stores the
setup token locally, then writes a **Pager connection confirmed** title. That
authenticated write enrolls the phone; Android derives the first expected
mailbox window from the global UTC grid while Xteink acknowledges the write and
completes the setup-to-mailbox handoff. A successful GATT write response completes the manual
policy refresh; the app does not immediately read status a second time while
Xteink is changing availability. Later refreshes send the same confirmation
title when the app still has the enrolled token. Normal Pager delivery does
not read status after a write. An event becomes sent after its own `ADD` write
callback succeeds; replay after a later failure is safe because Xteink ignores
an existing event ID.

## Connection mode for battery testing

The app has two Android-side connection modes:

- **Connect per message** scans, connects, sends or reads, then disconnects.
  This is the default. After the app has seen Xteink report
  `availability=mailbox`, it keeps bounded pending events and schedules scans
  around globally rounded UTC receive windows instead of scanning continuously.
  For example, five-minute windows start at `HH:05`, `HH:10`, and `HH:15`.
Connection, write, timeout, and disconnect times never shift that grid. Stored timing
  is retained across service and app restarts. Sending and relay controls remain
  unavailable until the selected Xteink has a stored policy and usable timing;
  they never scan randomly to discover or enroll a reader.
  The app's **Stored mailbox timing** section shows the persisted interval,
  receive-window duration, and next UTC-grid window. **Refresh
  pager policy** is located in the top-level Pager status section and continuously runs bounded scans with
  a short retry gap instead of waiting for the stored mailbox window. Its
  progress appears directly below the button and keeps retrying after scan,
  connection, or policy-read failures until Xteink responds, the user selects
  **Stop policy refresh**, or the 75-minute safety limit expires. A valid policy
  updates the top-level Pager status immediately; the refresh stops
  automatically after its authenticated confirmation succeeds.
- **Keep connected while relay is active** opens and holds the GATT connection
  while the **Notification relay** switch is enabled. A manual test send also
  simulates this mode by leaving its link open after delivery, even when the
  relay is off; selecting **Connect per message** closes that test link. Use
  keep-connected mode only with Xteink **Always Available** when measuring
  whether Xteink consumes less current while advertising idle or connected
  idle. Periodic availability still disconnects from the Xteink side after the
  receive window.

## Beat mode for diagnostics

The top-level **Pager status** section lets the user explicitly **Choose
Xteink**, then shows the verified model and short identity, enrollment state,
Availability, last policy refresh, and stored mailbox timing. Setup firmware
advertises the same short model/identity code shown on the Pager screen, so the
correct reader can be selected when several are nearby. The app filters all
later connection scans to that selected BLE address. **Change Xteink** stops the
relay and Beat mode and clears the old address, identity, token, policy, and
mailbox timing before storing the new selection.

By default, only **Refresh pager policy** may update the persisted Pager status
or complete enrollment. Notification sends do not read status or alter the
stored policy.
**Auto update pager policy** is an explicit opt-in: when
enabled, a successful Beat status read from the already-selected Xteink may
update the stored policy. The switch never selects, changes, or
enrolls a device and does not start an independent scan.

**Forget stored Xteink** removes the one local selection and its enrollment
data. Beat Mode, BLE link details, the test sender, live send status, and the
Event log remain under **Debug**.

Each operation owns its live status line: policy refresh below **Refresh pager
policy**, relay activity below **Notification relay**, Beat diagnostics below
the Beat switch, and manual sends below **Send pager update**. Background relay
or Beat events therefore cannot overwrite a Test page result.

The **Beat mode** switch continuously scans, briefly pausing only to read status
when the selected Xteink appears, and appends each result to the in-app event
log. Use it when validating whether Xteink ever exposes the Pager service. If
Xteink is in Always Available mode, the log should show regular
`Beat: Xteink online` entries. If Xteink is in periodic availability, Beat
reports misses and resumes scanning immediately. Disable it when debugging is
finished; it is intentionally not a battery-saving phone mode.

The Event log has separate **Notification relay** and **Beat mode** switches to
filter its bounded 40-entry history by source. They do not disable the current
status shown below **Send pager update**. Mailbox wait status counts down there
once per second without adding every countdown tick to the log. **Clear log**
removes the current history.

Notification Relay and Beat Mode switch states are persisted. If Android or an
APK update stops the service, reopening the app resumes whichever modes remain
enabled, so an ON switch always corresponds to an active foreground service.

## Build a debug APK

This checkout already uses a user-local Android toolchain. Do not download a
second SDK or JDK before checking these locations:

- SDK: `%LOCALAPPDATA%\CrossPointPager\android-sdk`
- JDK: `%LOCALAPPDATA%\CrossPointPager\jdk\jdk-17.0.19+10`

From the repository root, run this complete PowerShell build. The preflight
checks stop with a clear error when the established toolchain is actually
missing; they do not install or modify anything:

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'CrossPointPager\jdk\jdk-17.0.19+10'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'CrossPointPager\android-sdk'

if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "CrossPoint Pager JDK not found at $env:JAVA_HOME"
}
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-35\android.jar")) {
    throw "Android SDK Platform 35 not found at $env:ANDROID_HOME"
}

Push-Location tools\ble-pager-android
try {
    .\gradlew.bat clean lintDebug assembleDebug
} finally {
    Pop-Location
}
```

The build runs Android lint and writes the debug APK to:

```text
tools\ble-pager-android\app\build\outputs\apk\debug\crosspoint-pager.apk
```

Optionally verify the APK identity with the newest installed `aapt.exe`:

```powershell
$aapt = Get-ChildItem "$env:ANDROID_HOME\build-tools\*\aapt.exe" |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1
if (-not $aapt) {
    throw "aapt.exe not found under $env:ANDROID_HOME\build-tools"
}
& $aapt.FullName dump badging tools\ble-pager-android\app\build\outputs\apk\debug\crosspoint-pager.apk
```

Install on a connected Android device with:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r tools\ble-pager-android\app\build\outputs\apk\debug\crosspoint-pager.apk
```

## Use it

1. On the Xteink, select **Settings → Display → Sleep Screen → Pager**, reset
   **Settings → System → Pager → Enrolled Device** when necessary, then enter
   Pager standby.
2. Open the app and select **Choose Xteink**. Match the model/short-ID code in
   the scan list to the code on the Xteink Pager screen. This foreground choice
   does not connect or enroll the reader.
3. Select **Refresh pager policy**. This is the default and only path that reads
   and saves policy, obtains the setup token, and completes enrollment.
4. The **App access** section shows whether Bluetooth and Android
   notification access are ready and only shows an action button when needed.
5. To compare Xteink power modes, choose **Connect per message** or **Keep
   connected while relay is active** before starting the relay.
6. To relay notifications, open Android's **Notification access** screen from
   the app, allow *CrossPoint Pager*, choose **Maximum notifications** from
   1–10, then enable **Notification relay**. The persistent Android
   notification means the relay is active.
7. To test directly, enter Title, Message, and Footer and select **Send pager
   update**. Alternatively, select **Notification stack**, add up to the saved
   **Maximum notifications**, and enter a time, title, and message for each
   row. The test stack uses the same per-event commands as the live
   notification relay. The mode summary above the fields confirms whether the test will
   disconnect after delivery or keep the link open. The byte counter shows the
   largest authenticated command and must remain at or below 216. Select **Refresh pager policy** under **Pager status** to
   read the Xteink-owned availability, enrollment, model, identity, and link
   status. Policy progress stays beneath its own button. If Xteink says setup is
   open, this stores the setup token locally and sends a **Pager connection
   confirmed** title to complete enrollment. While either operation is queued
   or scanning, its button
   changes to **Stop pager update retry** or **Stop policy refresh**. Select it to
   cancel immediately; otherwise the 75-minute safety expiry still applies.
   When stored timing is blank, or the first policy scan misses, the app shows
   a reminder to reset **Enrolled Device** on Xteink. The reminder disappears
   as soon as the policy is read successfully.
8. To diagnose discovery, enable **Beat mode** and watch its event-log category
   for online/missed checks. Disable either Event log category when that history
   is not useful, or select **Clear log** to remove it.

For periodic availability, Android schedules around mailbox windows after it
learns `configured_availability=mailbox` and the interval from Xteink status. The
first enrollment send still connects immediately through effective Always
Available setup. X3 then uses its UTC RTC; X4 receives UTC phase in every
`BEGIN`. Later test sends and forwarded notifications use the mailbox queue.

If the phone loses its token or Xteink is reset, use **Settings → System → Pager
→ Enrolled Device → Reset** on Xteink, re-enter Pager standby, then select
**Refresh pager policy** in the app. The app forwards new or updated active,
non-ongoing notifications up to the selected maximum while the relay is
running. Pager enrollment is not Bluetooth bonding or strong
cryptographic authentication, so do not enable it where nearby unbonded BLE
delivery is inappropriate.

## Hardware validation still needed

An APK build verifies only the Android package. Validate on a physical Android
phone and Xteink X3/X4: permission prompts, notification access, scanner
discovery, repeated delivery, reconnect after range loss, power-button exit,
and the Pager battery/current behaviour described in
[`../../docs/ble-pager-power-plan.md`](../../docs/ble-pager-power-plan.md).
