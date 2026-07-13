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
  void renderPagerSleepScreen() const;
  void updatePagerText(const char* payload, size_t length);

  bool fromTimeout = false;
  bool pagerMode = false;
  bool pagerHasData = false;
  static constexpr size_t PAGER_TITLE_BYTES = 80;
  static constexpr size_t PAGER_MESSAGE_BYTES = 180;
  static constexpr size_t PAGER_FOOTER_BYTES = 56;
  char pagerTitle[PAGER_TITLE_BYTES] = {};
  char pagerMessage[PAGER_MESSAGE_BYTES] = {};
  char pagerFooter[PAGER_FOOTER_BYTES] = {};
};
