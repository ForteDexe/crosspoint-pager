#include "HalBlePager.h"

#include <Arduino.h>
#include <Logging.h>
#include <NimBLEDevice.h>

#include <cstring>

namespace {
constexpr char DEVICE_NAME[] = "CrossPoint Pager";
constexpr char SERVICE_UUID[] = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr char PAYLOAD_UUID[] = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr unsigned long RECEIVE_WINDOW_MS = 5UL * 1000UL;
constexpr unsigned long CONNECTION_TIMEOUT_MS = 5UL * 1000UL;
constexpr unsigned long PAYLOAD_ACK_GRACE_MS = 1000UL;

bool hasReached(const unsigned long now, const unsigned long target) {
  return static_cast<long>(now - target) >= 0;
}

class PagerServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& connectionInfo) override {
    blePager.setConnected(true, connectionInfo.getConnHandle());
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { blePager.setConnected(false); }
};

class PagerPayloadCallbacks final : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
    const NimBLEAttValue value = characteristic->getValue();
    blePager.storePayload(value.data(), value.length());
  }
};

PagerServerCallbacks serverCallbacks;
PagerPayloadCallbacks payloadCallbacks;
}  // namespace

HalBlePager blePager;

bool HalBlePager::begin(const ConnectionMode connectionMode, const uint8_t mailboxIntervalMinutes) {
  portENTER_CRITICAL(&payloadMutex);
  if (running) {
    portEXIT_CRITICAL(&payloadMutex);
    return true;
  }
  running = true;
  radioRunning = false;
  mailboxMode = connectionMode == ConnectionMode::Mailbox;
  connected = false;
  connectionHandle = 0;
  mailboxIntervalMs = static_cast<unsigned long>(mailboxIntervalMinutes) * 60UL * 1000UL;
  radioState = mailboxMode ? RadioState::MailboxWindow : RadioState::Normal;
  receiveWindowStartedAt = millis();
  nextMailboxWindowAt = 0;
  connectionStartedAt = 0;
  disconnectAfterAt = 0;
  disconnectRequested = false;
  advertisingRestartRequested = false;
  portEXIT_CRITICAL(&payloadMutex);

  if (startRadio()) {
    return true;
  }

  portENTER_CRITICAL(&payloadMutex);
  running = false;
  portEXIT_CRITICAL(&payloadMutex);
  return false;
}

bool HalBlePager::startRadio() {
  if (!NimBLEDevice::init(DEVICE_NAME)) {
    LOG_ERR("BLE", "NimBLE initialization failed");
    return false;
  }

  auto* server = NimBLEDevice::createServer();
  if (server == nullptr) {
    LOG_ERR("BLE", "Could not create GATT server");
    NimBLEDevice::deinit(true);
    return false;
  }
  server->setCallbacks(&serverCallbacks, false);

  auto* service = server->createService(SERVICE_UUID);
  if (service == nullptr) {
    LOG_ERR("BLE", "Could not create pager service");
    NimBLEDevice::deinit(true);
    return false;
  }

  auto* characteristic =
      service->createCharacteristic(PAYLOAD_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR, MAX_PAYLOAD_BYTES);
  if (characteristic == nullptr) {
    LOG_ERR("BLE", "Could not create pager characteristic");
    NimBLEDevice::deinit(true);
    return false;
  }
  characteristic->setCallbacks(&payloadCallbacks);

  auto* advertising = NimBLEDevice::getAdvertising();
  if (advertising == nullptr) {
    LOG_ERR("BLE", "Could not create advertiser");
    NimBLEDevice::deinit(true);
    return false;
  }

  // A 128-bit UUID plus this readable device name exceed the 31-byte primary
  // advertising packet. Put the name in scan response data, leaving the
  // custom service UUID in the primary packet for Web Bluetooth filtering.
  advertising->enableScanResponse(true);
  if (!advertising->setName(DEVICE_NAME)) {
    LOG_ERR("BLE", "Could not set pager device name");
    NimBLEDevice::deinit(true);
    return false;
  }
  if (!advertising->addServiceUUID(SERVICE_UUID)) {
    LOG_ERR("BLE", "Could not add pager service UUID");
    NimBLEDevice::deinit(true);
    return false;
  }
  if (!advertising->start() || !advertising->isAdvertising()) {
    LOG_ERR("BLE", "Could not start pager advertising");
    NimBLEDevice::deinit(true);
    return false;
  }

  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  radioRunning = true;
  receiveWindowStartedAt = now;
  advertisingRestartRequested = false;
  portEXIT_CRITICAL(&payloadMutex);
  LOG_INF("BLE", "Pager advertising started");
  return true;
}

void HalBlePager::end() {
  bool shouldDeinit = false;
  portENTER_CRITICAL(&payloadMutex);
  if (!running) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }
  shouldDeinit = radioRunning;
  running = false;
  radioRunning = false;
  mailboxMode = false;
  connected = false;
  connectionHandle = 0;
  mailboxIntervalMs = 0;
  radioState = RadioState::Normal;
  disconnectAfterAt = 0;
  disconnectRequested = false;
  advertisingRestartRequested = false;
  portEXIT_CRITICAL(&payloadMutex);

  if (shouldDeinit) {
    NimBLEDevice::deinit(true);
  }
  LOG_INF("BLE", "Pager stopped");
}

bool HalBlePager::isRunning() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = running;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

bool HalBlePager::isRadioRunning() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = radioRunning;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

void HalBlePager::update() {
  const unsigned long now = millis();
  bool startAdvertising = false;
  bool stopAdvertising = false;
  bool disconnectConnection = false;
  uint16_t disconnectHandle = 0;

  portENTER_CRITICAL(&payloadMutex);
  if (!running || !radioRunning) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  if (!mailboxMode) {
    if (!connected && advertisingRestartRequested) {
      advertisingRestartRequested = false;
      startAdvertising = true;
    }
    portEXIT_CRITICAL(&payloadMutex);
    if (startAdvertising && !NimBLEDevice::startAdvertising()) {
      portENTER_CRITICAL(&payloadMutex);
      advertisingRestartRequested = true;
      portEXIT_CRITICAL(&payloadMutex);
      LOG_ERR("BLE", "Could not restart pager advertising");
    }
    return;
  }

  switch (radioState) {
    case RadioState::MailboxWindow:
      if (connected) {
        const bool shouldDisconnectAfterPayload =
            disconnectAfterAt != 0 && hasReached(now, disconnectAfterAt);
        const bool connectionTimedOut = hasReached(now, connectionStartedAt + CONNECTION_TIMEOUT_MS);
        if ((shouldDisconnectAfterPayload || connectionTimedOut) && !disconnectRequested) {
          disconnectRequested = true;
          disconnectConnection = true;
          disconnectHandle = connectionHandle;
        }
      } else if (hasReached(now, receiveWindowStartedAt + RECEIVE_WINDOW_MS)) {
        radioState = RadioState::MailboxWaiting;
        nextMailboxWindowAt = now + mailboxIntervalMs;
        stopAdvertising = true;
      }
      break;
    case RadioState::MailboxWaiting:
      break;
    case RadioState::Normal:
      break;
  }
  portEXIT_CRITICAL(&payloadMutex);

  if (stopAdvertising) {
    NimBLEDevice::stopAdvertising();
    LOG_DBG("BLE", "Pager mailbox window closed");
  }

  if (disconnectConnection) {
    auto* server = NimBLEDevice::getServer();
    if (server == nullptr || !server->disconnect(disconnectHandle)) {
      portENTER_CRITICAL(&payloadMutex);
      disconnectRequested = false;
      portEXIT_CRITICAL(&payloadMutex);
      LOG_ERR("BLE", "Could not close pager connection");
    }
  }
}

bool HalBlePager::isConnected() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = connected;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

bool HalBlePager::isMailboxWaiting() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = running && mailboxMode && radioState == RadioState::MailboxWaiting;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

unsigned long HalBlePager::getMailboxSleepDurationMs() const {
  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  if (!running || !mailboxMode || radioState != RadioState::MailboxWaiting || hasReached(now, nextMailboxWindowAt)) {
    portEXIT_CRITICAL(&payloadMutex);
    return 0;
  }
  const unsigned long result = nextMailboxWindowAt - now;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

bool HalBlePager::suspendMailboxRadio() {
  portENTER_CRITICAL(&payloadMutex);
  const bool shouldSuspend = running && radioRunning && mailboxMode && !connected &&
                             radioState == RadioState::MailboxWaiting;
  if (shouldSuspend) {
    radioRunning = false;
    advertisingRestartRequested = false;
  }
  portEXIT_CRITICAL(&payloadMutex);

  if (!shouldSuspend) {
    return false;
  }

  NimBLEDevice::deinit(true);
  LOG_INF("BLE", "Pager radio suspended for mailbox interval");
  return true;
}

bool HalBlePager::resumeMailboxWindow() {
  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  const bool shouldResume = running && !radioRunning && mailboxMode && radioState == RadioState::MailboxWaiting &&
                            hasReached(now, nextMailboxWindowAt);
  if (shouldResume) {
    radioState = RadioState::MailboxWindow;
    receiveWindowStartedAt = now;
  }
  portEXIT_CRITICAL(&payloadMutex);

  if (!shouldResume) {
    return false;
  }
  if (startRadio()) {
    LOG_INF("BLE", "Pager mailbox window opened");
    return true;
  }

  portENTER_CRITICAL(&payloadMutex);
  radioState = RadioState::MailboxWaiting;
  nextMailboxWindowAt = now + mailboxIntervalMs;
  portEXIT_CRITICAL(&payloadMutex);
  return false;
}

size_t HalBlePager::takePayload(char* destination, size_t destinationSize) {
  if (destination == nullptr || destinationSize < MAX_PAYLOAD_BYTES + 1) {
    return 0;
  }

  portENTER_CRITICAL(&payloadMutex);
  if (!payloadPending) {
    portEXIT_CRITICAL(&payloadMutex);
    return 0;
  }

  std::memcpy(destination, payload, payloadLength + 1);
  const size_t result = payloadLength;
  payloadPending = false;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

void HalBlePager::setConnected(const bool isConnected, const uint16_t newConnectionHandle) {
  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  if (!running || !radioRunning) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  connected = isConnected;
  if (isConnected) {
    connectionHandle = newConnectionHandle;
    connectionStartedAt = now;
    disconnectAfterAt = 0;
    disconnectRequested = false;
    if (mailboxMode) {
      radioState = RadioState::MailboxWindow;
      receiveWindowStartedAt = now;
    }
  } else {
    connectionHandle = 0;
    disconnectAfterAt = 0;
    disconnectRequested = false;
    if (mailboxMode) {
      radioState = RadioState::MailboxWaiting;
      nextMailboxWindowAt = now + mailboxIntervalMs;
    } else {
      advertisingRestartRequested = true;
    }
  }
  portEXIT_CRITICAL(&payloadMutex);
}

void HalBlePager::storePayload(const uint8_t* data, const size_t length) {
  if (data == nullptr || length == 0 || length > MAX_PAYLOAD_BYTES) {
    return;
  }

  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  if (mailboxMode && connected) {
    disconnectAfterAt = now + PAYLOAD_ACK_GRACE_MS;
    disconnectRequested = false;
  }
  if (payloadLength == length && std::memcmp(payload, data, length) == 0) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  std::memcpy(payload, data, length);
  payload[length] = '\0';
  payloadLength = length;
  payloadPending = true;
  portEXIT_CRITICAL(&payloadMutex);
}
