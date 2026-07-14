#pragma once

#include "activities/Activity.h"
#include "util/ButtonNavigator.h"

class PagerSettingsActivity final : public Activity {
 public:
  explicit PagerSettingsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("PagerSettings", renderer, mappedInput) {}

  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;

 private:
  ButtonNavigator buttonNavigator;
  int selectedIndex = 0;

  void handleSelection();
};
