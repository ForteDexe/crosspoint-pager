#pragma once
#include <cstdint>

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
  enum class PagerContentType : uint8_t { None, Message, NotificationStack };

  struct PagerNotification {
    char eventId[17] = {};
    char time[12] = {};
    char title[49] = {};
    char message[93] = {};
  };

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
  bool startPagerBle(HalBlePager::MailboxStart mailboxStart = HalBlePager::MailboxStart::OpenWindow);
  void persistPagerEnrollmentIfNeeded();
  void transitionPagerMailboxIfReady();
  void processPagerCommands();
  void processPagerCommand(HalBlePager::Command& command);
  void finishPagerBatch();
  void requestPagerRingRender();
  HalDisplay::RefreshMode nextPagerRefreshMode();
  void renderPagerSleepScreen(HalDisplay::RefreshMode refreshMode) const;
  void updatePagerMessage(char* payload, size_t length);
  void copyPagerEllipsizedText(const char* source, char* destination, size_t destinationSize, int fontId,
                               int maxWidth, EpdFontFamily::Style style = EpdFontFamily::REGULAR) const;
  int drawPagerWrappedText(const char* text, int fontId, int x, int y, int maxWidth, int maxLines,
                           EpdFontFamily::Style style = EpdFontFamily::REGULAR) const;

  bool fromTimeout = false;
  bool pagerLowBatterySleep = false;
  bool pagerMode = false;
  bool pagerMailboxMode = false;
  bool pagerReturnToReader = false;
  PagerContentType pagerContentType = PagerContentType::None;
  bool pagerLowBatteryDetected = false;
  unsigned long pagerPowerButtonPressedAt = 0;
  unsigned long lastPagerBatteryCheckMs = 0;
  uint16_t pagerBatteryPercent = 100;
  int pagerUpdatesUntilCleanRefresh = 0;
  // Enter Pager with the X3 differential waveform; HALF_REFRESH requests a
  // panel resync and looks like a full refresh. Message cleanup still follows
  // Settings > Display > Refresh Frequency via nextPagerRefreshMode().
  HalDisplay::RefreshMode pagerRefreshMode = HalDisplay::FAST_REFRESH;
  static constexpr uint8_t PAGER_MAX_NOTIFICATIONS = 10;
  static constexpr uint16_t PAGER_LOW_BATTERY_PERCENT = 25;
  // Above this threshold, a slow probe is enough to notice the transition;
  // at or below it, poll promptly so the 25% deep-sleep safeguard is timely.
  static constexpr uint16_t PAGER_LOW_BATTERY_POLL_START_PERCENT = 40;
  static constexpr unsigned long PAGER_HEALTHY_BATTERY_PROBE_MS = 15UL * 60UL * 1000UL;
  static constexpr unsigned long PAGER_LOW_BATTERY_PROBE_MS = 5UL * 60UL * 1000UL;
  char pagerTitle[49] = {};
  char pagerMessage[93] = {};
  char pagerFooter[49] = {};
  PagerNotification pagerNotifications[PAGER_MAX_NOTIFICATIONS] = {};
  uint8_t pagerNotificationCount = 0;
  uint8_t pagerNotificationLimit = PAGER_MAX_NOTIFICATIONS;
  bool pagerBatchOpen = false;
  bool pagerRingChanged = false;
  char pagerBatchId[17] = {};
  unsigned long pagerStandaloneRenderAt = 0;
};
