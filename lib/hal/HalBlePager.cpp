#include "HalBlePager.h"

#include <Logging.h>
#include <NimBLEDevice.h>

#include <cstring>

namespace {
constexpr char DEVICE_NAME[] = "CrossPoint Pager";
constexpr char SERVICE_UUID[] = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
constexpr char PAYLOAD_UUID[] = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";

class PagerServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo&) override { blePager.setConnected(true); }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    blePager.setConnected(false);
    if (blePager.isRunning()) {
      NimBLEDevice::startAdvertising();
    }
  }
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

bool HalBlePager::begin() {
  if (running) {
    return true;
  }

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
  if (!advertising->start()) {
    LOG_ERR("BLE", "Could not start pager advertising");
    NimBLEDevice::deinit(true);
    return false;
  }
  if (!advertising->isAdvertising()) {
    LOG_ERR("BLE", "Pager advertiser is not active after start");
    NimBLEDevice::deinit(true);
    return false;
  }

  portENTER_CRITICAL(&payloadMutex);
  running = true;
  connected = false;
  portEXIT_CRITICAL(&payloadMutex);
  LOG_INF("BLE", "Pager advertising started");
  return true;
}

void HalBlePager::end() {
  portENTER_CRITICAL(&payloadMutex);
  if (!running) {
    portEXIT_CRITICAL(&payloadMutex);
    return;
  }
  running = false;
  connected = false;
  portEXIT_CRITICAL(&payloadMutex);

  NimBLEDevice::deinit(true);
  LOG_INF("BLE", "Pager stopped");
}

bool HalBlePager::isRunning() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = running;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
}

bool HalBlePager::isConnected() const {
  portENTER_CRITICAL(&payloadMutex);
  const bool result = connected;
  portEXIT_CRITICAL(&payloadMutex);
  return result;
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

void HalBlePager::setConnected(bool isConnected) {
  portENTER_CRITICAL(&payloadMutex);
  connected = isConnected;
  portEXIT_CRITICAL(&payloadMutex);
}

void HalBlePager::storePayload(const uint8_t* data, size_t length) {
  if (data == nullptr || length == 0 || length > MAX_PAYLOAD_BYTES) {
    return;
  }

  portENTER_CRITICAL(&payloadMutex);
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
