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
  Availability,
  NormalPowerProfile,
  Enrollment,
  Count,
};

constexpr int MENU_ITEM_COUNT = static_cast<int>(MenuItem::Count);
constexpr uint8_t MAILBOX_INTERVAL_MINUTES[] = {1, 5, 15, 30, 60};
constexpr size_t MAILBOX_INTERVAL_COUNT = sizeof(MAILBOX_INTERVAL_MINUTES) / sizeof(MAILBOX_INTERVAL_MINUTES[0]);
constexpr StrId MENU_NAMES[MENU_ITEM_COUNT] = {
    StrId::STR_PAGER_AVAILABILITY,
    StrId::STR_PAGER_NORMAL_POWER_PROFILE,
    StrId::STR_PAGER_ENROLLED_DEVICE,
};
constexpr StrId NORMAL_POWER_PROFILE_NAMES[] = {
    StrId::STR_PAGER_PROFILE_RESPONSIVE,
    StrId::STR_PAGER_PROFILE_BALANCED,
    StrId::STR_PAGER_PROFILE_BATTERY_SAVER,
};

bool isMailboxInterval(const uint8_t minutes) {
  for (const uint8_t option : MAILBOX_INTERVAL_MINUTES) {
    if (minutes == option) {
      return true;
    }
  }
  return false;
}

void selectNextAvailability() {
  if (SETTINGS.pagerConnectionMode == CrossPointSettings::PAGER_NORMAL) {
    SETTINGS.pagerConnectionMode = CrossPointSettings::PAGER_MAILBOX;
    SETTINGS.pagerMailboxIntervalMinutes = MAILBOX_INTERVAL_MINUTES[0];
    return;
  }

  for (size_t index = 0; index < MAILBOX_INTERVAL_COUNT; index++) {
    if (MAILBOX_INTERVAL_MINUTES[index] != SETTINGS.pagerMailboxIntervalMinutes) {
      continue;
    }
    if (index + 1 < MAILBOX_INTERVAL_COUNT) {
      SETTINGS.pagerMailboxIntervalMinutes = MAILBOX_INTERVAL_MINUTES[index + 1];
    } else {
      SETTINGS.pagerConnectionMode = CrossPointSettings::PAGER_NORMAL;
    }
    return;
  }

  SETTINGS.pagerMailboxIntervalMinutes = MAILBOX_INTERVAL_MINUTES[0];
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
  if (SETTINGS.pagerNormalPowerProfile >= CrossPointSettings::PAGER_NORMAL_POWER_PROFILE_COUNT) {
    SETTINGS.pagerNormalPowerProfile = CrossPointSettings::PAGER_PROFILE_BALANCED;
  }
  if (SETTINGS.pagerClientEnrolled > 1) {
    SETTINGS.pagerClientEnrolled = 0;
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
    case MenuItem::Availability:
      selectNextAvailability();
      break;
    case MenuItem::NormalPowerProfile:
      SETTINGS.pagerNormalPowerProfile =
          (SETTINGS.pagerNormalPowerProfile + 1) % CrossPointSettings::PAGER_NORMAL_POWER_PROFILE_COUNT;
      break;
    case MenuItem::Enrollment:
      SETTINGS.pagerClientEnrolled = 0;
      SETTINGS.pagerClientToken[0] = '\0';
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
          case MenuItem::Availability: {
            if (SETTINGS.pagerConnectionMode == CrossPointSettings::PAGER_NORMAL) {
              return I18N.get(StrId::STR_PAGER_ALWAYS_AVAILABLE);
            }
            char value[16] = {};
            snprintf(value, sizeof(value), tr(STR_PAGER_EVERY_MINUTES_FORMAT),
                     static_cast<unsigned int>(SETTINGS.pagerMailboxIntervalMinutes));
            return value;
          }
          case MenuItem::NormalPowerProfile:
            return I18N.get(NORMAL_POWER_PROFILE_NAMES[SETTINGS.pagerNormalPowerProfile]);
          case MenuItem::Enrollment:
            return SETTINGS.pagerClientEnrolled != 0 ? I18N.get(StrId::STR_PAGER_RESET_ENROLLMENT)
                                                     : I18N.get(StrId::STR_PAGER_SETUP_OPEN);
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
