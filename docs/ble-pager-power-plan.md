# BLE Pager power plan

This fork prioritizes battery life over always-on, instant delivery. Do not add
Pager features that keep the ESP32-C3 or BLE radio awake without measuring their
current cost first.

## Target sequence

### 1. Measure the current implementation

Measure current with USB disconnected, using an inline power meter or shunt
monitor. Record each state separately:

1. Normal firmware deep sleep.
2. Pager advertising with no client connected.
3. Pager connected to the browser with no messages arriving.
4. One changed-payload e-ink update, including its refresh waveform.

Battery percentage is not a substitute for current measurements: fuel-gauge
readings can move under a continuous radio load. Use the measurements to set
the next phase's success criteria.

### 2. Evaluate BLE modem-sleep and automatic light sleep

Investigate Espressif/NimBLE's supported Bluetooth modem-sleep and automatic
light-sleep path while preserving a connected Pager session. Validate discovery,
connection reliability, message delivery, and measured idle current on Xteink
X3. The existing manual 10 MHz CPU-idle path is not suitable for Pager because
the BLE controller was unreliable there.

The experimental build must arm ESP32-C3 Bluetooth as a light-sleep wake source
only after the Pager GATT service has started. Without that wake source, the
screen can enter standby but the controller cannot reliably advertise or accept
a connection.

**X3 validation note (July 2026):** automatic light sleep initially made Pager
look like a normal sleep screen, then physically powered the board off. The
ESP-IDF ESP32-C3 GPIO-reset workaround selects `CONFIG_PM_SLP_DISABLE_GPIO`,
which disconnects every GPIO during each automatic light-sleep interval. X3's
GPIO13 drives the battery-latch MOSFET, so disconnecting it removed the board's
power hold. `HalPowerManager::enablePagerLightSleep()` now explicitly drives
GPIO13 high and excludes it from sleep GPIO isolation with
`gpio_sleep_sel_dis()`. The physical X3 validation passed: after the power
button is released, Pager remains powered and discoverable through Web
Bluetooth. This confirms functional standby, not a current-draw improvement.

Keep MAC/baseband power-down disabled while validating Pager on Xteink X3. It
is more aggressive than modem sleep and must not be enabled unless advertising
survives automatic light sleep on the physical device.

The experimental `pager_power` PlatformIO environment is the test vehicle for
this phase. It rebuilds the ESP32-C3 Arduino/ESP-IDF libraries with
Espressif's documented modem-sleep and automatic-light-sleep settings, using
the main 40 MHz crystal as the BLE low-power clock. It does not assume that
the X3 has an external 32 kHz crystal connected to the ESP32-C3. It also builds
NimBLE for one peripheral connection only, without the unused central/observer
roles.

Build it with:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
.\.conda\Scripts\pio.exe run -e pager_power
```

The first build recompiles the framework libraries and can take longer than a
normal build. Its image is `.pio/build/pager_power/firmware.bin`. Validate
advertising, browser connection, a changed-message update, and battery-side
current before replacing the default build.

`pager_power` disables serial logging for production battery tests. When logs
are required, build `pager_power_debug` instead:

```powershell
.\.conda\Scripts\pio.exe run -e pager_power_debug
```

USB serial is not a valid current measurement method for this phase: opening
the X3 USB CDC port resets the device, and USB charging makes the fuel-gauge
current different from the device's battery draw. Measure on battery power
with an inline meter or shunt instead.

Do not claim multi-month battery life from this phase until it is measured.

### 3. Test a radio-off periodic mailbox

Mailbox mode is now an experimental `pager_power` path. X3 deinitializes
NimBLE, enters timer light sleep, then recreates the Pager GATT service for a
two-second receive window. Its GPIO13 battery latch is explicitly held high,
so this is light sleep rather than the X3's normal latch-cutting deep sleep.

The cadence is selected on X3 under **Settings → System → Pager →
Availability**: Always Available or every 1, 5, 15, 30, or 60 minutes (default
5). A sender must retain the latest notification
until an advertising window is found. A valid payload write receives its normal
GATT response, then X3 disconnects 250 ms later; idle connections close at
three seconds. Mailbox advertises every 100 ms at -6 dBm and omits the optional
scan-response name. Always Available retains the readable name and continuous
radio for development.

For Always Available, **Always Available Profile** makes X3 request one of three
BLE link policies: Responsive (30–50 ms, latency 0, 4 s timeout), Balanced
(100–150 ms, latency 2, 6 s timeout), or Battery Saver (200–300 ms, latency 4,
10 s timeout). The central remains responsible for the negotiated result. X3's
read-only status characteristic lets a test client inspect both the persisted
delivery policy and the latest link values without granting unauthenticated
remote control over battery behavior.

The powered-on work outside BLE was also reduced after comparing Pager with the
reader path:

- Pager scans the power button at a 50 ms cadence but skips the normal X3 USB
  state check, which otherwise reads the BQ27220 fuel gauge over I2C every loop.
- The QMI8658 tilt sensor is put to sleep outside the reader.
- The e-ink controller is explicitly powered off after every Pager paint; the
  panel keeps its image without power.
- The GATT receive buffer is owned by `SleepActivity` and reused rather than
  placed on the loop stack.
- Gauge reads remain only for the safety policy: every 15 minutes above 40%,
  every 5 minutes at or below 40%, and when a message is already redrawing the
  battery header. No battery-only display refresh is scheduled.

Some plausible changes are deliberately not included. The reader's manual
10 MHz idle clock breaks BLE reliability. Reusing a NimBLE server across full
host deinitialization is unsafe with the current library lifecycle. True X3
partial-window updates require support in the display SDK, and neither an SD
power switch nor an ESP-connected external 32 kHz clock is verified on this
hardware. Treat those as measurement-led hardware/SDK work, not assumptions.

Validate before treating this as a battery solution:

- Power-button wake/exit while X3 is in the timer-light-sleep interval.
- BLE discovery and message delivery in repeated windows.
- Recovery after an interrupted or idle browser connection.
- Current during radio-off interval, two-second window, and e-ink update.
- Sender queue/retry behavior and expected notification delay for the Android app.

## Non-goal

Immediate, continuously reachable BLE delivery and CR2032-style multi-month
battery life are not compatible with the current always-awake ESP32-C3 Pager
design. Reaching both requires a hardware architecture with an ultra-low-power
BLE receiver that can wake the main e-ink system only for an actual update.
