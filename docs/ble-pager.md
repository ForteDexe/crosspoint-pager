# BLE Pager experiment

This fork adds an opt-in BLE pager for Xteink X3/X4. It is a local
experiment, not an upstream CrossPoint feature.

## What it does

- **Settings → Display → Sleep Screen → Pager** makes the sleep screen
  advertise a small custom GATT peripheral named `CrossPoint Pager` and
  receive pager updates from it.
- The writable characteristic accepts an app-level authenticated UTF-8 packet.
  The display portion is still `title\nmessage\nfooter`; the full GATT write is
  capped at 320 bytes, leaving 290 bytes for display text after the token
  header.
- The display is refreshed only when the received payload differs from the
  previous one. Changed pager messages use a fast e-ink refresh; the cleanup
  refresh follows **Settings > Display > Refresh Frequency**, matching reader
  page turns.
- At 25% battery or lower, Pager stops BLE, shows a low-battery sleep message,
  and enters deep sleep using the Quick Resume presentation.
- While Pager is active, the front and side buttons are ignored. Hold the
  physical power button for the configured power-button duration, then release
  it to exit Pager. When Pager was entered from a book, it reopens that book at
  its saved reading position and continues the reader's refresh cycle; otherwise
  it returns to Home.
- Pager's existing themed header shows the battery when the screen opens and
  whenever a changed message redraws it; it never refreshes e-ink solely for
  the battery. Pager also skips the normal main-loop USB/fuel-gauge poll. The
  low-battery safeguard is the only periodic gauge user: above 40% it samples
  every 15 minutes, and at 40% or below every 5 minutes. It enters deep sleep
  at 25% or below.

The protocol identifiers are:

| Item | UUID |
| --- | --- |
| Pager service | `ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Writable payload characteristic | `ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001` |
| Read-only policy/status characteristic | `ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001` |

Use the [PC Web Bluetooth test page](../tools/ble-pager-test/README.md)
to exercise the protocol from Edge or Chrome.
For the user-facing local-server and browser connection steps, see [Connect
Pager from a PC browser](pager-web-bluetooth.md).

## Connection and power model

BLE is completely off outside Pager sleep mode. It is BLE-only: starting it
turns Wi-Fi off rather than attempting unmeasured Wi-Fi/BLE coexistence.

Pager is powered-on standby, not ESP32 deep sleep. Deep sleep powers the
radio off, so a truly periodic wake-and-receive design needs a later hardware
current-draw and reconnect study. While the pager service is running, the
firmware keeps the ESP32-C3 at normal CPU frequency; the BLE controller is not
reliable at the 10 MHz idle frequency.

Pager policy is controlled only on X3 in **Settings → System → Pager**. It is
persisted across reboots so a nearby client cannot choose a higher-power mode.
The **Availability** menu presents the two underlying operating strategies as
one delivery setting:

- **Always Available** advertises continuously and leaves an established
  connection open until the client disconnects. This is the easiest option for
  development and interactive delivery.
- **Every 1/5/15/30/60 min** uses the selected periodic cadence (5 minutes is
  the default). X3 advertises for two seconds at a slower 100 ms interval and
  lower transmit power, then deinitializes NimBLE and enters timer light sleep
  between windows. A valid GATT payload write is acknowledged by its write
  response, then X3 disconnects after 250 ms; an idle or misbehaving client is
  disconnected after three seconds. Periodic availability omits the
  scan-response device name to avoid another radio response; clients must
  filter by the Pager service UUID.

Pager uses app-level enrollment rather than Bluetooth OS pairing. If no client
is enrolled, Pager forces **Always Available** setup mode even when the saved
Availability is periodic. The read-only status characteristic exposes a fresh
16-character setup token only in that setup state. The phone or browser stores
that token and includes it in every later write. The first valid write enrolls
the client. If periodic availability was selected, X3 keeps the setup link
alive long enough to acknowledge that write, waits for the client disconnect
with a one-second fallback, and only then switches into the periodic mailbox
flow. To invalidate old clients, use **Settings → System →
Pager → Enrolled Device → Reset**; the next Pager session creates a new setup
token.

Changing **Availability** or **Always Available Profile** also resets the
enrolled device and invalidates its token. This deliberately reopens setup so
the phone can read the new policy before enrolling again.

**Always Available Profile** controls only the BLE link used by **Always
Available**. X3 requests these connection parameters after a client connects:

| Profile | Requested interval | Peripheral latency | Supervision timeout |
| --- | --- | --- | --- |
| Responsive | 30–50 ms | 0 | 4 s |
| Balanced (default) | 100–150 ms | 2 | 6 s |
| Battery Saver | 200–300 ms | 4 | 10 s |

The phone or PC is the BLE central and may accept or adjust that request, so
these values are preferences rather than guaranteed final timing. Mailbox mode
does not request this profile: its short connection races to deliver a payload
and disconnect. The read-only status characteristic exposes X3's policy and
the latest negotiated link values as semicolon-separated `key=value` text. Its
keys are `v`,
`availability` (effective radio behavior), `configured_availability`,
`interval_s`, `window_ms`, `connected`, `enrolled`, `enroll_token` (only
populated while setup is open), `last_write`,
`conn_interval_units` (1.25 ms units), `conn_latency`, `conn_timeout_units`
(10 ms units), and `next_window_ms`. The `profile` key is present only while
effective availability is Always Available. Clients can read the schedule and
link state, but cannot change the battery policy. Status version 3 separates the
Always Available enrollment session from its configured periodic policy so a
client can prepare its first mailbox scan before X3 completes the handoff.

Periodic availability is still not true deep sleep: the X3 battery latch stays powered so the
MCU can return from timer light sleep without rebooting. Current must still be
measured.

The battery-first implementation roadmap is tracked in [BLE Pager power
plan](ble-pager-power-plan.md).

An experimental modem-sleep/automatic-light-sleep firmware is available as the
`pager_power` build environment. Its serial logging is disabled to remove idle
debug work. Use `pager_power_debug` only while diagnostics are needed; see phase
2 of the power plan for build commands and test requirements.

Pager also adopts the reader's low-work standby patterns where BLE permits:
the activity loop runs at a 50 ms cadence, the tilt sensor is put to sleep, the
e-ink controller is powered off after each paint, payload storage is reused,
and cleanup refreshes share the reader's configured refresh counter. It does
not copy the reader's manual 10 MHz idle clock because that made BLE unreliable.

The service is still not Bluetooth-bonded and the token is not strong
cryptographic authentication. It prevents casual or stale clients from burning
battery after enrollment, but treat Pager as a nearby development receiver and
do not send sensitive notifications.

## Discovery notes and fixes

The first implementation could log that it had started advertising yet remain
undiscoverable. Two independent constraints caused that result:

1. In Always Available mode, a full 128-bit service UUID and the readable `CrossPoint Pager` name do
   not both fit in the 31-byte primary BLE advertising packet. The service UUID
   now stays in the primary advertisement, while the device name is returned
   in the active-scan response. Periodic availability intentionally sends no
   name response.
2. The normal firmware idles the ESP32-C3 at 10 MHz after a short period of
   inactivity. NimBLE may initialize at that clock but its controller is not
   dependable there. The main loop now holds normal CPU frequency for the
   lifetime of the opt-in pager service.

For diagnosis, serial output should include:

```text
[INF] [BLE] Pager advertising started
```

The regular Windows/macOS Bluetooth pairing UI may not present an unbonded
custom GATT peripheral as a normal pairing target. Use the Web Bluetooth page
or a BLE GATT scanner and filter by the service UUID instead.
