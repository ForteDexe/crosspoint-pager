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
the X3 has an external 32 kHz crystal connected to the ESP32-C3.

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

USB serial is not a valid current measurement method for this phase: opening
the X3 USB CDC port resets the device, and USB charging makes the fuel-gauge
current different from the device's battery draw. Measure on battery power
with an inline meter or shunt instead.

Do not claim multi-month battery life from this phase until it is measured.

### 3. Redesign Pager as a periodic mailbox if phase 2 is insufficient

Deep-sleep for most of the time, then wake on a timer, advertise for a short
receive window, collect the latest queued pager message, refresh e-ink only if
it changed, and return to deep sleep. The sender must retain notifications until
the next receive window, so delivery is intentionally delayed.

Before implementation, choose and document:

- The wake interval and advertising-window duration.
- The sender queue/retry behavior.
- The expected notification delay.
- Measured average current and battery-life estimate.

## Non-goal

Immediate, continuously reachable BLE delivery and CR2032-style multi-month
battery life are not compatible with the current always-awake ESP32-C3 Pager
design. Reaching both requires a hardware architecture with an ultra-low-power
BLE receiver that can wake the main e-ink system only for an actual update.
