#include "HalBlePager.h"

#include <Arduino.h>
#include <HalGPIO.h>
#include <Logging.h>
#include <NimBLEDevice.h>
#include <esp_mac.h>

#include <cstdio>
#include <cstring>

namespace {
constexpr char SERVICE_UUID[] = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr char PAYLOAD_UUID[] = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr char STATUS_UUID[] = "ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr char PAYLOAD_PREFIX[] = "XPAGER1\nDATA\n";
constexpr size_t PAYLOAD_PREFIX_BYTES = sizeof(PAYLOAD_PREFIX) - 1;
constexpr unsigned long RECEIVE_WINDOW_MS = 2UL * 1000UL;
constexpr unsigned long CONNECTION_TIMEOUT_MS = 3UL * 1000UL;
constexpr unsigned long PAYLOAD_ACK_GRACE_MS = 250UL;
constexpr unsigned long ENROLLMENT_ACK_GRACE_MS = 1UL * 1000UL;
// Units are 0.625 ms. The NimBLE default is a 30-60 ms fast interval;
// 100 ms still gives about 20 chances per Mailbox window with fewer TX events.
constexpr uint16_t MAILBOX_ADVERTISING_INTERVAL = 160;
constexpr int8_t MAILBOX_TX_POWER_DBM = -6;

const char* pagerDeviceId() {
  static char deviceId[13] = {};
  if (deviceId[0] != '\0') {
    return deviceId;
  }

  uint8_t hardwareMac[6] = {};
  if (esp_efuse_mac_get_default(hardwareMac) != ESP_OK) {
    std::memcpy(deviceId, "unknown", sizeof("unknown"));
    return deviceId;
  }
  snprintf(deviceId, sizeof(deviceId), "%02X%02X%02X%02X%02X%02X", hardwareMac[0], hardwareMac[1],
           hardwareMac[2], hardwareMac[3], hardwareMac[4], hardwareMac[5]);
  return deviceId;
}

const char* pagerDeviceName() {
  // Built once and reused by NimBLE. The 24-byte static buffer avoids a
  // temporary String allocation during every radio start.
  static char deviceName[24] = {};
  if (deviceName[0] == '\0') {
    const char* deviceId = pagerDeviceId();
    const size_t deviceIdLength = std::strlen(deviceId);
    const char* shortId = deviceIdLength >= 6 ? deviceId + deviceIdLength - 6 : deviceId;
    snprintf(deviceName, sizeof(deviceName), "CrossPoint %s %s", gpio.deviceIsX3() ? "X3" : "X4", shortId);
  }
  return deviceName;
}

struct ConnectionParameters {
  uint16_t minInterval;
  uint16_t maxInterval;
  uint16_t latency;
  uint16_t supervisionTimeout;
  const char* name;
};

ConnectionParameters parametersFor(const HalBlePager::NormalPowerProfile profile) {
  switch (profile) {
    case HalBlePager::NormalPowerProfile::Responsive:
      return {24, 40, 0, 400, "responsive"};       // 30-50 ms, 4 s timeout
    case HalBlePager::NormalPowerProfile::BatterySaver:
      return {160, 240, 4, 1000, "battery_saver"};  // 200-300 ms, up to 1.5 s idle gap
    case HalBlePager::NormalPowerProfile::Balanced:
    default:
      return {80, 120, 2, 600, "balanced"};         // 100-150 ms, up to 450 ms idle gap
  }
}

bool hasReached(const unsigned long now, const unsigned long target) {
  return static_cast<long>(now - target) >= 0;
}

bool isHexToken(const uint8_t* token) {
  if (token == nullptr) {
    return false;
  }
  for (size_t index = 0; index < HalBlePager::CLIENT_TOKEN_BYTES; index++) {
    const uint8_t character = token[index];
    if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f') ||
          (character >= 'A' && character <= 'F'))) {
      return false;
    }
  }
  return true;
}

bool tokenMatches(const char* expected, const uint8_t* provided) {
  if (expected == nullptr || provided == nullptr) {
    return false;
  }
  for (size_t index = 0; index < HalBlePager::CLIENT_TOKEN_BYTES; index++) {
    const char expectedCharacter = expected[index];
    const char providedCharacter = static_cast<char>(provided[index]);
    if (expectedCharacter == '\0' || expectedCharacter != providedCharacter) {
      return false;
    }
  }
  return expected[HalBlePager::CLIENT_TOKEN_BYTES] == '\0';
}

void copyToken(char* destination, const char* source) {
  if (destination == nullptr) {
    return;
  }
  if (source == nullptr) {
    destination[0] = '\0';
    return;
  }
  size_t index = 0;
  for (; index < HalBlePager::CLIENT_TOKEN_BYTES && source[index] != '\0'; index++) {
    destination[index] = source[index];
  }
  destination[index] = '\0';
}

const char* writeStatusName(const HalBlePager::WriteStatus status) {
  switch (status) {
    case HalBlePager::WriteStatus::Accepted:
      return "accepted";
    case HalBlePager::WriteStatus::Enrolled:
      return "enrolled";
    case HalBlePager::WriteStatus::AuthFailed:
      return "auth_failed";
    case HalBlePager::WriteStatus::Invalid:
      return "invalid";
    case HalBlePager::WriteStatus::None:
    default:
      return "none";
  }
}

class PagerServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& connectionInfo) override {
    blePager.setConnected(true, connectionInfo.getConnHandle(), connectionInfo.getConnInterval(),
                          connectionInfo.getConnLatency(), connectionInfo.getConnTimeout());
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { blePager.setConnected(false); }

  void onConnParamsUpdate(NimBLEConnInfo& connectionInfo) override {
    blePager.setConnectionParameters(connectionInfo.getConnInterval(), connectionInfo.getConnLatency(),
                                     connectionInfo.getConnTimeout());
  }
};

class PagerPayloadCallbacks final : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
    const NimBLEAttValue value = characteristic->getValue();
    blePager.storePayload(value.data(), value.length());
  }
};

class PagerStatusCallbacks final : public NimBLECharacteristicCallbacks {
  void onRead(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
    char status[HalBlePager::MAX_STATUS_BYTES + 1] = {};
    const size_t length = blePager.copyStatus(status, sizeof(status));
    characteristic->setValue(reinterpret_cast<const uint8_t*>(status), length);
  }
};

PagerServerCallbacks serverCallbacks;
PagerPayloadCallbacks payloadCallbacks;
PagerStatusCallbacks statusCallbacks;
}  // namespace

HalBlePager blePager;

bool HalBlePager::begin(const ConnectionMode connectionMode, const ConnectionMode requestedConfiguredConnectionMode,
                        const uint8_t mailboxIntervalMinutes,
                        const NormalPowerProfile requestedNormalPowerProfile, const bool requestedClientEnrolled,
                        const char* requestedClientToken) {
  portENTER_CRITICAL(&payloadMutex);
  if (running) {
    portEXIT_CRITICAL(&payloadMutex);
    return true;
  }
  running = true;
  radioRunning = false;
  mailboxMode = connectionMode == ConnectionMode::Mailbox;
  configuredConnectionMode = requestedConfiguredConnectionMode;
  normalPowerProfile = requestedNormalPowerProfile;
  clientEnrolled = requestedClientEnrolled;
  copyToken(clientToken, requestedClientToken);
  enrollmentPending = false;
  pendingEnrollmentToken[0] = '\0';
  lastWriteStatus = WriteStatus::None;
  connected = false;
  connectionHandle = 0;
  connectionIntervalUnits = 0;
  connectionLatency = 0;
  supervisionTimeoutUnits = 0;
  mailboxIntervalMs = static_cast<unsigned long>(mailboxIntervalMinutes) * 60UL * 1000UL;
  radioState = mailboxMode ? RadioState::MailboxWindow : RadioState::Normal;
  receiveWindowStartedAt = millis();
  nextMailboxWindowAt = 0;
  connectionStartedAt = 0;
  disconnectAfterAt = 0;
  disconnectRequested = false;
  advertisingRestartRequested = false;
  connectionParamsUpdateRequested = false;
  portEXIT_CRITICAL(&payloadMutex);

  if (startRadio()) {
    return true;
  }

  portENTER_CRITICAL(&payloadMutex);
  running = false;
  clientEnrolled = false;
  clientToken[0] = '\0';
  portEXIT_CRITICAL(&payloadMutex);
  return false;
}

bool HalBlePager::startRadio() {
  portENTER_CRITICAL(&payloadMutex);
  const bool useMailboxPolicy = mailboxMode;
  const NormalPowerProfile requestedNormalPowerProfile = normalPowerProfile;
  portEXIT_CRITICAL(&payloadMutex);

  if (!NimBLEDevice::init(pagerDeviceName())) {
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

  auto* statusCharacteristic =
      service->createCharacteristic(STATUS_UUID, NIMBLE_PROPERTY::READ, MAX_STATUS_BYTES);
  if (statusCharacteristic == nullptr) {
    LOG_ERR("BLE", "Could not create pager status characteristic");
    NimBLEDevice::deinit(true);
    return false;
  }
  statusCharacteristic->setCallbacks(&statusCallbacks);

  auto* advertising = NimBLEDevice::getAdvertising();
  if (advertising == nullptr) {
    LOG_ERR("BLE", "Could not create advertiser");
    NimBLEDevice::deinit(true);
    return false;
  }

  if (useMailboxPolicy) {
    advertising->enableScanResponse(false);
    advertising->setAdvertisingInterval(MAILBOX_ADVERTISING_INTERVAL);
    if (!NimBLEDevice::setPower(MAILBOX_TX_POWER_DBM, NimBLETxPowerType::Advertise) ||
        !NimBLEDevice::setPower(MAILBOX_TX_POWER_DBM, NimBLETxPowerType::Connection)) {
      LOG_ERR("BLE", "Could not lower Pager mailbox TX power");
    }
  } else {
    // A 128-bit UUID plus this readable device name exceed the 31-byte primary
    // advertising packet. Normal debug mode keeps the name in scan response.
    advertising->enableScanResponse(true);
    if (!advertising->setName(pagerDeviceName())) {
      LOG_ERR("BLE", "Could not set pager device name");
      NimBLEDevice::deinit(true);
      return false;
    }
    const auto parameters = parametersFor(requestedNormalPowerProfile);
    if (!advertising->setPreferredParams(parameters.minInterval, parameters.maxInterval)) {
      LOG_ERR("BLE", "Could not advertise preferred Pager connection parameters");
    }
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
  configuredConnectionMode = ConnectionMode::Normal;
  clientEnrolled = false;
  clientToken[0] = '\0';
  enrollmentPending = false;
  pendingEnrollmentToken[0] = '\0';
  lastWriteStatus = WriteStatus::None;
  connected = false;
  connectionHandle = 0;
  connectionIntervalUnits = 0;
  connectionLatency = 0;
  supervisionTimeoutUnits = 0;
  mailboxIntervalMs = 0;
  radioState = RadioState::Normal;
  disconnectAfterAt = 0;
  disconnectRequested = false;
  advertisingRestartRequested = false;
  connectionParamsUpdateRequested = false;
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
  bool updateConnectionParams = false;
  bool stopAdvertising = false;
  bool disconnectConnection = false;
  NormalPowerProfile requestedNormalPowerProfile = NormalPowerProfile::Balanced;
  uint16_t paramsConnectionHandle = 0;
  uint16_t disconnectHandle = 0;

  portENTER_CRITICAL(&payloadMutex);
  if (!running || !radioRunning) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  if (mailboxMode) {
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
      case RadioState::Normal:
      case RadioState::EnrollmentHandoff:
        break;
    }
  } else {
    switch (radioState) {
      case RadioState::Normal:
        if (connected && connectionParamsUpdateRequested) {
          connectionParamsUpdateRequested = false;
          updateConnectionParams = true;
          paramsConnectionHandle = connectionHandle;
          requestedNormalPowerProfile = normalPowerProfile;
        }
        if (!connected && advertisingRestartRequested) {
          advertisingRestartRequested = false;
          startAdvertising = true;
        }
        break;
      case RadioState::EnrollmentHandoff:
        if (connected && disconnectAfterAt != 0 && hasReached(now, disconnectAfterAt) && !disconnectRequested) {
          disconnectRequested = true;
          disconnectConnection = true;
          disconnectHandle = connectionHandle;
        }
        break;
      case RadioState::MailboxWindow:
      case RadioState::MailboxWaiting:
        break;
    }
  }
  portEXIT_CRITICAL(&payloadMutex);

  if (startAdvertising && !NimBLEDevice::startAdvertising()) {
    portENTER_CRITICAL(&payloadMutex);
    advertisingRestartRequested = true;
    portEXIT_CRITICAL(&payloadMutex);
    LOG_ERR("BLE", "Could not restart pager advertising");
  }

  if (updateConnectionParams) {
    auto* server = NimBLEDevice::getServer();
    if (server != nullptr) {
      const auto parameters = parametersFor(requestedNormalPowerProfile);
      server->updateConnParams(paramsConnectionHandle, parameters.minInterval, parameters.maxInterval,
                               parameters.latency, parameters.supervisionTimeout);
    }
  }

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

size_t HalBlePager::takeEnrollmentToken(char* destination, size_t destinationSize) {
  if (destination == nullptr || destinationSize < CLIENT_TOKEN_BYTES + 1) {
    return 0;
  }

  portENTER_CRITICAL(&payloadMutex);
  if (!enrollmentPending) {
    portEXIT_CRITICAL(&payloadMutex);
    return 0;
  }

  std::memcpy(destination, pendingEnrollmentToken, CLIENT_TOKEN_BYTES + 1);
  enrollmentPending = false;
  portEXIT_CRITICAL(&payloadMutex);
  return CLIENT_TOKEN_BYTES;
}

bool HalBlePager::takeMailboxHandoffRequest() {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = running && radioRunning && !mailboxMode && clientEnrolled && !connected &&
                      radioState == RadioState::EnrollmentHandoff;
  if (result) {
    radioState = RadioState::Normal;
  }
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

size_t HalBlePager::copyStatus(char* destination, const size_t destinationSize) const {
  if (destination == nullptr || destinationSize == 0) {
    return 0;
  }

  const unsigned long now = millis();
  char statusToken[CLIENT_TOKEN_BYTES + 1] = {};
  portENTER_CRITICAL(&payloadMutex);
  const bool statusMailboxMode = mailboxMode;
  const bool statusConfiguredMailboxMode = configuredConnectionMode == ConnectionMode::Mailbox;
  const bool statusConnected = connected;
  const bool statusClientEnrolled = clientEnrolled;
  const unsigned long statusMailboxIntervalMs = mailboxIntervalMs;
  const NormalPowerProfile statusPowerProfile = normalPowerProfile;
  const uint16_t statusConnectionIntervalUnits = connectionIntervalUnits;
  const uint16_t statusConnectionLatency = connectionLatency;
  const uint16_t statusSupervisionTimeoutUnits = supervisionTimeoutUnits;
  const WriteStatus statusLastWrite = lastWriteStatus;
  if (!statusClientEnrolled) {
    std::memcpy(statusToken, clientToken, CLIENT_TOKEN_BYTES + 1);
  }
  const unsigned long nextWindowMs =
      statusMailboxMode && radioState == RadioState::MailboxWaiting && !hasReached(now, nextMailboxWindowAt)
          ? nextMailboxWindowAt - now
          : 0;
  portEXIT_CRITICAL(&payloadMutex);

  int written = 0;
  if (statusMailboxMode) {
    written = snprintf(
        destination, destinationSize,
        "v=4;model=%s;device_id=%s;availability=mailbox;configured_availability=%s;interval_s=%lu;window_ms=%lu;"
        "connected=%u;enrolled=%u;"
        "enroll_token=%s;last_write=%s;conn_interval_units=%u;conn_latency=%u;conn_timeout_units=%u;"
        "next_window_ms=%lu",
        gpio.deviceIsX3() ? "X3" : "X4", pagerDeviceId(), statusConfiguredMailboxMode ? "mailbox" : "always",
        statusMailboxIntervalMs / 1000UL, RECEIVE_WINDOW_MS, statusConnected ? 1U : 0U,
        statusClientEnrolled ? 1U : 0U, statusToken,
        writeStatusName(statusLastWrite), statusConnectionIntervalUnits, statusConnectionLatency,
        statusSupervisionTimeoutUnits, nextWindowMs);
  } else {
    written = snprintf(
        destination, destinationSize,
        "v=4;model=%s;device_id=%s;availability=always;configured_availability=%s;interval_s=%lu;window_ms=%lu;profile=%s;"
        "connected=%u;enrolled=%u;"
        "enroll_token=%s;last_write=%s;conn_interval_units=%u;conn_latency=%u;conn_timeout_units=%u;"
        "next_window_ms=%lu",
        gpio.deviceIsX3() ? "X3" : "X4", pagerDeviceId(), statusConfiguredMailboxMode ? "mailbox" : "always",
        statusMailboxIntervalMs / 1000UL, RECEIVE_WINDOW_MS, parametersFor(statusPowerProfile).name,
        statusConnected ? 1U : 0U, statusClientEnrolled ? 1U : 0U, statusToken, writeStatusName(statusLastWrite),
        statusConnectionIntervalUnits, statusConnectionLatency, statusSupervisionTimeoutUnits, nextWindowMs);
  }
  if (written <= 0) {
    destination[0] = '\0';
    return 0;
  }
  return static_cast<size_t>(written) < destinationSize ? static_cast<size_t>(written) : destinationSize - 1;
}

size_t HalBlePager::copySetupLabel(char* destination, const size_t destinationSize) {
  if (destination == nullptr || destinationSize == 0) {
    return 0;
  }

  const char* deviceId = pagerDeviceId();
  const size_t deviceIdLength = std::strlen(deviceId);
  const char* shortId = deviceIdLength >= 6 ? deviceId + deviceIdLength - 6 : deviceId;
  const int written = snprintf(destination, destinationSize, "%s %s", gpio.deviceIsX3() ? "X3" : "X4", shortId);
  if (written <= 0 || static_cast<size_t>(written) >= destinationSize) {
    destination[0] = '\0';
    return 0;
  }
  return static_cast<size_t>(written);
}

void HalBlePager::setConnected(const bool isConnected, const uint16_t newConnectionHandle,
                               const uint16_t intervalUnits, const uint16_t latency,
                               const uint16_t newSupervisionTimeoutUnits) {
  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  if (!running || !radioRunning) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  connected = isConnected;
  if (isConnected) {
    connectionHandle = newConnectionHandle;
    connectionIntervalUnits = intervalUnits;
    connectionLatency = latency;
    supervisionTimeoutUnits = newSupervisionTimeoutUnits;
    connectionStartedAt = now;
    disconnectAfterAt = 0;
    disconnectRequested = false;
    if (mailboxMode) {
      radioState = RadioState::MailboxWindow;
      receiveWindowStartedAt = now;
    } else {
      connectionParamsUpdateRequested = true;
    }
  } else {
    connectionHandle = 0;
    connectionIntervalUnits = 0;
    connectionLatency = 0;
    supervisionTimeoutUnits = 0;
    disconnectAfterAt = 0;
    disconnectRequested = false;
    connectionParamsUpdateRequested = false;
    if (mailboxMode) {
      radioState = RadioState::MailboxWaiting;
      nextMailboxWindowAt = now + mailboxIntervalMs;
    } else if (radioState != RadioState::EnrollmentHandoff) {
      advertisingRestartRequested = true;
    }
  }
  portEXIT_CRITICAL(&payloadMutex);
}

void HalBlePager::setConnectionParameters(const uint16_t intervalUnits, const uint16_t latency,
                                          const uint16_t newSupervisionTimeoutUnits) {
  portENTER_CRITICAL(&payloadMutex);
  if (connected) {
    connectionIntervalUnits = intervalUnits;
    connectionLatency = latency;
    supervisionTimeoutUnits = newSupervisionTimeoutUnits;
  }
  portEXIT_CRITICAL(&payloadMutex);
}

void HalBlePager::storePayload(const uint8_t* data, const size_t length) {
  if (data == nullptr || length == 0 || length > MAX_PAYLOAD_BYTES) {
    return;
  }

  const size_t tokenOffset = PAYLOAD_PREFIX_BYTES;
  const size_t payloadOffset = PAYLOAD_PREFIX_BYTES + CLIENT_TOKEN_BYTES + 1;
  const bool hasProtocolFrame =
      length > payloadOffset && std::memcmp(data, PAYLOAD_PREFIX, PAYLOAD_PREFIX_BYTES) == 0 &&
      data[PAYLOAD_PREFIX_BYTES + CLIENT_TOKEN_BYTES] == '\n' && isHexToken(data + tokenOffset);
  const uint8_t* displayPayload = hasProtocolFrame ? data + payloadOffset : nullptr;
  const size_t displayPayloadLength = hasProtocolFrame ? length - payloadOffset : 0;

  const unsigned long now = millis();
  portENTER_CRITICAL(&payloadMutex);
  if (mailboxMode && connected) {
    disconnectAfterAt = now + PAYLOAD_ACK_GRACE_MS;
    disconnectRequested = false;
  }

  if (!hasProtocolFrame) {
    lastWriteStatus = WriteStatus::Invalid;
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }
  if (!tokenMatches(clientToken, data + tokenOffset)) {
    lastWriteStatus = WriteStatus::AuthFailed;
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }

  if (!clientEnrolled) {
    clientEnrolled = true;
    enrollmentPending = true;
    std::memcpy(pendingEnrollmentToken, data + tokenOffset, CLIENT_TOKEN_BYTES);
    pendingEnrollmentToken[CLIENT_TOKEN_BYTES] = '\0';
    lastWriteStatus = WriteStatus::Enrolled;
    if (!mailboxMode && configuredConnectionMode == ConnectionMode::Mailbox && connected) {
      radioState = RadioState::EnrollmentHandoff;
      disconnectAfterAt = now + ENROLLMENT_ACK_GRACE_MS;
      disconnectRequested = false;
    }
  } else {
    lastWriteStatus = WriteStatus::Accepted;
  }

  if (payloadLength != displayPayloadLength || std::memcmp(payload, displayPayload, displayPayloadLength) != 0) {
    std::memcpy(payload, displayPayload, displayPayloadLength);
    payload[displayPayloadLength] = '\0';
    payloadLength = displayPayloadLength;
    payloadPending = true;
  }
  portEXIT_CRITICAL(&payloadMutex);
}
