package org.crosspointreader.pagerrelay;

import android.content.Context;

final class RelayPreferences {
    private static final String NAME = "pager_relay";
    private static final String ENABLED = "enabled";
    private static final String KEEP_CONNECTED = "keep_connected";

    private RelayPreferences() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
    }

    static boolean shouldKeepConnected(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEEP_CONNECTED, false);
    }

    static void setKeepConnected(Context context, boolean keepConnected) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEEP_CONNECTED, keepConnected).apply();
    }
}
