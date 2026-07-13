#pragma once
#include "activities/Activity.h"

class Bitmap;

class SleepActivity final : public Activity {
 public:
  explicit SleepActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, bool fromTimeout = false)
      : Activity("Sleep", renderer, mappedInput), fromTimeout(fromTimeout) {}
  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override;

 private:
  void renderDefaultSleepScreen() const;
  void renderCustomSleepScreen() const;
  void renderCoverSleepScreen() const;
  void renderBitmapSleepScreen(const Bitmap& bitmap) const;
  void renderLastScreenSleepScreen() const;
  void renderBlankSleepScreen() const;
  void renderDashboardSleepScreen() const;
  void updateDashboardText(const char* payload, size_t length);

  bool fromTimeout = false;
  bool dashboardMode = false;
  bool dashboardHasData = false;
  static constexpr size_t DASHBOARD_TITLE_BYTES = 80;
  static constexpr size_t DASHBOARD_MESSAGE_BYTES = 180;
  static constexpr size_t DASHBOARD_FOOTER_BYTES = 56;
  char dashboardTitle[DASHBOARD_TITLE_BYTES] = {};
  char dashboardMessage[DASHBOARD_MESSAGE_BYTES] = {};
  char dashboardFooter[DASHBOARD_FOOTER_BYTES] = {};
};
