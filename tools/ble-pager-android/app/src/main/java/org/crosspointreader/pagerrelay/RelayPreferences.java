package org.crosspointreader.pagerrelay;

import android.content.Context;

final class RelayPreferences {
    private static final String NAME = "pager_relay";
    private static final String ENABLED = "enabled";

    private RelayPreferences() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
    }
}
