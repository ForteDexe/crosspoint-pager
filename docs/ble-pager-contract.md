# BLE Pager contract

Status: approved recovery source of truth, based on `dcfd20f9` and revised on
2026-07-15 for event delivery.

This file defines the intended behavior shared by the Xteink firmware and the
Android companion. If code and this file disagree, the code is wrong. Change
this contract and obtain approval before changing behavior on either side.

Pager is an opt-in fork experiment for Xteink X3/X4. It is not an upstream
CrossPoint feature. It must remain passive, BLE-only, low-power, and isolated
from normal reading.

## Scope

Pager does only the following:

- receive a bounded feed of recent notification events from one selected
  Android device;
- display 1-11 notifications without scrolling;
- offer continuous availability for setup/debugging or periodic availability
  for lower-power delivery;
- preserve normal reader behavior when Pager is inactive.

Pager does not provide chat, an unbounded notification history, remote firmware
settings, Bluetooth bonding, sensitive-message security, or guaranteed
real-time delivery. Wi-Fi must not run with Pager BLE.

## Authorities and ownership

Xteink firmware is the authority for:

- configured availability and mailbox interval;
- Always Available power profile;
- enrollment token and enrollment state;
- the fixed notification ring and rendered Pager state;
- BLE/window lifetime, battery safeguards, and e-ink refreshes.

The Android app is the authority for:

- the one user-selected Xteink;
- the copied enrollment token;
- stable event IDs and selection of new or updated Android notifications;
- the bounded pending-event queue, notification limit, retry lifetime, and
  diagnostic Beat mode.

Firmware implementation ownership:

- `HalBlePager` owns GATT, authentication, command parsing/queuing,
  connections, advertising, and mailbox radio state;
- `SleepActivity` owns Pager entry/exit, the fixed notification ring, batch
  state, rendering, enrollment persistence, battery checks, and mailbox sleep
  coordination;
- `HalPowerManager` owns light/deep sleep and the Xteink power latch.

Android implementation ownership:

- `PagerProtocol` owns wire encoding, size limits, and status parsing;
- `PagerGattClient` owns one serialized scan/connect/read-or-batch-write
  operation, mailbox scheduling, retries, and Beat;
- `RelayPreferences` owns persisted Android state and sent event IDs;
- `NotificationRelayService` owns notification extraction and new/updated
  event selection;
- `PagerRelayService` owns the pending-event queue and foreground-service
  lifetime.

## Change impact map

Use this table before editing Pager behavior. A change is incomplete until each
applicable owner in its row has been reviewed, even when only one side needs a
code change.

| Contract area | Firmware owners | Android owners | Required checks |
| --- | --- | --- | --- |
| Enrollment and identity | `HalBlePager` authentication/handoff; `SleepActivity` persistence and transition; Pager settings reset | `PagerProtocol` status/token parsing; `PagerGattClient` manual refresh/confirmation; `RelayPreferences` selected identity | Setup status, confirmation, disconnect, configured-mode handoff |
| Mailbox cadence | `HalBlePager` window state; `SleepActivity` UTC phase and radio suspend/resume; `HalClock`; `HalPowerManager` timer sleep | `PagerGattClient` UTC-grid scan/retry; `RelayPreferences` persisted interval | Boundary, missed, restored, X3 RTC, and X4 phase cases |
| Event delivery | `HalBlePager` authenticated command queue and fallback disconnect; `SleepActivity` batch state/ring/render | `PagerProtocol` commands; `PagerGattClient` serialized batch; `PagerRelayService` pending queue | BEGIN/ADD/END, missing END, duplicate ID, full ring, and retry |
| Notification layout | `SleepActivity` final pixel clamp and refresh count | `NotificationRelayService` extraction; Android text fitter; notification-limit setting | 1, maximum, long UTF-8, narrow title beside time, and overflow |
| Beat diagnostics | Status read must remain passive | `PagerGattClient` continuous scan/serialization; service/UI enable state | Consecutive windows and pause/resume around foreground BLE |
| Pager entry, exit, and battery | `SleepActivity`, `HalPowerManager`, display refresh ownership | Status text only | Reader return, power-button-only exit, low-battery deep sleep |
| Wire or status fields | `HalBlePager` UUIDs, limits, parser/status writer | `PagerProtocol` constants/parser plus every consumer above | Protocol version, unknown fields, and exact byte boundaries |

For every change, record the contract section, affected row, code owners
reviewed, deterministic checks, builds, and hardware gates in the commit or
handoff. Do not infer impact from the file being edited; trace it from this
table first.

## Terms

- **Always Available:** Xteink advertises whenever Pager is active and no
  client is connected.
- **Mailbox:** Xteink advertises only during a periodic receive window and uses
  timer light sleep between windows.
- **Window:** one mailbox advertising opportunity, currently 2 seconds.
- **Interval:** time between globally aligned mailbox window starts.
- **Enrollment:** app-level possession of the current 16-hex token. It is not
  operating-system Bluetooth pairing.
- **Event ID:** a stable 16-hex identifier derived by Android from notification
  identity and update generation. It is not message content comparison.
- **Batch:** one connection transaction containing `BEGIN`, zero or more
  ordered `ADD` commands, and `END`.
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
| Delivering | One scan/connect/read-or-batch-write owns BLE | Complete, fail, cancel, or retain pending events |

Selecting another Xteink stops relay/Beat and clears the previous identity,
token, policy, mailbox schedule, pending events, and sent-ID ledger. The app
does not maintain multiple device profiles.

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
   `Pager connection confirmed` body using the authenticated legacy `DATA`
   command.
6. A valid confirmation enrolls the client. Android closes the connection.
7. If Always Available is configured, firmware resumes advertising.
8. If Mailbox is configured on X3, firmware schedules the next rounded UTC
   boundary and sleeps until then.
9. An X4 without a valid phase remains in its initial setup/bootstrap window
   until a phone `BEGIN` supplies UTC time. It then follows the same rounded
   schedule using retained monotonic phase while Pager remains active.

An already-enrolled Xteink never exposes its token. An app without the matching
token must instruct the user to reset Enrolled Device.

## Mailbox timing

Mailbox intervals are 1, 5, 15, 30, or 60 minutes. Window starts lie on a
global UTC grid:

```text
1 minute:  HH:01:00, HH:02:00, HH:03:00, ...
5 minutes: HH:05:00, HH:10:00, HH:15:00, ...
60 minutes: HH+1:00:00, HH+2:00:00, ...
```

Equivalently, a boundary has UTC seconds since epoch divisible by the configured
interval in seconds. If Pager starts exactly on a boundary, that boundary may
open immediately; otherwise it waits for the next one.

Rules:

1. X3 uses its RTC as UTC. Local time zones and daylight-saving changes never
   shift the grid.
2. X4 receives UTC epoch seconds in every `BEGIN` and retains phase using the
   monotonic timer while Pager remains active.
3. Android computes scan times from its wall clock and the stored interval. It
   starts early enough to cover clock error and the full receive window.
4. Closing a window, connecting, receiving `END`, timing out, or disconnecting
   never re-anchors the grid.
5. Missing one or more windows advances to the next future grid boundary.
6. A pending event expires after 75 minutes.
7. App restart restores interval/policy and recomputes the grid. Pending event
   IDs and bodies are persisted until sent or expired.
8. `next_window_ms` is an optional correction hint. `interval_s` and the UTC
   grid are the stable scheduling inputs.

Mailbox firmware normally lets Android disconnect after `END`. Firmware also
has bounded idle and absolute connection timeouts. On timeout it preserves any
accepted `ADD` events, performs the specified render, disconnects, and returns
to the unchanged rounded schedule.

Mailbox requires the `pager_power` firmware. A build that cannot support Pager
timer light sleep must not claim or emulate mailbox timing.

## Beat

Beat is independent of the learned mailbox schedule.

While Beat is enabled, Android continuously scans for the selected address and
Pager service. It does not stop after a short sample or wait for a predicted
window. When Xteink appears, Beat reads status, disconnects, and resumes
scanning. A foreground delivery or manual policy refresh may temporarily own
BLE; Beat resumes afterward.

Beat is diagnostic and may consume significant phone battery. It must not keep
Xteink awake, change Xteink policy, send display content, or enroll a device.

## Wire protocol

| Item | Value |
| --- | --- |
| Service UUID | `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Payload characteristic | `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Status characteristic | `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Protocol status version | 8 |
| Maximum authenticated write | 216 UTF-8 bytes |
| Token, batch ID, event ID | 16 lowercase hexadecimal characters each |
| Maximum time field | 11 UTF-8 bytes |
| Maximum title field | 48 UTF-8 bytes |
| Maximum body field | Dynamic: remaining authenticated-write space, up to 151 UTF-8 bytes |

The 216-byte transport limit is the X3 boundary verified with the current
NimBLE configuration: a legacy `DATA` display body of 186 bytes succeeds and
187 bytes fails. Every command must satisfy both the total authenticated-write
limit and its per-field limits. Limits are counted after UTF-8 encoding, never
as Java/Kotlin UTF-16 character count.

Enrollment confirmation remains:

```text
XPAGER1
DATA
<token>
<title>
<message>
<footer>
```

Notification delivery uses:

```text
XPAGER1
BEGIN
<token>
<batch_id>
<notification_limit>
<utc_epoch_seconds>
```

```text
XPAGER1
ADD
<token>
<batch_id>
<event_id>
<time>
<title>
<body>
```

```text
XPAGER1
END
<token>
<batch_id>
```

Android writes one command at a time and waits for its GATT write callback
before writing the next. Complete-stack packets such as `XPSTACK1` are removed.

Status v8 retains the v7 policy fields: `v`, `model`, `device_id`,
`availability`, `configured_availability`, `interval_s`, `window_ms`,
`enrolled`, `enroll_token`, `next_window_ms`, and `schedule=utc_grid`. Version
7 allows an `ADD` body to use bytes left unused by its time and title fields.
Version 8 raises the bounded notification limit from 10 to 11; the complete
authenticated command must still remain at or below 216 bytes.
Clients ignore unknown fields. Link timing, connection state, power profile,
and `last_write` may exist as diagnostics but are not synchronization inputs.

## Event selection and delivery

1. Android assigns a stable ID to each new or updated system notification. An
   unchanged notification retains its ID; an update generation receives a new
   ID.
2. Android queues only event IDs not already recorded as sent. It persists the
   bounded pending queue and sent-ID ledger across app restart.
3. One GATT operation sends `BEGIN`, pending `ADD` commands in oldest-to-newest
   order, then `END`.
4. A successful GATT write callback is terminal for that command. Android does
   not perform post-write status verification.
5. Android records an event ID as sent only after its `ADD` callback succeeds.
   If a later command fails, unsent events remain pending; replay of an already
   accepted ID is harmless because firmware ignores that ID.
6. Firmware keeps a fixed ring of 1-11 notifications. A new ID appends at the
   bottom; when full, the oldest top entry is discarded. Existing IDs are
   ignored. Firmware never compares title/body content for duplication.
7. `BEGIN` applies the 1-11 display limit and opens a batch. `END` closes the
   matching batch and requests one render of the resulting ring.
8. If `END` is missing, accepted events remain in the ring. At the bounded idle
   timeout, firmware renders once if the ring changed, disconnects, and follows
   the unchanged mailbox grid.
9. A valid standalone `ADD` outside a matching batch is retained and rendered
   once after a short debounce. Android's normal path always uses a batch.
10. One client connection is accepted at a time. The notification ring resets
    on a new Pager entry, not on each mailbox radio restart.

## Text layout and e-ink refresh

Each notification uses one title line beside its time and one body line below
it. There is no scrolling or wrapped continuation line.

1. Before encoding `ADD`, Android measures the title against the remaining
   title-line width after the time and gap, and measures the body against the
   full content width.
2. If text does not fit, Android removes trailing Unicode code points until the
   text plus ASCII `...` fits. It must not split a UTF-8 sequence or UTF-16
   surrogate pair.
3. Android also trims to the protocol byte limits. The body receives the bytes
   left after the actual time and fitted title are encoded, up to 151 bytes.
   Pixel and complete-command byte constraints must both pass.
4. Android adds a bounded 24-pixel body allowance to compensate for its
   sans-serif font overestimating narrow glyphs relative to the firmware's
   Noto Sans 8 font. Firmware performs the authoritative final measurement
   with the actual e-ink font and applies the same trailing `...` rule.
5. Xteink renders the complete changed ring once at `END`, once at missing-END
   timeout, or once after the standalone-ADD debounce. It does not repaint for
   duplicate event IDs or an empty batch.
6. Changed content uses the existing fast e-ink refresh cycle; cleanup follows
   **Settings > Display > Refresh Frequency**. `END` never means full refresh.
7. Entering or exiting Pager does not force an unrelated full refresh. Battery
   changes alone do not repaint the screen.

## Acceptance gates

No Pager behavior change is complete until the relevant gate passes:

1. Always Available enrollment and two consecutive batches.
2. Mailbox enrollment followed by batches at second and third rounded UTC
   boundaries.
3. Recovery after one and multiple intentionally missed mailbox windows,
   without schedule drift after connection or timeout.
4. Android restart followed by delivery using the restored policy and pending
   queue.
5. Batch sizes 0, 1, and 11; a requested limit of 12; missing `END`; mismatched
   batch ID; and ring eviction all behave as specified.
6. Replayed event ID does not repaint; a content update with a new event ID does.
7. Authenticated writes of exactly 216 bytes pass; 217 bytes are rejected
   before Android starts the write. A narrow body may use the dynamic bytes
   left by a short time/title instead of stopping at the former 92-byte cap.
8. Long ASCII, multibyte UTF-8, emoji, and narrow title-plus-time cases end in
   `...` and remain inside the screen.
9. Beat observes consecutive windows without timed scan gaps and resumes after
   a foreground operation.
10. Power-button exit restores the book/Home destination and refresh cycle.
11. Low-battery deep sleep makes BLE unavailable as specified.

Build checks do not replace these X3/X4 hardware gates.

## Change rule

Every behavior change must name the contract section it changes. The sequence
is: contract approval, deterministic test where possible, one focused code
change, builds, then the relevant hardware gate. Refactoring and behavior
changes are never combined in one commit.
