#include "HalPowerManager.h"

#include <Logging.h>
#include <WiFi.h>
#include <driver/gpio.h>
#include <esp_pm.h>
#include <esp_sleep.h>

#include <cassert>

#include "HalGPIO.h"

HalPowerManager powerManager;  // Singleton instance

namespace {

constexpr int PAGER_LIGHT_SLEEP_MIN_FREQ_MHZ = 40;
// X3's battery latch MOSFET is controlled by GPIO13. The ESP-IDF C3 light
// sleep workaround otherwise disconnects every GPIO during automatic sleep,
// which removes this hold signal and physically powers the device off.
constexpr gpio_num_t X3_BATTERY_LATCH_GPIO = GPIO_NUM_13;
bool pagerLightSleepEnabled = false;

bool readFuelGaugeWord(uint8_t registerAddress, uint16_t* outValue) {
  if (outValue == nullptr) {
    return false;
  }

  Wire.beginTransmission(I2C_ADDR_BQ27220);
  Wire.write(registerAddress);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }
  if (Wire.requestFrom(I2C_ADDR_BQ27220, static_cast<uint8_t>(2)) < 2) {
    while (Wire.available()) {
      Wire.read();
    }
    return false;
  }

  const uint8_t lo = Wire.read();
  const uint8_t hi = Wire.read();
  *outValue = static_cast<uint16_t>(hi) << 8 | lo;
  return true;
}

}  // namespace

void HalPowerManager::begin() {
  if (gpio.deviceIsX3()) {
    // X3 uses an I2C fuel gauge for battery monitoring.
    // I2C init must come AFTER gpio.begin() so early hardware detection/probes are finished.
    Wire.begin(X3_I2C_SDA, X3_I2C_SCL, X3_I2C_FREQ);
    Wire.setTimeOut(4);
    _batteryUseI2C = true;
  } else {
    pinMode(BAT_GPIO0, INPUT);
  }
  normalFreq = getCpuFrequencyMhz();
  modeMutex = xSemaphoreCreateMutex();
  assert(modeMutex != nullptr);
}

void HalPowerManager::setPowerSaving(bool enabled) {
  if (normalFreq <= 0) {
    return;  // invalid state
  }

  auto wifiMode = WiFi.getMode();
  if (wifiMode != WIFI_MODE_NULL) {
    // Wifi is active, force disabling power saving
    enabled = false;
  }

  // Note: We don't use mutex here to avoid too much overhead,
  // it's not very important if we read a slightly stale value for currentLockMode
  const LockMode mode = currentLockMode;

  if (mode == None && enabled && !isLowPower) {
    LOG_DBG("PWR", "Going to low-power mode");
    if (!setCpuFrequencyMhz(LOW_POWER_FREQ)) {
      LOG_DBG("PWR", "Failed to set CPU frequency = %d MHz", LOW_POWER_FREQ);
      return;
    }
    isLowPower = true;

  } else if ((!enabled || mode != None) && isLowPower) {
    LOG_DBG("PWR", "Restoring normal CPU frequency");
    if (!setCpuFrequencyMhz(normalFreq)) {
      LOG_DBG("PWR", "Failed to set CPU frequency = %d MHz", normalFreq);
      return;
    }
    isLowPower = false;
  }

  // Otherwise, no change needed
}

void HalPowerManager::startDeepSleep(HalGPIO& gpio) const {
  // Ensure that the power button has been released to avoid immediately turning back on if you're holding it
  while (gpio.isPressed(HalGPIO::BTN_POWER)) {
    delay(50);
    gpio.update();
  }

#ifdef ENABLE_SERIAL_LOG
  // Tear down HWCDC so the host sees a clean disconnect and the peripheral
  // doesn't hold power domains that interfere with USB-powered GPIO wake.
  // logSerial is the raw HWCDC reference; Serial is the MySerialImpl proxy
  // (which doesn't expose end()).
  logSerial.end();
#endif

  // Pre-sleep routines from the original firmware
  // GPIO13 is connected to battery latch MOSFET, we need to make sure it's low during sleep
  // Note that this means the MCU will be completely powered off during sleep, including RTC
  constexpr gpio_num_t GPIO_SPIWP = GPIO_NUM_13;
  gpio_set_direction(GPIO_SPIWP, GPIO_MODE_OUTPUT);
  gpio_set_level(GPIO_SPIWP, 0);
  esp_sleep_config_gpio_isolate();
  gpio_deep_sleep_hold_en();
  gpio_hold_en(GPIO_SPIWP);
  pinMode(InputManager::POWER_BUTTON_PIN, INPUT_PULLUP);
  // Arm the wakeup trigger *after* the button is released
  // Note: this is only useful for waking up on USB power. On battery, the MCU will be completely powered off, so the
  // power button is hard-wired to briefly provide power to the MCU, waking it up regardless of the wakeup source
  // configuration
  esp_deep_sleep_enable_gpio_wakeup(1ULL << InputManager::POWER_BUTTON_PIN, ESP_GPIO_WAKEUP_GPIO_LOW);
  // Enter Deep Sleep
  esp_deep_sleep_start();
}

uint16_t HalPowerManager::getBatteryPercentage() const {
  if (_batteryUseI2C) {
    const unsigned long now = millis();
    if (_batteryLastPollMs != 0 && (now - _batteryLastPollMs) < BATTERY_POLL_MS) {
      return _batteryCachedPercent;
    }

    // On I2C error, keep last known value to avoid UI jitter/slowdowns.
    uint16_t soc = 0;
    if (!readFuelGaugeWord(BQ27220_SOC_REG, &soc)) {
      _batteryLastPollMs = now;
      return _batteryCachedPercent;
    }
    _batteryCachedPercent = soc > 100 ? 100 : soc;
    _batteryLastPollMs = now;
    return _batteryCachedPercent;
  }
  static const BatteryMonitor battery = BatteryMonitor(BAT_GPIO0);

  // smooth the battery %.
  if (_batteryCachedPercent == 0) {
    _batteryCachedPercent = 10 * battery.readPercentage();
  } else {
    _batteryCachedPercent = (_batteryCachedPercent * 9 + battery.readPercentage() * 10) / 10;
  }
  return _batteryCachedPercent / 10;
}

bool HalPowerManager::enablePagerLightSleep() {
#if CONFIG_PM_ENABLE
  if (pagerLightSleepEnabled) {
    return true;
  }
  if (normalFreq <= 0) {
    LOG_ERR("PWR", "Cannot configure Pager light sleep before power manager initialization");
    return false;
  }

  // Keep the board powered while the CPU automatically enters light sleep.
  // CONFIG_PM_SLP_DISABLE_GPIO is selected on ESP32-C3 by the IDF GPIO-reset
  // workaround; opt this latch pin out of that all-GPIO sleep isolation.
  // The normal deep-sleep path explicitly drives this same pin low later.
  esp_err_t latchResult = gpio_set_direction(X3_BATTERY_LATCH_GPIO, GPIO_MODE_OUTPUT);
  if (latchResult == ESP_OK) {
    latchResult = gpio_set_level(X3_BATTERY_LATCH_GPIO, 1);
  }
  if (latchResult == ESP_OK) {
    latchResult = gpio_sleep_sel_dis(X3_BATTERY_LATCH_GPIO);
  }
  if (latchResult != ESP_OK) {
    LOG_ERR("PWR", "Could not preserve X3 battery latch for Pager light sleep: %s", esp_err_to_name(latchResult));
    return false;
  }

  // The Bluetooth controller has its own modem-sleep timer, but ESP32-C3
  // automatic light sleep needs this wake source explicitly armed so that the
  // CPU resumes in time for advertising and connection events.
  const esp_err_t btWakeResult = esp_sleep_enable_bt_wakeup();
  if (btWakeResult != ESP_OK) {
    LOG_ERR("PWR", "Could not enable Pager Bluetooth wakeup: %s", esp_err_to_name(btWakeResult));
    return false;
  }

  const esp_pm_config_t config = {
      .max_freq_mhz = normalFreq,
      .min_freq_mhz = normalFreq < PAGER_LIGHT_SLEEP_MIN_FREQ_MHZ ? normalFreq : PAGER_LIGHT_SLEEP_MIN_FREQ_MHZ,
      .light_sleep_enable = true,
  };
  const esp_err_t result = esp_pm_configure(&config);
  if (result != ESP_OK) {
    esp_sleep_disable_bt_wakeup();
    LOG_ERR("PWR", "Pager automatic light sleep configuration failed: %s", esp_err_to_name(result));
    return false;
  }

  pagerLightSleepEnabled = true;
  LOG_INF("PWR", "Pager automatic light sleep enabled");
  return true;
#else
  LOG_DBG("PWR", "Pager automatic light sleep unavailable in this build");
  return false;
#endif
}

void HalPowerManager::disablePagerLightSleep() {
#if CONFIG_PM_ENABLE
  if (!pagerLightSleepEnabled) {
    return;
  }

  const esp_pm_config_t config = {
      .max_freq_mhz = normalFreq,
      .min_freq_mhz = normalFreq,
      .light_sleep_enable = false,
  };
  const esp_err_t result = esp_pm_configure(&config);
  if (result != ESP_OK) {
    LOG_ERR("PWR", "Could not disable Pager automatic light sleep: %s", esp_err_to_name(result));
  }

  const esp_err_t btWakeResult = esp_sleep_disable_bt_wakeup();
  if (btWakeResult != ESP_OK) {
    LOG_ERR("PWR", "Could not disable Pager Bluetooth wakeup: %s", esp_err_to_name(btWakeResult));
  }

  pagerLightSleepEnabled = false;
  LOG_INF("PWR", "Pager automatic light sleep disabled");
#endif
}

HalPowerManager::Lock::Lock() {
  xSemaphoreTake(powerManager.modeMutex, portMAX_DELAY);
  // Current limitation: only one lock at a time
  if (powerManager.currentLockMode != None) {
    LOG_ERR("PWR", "Lock already held, ignore");
    valid = false;
  } else {
    powerManager.currentLockMode = NormalSpeed;
    valid = true;
  }
  xSemaphoreGive(powerManager.modeMutex);
  if (valid) {
    // Immediately restore normal CPU frequency if currently in low-power mode
    powerManager.setPowerSaving(false);
  }
}

HalPowerManager::Lock::~Lock() {
  xSemaphoreTake(powerManager.modeMutex, portMAX_DELAY);
  if (valid) {
    powerManager.currentLockMode = None;
  }
  xSemaphoreGive(powerManager.modeMutex);
}
