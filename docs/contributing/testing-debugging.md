# Testing and Debugging

CrossPoint runs on real hardware, so debugging usually combines local build checks and on-device logs.

## Local checks

Make sure `clang-format` 21+ is installed and available in `PATH` before running the formatting step.
If needed, see [Getting Started](./getting-started.md).

```sh
./bin/clang-format-fix
pio check --fail-on-defect low --fail-on-defect medium --fail-on-defect high
pio run
```

## Flash and monitor

Flash firmware:

```sh
pio run --target upload
```

### Windows X3 build and flash

Use the repository-local PlatformIO environment. Do not start a second build
while another `pio` or project-local Python process is still running.

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$environment = 'pager_power'
.\.conda\Scripts\pio.exe run -e $environment -j 4
.\.conda\Scripts\pio.exe device list
```

The four-job limit avoids the unbounded compiler fan-out that can stall a clean
ESP-IDF Pager build on this machine. A first `pager_power` build can still take
several minutes. If the command runner times out, check for this checkout's
active `pio`, Python, and `riscv32-esp-elf` processes before retrying; do not
start an overlapping build.

Identify the X3 port from `USB VID:PID=303A:1001`; do not assume that it is
always `COM4`. Close serial monitors and any other process using that port,
then replace `COM4` below with the detected port:

```powershell
.\.conda\Scripts\pio.exe run -e $environment -j 4 -t upload --upload-port COM4
```

Use `pager_power` whenever the X3 must retain the production low-power BLE
Pager, including when testing changes in shared reader code such as X3
no-flash maintenance. Use `default` only for reader-only firmware, and
`pager_power_debug` only for serial diagnosis. A successful upload ends with
both:

```text
Hash of data verified.
Hard resetting via RTS pin...
```

If no port appears, reconnect the X3 with a data-capable USB cable and rerun
`device list`. If the port is present but upload reports access denied, close
all PlatformIO monitors, terminal programs, and other serial clients before
retrying.

#### Interrupted compiler installation

An interrupted or overlapping first build can leave
`%USERPROFILE%\.platformio\tools\toolchain-riscv32-esp` empty while the complete
compiler remains under `toolchain-riscv32-esp.tmp\riscv32-esp-elf`. The build
then reports `riscv32-esp-elf-g++ is not recognized` or an `idf_tools.py`
`expected 1 entry` error.

First confirm no PlatformIO process from this checkout is running. If the final
directory is empty and the temporary compiler below exists, finish the
interrupted installation without downloading it again:

```powershell
$tools = Join-Path $env:USERPROFILE '.platformio\tools'
$temp = Join-Path $tools 'toolchain-riscv32-esp.tmp'
$payload = Join-Path $temp 'riscv32-esp-elf'
$target = Join-Path $tools 'toolchain-riscv32-esp'

Test-Path (Join-Path $payload 'bin\riscv32-esp-elf-g++.exe')
Get-ChildItem -Force -LiteralPath $target

Get-ChildItem -Force -LiteralPath $payload | Move-Item -Destination $target -Force
Copy-Item -LiteralPath (Join-Path $temp 'package.json') -Destination (Join-Path $target 'package.json') -Force
& (Join-Path $target 'bin\riscv32-esp-elf-g++.exe') --version
```

Only run that recovery when the compiler test returns `True` and the final
directory listing is empty. Then rerun the normal build once and let it finish.

Open serial monitor:

```sh
pio device monitor
```

Capture a timestamped serial log for a bug report:

```sh
pio device monitor --baud 115200 --filter default --filter time --filter log2file
```

PlatformIO writes the capture under the project-root `logs/` directory. Keep
the monitor running from before the reproduction begins until after the failure,
then stop it with Ctrl-C. Close every serial monitor before uploading firmware;
only one process can own the device port at a time.

Optional enhanced monitor:

```sh
python3 -m pip install pyserial colorama matplotlib
python3 scripts/debugging_monitor.py
```

The enhanced monitor provides colors, filtering, a memory graph, commands, and
screenshot handling. Use PlatformIO's `log2file` filter when a persistent text
capture is required; the enhanced monitor does not save its normal console
stream by itself.

### Pager power build

The production `pager_power` environment disables serial output to avoid its
idle power cost. Reproduce Pager faults with `pager_power_debug`, which keeps
the same Pager SDK configuration but enables debug-level serial logging:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
.\.conda\Scripts\pio.exe run -e pager_power_debug -t upload --upload-port COM4
.\.conda\Scripts\pio.exe device monitor -e pager_power_debug --port COM4 --baud 115200 --filter default --filter time --filter log2file
```

Replace `COM4` with the detected device port. Return to `pager_power` after the
diagnosis; measurements taken from `pager_power_debug` do not represent normal
Pager battery consumption.

## Useful bug report contents

- Firmware version and build environment
- Exact steps to reproduce
- Expected vs actual behavior
- Serial logs from boot through failure
- Whether issue reproduces after clearing `.crosspoint/` cache on SD card

## Common troubleshooting references

- [User Guide troubleshooting section](../../USER_GUIDE.md#7-troubleshooting-issues--escaping-bootloop)
- [Webserver troubleshooting](../troubleshooting.md)
