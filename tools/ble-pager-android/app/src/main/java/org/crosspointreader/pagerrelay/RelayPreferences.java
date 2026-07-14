package org.crosspointreader.pagerrelay;

import android.content.Context;
import android.content.SharedPreferences;

final class RelayPreferences {
    private static final String NAME = "pager_relay";
    private static final String ENABLED = "enabled";
    private static final String BEAT_ENABLED = "beat_enabled";
    private static final String KEEP_CONNECTED = "keep_connected";
    private static final String LOG_ENABLED = "log_enabled";
    private static final String SHOW_NOTIFICATION_RELAY_LOG = "show_notification_relay_log";
    private static final String SHOW_BEAT_MODE_LOG = "show_beat_mode_log";
    private static final String MAILBOX_INTERVAL_MS = "mailbox_interval_ms";
    private static final String MAILBOX_WINDOW_MS = "mailbox_window_ms";
    private static final String MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS = "mailbox_next_window_wall_clock_ms";
    private static final String SEND_RETRY_ACTIVE = "send_retry_active";
    private static final String POLICY_RETRY_ACTIVE = "policy_retry_active";
    private static final String CLIENT_TOKEN = "client_token";

    private RelayPreferences() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
    }

    static boolean isBeatEnabled(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(BEAT_ENABLED, false);
    }

    static void setBeatEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(BEAT_ENABLED, enabled).apply();
    }

    static boolean shouldKeepConnected(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEEP_CONNECTED, false);
    }

    static void setKeepConnected(Context context, boolean keepConnected) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEEP_CONNECTED, keepConnected).apply();
    }

    static boolean showNotificationRelayLog(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(SHOW_NOTIFICATION_RELAY_LOG, preferences.getBoolean(LOG_ENABLED, true));
    }

    static void setShowNotificationRelayLog(Context context, boolean show) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(SHOW_NOTIFICATION_RELAY_LOG, show).apply();
    }

    static boolean showBeatModeLog(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(SHOW_BEAT_MODE_LOG, preferences.getBoolean(LOG_ENABLED, true));
    }

    static void setShowBeatModeLog(Context context, boolean show) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(SHOW_BEAT_MODE_LOG, show).apply();
    }

    static long mailboxIntervalMs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(MAILBOX_INTERVAL_MS, 0L);
    }

    static long mailboxWindowMs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(MAILBOX_WINDOW_MS, 0L);
    }

    static long mailboxNextWindowWallClockMs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getLong(MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS, 0L);
    }

    static void setMailboxSchedule(Context context, long intervalMs, long windowMs, long nextWindowWallClockMs) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putLong(MAILBOX_INTERVAL_MS, intervalMs)
                .putLong(MAILBOX_WINDOW_MS, windowMs)
                .putLong(MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS, nextWindowWallClockMs)
                .apply();
    }

    static void clearMailboxSchedule(Context context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .remove(MAILBOX_INTERVAL_MS)
                .remove(MAILBOX_WINDOW_MS)
                .remove(MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS)
                .apply();
    }

    static boolean isSendRetryActive(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(SEND_RETRY_ACTIVE, false);
    }

    static void setSendRetryActive(Context context, boolean active) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(SEND_RETRY_ACTIVE, active).apply();
    }

    static boolean isPolicyRetryActive(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(POLICY_RETRY_ACTIVE, false);
    }

    static void setPolicyRetryActive(Context context, boolean active) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(POLICY_RETRY_ACTIVE, active).apply();
    }

    static String clientToken(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(CLIENT_TOKEN, "");
    }

    static void setClientToken(Context context, String token) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(CLIENT_TOKEN, token).apply();
    }

    static void clearClientToken(Context context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(CLIENT_TOKEN).apply();
    }
}
