#pragma once

#include "activities/Activity.h"

class BluetoothPairingActivity final : public Activity {
 public:
  explicit BluetoothPairingActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("BluetoothPairing", renderer, mappedInput) {}

  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;

 private:
  bool connected = false;
  bool started = false;
};
