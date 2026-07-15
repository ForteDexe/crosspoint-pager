# BLE Pager contract

Status: proposed recovery contract, based on `dcfd20f9`.

This file defines the intended behavior shared by the Xteink firmware and the
Android companion. If code and this file disagree, the code is wrong. Change
this contract and obtain approval before changing behavior on either side.

Pager is an opt-in fork experiment for Xteink X3/X4. It is not an upstream
CrossPoint feature. It must remain passive, BLE-only, low-power, and isolated
from normal reading.

## Scope

Pager does only the following:

- receive one latest notification snapshot from one selected Android device;
- display that snapshot without scrolling;
- offer continuous availability for setup/debugging or periodic availability
  for lower-power delivery;
- preserve normal reader behavior when Pager is inactive.

Pager does not provide chat, notification history, remote firmware settings,
Bluetooth bonding, sensitive-message security, or guaranteed real-time
delivery. Wi-Fi must not run with Pager BLE.

## Authorities and ownership

Xteink firmware is the authority for:

- configured availability and mailbox interval;
- Always Available power profile;
- enrollment token and enrollment state;
- BLE/window lifetime, battery safeguards, and e-ink refreshes.

The Android app is the authority for:

- the one user-selected Xteink;
- the copied enrollment token;
- the latest pending notification snapshot;
- notification count, retry lifetime, and diagnostic Beat mode.

Firmware implementation ownership:

- `HalBlePager` owns GATT, authentication, duplicate comparison, connections,
  advertising, and mailbox radio state;
- `SleepActivity` owns Pager entry/exit, rendering, enrollment persistence,
  battery checks, and mailbox sleep coordination;
- `HalPowerManager` owns light/deep sleep and the Xteink power latch.

Android implementation ownership:

- `PagerProtocol` owns wire encoding and status parsing;
- `PagerGattClient` owns one serialized BLE operation, mailbox scheduling,
  retries, and Beat;
- `RelayPreferences` owns persisted Android state;
- `NotificationRelayService` owns the complete notification snapshot;
- `PagerRelayService` owns foreground-service lifetime.

## Change impact map

Use this table before editing Pager behavior. A change is incomplete until each
applicable owner in its row has been reviewed, even when only one side needs a
code change.

| Contract area | Firmware owners | Android owners | Required checks |
| --- | --- | --- | --- |
| Enrollment and identity | `HalBlePager` authentication/handoff; `SleepActivity` persistence and transition; Pager settings reset | `PagerProtocol` status/token parsing; `PagerGattClient` manual refresh/confirmation; `RelayPreferences` selected identity | Setup status, confirmation, disconnect, configured-mode handoff |
| Mailbox cadence | `HalBlePager` window anchor/state; `SleepActivity` radio suspend/resume; `HalPowerManager` timer sleep | `PagerGattClient` estimate/retry; `RelayPreferences` persisted schedule | First, second, third, missed, and restored windows |
| Delivery result | `HalBlePager` authenticated write response and fallback disconnect | `PagerProtocol` packet; `PagerGattClient` serialized write/terminal result | Changed, duplicate, rejected, and expired payloads |
| Notification snapshot | `SleepActivity` parsing/rendering and refresh count | `NotificationRelayService` snapshot; app notification-limit setting | 1, maximum, empty, long UTF-8, and overflow cases |
| Beat diagnostics | Status read must remain passive | `PagerGattClient` continuous scan/serialization; service/UI enable state | Consecutive windows and pause/resume around foreground BLE |
| Pager entry, exit, and battery | `SleepActivity`, `HalPowerManager`, display refresh ownership | Status text only | Reader return, power-button-only exit, low-battery deep sleep |
| Wire or status fields | `HalBlePager` UUIDs, limits, status writer | `PagerProtocol` constants/parser plus every consumer above | Backward parsing, unknown fields, payload-size boundary |

For every change, record the contract section, affected row, code owners
reviewed, deterministic checks, builds, and hardware gates in the commit or
handoff. Do not infer impact from the file being edited; trace it from this
table first.

## Terms

- **Always Available:** Xteink advertises whenever Pager is active and no client
  is connected.
- **Mailbox:** Xteink advertises only during a periodic receive window and uses
  timer light sleep between windows.
- **Window:** one mailbox advertising opportunity, currently 2 seconds.
- **Interval:** time from the scheduled start of one mailbox window to the
  scheduled start of the next. A 5-minute interval means window starts are
  five minutes apart; the 2-second window is not added to that period.
- **Enrollment:** app-level possession of the current 16-hex token. It is not
  operating-system Bluetooth pairing.
- **Beat:** an explicit, phone-battery-heavy diagnostic that continuously looks
  for the selected Xteink.

## Firmware states

| State | BLE | Exit/transition |
| --- | --- | --- |
| Inactive | Off | Enter Pager -> Setup, Always Available, or Mailbox |
| Setup | Advertises continuously | Valid confirmation -> configured availability |
| Always Available | Advertises unless connected | Client disconnect -> advertise again |
| Mailbox window | Advertises for one window | Window ends -> Mailbox sleep |
| Mailbox sleep | NimBLE deinitialized; timer light sleep | Scheduled timer -> Mailbox window |
| Low-battery sleep | Off; normal deep sleep | Physical wake/recharge path |

Only a configured power-button hold/release exits Pager. Other buttons do
nothing. Exit returns to the previous book and refresh cycle when Pager was
entered while reading; otherwise it returns Home.

At 25% battery or lower, firmware stops Pager BLE, shows the low-battery sleep
screen, and enters deep sleep.

## Android states

| State | Meaning | Allowed next action |
| --- | --- | --- |
| No selection | No Xteink address is trusted | User selects one advertised Pager |
| Needs policy | Address selected; identity/token/policy incomplete | Manual Refresh pager policy |
| Ready | Identity, token, enrollment, and policy are stored | Relay, Test send, Refresh, or Beat |
| Delivering | One scan/connect/read-or-write owns BLE | Complete, fail, cancel, or retain latest payload |

Selecting another Xteink stops relay/Beat and clears the previous identity,
token, policy, and mailbox schedule. The app does not maintain multiple device
profiles.

Only manual **Refresh pager policy** may establish identity, copy a setup token,
or complete enrollment. Other operations use stored Pager status. If **Auto
update pager policy** is enabled, Beat may refresh policy fields for the already
selected identity; it still cannot select or enroll another device.

A manual policy refresh scans until it succeeds, the user cancels it, Bluetooth
becomes unavailable, or its 75-minute retry lifetime expires. It stops after a
valid policy and any required enrollment confirmation are complete.

## Enrollment

1. Changing any setting under **Settings > System > Pager**, or selecting
   **Reset Enrolled Device**, invalidates the current enrollment and token.
2. On the next Pager entry, firmware generates a fresh token and enters Setup.
3. Setup is effectively Always Available even when Mailbox is configured.
4. Status reports effective availability, configured availability, identity,
   and the setup token separately.
5. Android's manual policy refresh reads and validates that status, stores the
   selected identity/policy/token, then writes the visible
   `Pager connection confirmed` body with the token.
6. A valid confirmation enrolls the client. Android closes the connection.
7. If Always Available is configured, firmware resumes advertising.
8. If Mailbox is configured, firmware schedules the first mailbox window one
   interval after the enrollment connection closes and sleeps until then.

An already-enrolled Xteink never exposes its token. An app without the matching
token must instruct the user to reset Enrolled Device.

## Mailbox timing

Mailbox uses one repeating schedule:

```text
window_start[n + 1] = window_start[n] + interval
```

Rules:

1. Closing an unanswered window does not add `window_ms` to the interval.
2. Android persists the interval, window duration, and estimated next start.
3. A missed window advances the estimate by whole intervals until it is in the
   future.
4. A successful mailbox contact re-anchors Android's next estimate to the
   observed window plus one interval.
5. Android begins its scheduled scan early enough to cover the full receive
   window. A miss retains only the newest payload for the next window.
6. A pending payload expires after 75 minutes.
7. App restart restores the schedule, not an in-memory pending payload.
8. `next_window_ms` is an optional correction hint. `interval_s` is the stable
   scheduling field.

Mailbox firmware normally lets Android disconnect. As battery-protection
fallbacks, firmware disconnects shortly after a write response and disconnects
an idle connection after a bounded timeout. These fallbacks must not change the
next scheduled window start.

Mailbox requires the `pager_power` firmware. A build that cannot support Pager
timer light sleep must not claim or emulate mailbox timing.

## Beat

Beat is independent of the learned mailbox schedule.

While Beat is enabled, Android continuously scans for the selected address and
Pager service. It does not stop after a 12-second sample or wait for a predicted
window. When Xteink appears, Beat:

1. pauses scanning;
2. connects and reads status;
3. logs the observed time and result;
4. disconnects;
5. re-anchors mailbox timing from that observation;
6. immediately resumes scanning.

A foreground send or manual policy refresh may temporarily own BLE. Beat resumes
as soon as that operation ends. Beat stops only when the user disables it, the
selected device is forgotten/replaced, Bluetooth becomes unavailable, or the
service is explicitly stopped.

Beat is diagnostic and may consume significant phone battery. It must not keep
Xteink awake, change Xteink policy, send display content, or enroll a device.

## Wire protocol

| Item | Value |
| --- | --- |
| Service UUID | `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Payload characteristic | `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Status characteristic | `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Maximum authenticated write | 320 UTF-8 bytes |
| Token | 16 hexadecimal characters |
| Maximum display body | 290 UTF-8 bytes |

Authenticated writes are:

```text
XPAGER1
DATA
<token>
<display body>
```

Supported display bodies are:

```text
<title>
<message>
<footer>
```

and a complete replacement notification snapshot:

```text
XPSTACK1
<time>\t<title>\t<message>
...
```

The snapshot is newest first, contains 0-10 notifications, and never scrolls.
Older entries beyond the configured maximum are discarded. An empty stack
returns the display to Pager standby.

Status v5 is the recovery compatibility baseline. Required policy fields are
`v`, `model`, `device_id`, `availability`, `configured_availability`,
`interval_s`, `window_ms`, `enrolled`, `enroll_token`, and `next_window_ms`.
Clients ignore unknown fields. Link timing, connection state, power profile,
and `last_write` may exist as diagnostics but are not synchronization inputs.

## Delivery and display rules

1. Android allows only one GATT operation at a time.
2. The newest complete notification snapshot replaces any older queued
   snapshot.
3. Android normally closes the connection after the GATT write response.
4. A successful GATT write response is the terminal phone-side delivery result.
   Android does not perform a post-write status read.
5. GATT success proves transport delivery, not token acceptance or a display
   repaint. This limitation is accepted to keep the protocol small.
6. Firmware repaints only when the authenticated display body differs from the
   previous body in the current Pager session.
7. Duplicate history resets on every new Pager entry, not on every mailbox
   radio restart.
8. Changed content uses a fast e-ink refresh; cleanup follows **Settings >
   Display > Refresh Frequency**. Entering or exiting Pager does not force an
   unrelated full refresh.
9. Battery changes alone do not repaint the screen.

## Acceptance gates

No Pager behavior change is complete until the relevant gate passes:

1. Always Available enrollment and two consecutive messages.
2. Mailbox enrollment followed by second and third messages in later windows.
3. Recovery after one and multiple intentionally missed mailbox windows.
4. Android restart followed by delivery at a restored mailbox window.
5. Beat observes consecutive windows without timed scan gaps.
6. Beat pauses for a send and resumes immediately afterward.
7. Same-session duplicate does not repaint; re-entering Pager resets that
   duplicate baseline.
8. Notification stacks of 1, configured maximum, and empty render correctly;
   long UTF-8 text stays in bounds.
9. Power-button exit restores the book/Home destination and refresh cycle.
10. Low-battery deep sleep makes BLE unavailable as specified.

Build checks do not replace these X3/X4 hardware gates.

## Change rule

Every behavior change must name the contract section it changes. The sequence
is: contract approval, deterministic test where possible, one focused code
change, builds, then the relevant hardware gate. Refactoring and behavior
changes are never combined in one commit.
