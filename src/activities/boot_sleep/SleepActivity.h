#pragma once
#include <HalDisplay.h>

#include "activities/Activity.h"

class Bitmap;

class SleepActivity final : public Activity {
 public:
  explicit SleepActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, bool fromTimeout = false,
                         bool pagerLowBatterySleep = false)
      : Activity("Sleep", renderer, mappedInput),
        fromTimeout(fromTimeout),
        pagerLowBatterySleep(pagerLowBatterySleep) {}
  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override;
  bool shouldEnterDeepSleep() override;

 private:
  void renderDefaultSleepScreen() const;
  void renderCustomSleepScreen() const;
  void renderCoverSleepScreen() const;
  void renderBitmapSleepScreen(const Bitmap& bitmap) const;
  void renderLastScreenSleepScreen() const;
  void renderBlankSleepScreen() const;
  void renderPagerLowBatterySleepScreen() const;
  void checkPagerBatteryLevel();
  HalDisplay::RefreshMode nextPagerRefreshMode();
  void renderPagerSleepScreen(HalDisplay::RefreshMode refreshMode) const;
  void updatePagerText(const char* payload, size_t length);

  bool fromTimeout = false;
  bool pagerLowBatterySleep = false;
  bool pagerMode = false;
  bool pagerHasData = false;
  bool pagerLowBatteryDetected = false;
  unsigned long lastPagerBatteryCheckMs = 0;
  int pagerUpdatesUntilCleanRefresh = 0;
  HalDisplay::RefreshMode pagerRefreshMode = HalDisplay::HALF_REFRESH;
  static constexpr size_t PAGER_TITLE_BYTES = 80;
  static constexpr size_t PAGER_MESSAGE_BYTES = 180;
  static constexpr size_t PAGER_FOOTER_BYTES = 56;
  static constexpr uint16_t PAGER_LOW_BATTERY_PERCENT = 25;
  char pagerTitle[PAGER_TITLE_BYTES] = {};
  char pagerMessage[PAGER_MESSAGE_BYTES] = {};
  char pagerFooter[PAGER_FOOTER_BYTES] = {};
};
