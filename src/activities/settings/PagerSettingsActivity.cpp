#include "PagerSettingsActivity.h"

#include <GfxRenderer.h>
#include <I18n.h>

#include <cstdio>
#include <string>

#include "CrossPointSettings.h"
#include "MappedInputManager.h"
#include "components/UITheme.h"

namespace {
enum class MenuItem : uint8_t {
  ConnectionMode,
  MailboxInterval,
  Count,
};

constexpr int MENU_ITEM_COUNT = static_cast<int>(MenuItem::Count);
constexpr uint8_t MAILBOX_INTERVAL_MINUTES[] = {1, 5, 15, 30, 60};
constexpr size_t MAILBOX_INTERVAL_COUNT = sizeof(MAILBOX_INTERVAL_MINUTES) / sizeof(MAILBOX_INTERVAL_MINUTES[0]);
constexpr StrId MENU_NAMES[MENU_ITEM_COUNT] = {
    StrId::STR_PAGER_CONNECTION_MODE,
    StrId::STR_PAGER_MAILBOX_INTERVAL,
};
constexpr StrId CONNECTION_MODE_NAMES[] = {
    StrId::STR_PAGER_NORMAL,
    StrId::STR_PAGER_MAILBOX,
};

bool isMailboxInterval(const uint8_t minutes) {
  for (const uint8_t option : MAILBOX_INTERVAL_MINUTES) {
    if (minutes == option) {
      return true;
    }
  }
  return false;
}

uint8_t nextMailboxInterval(const uint8_t current) {
  for (size_t index = 0; index < MAILBOX_INTERVAL_COUNT; index++) {
    if (MAILBOX_INTERVAL_MINUTES[index] == current) {
      return MAILBOX_INTERVAL_MINUTES[(index + 1) % MAILBOX_INTERVAL_COUNT];
    }
  }
  return MAILBOX_INTERVAL_MINUTES[0];
}
}  // namespace

void PagerSettingsActivity::onEnter() {
  Activity::onEnter();

  if (SETTINGS.pagerConnectionMode >= CrossPointSettings::PAGER_CONNECTION_MODE_COUNT) {
    SETTINGS.pagerConnectionMode = CrossPointSettings::PAGER_NORMAL;
  }
  if (!isMailboxInterval(SETTINGS.pagerMailboxIntervalMinutes)) {
    SETTINGS.pagerMailboxIntervalMinutes = 5;
  }
  selectedIndex = 0;
  requestUpdate();
}

void PagerSettingsActivity::loop() {
  if (mappedInput.wasPressed(MappedInputManager::Button::Back)) {
    finish();
    return;
  }

  if (mappedInput.wasPressed(MappedInputManager::Button::Confirm)) {
    handleSelection();
    requestUpdate();
    return;
  }

  buttonNavigator.onNextRelease([this] {
    selectedIndex = ButtonNavigator::nextIndex(selectedIndex, MENU_ITEM_COUNT);
    requestUpdate();
  });
  buttonNavigator.onPreviousRelease([this] {
    selectedIndex = ButtonNavigator::previousIndex(selectedIndex, MENU_ITEM_COUNT);
    requestUpdate();
  });
  buttonNavigator.onNextContinuous([this] {
    selectedIndex = ButtonNavigator::nextIndex(selectedIndex, MENU_ITEM_COUNT);
    requestUpdate();
  });
  buttonNavigator.onPreviousContinuous([this] {
    selectedIndex = ButtonNavigator::previousIndex(selectedIndex, MENU_ITEM_COUNT);
    requestUpdate();
  });
}

void PagerSettingsActivity::handleSelection() {
  switch (static_cast<MenuItem>(selectedIndex)) {
    case MenuItem::ConnectionMode:
      SETTINGS.pagerConnectionMode =
          (SETTINGS.pagerConnectionMode + 1) % CrossPointSettings::PAGER_CONNECTION_MODE_COUNT;
      break;
    case MenuItem::MailboxInterval:
      SETTINGS.pagerMailboxIntervalMinutes = nextMailboxInterval(SETTINGS.pagerMailboxIntervalMinutes);
      break;
    case MenuItem::Count:
      return;
  }
  SETTINGS.saveToFile();
}

void PagerSettingsActivity::render(RenderLock&&) {
  renderer.clearScreen();

  const auto& metrics = UITheme::getInstance().getMetrics();
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();
  const int contentTop = metrics.topPadding + metrics.headerHeight + metrics.verticalSpacing;
  const int contentHeight = pageHeight - contentTop - metrics.buttonHintsHeight - metrics.verticalSpacing * 2;

  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, pageWidth, metrics.headerHeight}, tr(STR_PAGER));
  GUI.drawList(
      renderer, Rect{0, contentTop, pageWidth, contentHeight}, MENU_ITEM_COUNT, selectedIndex,
      [](const int index) { return std::string(I18N.get(MENU_NAMES[index])); }, nullptr, nullptr,
      [](const int index) -> std::string {
        switch (static_cast<MenuItem>(index)) {
          case MenuItem::ConnectionMode:
            return I18N.get(CONNECTION_MODE_NAMES[SETTINGS.pagerConnectionMode]);
          case MenuItem::MailboxInterval: {
            char value[16] = {};
            snprintf(value, sizeof(value), tr(STR_SLEEP_TIMER_VALUE_FORMAT),
                     static_cast<unsigned int>(SETTINGS.pagerMailboxIntervalMinutes));
            return value;
          }
          case MenuItem::Count:
            return "";
        }
        return "";
      },
      true);

  const auto labels = mappedInput.mapLabels(tr(STR_BACK), tr(STR_TOGGLE), tr(STR_DIR_UP), tr(STR_DIR_DOWN));
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}
