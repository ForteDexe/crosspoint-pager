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
    private static final String PAGER_MODEL = "pager_model";
    private static final String PAGER_DEVICE_ID = "pager_device_id";
    private static final String PAGER_BLUETOOTH_ADDRESS = "pager_bluetooth_address";
    private static final String PAGER_SELECTED_LABEL = "pager_selected_label";
    private static final String PAGER_ENROLLED = "pager_enrolled";
    private static final String PAGER_AVAILABILITY = "pager_availability";
    private static final String PAGER_POLICY_INTERVAL_MS = "pager_policy_interval_ms";
    private static final String PAGER_LAST_SYNC_WALL_CLOCK_MS = "pager_last_sync_wall_clock_ms";
    private static final String PAGER_TECHNICAL_STATUS = "pager_technical_status";
    private static final String AUTO_UPDATE_PAGER_POLICY = "auto_update_pager_policy";
    private static final String MAX_NOTIFICATIONS = "max_notifications";
    static final int DEFAULT_MAX_NOTIFICATIONS = 4;

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

    static void setPagerPolicy(Context context, PagerProtocol.PagerStatus status, String technicalStatus) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PAGER_ENROLLED, status.enrolled)
                .putString(PAGER_AVAILABILITY, status.configuredMailbox ? "mailbox" : "always")
                .putLong(PAGER_POLICY_INTERVAL_MS, status.intervalMs)
                .putLong(PAGER_LAST_SYNC_WALL_CLOCK_MS, System.currentTimeMillis())
                .putString(PAGER_TECHNICAL_STATUS, technicalStatus)
                .apply();
    }

    static void setPagerIdentity(Context context, PagerProtocol.PagerStatus status, String bluetoothAddress) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString(PAGER_MODEL, status.model)
                .putString(PAGER_DEVICE_ID, status.deviceId)
                .putString(PAGER_BLUETOOTH_ADDRESS, bluetoothAddress)
                .apply();
    }

    static void selectPager(Context context, String bluetoothAddress, String selectedLabel) {
        SharedPreferences.Editor editor = clearedPagerEditor(context)
                .putString(PAGER_BLUETOOTH_ADDRESS, bluetoothAddress)
                .putString(PAGER_SELECTED_LABEL, selectedLabel == null ? "" : selectedLabel);
        editor.apply();
    }

    static String pagerModel(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_MODEL, "");
    }

    static String pagerDeviceId(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_DEVICE_ID, "");
    }

    static String pagerBluetoothAddress(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_BLUETOOTH_ADDRESS, "");
    }

    static String pagerSelectedLabel(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_SELECTED_LABEL, "");
    }

    static boolean pagerEnrolled(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(PAGER_ENROLLED, false);
    }

    static String pagerAvailability(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_AVAILABILITY, "");
    }

    static long pagerPolicyIntervalMs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(PAGER_POLICY_INTERVAL_MS, 0L);
    }

    static long pagerLastSyncWallClockMs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(PAGER_LAST_SYNC_WALL_CLOCK_MS, 0L);
    }

    static String pagerTechnicalStatus(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PAGER_TECHNICAL_STATUS, "");
    }

    static boolean hasPagerIdentity(Context context) {
        return PagerProtocol.isValidDeviceId(pagerDeviceId(context))
                && android.bluetooth.BluetoothAdapter.checkBluetoothAddress(pagerBluetoothAddress(context));
    }

    static boolean hasPagerSelection(Context context) {
        return android.bluetooth.BluetoothAdapter.checkBluetoothAddress(pagerBluetoothAddress(context));
    }

    static boolean isAutoUpdatePagerPolicy(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(AUTO_UPDATE_PAGER_POLICY, false);
    }

    static void setAutoUpdatePagerPolicy(Context context, boolean enabled) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(AUTO_UPDATE_PAGER_POLICY, enabled).apply();
    }

    static int maxNotifications(Context context) {
        int value = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(MAX_NOTIFICATIONS, DEFAULT_MAX_NOTIFICATIONS);
        return Math.max(1, Math.min(PagerProtocol.MAX_NOTIFICATION_COUNT, value));
    }

    static void setMaxNotifications(Context context, int value) {
        int boundedValue = Math.max(1, Math.min(PagerProtocol.MAX_NOTIFICATION_COUNT, value));
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(MAX_NOTIFICATIONS, boundedValue).apply();
    }

    static boolean isPagerReadyForUse(Context context) {
        if (!hasPagerIdentity(context) || !PagerProtocol.isValidClientToken(clientToken(context))
                || !pagerEnrolled(context)) {
            return false;
        }
        String availability = pagerAvailability(context);
        if ("always".equals(availability)) {
            return true;
        }
        return "mailbox".equals(availability)
                && mailboxIntervalMs(context) > 0L
                && mailboxWindowMs(context) > 0L
                && mailboxNextWindowWallClockMs(context) > 0L;
    }

    static void forgetPager(Context context) {
        clearedPagerEditor(context).apply();
    }

    private static SharedPreferences.Editor clearedPagerEditor(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .remove(CLIENT_TOKEN)
                .remove(PAGER_MODEL)
                .remove(PAGER_DEVICE_ID)
                .remove(PAGER_BLUETOOTH_ADDRESS)
                .remove(PAGER_SELECTED_LABEL)
                .remove(PAGER_ENROLLED)
                .remove(PAGER_AVAILABILITY)
                .remove(PAGER_POLICY_INTERVAL_MS)
                .remove(PAGER_LAST_SYNC_WALL_CLOCK_MS)
                .remove(PAGER_TECHNICAL_STATUS)
                .remove(MAILBOX_INTERVAL_MS)
                .remove(MAILBOX_WINDOW_MS)
                .remove(MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS);
    }
}
