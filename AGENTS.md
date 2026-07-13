# CrossPoint BLE Dashboard Agent Guide

## Project and scope

This fork is based on CrossPoint Reader's `feat-bluetooth` branch and explores
an opt-in BLE dashboard for Xteink X3/X4. Preserve normal reader behavior and
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

## BLE dashboard direction

- The existing BLE implementation is a HID client for page-turner remotes. A
  phone-to-dashboard design needs a separate, minimal GATT peripheral service;
  do not repurpose HID input as a notification protocol.
- Normal battery deep sleep turns the MCU off, so it cannot provide timer-based
  BLE wakeups. Treat periodic dashboard updates as a low-power-standby research
  task and measure current before making battery-life claims.
- Keep BLE state bounded and opt-in. Do not keep Wi-Fi and BLE active together
  without an explicit RAM, coexistence, and battery budget. Refresh e-ink only
  when persisted dashboard state changes.

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

Never flash or upload firmware without explicit user approval. Do not claim
memory, performance, or battery improvements without identifying the mechanism
and collecting relevant build, heap, or current-draw evidence.

## Git workflow

This checkout uses `origin` for `ForteDexe/crosspoint-reader` and `upstream` for
`crosspoint-reader/crosspoint-reader`; `dashboard-ble` tracks
`upstream/feat-bluetooth`. Check `git status --short`, the current branch, and
remotes before Git operations. Fetch upstream before starting a substantial
feature, preserve unrelated user changes, and push only when requested.

Use focused commits with a conventional prefix (`feat:`, `fix:`, `refactor:`,
`docs:`, `test:`, `chore:`, or `perf:`). Keep refactors separate from behavior
changes and do not stage ignored or generated artifacts.
