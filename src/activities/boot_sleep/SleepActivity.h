#pragma once
#include <HalBlePager.h>
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
  bool handlesPowerButtonSleepGesture() const override { return pagerMode; }

 private:
  void renderDefaultSleepScreen() const;
  void renderCustomSleepScreen() const;
  void renderCoverSleepScreen() const;
  void renderBitmapSleepScreen(const Bitmap& bitmap) const;
  void renderLastScreenSleepScreen() const;
  void renderBlankSleepScreen() const;
  void renderPagerLowBatterySleepScreen() const;
  void checkPagerBatteryLevel(bool force = false);
  void runPagerMailboxSleep();
  void exitPager();
  bool startPagerBle();
  void persistPagerEnrollmentIfNeeded();
  void transitionPagerMailboxIfReady();
  HalDisplay::RefreshMode nextPagerRefreshMode();
  void renderPagerSleepScreen(HalDisplay::RefreshMode refreshMode) const;
  void updatePagerText(const char* payload, size_t length);

  bool fromTimeout = false;
  bool pagerLowBatterySleep = false;
  bool pagerMode = false;
  bool pagerMailboxMode = false;
  bool pagerReturnToReader = false;
  bool pagerHasData = false;
  bool pagerLowBatteryDetected = false;
  unsigned long pagerPowerButtonPressedAt = 0;
  unsigned long lastPagerBatteryCheckMs = 0;
  uint16_t pagerBatteryPercent = 100;
  int pagerUpdatesUntilCleanRefresh = 0;
  // Enter Pager with the X3 differential waveform; HALF_REFRESH requests a
  // panel resync and looks like a full refresh. Message cleanup still follows
  // Settings > Display > Refresh Frequency via nextPagerRefreshMode().
  HalDisplay::RefreshMode pagerRefreshMode = HalDisplay::FAST_REFRESH;
  static constexpr size_t PAGER_TITLE_BYTES = 80;
  static constexpr size_t PAGER_MESSAGE_BYTES = 180;
  static constexpr size_t PAGER_FOOTER_BYTES = 56;
  static constexpr uint16_t PAGER_LOW_BATTERY_PERCENT = 25;
  // Above this threshold, a slow probe is enough to notice the transition;
  // at or below it, poll promptly so the 25% deep-sleep safeguard is timely.
  static constexpr uint16_t PAGER_LOW_BATTERY_POLL_START_PERCENT = 40;
  static constexpr unsigned long PAGER_HEALTHY_BATTERY_PROBE_MS = 15UL * 60UL * 1000UL;
  static constexpr unsigned long PAGER_LOW_BATTERY_PROBE_MS = 5UL * 60UL * 1000UL;
  // Reused for every transfer so the 321-byte GATT payload never sits in a
  // hot-path stack frame.
  char pagerPayload[HalBlePager::MAX_PAYLOAD_BYTES + 1] = {};
  char pagerTitle[PAGER_TITLE_BYTES] = {};
  char pagerMessage[PAGER_MESSAGE_BYTES] = {};
  char pagerFooter[PAGER_FOOTER_BYTES] = {};
};
