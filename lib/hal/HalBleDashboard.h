#pragma once

#include <cstddef>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

// Minimal, opt-in BLE GATT receiver for the dashboard sleep screen. The
// characteristic value is UTF-8 text in the form "title\nmessage\nfooter".
class HalBleDashboard;
extern HalBleDashboard bleDashboard;

class HalBleDashboard {
 public:
  static constexpr size_t MAX_PAYLOAD_BYTES = 320;

  bool begin();
  void end();

  bool isRunning() const;
  bool isConnected() const;

  // Copies the newest received payload and consumes its pending-update flag.
  // Returns zero when no changed payload is waiting or the destination is too
  // small. The result is always NUL-terminated when non-zero.
  size_t takePayload(char* destination, size_t destinationSize);

  // These are called only by the NimBLE callbacks. They keep callback work
  // bounded and leave all rendering to the activity loop.
  void setConnected(bool connected);
  void storePayload(const uint8_t* data, size_t length);

 private:
  mutable portMUX_TYPE payloadMutex = portMUX_INITIALIZER_UNLOCKED;
  char payload[MAX_PAYLOAD_BYTES + 1] = {};
  size_t payloadLength = 0;
  bool payloadPending = false;
  bool running = false;
  bool connected = false;
};
