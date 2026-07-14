package org.crosspointreader.pagerrelay;

enum EventLogCategory {
    NOTIFICATION_RELAY("notification_relay"),
    BEAT_MODE("beat_mode");

    private final String wireValue;

    EventLogCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static EventLogCategory fromWireValue(String value) {
        return BEAT_MODE.wireValue.equals(value) ? BEAT_MODE : NOTIFICATION_RELAY;
    }
}
