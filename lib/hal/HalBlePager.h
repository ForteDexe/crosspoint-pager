#pragma once

#include <cstddef>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>

// Minimal, opt-in BLE GATT receiver for the pager sleep screen. The
// characteristic value is an authenticated UTF-8 pager packet:
// "XPAGER1\nDATA\n<16-hex-token>\n<title>\nmessage\nfooter".
class HalBlePager;
extern HalBlePager blePager;

class HalBlePager {
 public:
  static constexpr size_t MAX_PAYLOAD_BYTES = 320;
  static constexpr size_t MAX_STATUS_BYTES = 320;
  static constexpr size_t CLIENT_TOKEN_BYTES = 16;
  static constexpr size_t AUTH_PAYLOAD_OVERHEAD_BYTES = 30;

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
             MailboxStart mailboxStart);
  void end();

  // Starts a new screen session with no duplicate-comparison baseline. Radio
  // restarts inside the same Pager session deliberately do not call this.
  void resetPayloadHistory();

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
  char payload[MAX_PAYLOAD_BYTES + 1] = {};
  size_t payloadLength = 0;
  bool payloadPending = false;
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
  unsigned long disconnectAfterAt = 0;
  bool disconnectRequested = false;
  bool advertisingRestartRequested = false;
  bool connectionParamsUpdateRequested = false;
};
