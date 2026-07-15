#pragma once

#include <cstddef>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

// Minimal, opt-in BLE GATT receiver for the pager sleep screen. It authenticates
// and queues bounded DATA or BEGIN/ADD/END commands; rendering stays in the
// activity layer.
class HalBlePager;
extern HalBlePager blePager;

class HalBlePager {
 public:
  static constexpr size_t MAX_PAYLOAD_BYTES = 216;
  static constexpr size_t MAX_STATUS_BYTES = 320;
  static constexpr size_t CLIENT_TOKEN_BYTES = 16;
  static constexpr size_t COMMAND_DATA_BYTES = 187;
  static constexpr size_t COMMAND_QUEUE_CAPACITY = 12;

  enum class CommandType : uint8_t {
    Data,
    Begin,
    Add,
    End,
  };

  struct Command {
    CommandType type = CommandType::Data;
    uint16_t length = 0;
    char data[COMMAND_DATA_BYTES + 1] = {};
  };

  enum class ConnectionMode : uint8_t {
    Normal,
    Mailbox,
  };

  enum class NormalPowerProfile : uint8_t {
    Responsive,
    Balanced,
    BatterySaver,
  };

  enum class MailboxStart : uint8_t {
    OpenWindow,
    WaitForInterval,
  };

  enum class WriteStatus : uint8_t {
    None,
    Accepted,
    Unchanged,
    Enrolled,
    AuthFailed,
    Invalid,
  };

  bool begin(ConnectionMode connectionMode, ConnectionMode configuredConnectionMode, uint8_t mailboxIntervalMinutes,
             NormalPowerProfile normalPowerProfile, bool clientEnrolled, const char* clientToken,
             MailboxStart mailboxStart, unsigned long firstMailboxDelayMs = 0);
  void end();

  // Advances the bounded connection/window state machine from the activity
  // loop. This deliberately keeps NimBLE operations out of its callbacks.
  void update();

  bool isRunning() const;
  bool isRadioRunning() const;
  bool isConnected() const;
  bool isMailboxWaiting() const;
  unsigned long getMailboxSleepDurationMs() const;
  void alignNextMailboxWindow(unsigned long delayMs);
  bool suspendMailboxRadio();
  bool resumeMailboxWindow();

  // Consumes the oldest authenticated command. Android serializes writes, and
  // this fixed queue holds one maximum BEGIN + 10 ADD + END transaction without
  // allocating in the NimBLE callback.
  bool takeCommand(Command& destination);

  // Produces a read-only, semicolon-delimited policy/status value for the
  // companion app. Xteink remains the configuration authority.
  size_t copyStatus(char* destination, size_t destinationSize) const;

  // Copies the short model/identity shown during explicit app enrollment.
  static size_t copySetupLabel(char* destination, size_t destinationSize);

  // Consumes the token that completed first-time enrollment. The activity owns
  // persistent settings and saves it outside the BLE callback path.
  size_t takeEnrollmentToken(char* destination, size_t destinationSize);

  // Consumes a setup-to-mailbox transition only after the enrollment client
  // disconnects. The state machine requests that disconnect after a bounded
  // acknowledgement grace period when the client does not close first.
  bool takeMailboxHandoffRequest();

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
    EnrollmentHandoff,
  };

  bool startRadio();

  mutable portMUX_TYPE payloadMutex = portMUX_INITIALIZER_UNLOCKED;
  Command commandQueue[COMMAND_QUEUE_CAPACITY] = {};
  uint8_t commandQueueHead = 0;
  uint8_t commandQueueCount = 0;
  bool clientEnrolled = false;
  char clientToken[CLIENT_TOKEN_BYTES + 1] = {};
  bool enrollmentPending = false;
  char pendingEnrollmentToken[CLIENT_TOKEN_BYTES + 1] = {};
  WriteStatus lastWriteStatus = WriteStatus::None;
  bool running = false;
  bool radioRunning = false;
  bool mailboxMode = false;
  ConnectionMode configuredConnectionMode = ConnectionMode::Normal;
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
  unsigned long lastCommandAt = 0;
  unsigned long disconnectAfterAt = 0;
  bool commandBatchOpen = false;
  bool disconnectRequested = false;
  bool advertisingRestartRequested = false;
  bool connectionParamsUpdateRequested = false;
};
