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
