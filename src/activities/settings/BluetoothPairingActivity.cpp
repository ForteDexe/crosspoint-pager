#include "BluetoothPairingActivity.h"

#include <GfxRenderer.h>
#include <HalBleDashboard.h>
#include <I18n.h>
#include <WiFi.h>

#include "components/UITheme.h"
#include "fontIds.h"

void BluetoothPairingActivity::onEnter() {
  Activity::onEnter();
  // This test service has no coexistence budget. Pairing is explicit, so it
  // is safe to shut down any radio session left by a previous activity.
  if (WiFi.getMode() != WIFI_MODE_NULL) {
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
  }
  started = bleDashboard.begin();
  connected = bleDashboard.isConnected();
  requestUpdate();
}

void BluetoothPairingActivity::onExit() {
  bleDashboard.end();
  Activity::onExit();
}

void BluetoothPairingActivity::loop() {
  if (mappedInput.wasPressed(MappedInputManager::Button::Back)) {
    finish();
    return;
  }

  const bool nextConnected = bleDashboard.isConnected();
  if (nextConnected != connected) {
    connected = nextConnected;
    requestUpdate();
  }
}

void BluetoothPairingActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();
  const auto& metrics = UITheme::getInstance().getMetrics();

  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, pageWidth, metrics.headerHeight}, tr(STR_BLUETOOTH_PAIRING));
  const int contentTop = metrics.topPadding + metrics.headerHeight + metrics.verticalSpacing;
  renderer.drawCenteredText(UI_12_FONT_ID, contentTop + 35,
                            started ? (connected ? tr(STR_BLUETOOTH_CONNECTED) : tr(STR_BLUETOOTH_ADVERTISING))
                                    : tr(STR_CONNECTION_FAILED),
                            true, EpdFontFamily::BOLD);
  GUI.drawHelpText(renderer, Rect{metrics.contentSidePadding, contentTop + 75,
                                  pageWidth - metrics.contentSidePadding * 2, pageHeight - contentTop - 150},
                   tr(STR_BLUETOOTH_PAIRING_HINT));
  const auto labels = mappedInput.mapLabels(tr(STR_BACK), "", "", "");
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}
