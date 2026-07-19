# CrossPoint BLE Pager Agent Guide

## Project and scope

This fork starts pager work from CrossPoint Reader v1.4.1; the upstream
`feat-bluetooth` branch is retained only as a BLE HID reference. It explores
an opt-in BLE pager for Xteink X3/X4. Preserve normal reader behavior and
upstream compatibility. The upstream project scope rejects active connectivity;
keep this experiment passive, low-power, user-controlled, and isolated from the
default reader experience. Do not represent it as upstream-ready without an
explicit scope discussion.

Before a feature changes surface area, read `SCOPE.md` and use the applicable
project skill under `.codex/skills/`. In particular, use the scope, heap, HAL,
control-flow, and review skills when their descriptions match the task.

## Hardware constraints

- Target ESP32-C3: about 380 KB usable RAM, no PSRAM, and one 48 KB display
  framebuffer. Fragmentation and the largest free block matter as much as total
  free heap.
- Keep local stack use below about 256 bytes. Put compile-time data in
  `static constexpr`; do not allocate or grow containers in render loops.
- Use `makeUniqueNoThrow` from `lib/Memory/Memory.h` for fallible allocations,
  check it, and log failure. Never use bare `new` with exceptions disabled.
- `std::vector` needs `reserve()` before a `push_back()` loop. Do not use
  `std::string` or Arduino `String` in hot/render paths.
- Do not add a second full-screen buffer. Use GfxRenderer's BW store/restore
  pattern for grayscale work.

## Firmware architecture

- Use `HalStorage`/`Storage` and `HalFile` for all SD access. Never call
  SdFat, raw `FsFile`, `SDCardManager`, `EInkDisplay`, or `InputManager`
  directly outside `lib/hal`; those layers own locking and hardware contracts.
- Activities use `MappedInputManager::Button` logical buttons, not raw GPIO
  button indices. Render through `GUI`/`UITheme` using renderer dimensions and
  oriented metrics; do not hardcode display dimensions, fonts, coordinates, or
  colors.
- Route user-visible text through `tr(STR_*)`; update the English translation
  YAML for new strings. `LOG_*` messages may remain literal.
- Treat `onEnter`, `loop`, and `onExit` as an ownership boundary: release
  tasks, activity-owned buffers, and member file handles in `onExit` before an
  activity is destroyed. Local `HalFile` handles close by RAII.
- Prefer `enum class` plus exhaustive `switch` for closed states. Use early
  returns for error and skip paths.
- Do not manually edit generated HTML headers, I18n headers, `.pio/`, or other
  ignored build outputs. Edit their source files and regenerate through the
  normal build steps.

## BLE pager direction

- Before changing the Pager protocol, enrollment, mailbox scheduling, or app
  retry behavior, audit both sides against `docs/ble-pager-contract.md` and
  update that contract and its change-impact map first.
- The existing BLE implementation is a HID client for page-turner remotes. A
  phone-to-pager design needs a separate, minimal GATT peripheral service;
  do not repurpose HID input as a notification protocol.
- Normal battery deep sleep turns the MCU off, so it cannot provide timer-based
  BLE wakeups. Treat periodic pager updates as a low-power-standby research
  task and measure current before making battery-life claims.
- Keep BLE state bounded and opt-in. Do not keep Wi-Fi and BLE active together
  without an explicit RAM, coexistence, and battery budget. Refresh e-ink only
  when persisted pager state changes.

## Build and verification

Use the committed project-local environment:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
.\.conda\Scripts\pio.exe run -e default
```

The UTF-8 variables avoid PlatformIO output failures on Windows consoles using
CP1252. For C/C++ changes, build before handoff; run `pio check -e default`
when the change warrants static analysis. Report that device, battery, BLE, and
all-orientation testing still require hardware unless actually performed.

For persistent USB serial captures, including the `pager_power_debug`
build/flash workflow and PlatformIO's `log2file` filter, follow
`docs/contributing/testing-debugging.md`. The Pager-specific connection guide at
`docs/pager-web-bluetooth.md` links to the same procedure.

For the Android companion, reuse the existing user-local toolchain instead of
downloading another SDK or JDK:

```powershell
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'CrossPointPager\jdk\jdk-17.0.19+10'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'CrossPointPager\android-sdk'
Push-Location tools\ble-pager-android
try {
    .\gradlew.bat clean lintDebug assembleDebug
} finally {
    Pop-Location
}
```

The APK is generated at
`tools\ble-pager-android\app\build\outputs\apk\debug\crosspoint-pager.apk`.
See `tools/ble-pager-android/README.md` for toolchain preflight checks, `aapt`
package verification, and installation. If the documented tools are absent,
report that first; do not silently install a replacement toolchain.

Never flash or upload firmware without explicit user approval. Do not claim
memory, performance, or battery improvements without identifying the mechanism
and collecting relevant build, heap, or current-draw evidence.

## Git workflow

This checkout uses `origin` for `ForteDexe/crosspoint-reader` and `upstream` for
`crosspoint-reader/crosspoint-reader`. `feature/ble-pager` is the active Pager
line and is intended to be this fork's default branch. `upstream/feat-bluetooth`
is a BLE HID reference only; never merge it into Pager. Check `git status
--short`, the current branch, and remotes before Git operations. Fetch upstream
before starting a substantial feature, preserve unrelated user changes, and
push only when requested.

## Upstream-sync guard

Never merge or rebase `upstream/master` directly into the published Pager line.
Upstream deliberately does not share this fork's BLE Pager scope, so a routine
update can silently remove Pager behavior or reintroduce incompatible power
management.

For every upstream update:

1. Fetch `upstream`, then create a disposable branch such as
   `sync/upstream-YYYY-MM-DD` from `feature/ble-pager`.
2. Merge `upstream/master` into that branch and resolve conflicts there. Do not
   force-push, reset, or rewrite the published Pager branch.
3. Explicitly review these Pager-owned surfaces before proposing the merge:
   `lib/hal/HalBlePager.*`, `lib/hal/HalPowerManager.*`,
   `src/activities/boot_sleep/SleepActivity.*`, `src/main.cpp`,
   `platformio.ini`, `sdkconfig.pager-power`, Pager translations, and
   `docs/ble-pager*.md`.
4. Preserve the normal `default` firmware as reader-only. Pager modem/light
   sleep must remain isolated to `pager_power`; do not carry its SDK config
   into the default environment.
5. Build both `default` and `pager_power`. For changes touching Pager, also
   perform X3 smoke tests: BLE discovery after the power button is released,
   browser message delivery, power-button exit/reader return, and low-battery
   behavior where practical.
6. Present the integration diff and verification results to the user. Merge it
   into `feature/ble-pager` only after review/approval, then keep the sync
   branch until the remote update is confirmed.

Use focused commits with a conventional prefix (`feat:`, `fix:`, `refactor:`,
`docs:`, `test:`, `chore:`, or `perf:`). Keep refactors separate from behavior
changes and do not stage ignored or generated artifacts.
