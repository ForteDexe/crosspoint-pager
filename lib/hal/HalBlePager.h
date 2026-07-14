#pragma once

#include <cstddef>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

// Minimal, opt-in BLE GATT receiver for the pager sleep screen. The
// characteristic value is UTF-8 text in the form "title\nmessage\nfooter".
class HalBlePager;
extern HalBlePager blePager;

class HalBlePager {
 public:
  static constexpr size_t MAX_PAYLOAD_BYTES = 320;
  static constexpr size_t MAX_STATUS_BYTES = 224;

  enum class ConnectionMode : uint8_t {
    Normal,
    Mailbox,
  };

  enum class NormalPowerProfile : uint8_t {
    Responsive,
    Balanced,
    BatterySaver,
  };

  bool begin(ConnectionMode connectionMode, uint8_t mailboxIntervalMinutes, NormalPowerProfile normalPowerProfile);
  void end();

  // Advances the bounded connection/window state machine from the activity
  // loop. This deliberately keeps NimBLE operations out of its callbacks.
  void update();

  bool isRunning() const;
  bool isRadioRunning() const;
  bool isConnected() const;
  bool isMailboxWaiting() const;
  unsigned long getMailboxSleepDurationMs() const;
  bool suspendMailboxRadio();
  bool resumeMailboxWindow();

  // Copies the newest received payload and consumes its pending-update flag.
  // Returns zero when no changed payload is waiting or the destination is too
  // small. The result is always NUL-terminated when non-zero.
  size_t takePayload(char* destination, size_t destinationSize);

  // Produces a read-only, semicolon-delimited policy/status value for the
  // companion app. X3 remains the configuration authority.
  size_t copyStatus(char* destination, size_t destinationSize) const;

  // These are called only by the NimBLE callbacks. They keep callback work
  // bounded and leave all rendering to the activity loop.
  void setConnected(bool connected, uint16_t connectionHandle = 0, uint16_t intervalUnits = 0,
                    uint16_t latency = 0, uint16_t supervisionTimeoutUnits = 0);
  void setConnectionParameters(uint16_t intervalUnits, uint16_t latency, uint16_t supervisionTimeoutUnits);
  void storePayload(const uint8_t* data, size_t length);

 private:
  enum class RadioState : uint8_t {
    Normal,
    MailboxWindow,
    MailboxWaiting,
  };

  bool startRadio();

  mutable portMUX_TYPE payloadMutex = portMUX_INITIALIZER_UNLOCKED;
  char payload[MAX_PAYLOAD_BYTES + 1] = {};
  size_t payloadLength = 0;
  bool payloadPending = false;
  bool running = false;
  bool radioRunning = false;
  bool mailboxMode = false;
  NormalPowerProfile normalPowerProfile = NormalPowerProfile::Balanced;
  bool connected = false;
  uint16_t connectionHandle = 0;
  uint16_t connectionIntervalUnits = 0;
  uint16_t connectionLatency = 0;
  uint16_t supervisionTimeoutUnits = 0;
  unsigned long mailboxIntervalMs = 0;
  RadioState radioState = RadioState::Normal;
  unsigned long receiveWindowStartedAt = 0;
  unsigned long nextMailboxWindowAt = 0;
  unsigned long connectionStartedAt = 0;
  unsigned long disconnectAfterAt = 0;
  bool disconnectRequested = false;
  bool advertisingRestartRequested = false;
  bool connectionParamsUpdateRequested = false;
};
