#include "SleepActivity.h"

#include <Epub.h>
#include <FsHelpers.h>
#include <GfxRenderer.h>
#include <HalBlePager.h>
#include <HalPowerManager.h>
#include <HalStorage.h>
#include <I18n.h>
#include <Txt.h>
#include <Xtc.h>
#include <esp_system.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "CrossPointSettings.h"
#include "CrossPointState.h"
#include "activities/reader/ReaderUtils.h"
#include "components/UITheme.h"
#include "fontIds.h"
#include "images/Logo120.h"
#include "images/MoonIcon.h"

namespace {
static_assert(HalBlePager::CLIENT_TOKEN_BYTES == CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES,
              "Pager BLE and settings token sizes must match");

HalBlePager::NormalPowerProfile pagerNormalPowerProfile() {
  switch (SETTINGS.pagerNormalPowerProfile) {
    case CrossPointSettings::PAGER_PROFILE_RESPONSIVE:
      return HalBlePager::NormalPowerProfile::Responsive;
    case CrossPointSettings::PAGER_PROFILE_BATTERY_SAVER:
      return HalBlePager::NormalPowerProfile::BatterySaver;
    case CrossPointSettings::PAGER_PROFILE_BALANCED:
    default:
      return HalBlePager::NormalPowerProfile::Balanced;
  }
}

bool isPagerClientTokenValid(const char* token) {
  if (token == nullptr || std::strlen(token) != CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES) {
    return false;
  }
  for (size_t index = 0; index < CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES; index++) {
    const char character = token[index];
    if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f') ||
          (character >= 'A' && character <= 'F'))) {
      return false;
    }
  }
  return true;
}

void generatePagerClientToken(char* destination, const size_t destinationSize) {
  if (destination == nullptr || destinationSize < CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES + 1) {
    return;
  }

  static constexpr char TOKEN_ALPHABET[] = "0123456789abcdef";
  uint32_t randomValue = 0;
  for (size_t index = 0; index < CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES; index++) {
    if ((index % 8) == 0) {
      randomValue = esp_random();
    }
    destination[index] = TOKEN_ALPHABET[randomValue & 0x0F];
    randomValue >>= 4;
  }
  destination[CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES] = '\0';
}

void ensurePagerClientToken() {
  if (isPagerClientTokenValid(SETTINGS.pagerClientToken)) {
    return;
  }

  generatePagerClientToken(SETTINGS.pagerClientToken, sizeof(SETTINGS.pagerClientToken));
  SETTINGS.pagerClientEnrolled = 0;
  if (!SETTINGS.saveToFile()) {
    LOG_ERR("PAGER", "Could not save generated Pager enrollment token");
  }
}
}  // namespace

void SleepActivity::onEnter() {
  Activity::onEnter();

  if (pagerLowBatterySleep) {
    renderPagerLowBatterySleepScreen();
    return;
  }

  pagerMode = SETTINGS.sleepScreen == CrossPointSettings::SLEEP_SCREEN_MODE::PAGER;
  if (pagerMode) {
    // Pager replaces the reader activity instead of deep-sleeping it. Preserve
    // the normal sleep-wake destination so its power-button exit reopens the
    // book at its already-persisted reading position.
    pagerReturnToReader = APP_STATE.lastSleepFromReader && !APP_STATE.openEpubPath.empty();
    pagerUpdatesUntilCleanRefresh = SETTINGS.getRefreshFrequency();
    checkPagerBatteryLevel(true);
    if (pagerLowBatteryDetected) {
      return;
    }
    blePager.resetPayloadHistory();
    ensurePagerClientToken();
    pagerMailboxMode = SETTINGS.pagerClientEnrolled != 0 &&
                       SETTINGS.pagerConnectionMode == CrossPointSettings::PAGER_MAILBOX;
    if (pagerMailboxMode && !powerManager.canUsePagerMailboxLightSleep()) {
      LOG_ERR("PAGER", "Mailbox mode requires the pager_power firmware; using Normal mode");
      pagerMailboxMode = false;
    }
    // The controller must be initialized before automatic light sleep is
    // enabled. Its BLE wake source then keeps advertising and GATT events
    // alive while Pager otherwise sleeps.
    startPagerBle();
    renderPagerSleepScreen(pagerRefreshMode);
    return;
  }

  const bool renderQuickResume =
      SETTINGS.sleepScreen == CrossPointSettings::SLEEP_SCREEN_MODE::QUICK_RESUME ||
      (fromTimeout &&
       SETTINGS.quickResumeSleepScreen == CrossPointSettings::QUICK_RESUME_SLEEP_SCREEN::QUICK_RESUME_AFTER_TIMEOUT);

  if (renderQuickResume) {
    return renderLastScreenSleepScreen();
  }

  // Show popup with reader orientation only when going to sleep from reader
  if (APP_STATE.lastSleepFromReader) {
    ReaderUtils::applyOrientation(renderer, SETTINGS.orientation);
    GUI.drawPopup(renderer, tr(STR_ENTERING_SLEEP));
    renderer.setOrientation(GfxRenderer::Orientation::Portrait);
  } else {
    GUI.drawPopup(renderer, tr(STR_ENTERING_SLEEP));
  }

  switch (SETTINGS.sleepScreen) {
    case (CrossPointSettings::SLEEP_SCREEN_MODE::BLANK):
      return renderBlankSleepScreen();
    case (CrossPointSettings::SLEEP_SCREEN_MODE::CUSTOM):
      return renderCustomSleepScreen();
    case (CrossPointSettings::SLEEP_SCREEN_MODE::COVER):
      return renderCoverSleepScreen();
    case (CrossPointSettings::SLEEP_SCREEN_MODE::COVER_CUSTOM):
      if (APP_STATE.lastSleepFromReader) {
        return renderCoverSleepScreen();
      } else {
        return renderCustomSleepScreen();
      }
    default:
      return renderDefaultSleepScreen();
  }
}

void SleepActivity::onExit() {
  if (pagerMode) {
    blePager.end();
    powerManager.disablePagerLightSleep();
  }
  Activity::onExit();
}

void SleepActivity::loop() {
  if (!pagerMode) {
    return;
  }

  checkPagerBatteryLevel();
  if (pagerLowBatteryDetected) {
    return;
  }

  if (mappedInput.wasPressed(MappedInputManager::Button::Power)) {
    pagerPowerButtonPressedAt = millis();
  }

  if (mappedInput.wasReleased(MappedInputManager::Button::Power) && pagerPowerButtonPressedAt != 0) {
    const bool shouldExit = millis() - pagerPowerButtonPressedAt >= SETTINGS.getPowerButtonDuration();
    pagerPowerButtonPressedAt = 0;
    if (shouldExit) {
      exitPager();
    }
    return;
  }
  if (pagerPowerButtonPressedAt != 0) {
    return;
  }

  blePager.update();
  persistPagerEnrollmentIfNeeded();
  transitionPagerMailboxIfReady();
  if (pagerMailboxMode && blePager.isMailboxWaiting()) {
    runPagerMailboxSleep();
    return;
  }

  const size_t payloadLength = blePager.takePayload(pagerPayload, sizeof(pagerPayload));
  if (payloadLength > 0) {
    // The screen will render below, so refresh the header's battery reading at
    // the same time without scheduling a battery-only e-ink update.
    checkPagerBatteryLevel(true);
    if (pagerLowBatteryDetected) {
      return;
    }
    updatePagerText(pagerPayload, payloadLength);
    pagerRefreshMode = nextPagerRefreshMode();
    requestUpdate();
  }
}

bool SleepActivity::startPagerBle(const HalBlePager::MailboxStart mailboxStart) {
  const auto connectionMode =
      pagerMailboxMode ? HalBlePager::ConnectionMode::Mailbox : HalBlePager::ConnectionMode::Normal;
  const auto configuredConnectionMode =
      SETTINGS.pagerConnectionMode == CrossPointSettings::PAGER_MAILBOX &&
              powerManager.canUsePagerMailboxLightSleep()
          ? HalBlePager::ConnectionMode::Mailbox
          : HalBlePager::ConnectionMode::Normal;
  if (blePager.begin(connectionMode, configuredConnectionMode, SETTINGS.pagerMailboxIntervalMinutes,
                     pagerNormalPowerProfile(), SETTINGS.pagerClientEnrolled != 0, SETTINGS.pagerClientToken,
                     mailboxStart)) {
    if (blePager.isRadioRunning()) {
      powerManager.enablePagerLightSleep();
    }
    return true;
  }

  LOG_ERR("PAGER", "Bluetooth unavailable; Pager will stay awake");
  return false;
}

void SleepActivity::persistPagerEnrollmentIfNeeded() {
  char enrolledToken[CrossPointSettings::PAGER_CLIENT_TOKEN_BYTES + 1] = {};
  if (blePager.takeEnrollmentToken(enrolledToken, sizeof(enrolledToken)) == 0) {
    return;
  }

  std::memcpy(SETTINGS.pagerClientToken, enrolledToken, sizeof(enrolledToken));
  SETTINGS.pagerClientEnrolled = 1;
  if (!SETTINGS.saveToFile()) {
    LOG_ERR("PAGER", "Could not save Pager enrollment");
  }
  LOG_INF("PAGER", "Pager client enrolled");
}

void SleepActivity::transitionPagerMailboxIfReady() {
  if (pagerMailboxMode || !blePager.takeMailboxHandoffRequest()) {
    return;
  }
  if (!powerManager.canUsePagerMailboxLightSleep()) {
    LOG_ERR("PAGER", "Mailbox mode requires the pager_power firmware; staying Always Available after enrollment");
    return;
  }

  powerManager.disablePagerLightSleep();
  blePager.end();
  pagerMailboxMode = true;
  startPagerBle(HalBlePager::MailboxStart::WaitForInterval);
}

void SleepActivity::runPagerMailboxSleep() {
  if (blePager.isRadioRunning()) {
    if (!blePager.suspendMailboxRadio()) {
      return;
    }
  }

  const unsigned long untilNextWindowMs = blePager.getMailboxSleepDurationMs();
  if (untilNextWindowMs == 0) {
    if (blePager.resumeMailboxWindow() && !powerManager.enablePagerLightSleep()) {
      LOG_ERR("PAGER", "Bluetooth started without automatic light sleep");
    }
    return;
  }

  const unsigned long batteryIntervalMs = pagerBatteryPercent <= PAGER_LOW_BATTERY_POLL_START_PERCENT
                                              ? PAGER_LOW_BATTERY_PROBE_MS
                                              : PAGER_HEALTHY_BATTERY_PROBE_MS;
  const unsigned long elapsedSinceBatteryCheckMs = millis() - lastPagerBatteryCheckMs;
  if (elapsedSinceBatteryCheckMs >= batteryIntervalMs) {
    checkPagerBatteryLevel(true);
    if (pagerLowBatteryDetected) {
      return;
    }
  }

  const unsigned long untilBatteryCheckMs = batteryIntervalMs - (millis() - lastPagerBatteryCheckMs);
  const auto wake = powerManager.sleepForPagerMailbox(std::min(untilNextWindowMs, untilBatteryCheckMs));
  if (wake == HalPowerManager::PagerMailboxWake::Timer) {
    checkPagerBatteryLevel();
  }
}

void SleepActivity::render(RenderLock&&) {
  if (pagerMode) {
    renderPagerSleepScreen(pagerRefreshMode);
  }
}

bool SleepActivity::preventAutoSleep() { return pagerMode; }

bool SleepActivity::shouldEnterDeepSleep() { return pagerLowBatteryDetected; }

void SleepActivity::checkPagerBatteryLevel(const bool force) {
  const unsigned long now = millis();
  const unsigned long interval = pagerBatteryPercent <= PAGER_LOW_BATTERY_POLL_START_PERCENT
                                     ? PAGER_LOW_BATTERY_PROBE_MS
                                     : PAGER_HEALTHY_BATTERY_PROBE_MS;
  if (!force && lastPagerBatteryCheckMs != 0 && now - lastPagerBatteryCheckMs < interval) {
    return;
  }
  lastPagerBatteryCheckMs = now;

  pagerBatteryPercent = powerManager.getBatteryPercentage();
  if (pagerBatteryPercent > PAGER_LOW_BATTERY_PERCENT) {
    return;
  }

  pagerLowBatteryDetected = true;
  LOG_INF("PAGER", "Battery at %u%%; entering deep sleep", pagerBatteryPercent);
}

void SleepActivity::exitPager() {
  if (pagerReturnToReader) {
    // Pager stays powered and has replaced the reader activity, so it does
    // not pass through the normal Quick Resume boot path. Re-arm that same
    // saved refresh-cycle handoff before reopening the book; otherwise the
    // fresh reader starts at zero and immediately forces a HALF refresh.
    APP_STATE.restoreReaderRefreshCycle = true;
    APP_STATE.saveToFile();
    onSelectBook(APP_STATE.openEpubPath);
    return;
  }
  onGoHome();
}

HalDisplay::RefreshMode SleepActivity::nextPagerRefreshMode() {
  if (pagerUpdatesUntilCleanRefresh <= 1) {
    pagerUpdatesUntilCleanRefresh = SETTINGS.getRefreshFrequency();
    return HalDisplay::HALF_REFRESH;
  }

  pagerUpdatesUntilCleanRefresh--;
  return HalDisplay::FAST_REFRESH;
}

void SleepActivity::updatePagerText(char* payload, const size_t length) {
  char* cursor = payload;
  char* const end = payload + length;
  pagerNotificationCount = 0;

  const auto takeLine = [&cursor, end]() -> char* {
    if (cursor >= end) {
      return nullptr;
    }
    char* const line = cursor;
    while (cursor < end && *cursor != '\n') {
      cursor++;
    }
    if (cursor < end) {
      *cursor++ = '\0';
    }
    return line;
  };
  const auto sanitizeField = [](char* field) {
    if (field == nullptr) {
      return;
    }
    for (char* character = field; *character != '\0'; character++) {
      if (static_cast<unsigned char>(*character) < 0x20) {
        *character = ' ';
      }
    }
  };

  char* const firstLine = takeLine();
  if (firstLine != nullptr && std::strcmp(firstLine, "XPSTACK1") == 0) {
    pagerContentType = PagerContentType::NotificationStack;
    while (pagerNotificationCount < PAGER_MAX_NOTIFICATIONS) {
      char* const line = takeLine();
      if (line == nullptr) {
        break;
      }
      char* const firstSeparator = std::strchr(line, '\t');
      if (firstSeparator == nullptr) {
        continue;
      }
      *firstSeparator = '\0';
      char* const secondSeparator = std::strchr(firstSeparator + 1, '\t');
      if (secondSeparator == nullptr) {
        continue;
      }
      *secondSeparator = '\0';

      char* const time = line;
      char* const title = firstSeparator + 1;
      char* const message = secondSeparator + 1;
      sanitizeField(time);
      sanitizeField(title);
      sanitizeField(message);
      if (*title == '\0' && *message == '\0') {
        continue;
      }
      pagerNotifications[pagerNotificationCount++] = PagerNotification{time, title, message};
    }
    return;
  }

  pagerContentType = PagerContentType::Message;
  char* const message = takeLine();
  char* const footer = takeLine();
  sanitizeField(firstLine);
  sanitizeField(message);
  sanitizeField(footer);
  pagerTitle = firstLine == nullptr ? "" : firstLine;
  pagerMessage = message == nullptr ? "" : message;
  pagerFooter = footer == nullptr ? "" : footer;
}

int SleepActivity::drawPagerWrappedText(const char* text, const int fontId, const int x, int y, const int maxWidth,
                                        const int maxLines, const EpdFontFamily::Style style) const {
  if (text == nullptr || *text == '\0' || maxWidth <= 0 || maxLines <= 0) {
    return y;
  }

  static constexpr size_t LINE_BYTES = 128;
  const char* cursor = text;
  const int lineHeight = renderer.getLineHeight(fontId);
  for (int lineNumber = 0; lineNumber < maxLines && *cursor != '\0'; lineNumber++) {
    while (*cursor == ' ') {
      cursor++;
    }
    char line[LINE_BYTES] = {};
    size_t used = 0;

    while (*cursor != '\0') {
      while (*cursor == ' ') {
        cursor++;
      }
      if (*cursor == '\0') {
        break;
      }
      const char* const wordStart = cursor;
      const char* wordEnd = wordStart;
      while (*wordEnd != '\0' && *wordEnd != ' ') {
        wordEnd++;
      }
      const size_t wordBytes = static_cast<size_t>(wordEnd - wordStart);
      const size_t separatorBytes = used == 0 ? 0 : 1;
      if (used + separatorBytes + wordBytes < LINE_BYTES) {
        const size_t previousUsed = used;
        if (separatorBytes != 0) {
          line[used++] = ' ';
        }
        std::memcpy(line + used, wordStart, wordBytes);
        used += wordBytes;
        line[used] = '\0';
        if (renderer.getTextWidth(fontId, line, style) <= maxWidth) {
          cursor = wordEnd;
          while (*cursor == ' ') {
            cursor++;
          }
          continue;
        }
        used = previousUsed;
        line[used] = '\0';
      }
      if (used != 0) {
        break;
      }

      while (cursor < wordEnd && used + 4 < LINE_BYTES) {
        const unsigned char lead = static_cast<unsigned char>(*cursor);
        const size_t characterBytes = lead < 0x80 ? 1 : (lead & 0xE0) == 0xC0 ? 2 : (lead & 0xF0) == 0xE0 ? 3 : 4;
        if (cursor + characterBytes > wordEnd || used + characterBytes >= LINE_BYTES) {
          break;
        }
        std::memcpy(line + used, cursor, characterBytes);
        used += characterBytes;
        line[used] = '\0';
        if (renderer.getTextWidth(fontId, line, style) > maxWidth) {
          used -= characterBytes;
          line[used] = '\0';
          break;
        }
        cursor += characterBytes;
      }
      if (used == 0 && cursor < wordEnd) {
        cursor++;
      }
      break;
    }

    if (used == 0) {
      break;
    }
    renderer.drawText(fontId, x, y, line, true, style);
    y += lineHeight;
  }
  return y;
}

void SleepActivity::renderPagerSleepScreen(const HalDisplay::RefreshMode refreshMode) const {
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();
  const auto& metrics = UITheme::getInstance().getMetrics();
  int marginTop = 0;
  int marginRight = 0;
  int marginBottom = 0;
  int marginLeft = 0;
  renderer.getOrientedViewableTRBL(&marginTop, &marginRight, &marginBottom, &marginLeft);
  const int sidePadding = metrics.contentSidePadding;
  const int headingX = marginLeft + sidePadding;
  const int headingY = marginTop + metrics.topPadding;
  const int contentRight = pageWidth - marginRight - sidePadding;
  const int headingHeight = renderer.getLineHeight(NOTOSANS_18_FONT_ID);
  const int timelineTop = headingY + headingHeight + metrics.verticalSpacing;
  const int timelineBottom = pageHeight - marginBottom - metrics.verticalSpacing;
  const int timelineX = headingX;
  const int contentX = timelineX + sidePadding;
  const int contentWidth = contentRight - contentX;

  renderer.clearScreen();
  renderer.drawText(NOTOSANS_18_FONT_ID, headingX, headingY, tr(STR_PAGER), true, EpdFontFamily::BOLD);
  const int batteryX = contentRight - metrics.batteryWidth;
  const int batteryY = headingY + (headingHeight - metrics.batteryHeight) / 2;
  BaseTheme::drawBatteryOutline(renderer, batteryX, batteryY, metrics.batteryWidth, metrics.batteryHeight);
  GUI.fillBatteryIcon(renderer, Rect{batteryX, batteryY, metrics.batteryWidth, metrics.batteryHeight},
                      pagerBatteryPercent);
  if (SETTINGS.hideBatteryPercentage != CrossPointSettings::HIDE_BATTERY_PERCENTAGE::HIDE_ALWAYS) {
    char batteryLabel[8] = {};
    snprintf(batteryLabel, sizeof(batteryLabel), "%u%%", static_cast<unsigned int>(pagerBatteryPercent));
    const int batteryLabelWidth = renderer.getTextWidth(SMALL_FONT_ID, batteryLabel);
    renderer.drawText(SMALL_FONT_ID, batteryX - batteryLabelWidth - BaseTheme::batteryPercentSpacing,
                      headingY + headingHeight / 2, batteryLabel);
  }
  renderer.fillRect(timelineX, timelineTop, 3, timelineBottom - timelineTop);

  if (SETTINGS.pagerClientEnrolled == 0) {
    char setupLabel[16] = {};
    HalBlePager::copySetupLabel(setupLabel, sizeof(setupLabel));
    renderer.drawCenteredText(UI_12_FONT_ID, pageHeight / 2 - 20, tr(STR_PAGER_WAITING_ENROLLMENT), true,
                              EpdFontFamily::BOLD);
    renderer.drawCenteredText(SMALL_FONT_ID, pageHeight / 2 + 15, tr(STR_PAGER_SETUP_OPEN));
    renderer.drawCenteredText(SMALL_FONT_ID, pageHeight / 2 + 45, setupLabel);
  } else {
    switch (pagerContentType) {
      case PagerContentType::NotificationStack: {
        if (pagerNotificationCount == 0) {
          renderer.drawCenteredText(UI_12_FONT_ID, pageHeight / 2 - 20, tr(STR_BLUETOOTH_WAITING), true,
                                    EpdFontFamily::BOLD);
          renderer.drawCenteredText(SMALL_FONT_ID, pageHeight / 2 + 20, tr(STR_PAGER_STANDBY));
          break;
        }
        const int availableHeight = timelineBottom - timelineTop;
        const int naturalRowHeight = renderer.getLineHeight(UI_10_FONT_ID) +
                                     renderer.getLineHeight(SMALL_FONT_ID) * 2 + metrics.verticalSpacing;
        const int rowHeight = std::max(1, std::min(naturalRowHeight, availableHeight / pagerNotificationCount));
        for (uint8_t index = 0; index < pagerNotificationCount; index++) {
          const int rowTop = timelineTop + rowHeight * index;
          const int rowBottom = std::min(timelineBottom, rowTop + rowHeight);
          const int rowPadding = std::min(metrics.verticalSpacing, std::max(2, (rowBottom - rowTop) / 8));
          const auto& notification = pagerNotifications[index];
          const int timeWidth = renderer.getTextWidth(SMALL_FONT_ID, notification.time);
          const int titleWidth = std::max(1, contentWidth - timeWidth - metrics.verticalSpacing);
          const int titleY = rowTop + rowPadding;
          renderer.drawText(SMALL_FONT_ID, contentRight - timeWidth, titleY, notification.time);
          const int messageY = drawPagerWrappedText(notification.title, UI_10_FONT_ID, contentX, titleY, titleWidth, 1,
                                                    EpdFontFamily::BOLD) + 2;
          const int messageLines = std::max(0, (rowBottom - rowPadding - messageY) /
                                                   renderer.getLineHeight(SMALL_FONT_ID));
          drawPagerWrappedText(notification.message, SMALL_FONT_ID, contentX, messageY, contentWidth, messageLines);
        }
        break;
      }
      case PagerContentType::Message: {
        int textY = timelineTop + metrics.verticalSpacing;
        textY = drawPagerWrappedText(pagerTitle, NOTOSANS_14_FONT_ID, contentX, textY, contentWidth, 2,
                                     EpdFontFamily::BOLD) + metrics.verticalSpacing;
        const int footerHeight = *pagerFooter == '\0' ? 0 : renderer.getLineHeight(SMALL_FONT_ID) * 2;
        const int messageLines = std::max(0, (timelineBottom - textY - footerHeight - metrics.verticalSpacing) /
                                                 renderer.getLineHeight(UI_10_FONT_ID));
        drawPagerWrappedText(pagerMessage, UI_10_FONT_ID, contentX, textY, contentWidth, messageLines);
        if (footerHeight != 0) {
          drawPagerWrappedText(pagerFooter, SMALL_FONT_ID, contentX, timelineBottom - footerHeight, contentWidth, 2);
        }
        break;
      }
      case PagerContentType::None:
        renderer.drawCenteredText(UI_12_FONT_ID, pageHeight / 2 - 20, tr(STR_BLUETOOTH_WAITING), true,
                                  EpdFontFamily::BOLD);
        renderer.drawCenteredText(SMALL_FONT_ID, pageHeight / 2 + 20, tr(STR_PAGER_STANDBY));
        break;
    }
  }
  // E-ink retains the image without power. Match the reader's deep-sleep
  // cleanup by shutting down the controller analog rails after every Pager
  // paint; the next update powers it back up while preserving fast refresh.
  renderer.displayBufferAndPowerOff(refreshMode);
}

void SleepActivity::renderCustomSleepScreen() const {
  // Check if we have a /.sleep (preferred) or /sleep directory
  const char* sleepDir = nullptr;
  auto dir = Storage.open("/.sleep");

  // Look for sleep.bmp on the root of the sd card to determine if we should
  // render a custom sleep screen instead of the default.
  // This takes priority over the /sleep folder.
  HalFile file;
  if (Storage.openFileForRead("SLP", "/sleep.bmp", file)) {
    Bitmap bitmap(file, true);
    if (bitmap.parseHeaders() == BmpReaderError::Ok) {
      LOG_DBG("SLP", "Loading: /sleep.bmp");
      renderBitmapSleepScreen(bitmap);
      file.close();
      if (dir) dir.close();
      return;
    }
    file.close();
  }

  if (dir && dir.isDirectory()) {
    sleepDir = "/.sleep";
  } else {
    dir = Storage.open("/sleep");
    if (dir && dir.isDirectory()) {
      sleepDir = "/sleep";
    }
  }

  if (sleepDir) {
    std::vector<std::string> files;
    char name[500];
    // collect all valid BMP files
    for (auto dirFile = dir.openNextFile(); dirFile; dirFile = dir.openNextFile()) {
      if (dirFile.isDirectory()) {
        dirFile.close();
        continue;
      }
      dirFile.getName(name, sizeof(name));
      auto filename = std::string(name);
      if (filename[0] == '.') {
        dirFile.close();
        continue;
      }

      if (!FsHelpers::hasBmpExtension(filename)) {
        LOG_DBG("SLP", "Skipping non-.bmp file name: %s", name);
        dirFile.close();
        continue;
      }
      Bitmap bitmap(dirFile);
      if (bitmap.parseHeaders() != BmpReaderError::Ok) {
        LOG_DBG("SLP", "Skipping invalid BMP file: %s", name);
        dirFile.close();
        continue;
      }
      files.emplace_back(filename);
      dirFile.close();
    }
    const auto numFiles = files.size();
    if (numFiles > 0) {
      // Pick a random wallpaper, excluding recently shown ones.
      // Window: up to SLEEP_RECENT_COUNT entries, capped at numFiles-1.
      const uint16_t fileCount = static_cast<uint16_t>(std::min(numFiles, static_cast<size_t>(UINT16_MAX)));
      const uint8_t window =
          static_cast<uint8_t>(std::min(static_cast<size_t>(APP_STATE.recentSleepFill), numFiles - 1));
      auto randomFileIndex = static_cast<uint16_t>(random(fileCount));
      for (uint8_t attempt = 0; attempt < 20 && APP_STATE.isRecentSleep(randomFileIndex, window); attempt++) {
        randomFileIndex = static_cast<uint16_t>(random(fileCount));
      }
      APP_STATE.pushRecentSleep(randomFileIndex);
      APP_STATE.saveToFile();
      const auto filename = std::string(sleepDir) + "/" + files[randomFileIndex];
      HalFile randFile;
      if (Storage.openFileForRead("SLP", filename, randFile)) {
        LOG_DBG("SLP", "Randomly loading: %s/%s", sleepDir, files[randomFileIndex].c_str());
        delay(100);
        Bitmap bitmap(randFile, true);
        if (bitmap.parseHeaders() == BmpReaderError::Ok) {
          renderBitmapSleepScreen(bitmap);
          randFile.close();
          dir.close();
          return;
        }
        randFile.close();
      }
    }
  }
  if (dir) dir.close();

  renderDefaultSleepScreen();
}

void SleepActivity::renderDefaultSleepScreen() const {
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();

  renderer.clearScreen();
  renderer.drawImage(Logo120, (pageWidth - 120) / 2, (pageHeight - 120) / 2, 120, 120);
  renderer.drawCenteredText(UI_10_FONT_ID, pageHeight / 2 + 70, tr(STR_CROSSPOINT), true, EpdFontFamily::BOLD);
  renderer.drawCenteredText(SMALL_FONT_ID, pageHeight / 2 + 95, tr(STR_SLEEPING));

  // Make sleep screen dark unless light is selected in settings
  if (SETTINGS.sleepScreen != CrossPointSettings::SLEEP_SCREEN_MODE::LIGHT) {
    renderer.invertScreen();
  }

  renderer.displayBuffer(HalDisplay::HALF_REFRESH);
}

void SleepActivity::renderBitmapSleepScreen(const Bitmap& bitmap) const {
  int x, y;
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();
  float cropX = 0, cropY = 0;

  LOG_DBG("SLP", "bitmap %d x %d, screen %d x %d", bitmap.getWidth(), bitmap.getHeight(), pageWidth, pageHeight);
  if (bitmap.getWidth() > pageWidth || bitmap.getHeight() > pageHeight) {
    // image will scale, make sure placement is right
    float ratio = static_cast<float>(bitmap.getWidth()) / static_cast<float>(bitmap.getHeight());
    const float screenRatio = static_cast<float>(pageWidth) / static_cast<float>(pageHeight);

    LOG_DBG("SLP", "bitmap ratio: %f, screen ratio: %f", ratio, screenRatio);
    if (ratio > screenRatio) {
      // image wider than viewport ratio, scaled down image needs to be centered vertically
      if (SETTINGS.sleepScreenCoverMode == CrossPointSettings::SLEEP_SCREEN_COVER_MODE::CROP) {
        cropX = 1.0f - (screenRatio / ratio);
        LOG_DBG("SLP", "Cropping bitmap x: %f", cropX);
        ratio = (1.0f - cropX) * static_cast<float>(bitmap.getWidth()) / static_cast<float>(bitmap.getHeight());
      }
      x = 0;
      y = std::round((static_cast<float>(pageHeight) - static_cast<float>(pageWidth) / ratio) / 2);
      LOG_DBG("SLP", "Centering with ratio %f to y=%d", ratio, y);
    } else {
      // image taller than viewport ratio, scaled down image needs to be centered horizontally
      if (SETTINGS.sleepScreenCoverMode == CrossPointSettings::SLEEP_SCREEN_COVER_MODE::CROP) {
        cropY = 1.0f - (ratio / screenRatio);
        LOG_DBG("SLP", "Cropping bitmap y: %f", cropY);
        ratio = static_cast<float>(bitmap.getWidth()) / ((1.0f - cropY) * static_cast<float>(bitmap.getHeight()));
      }
      x = std::round((static_cast<float>(pageWidth) - static_cast<float>(pageHeight) * ratio) / 2);
      y = 0;
      LOG_DBG("SLP", "Centering with ratio %f to x=%d", ratio, x);
    }
  } else {
    // center the image
    x = (pageWidth - bitmap.getWidth()) / 2;
    y = (pageHeight - bitmap.getHeight()) / 2;
  }

  LOG_DBG("SLP", "drawing to %d x %d", x, y);
  renderer.clearScreen();

  const bool hasGreyscale = bitmap.hasGreyscale() &&
                            SETTINGS.sleepScreenCoverFilter == CrossPointSettings::SLEEP_SCREEN_COVER_FILTER::NO_FILTER;

  renderer.drawBitmap(bitmap, x, y, pageWidth, pageHeight, cropX, cropY);

  if (SETTINGS.sleepScreenCoverFilter == CrossPointSettings::SLEEP_SCREEN_COVER_FILTER::INVERTED_BLACK_AND_WHITE) {
    renderer.invertScreen();
  }

  if (hasGreyscale) {
    // OEM grayscale pipeline base: on X3 this displays the frame with the
    // dedicated "AA-pre-BW(mid)" differential waveform, leaving every pixel
    // in the calibrated state the gray nudge refresh expects; on X4 it is a
    // plain HALF refresh (previous behavior).
    renderer.displayGrayscaleBase(HalDisplay::HALF_REFRESH);
  } else {
    renderer.displayBuffer(HalDisplay::HALF_REFRESH);
  }

  if (hasGreyscale) {
    bitmap.rewindToData();
    renderer.clearScreen(0x00);
    renderer.setRenderMode(GfxRenderer::GRAYSCALE_LSB);
    renderer.drawBitmap(bitmap, x, y, pageWidth, pageHeight, cropX, cropY);
    renderer.copyGrayscaleLsbBuffers();

    bitmap.rewindToData();
    renderer.clearScreen(0x00);
    renderer.setRenderMode(GfxRenderer::GRAYSCALE_MSB);
    renderer.drawBitmap(bitmap, x, y, pageWidth, pageHeight, cropX, cropY);
    renderer.copyGrayscaleMsbBuffers();

    renderer.displayGrayBuffer();
    renderer.setRenderMode(GfxRenderer::BW);
  }
}

void SleepActivity::renderCoverSleepScreen() const {
  void (SleepActivity::*renderNoCoverSleepScreen)() const;
  switch (SETTINGS.sleepScreen) {
    case (CrossPointSettings::SLEEP_SCREEN_MODE::COVER_CUSTOM):
      renderNoCoverSleepScreen = &SleepActivity::renderCustomSleepScreen;
      break;
    default:
      renderNoCoverSleepScreen = &SleepActivity::renderDefaultSleepScreen;
      break;
  }

  if (APP_STATE.openEpubPath.empty()) {
    return (this->*renderNoCoverSleepScreen)();
  }

  std::string coverBmpPath;
  bool cropped = SETTINGS.sleepScreenCoverMode == CrossPointSettings::SLEEP_SCREEN_COVER_MODE::CROP;

  // Check if the current book is XTC, TXT, or EPUB
  if (FsHelpers::hasXtcExtension(APP_STATE.openEpubPath)) {
    // Handle XTC file
    Xtc lastXtc(APP_STATE.openEpubPath, "/.crosspoint");
    if (!lastXtc.load()) {
      LOG_ERR("SLP", "Failed to load last XTC");
      return (this->*renderNoCoverSleepScreen)();
    }

    if (!lastXtc.generateCoverBmp()) {
      LOG_ERR("SLP", "Failed to generate XTC cover bmp");
      return (this->*renderNoCoverSleepScreen)();
    }

    coverBmpPath = lastXtc.getCoverBmpPath();
  } else if (FsHelpers::hasTxtExtension(APP_STATE.openEpubPath)) {
    // Handle TXT file - looks for cover image in the same folder
    Txt lastTxt(APP_STATE.openEpubPath, "/.crosspoint");
    if (!lastTxt.load()) {
      LOG_ERR("SLP", "Failed to load last TXT");
      return (this->*renderNoCoverSleepScreen)();
    }

    if (!lastTxt.generateCoverBmp()) {
      LOG_ERR("SLP", "No cover image found for TXT file");
      return (this->*renderNoCoverSleepScreen)();
    }

    coverBmpPath = lastTxt.getCoverBmpPath();
  } else if (FsHelpers::hasEpubExtension(APP_STATE.openEpubPath)) {
    // Handle EPUB file
    Epub lastEpub(APP_STATE.openEpubPath, "/.crosspoint");
    // Skip loading css since we only need metadata here
    if (!lastEpub.load(true, true)) {
      LOG_ERR("SLP", "Failed to load last epub");
      return (this->*renderNoCoverSleepScreen)();
    }

    if (!lastEpub.generateCoverBmp(cropped)) {
      LOG_ERR("SLP", "Failed to generate cover bmp");
      return (this->*renderNoCoverSleepScreen)();
    }

    coverBmpPath = lastEpub.getCoverBmpPath(cropped);
  } else {
    return (this->*renderNoCoverSleepScreen)();
  }

  HalFile file;
  if (Storage.openFileForRead("SLP", coverBmpPath, file)) {
    Bitmap bitmap(file);
    if (bitmap.parseHeaders() == BmpReaderError::Ok) {
      LOG_DBG("SLP", "Rendering sleep cover: %s", coverBmpPath.c_str());
      renderBitmapSleepScreen(bitmap);
      return;
    }
  }

  return (this->*renderNoCoverSleepScreen)();
}

void SleepActivity::renderLastScreenSleepScreen() const {
  const auto pageHeight = renderer.getScreenHeight();
  renderer.drawImage(MoonIcon, 0, pageHeight - MOONICON_HEIGHT, MOONICON_WIDTH, MOONICON_HEIGHT);
  renderer.displayBuffer(HalDisplay::FAST_REFRESH);
}

void SleepActivity::renderPagerLowBatterySleepScreen() const {
  GUI.drawPopup(renderer, tr(STR_PAGER_LOW_BATTERY));
  renderLastScreenSleepScreen();
}

void SleepActivity::renderBlankSleepScreen() const {
  renderer.clearScreen();
  renderer.displayBuffer(HalDisplay::HALF_REFRESH);
}
