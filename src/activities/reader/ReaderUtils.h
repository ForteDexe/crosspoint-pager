#pragma once

#include <CrossPointState.h>
#include <CrossPointSettings.h>
#include <GfxRenderer.h>
#include <HalGPIO.h>
#include <HalTiltSensor.h>
#include <Logging.h>

#include "MappedInputManager.h"

namespace ReaderUtils {

constexpr unsigned long GO_HOME_MS = 1000;
constexpr unsigned long SKIP_HOLD_MS = 700;
constexpr unsigned long BOOKMARK_HOLD_MS = 400;
constexpr unsigned long BOOKMARK_MESSAGE_DURATION_MS = 2500;

inline void applyOrientation(GfxRenderer& renderer, const uint8_t orientation) {
  switch (orientation) {
    case CrossPointSettings::ORIENTATION::PORTRAIT:
      renderer.setOrientation(GfxRenderer::Orientation::Portrait);
      break;
    case CrossPointSettings::ORIENTATION::LANDSCAPE_CW:
      renderer.setOrientation(GfxRenderer::Orientation::LandscapeClockwise);
      break;
    case CrossPointSettings::ORIENTATION::INVERTED:
      renderer.setOrientation(GfxRenderer::Orientation::PortraitInverted);
      break;
    case CrossPointSettings::ORIENTATION::LANDSCAPE_CCW:
      renderer.setOrientation(GfxRenderer::Orientation::LandscapeCounterClockwise);
      break;
    default:
      break;
  }
}

struct PageTurnResult {
  bool prev;
  bool next;
  bool fromTilt;
};

inline PageTurnResult detectPageTurn(const MappedInputManager& input) {
  const bool usePress = SETTINGS.longPressButtonBehavior == SETTINGS.OFF;
  const bool tiltNext = SETTINGS.tiltPageTurn && halTiltSensor.wasTiltedForward();
  const bool tiltPrev = SETTINGS.tiltPageTurn && halTiltSensor.wasTiltedBack();
  const bool swapFront = input.isNavDirectionSwapped();
  const auto prevButton = swapFront ? MappedInputManager::Button::Right : MappedInputManager::Button::Left;
  const auto nextButton = swapFront ? MappedInputManager::Button::Left : MappedInputManager::Button::Right;
  const bool prev =
      tiltPrev ||
      (usePress ? (input.wasPressed(MappedInputManager::Button::PageBack) || input.wasPressed(prevButton))
                : (input.wasReleased(MappedInputManager::Button::PageBack) || input.wasReleased(prevButton)));
  const bool powerTurn = SETTINGS.shortPwrBtn == CrossPointSettings::SHORT_PWRBTN::PAGE_TURN &&
                         input.wasReleased(MappedInputManager::Button::Power);
  const bool next = tiltNext || (usePress ? (input.wasPressed(MappedInputManager::Button::PageForward) || powerTurn ||
                                             input.wasPressed(nextButton))
                                          : (input.wasReleased(MappedInputManager::Button::PageForward) || powerTurn ||
                                             input.wasReleased(nextButton)));
  return {prev, next, tiltPrev || tiltNext};
}

inline bool isRefreshActionDue(const int pagesUntilRefreshAction) {
  return pagesUntilRefreshAction != CrossPointSettings::REFRESH_COUNTDOWN_DISABLED && pagesUntilRefreshAction <= 1;
}

inline bool isFullRefreshForced(const int pagesUntilRefreshAction) {
  return pagesUntilRefreshAction == CrossPointSettings::REFRESH_COUNTDOWN_FORCE_FULL;
}

inline bool isFastRefreshForced(const int pagesUntilRefreshAction) {
  return pagesUntilRefreshAction == CrossPointSettings::REFRESH_COUNTDOWN_FORCE_FAST;
}

inline void forceFullRefresh(int& pagesUntilRefreshAction) {
  pagesUntilRefreshAction = CrossPointSettings::REFRESH_COUNTDOWN_FORCE_FULL;
}

inline bool isX3NoFlashEnabled() {
  return gpio.deviceIsX3() && SETTINGS.refreshAction == CrossPointSettings::REFRESH_ACTION_BW_REINFORCEMENT;
}

inline bool shouldForceGrayscaleBlackWhite() {
  return isX3NoFlashEnabled() && SETTINGS.x3ForceGrayscaleBlackWhite != 0;
}

inline void countOrdinaryRefresh(int& pagesUntilRefreshAction) {
  if (pagesUntilRefreshAction != CrossPointSettings::REFRESH_COUNTDOWN_DISABLED) {
    pagesUntilRefreshAction--;
  }
}

inline void rememberRefreshCycle(const int pagesUntilRefreshAction) {
  APP_STATE.readerPagesUntilFullRefresh =
      pagesUntilRefreshAction == CrossPointSettings::REFRESH_COUNTDOWN_DISABLED ||
              pagesUntilRefreshAction == CrossPointSettings::REFRESH_COUNTDOWN_FORCE_FAST
          ? UINT8_MAX
          : static_cast<uint8_t>(pagesUntilRefreshAction);
}

inline void displayNoFlashQuickRefresh(const GfxRenderer& renderer) {
  // The X3 OEM differential waveform turns the page and reinforces unchanged
  // black and white pixels in the same update.
  renderer.displayGrayscaleBase(HalDisplay::FAST_REFRESH);
  // The first pass leaves DTM1 and DTM2 holding the displayed BW frame.
  // Repeating the same bank in that equal-plane state fires only its gentle
  // unchanged-white and unchanged-black settling cells.
  static constexpr uint8_t X3_NO_FLASH_EXTRA_PASSES = 2;
  for (uint8_t pass = 0; pass < X3_NO_FLASH_EXTRA_PASSES; pass++) {
    renderer.displayGrayscaleBase(HalDisplay::FAST_REFRESH);
  }
}

inline bool scheduleNoFlashTransitionAfterGrayscale(int& pagesUntilRefreshAction) {
  if (!isX3NoFlashEnabled()) {
    return false;
  }

  switch (static_cast<CrossPointSettings::X3_GRAYSCALE_REFRESH_MODE>(SETTINGS.x3GrayscaleRefreshMode)) {
    case CrossPointSettings::X3_GRAYSCALE_REFRESH_FAST:
      pagesUntilRefreshAction = CrossPointSettings::REFRESH_COUNTDOWN_FORCE_FAST;
      break;
    case CrossPointSettings::X3_GRAYSCALE_REFRESH_QUICK:
    case CrossPointSettings::X3_GRAYSCALE_REFRESH_MODE_COUNT:
      pagesUntilRefreshAction = 1;
      break;
  }
  rememberRefreshCycle(pagesUntilRefreshAction);
  return true;
}

inline void displayWithRefreshCycle(const GfxRenderer& renderer, int& pagesUntilRefreshAction,
                                    const bool isBlackAndWhitePage = true) {
  if (isFastRefreshForced(pagesUntilRefreshAction)) {
    renderer.displayBuffer(HalDisplay::FAST_REFRESH);
    pagesUntilRefreshAction = SETTINGS.getRefreshFrequency();
  } else if (isRefreshActionDue(pagesUntilRefreshAction)) {
    const bool useNoFlashMaintenance =
        gpio.deviceIsX3() && isBlackAndWhitePage && !isFullRefreshForced(pagesUntilRefreshAction) &&
        SETTINGS.refreshAction == CrossPointSettings::REFRESH_ACTION_BW_REINFORCEMENT;
    if (useNoFlashMaintenance) {
      displayNoFlashQuickRefresh(renderer);
    } else {
      renderer.displayBuffer(HalDisplay::HALF_REFRESH);
    }
    pagesUntilRefreshAction = SETTINGS.getRefreshFrequency();
  } else {
    renderer.displayBuffer();
    countOrdinaryRefresh(pagesUntilRefreshAction);
  }
  rememberRefreshCycle(pagesUntilRefreshAction);
}

inline void restoreRefreshCycleAfterQuickResume(int& pagesUntilFullRefresh) {
  if (!APP_STATE.restoreReaderRefreshCycle) {
    return;
  }

  const int configuredFrequency = SETTINGS.getRefreshFrequency();
  if (configuredFrequency == CrossPointSettings::REFRESH_COUNTDOWN_DISABLED) {
    pagesUntilFullRefresh = configuredFrequency;
  } else if (APP_STATE.readerPagesUntilFullRefresh == UINT8_MAX) {
    pagesUntilFullRefresh = configuredFrequency;
  } else {
    pagesUntilFullRefresh = APP_STATE.readerPagesUntilFullRefresh;
  }
  APP_STATE.restoreReaderRefreshCycle = false;
  APP_STATE.saveToFile();
}

// Grayscale anti-aliasing pass. Renders content twice (LSB + MSB) to build
// the grayscale buffer. Only the content callback is re-rendered — status bars
// and other overlays should be drawn before calling this.
// Kept as a template to avoid std::function overhead; instantiated once per reader type.
template <typename RenderFn>
void renderAntiAliased(GfxRenderer& renderer, RenderFn&& renderFn) {
  if (!renderer.storeBwBuffer()) {
    LOG_ERR("READER", "Failed to store BW buffer for anti-aliasing");
    return;
  }

  renderer.clearScreen(0x00);
  renderer.setRenderMode(GfxRenderer::GRAYSCALE_LSB);
  renderFn();
  renderer.copyGrayscaleLsbBuffers();

  renderer.clearScreen(0x00);
  renderer.setRenderMode(GfxRenderer::GRAYSCALE_MSB);
  renderFn();
  renderer.copyGrayscaleMsbBuffers();

  renderer.displayGrayBuffer();
  renderer.setRenderMode(GfxRenderer::BW);

  renderer.restoreBwBuffer();
}

}  // namespace ReaderUtils
