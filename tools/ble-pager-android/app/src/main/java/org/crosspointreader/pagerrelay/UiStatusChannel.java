package org.crosspointreader.pagerrelay;

enum UiStatusChannel {
    NONE("none"),
    POLICY("policy"),
    RELAY("relay"),
    TEST("test"),
    BEAT("beat");

    private final String wireValue;

    UiStatusChannel(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static UiStatusChannel fromWireValue(String value) {
        for (UiStatusChannel channel : values()) {
            if (channel.wireValue.equals(value)) {
                return channel;
            }
        }
        return NONE;
    }
}
