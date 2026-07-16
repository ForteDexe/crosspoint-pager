package org.crosspointreader.pagerrelay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final String NOTIFICATION_APP_FILTER_CONFIGURED = "notification_app_filter_configured";
    private static final String TRACKED_NOTIFICATION_PACKAGES = "tracked_notification_packages";
    private static final String PENDING_EVENTS = "pending_events";
    private static final String SENT_EVENT_IDS = "sent_event_ids";
    private static final String SENT_EVENT_CONTENTS = "sent_event_contents";
    private static final int MAX_SENT_EVENT_IDS = 64;
    private static final int MAX_SENT_EVENT_CONTENTS = 64;
    private static final long PENDING_EVENT_MAX_AGE_MS = 75L * 60L * 1000L;
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

    static void setPagerEnrollmentConfirmed(Context context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PAGER_ENROLLED, true).apply();
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
        List<PagerProtocol.NotificationItem> pending = pendingEvents(context);
        while (pending.size() > boundedValue) {
            pending.remove(0);
        }
        storePendingEvents(context, pending);
    }

    static boolean isNotificationPackageTracked(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(NOTIFICATION_APP_FILTER_CONFIGURED, false)) {
            return true;
        }
        Set<String> tracked = preferences.getStringSet(TRACKED_NOTIFICATION_PACKAGES, new HashSet<>());
        return tracked != null && tracked.contains(packageName);
    }

    static boolean hasNotificationAppFilter(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(NOTIFICATION_APP_FILTER_CONFIGURED, false);
    }

    static Set<String> trackedNotificationPackages(Context context) {
        Set<String> stored = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getStringSet(TRACKED_NOTIFICATION_PACKAGES, new HashSet<>());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    static void setTrackedNotificationPackages(Context context, Set<String> packages) {
        Set<String> sanitized = new HashSet<>();
        if (packages != null) {
            for (String packageName : packages) {
                if (packageName != null && !packageName.isEmpty()) {
                    sanitized.add(packageName);
                }
            }
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(NOTIFICATION_APP_FILTER_CONFIGURED, true)
                .putStringSet(TRACKED_NOTIFICATION_PACKAGES, sanitized)
                .remove(PENDING_EVENTS)
                .apply();
    }

    static boolean enqueuePendingEvent(Context context, PagerProtocol.NotificationItem item) {
        if (item == null || !PagerProtocol.isValidEventId(item.eventId)
                || hasSentEvent(context, item.eventId) || hasSentContent(context, item)) {
            return false;
        }
        List<PagerProtocol.NotificationItem> pending = pendingEvents(context);
        for (PagerProtocol.NotificationItem existing : pending) {
            if (existing.eventId.equals(item.eventId)
                    || PagerProtocol.hasSameNotificationContent(existing, item)) {
                return false;
            }
        }
        pending.add(item);
        while (pending.size() > maxNotifications(context)) {
            pending.remove(0);
        }
        storePendingEvents(context, pending);
        return true;
    }

    static List<PagerProtocol.NotificationItem> pendingEvents(Context context) {
        List<PagerProtocol.NotificationItem> result = new ArrayList<>();
        String raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(PENDING_EVENTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject value = array.getJSONObject(index);
                String eventId = value.optString("id");
                if (!PagerProtocol.isValidEventId(eventId)) {
                    continue;
                }
                long queuedAtMs = value.optLong("queued_at_ms", System.currentTimeMillis());
                if (System.currentTimeMillis() - queuedAtMs > PENDING_EVENT_MAX_AGE_MS) {
                    continue;
                }
                result.add(new PagerProtocol.NotificationItem(eventId, value.optString("time"),
                        value.optString("title"), value.optString("message"), queuedAtMs));
            }
        } catch (JSONException ignored) {
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(PENDING_EVENTS).apply();
        }
        return result;
    }

    static void markBatchSent(Context context, List<PagerProtocol.WriteCommand> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<PagerProtocol.NotificationItem> pending = pendingEvents(context);
        List<String> sentIds = sentEventIds(context);
        List<PagerProtocol.NotificationItem> sentContents = sentEventContents(context);
        for (PagerProtocol.WriteCommand command : batch) {
            if (!PagerProtocol.isValidEventId(command.eventId)) {
                continue;
            }
            pending.removeIf(item -> command.eventId.equals(item.eventId));
            sentIds.remove(command.eventId);
            sentIds.add(command.eventId);
            if (command.notification == null) {
                continue;
            }
            sentContents.removeIf(item -> PagerProtocol.hasSameNotificationContent(item, command.notification));
            sentContents.add(command.notification);
        }
        storePendingEvents(context, pending);
        while (sentIds.size() > MAX_SENT_EVENT_IDS) {
            sentIds.remove(0);
        }
        storeSentEventIds(context, sentIds);
        while (sentContents.size() > MAX_SENT_EVENT_CONTENTS) {
            sentContents.remove(0);
        }
        storeSentEventContents(context, sentContents);
    }

    static void clearPendingEvents(Context context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(PENDING_EVENTS).apply();
    }

    private static boolean hasSentEvent(Context context, String eventId) {
        return sentEventIds(context).contains(eventId);
    }

    private static boolean hasSentContent(Context context, PagerProtocol.NotificationItem item) {
        for (PagerProtocol.NotificationItem sent : sentEventContents(context)) {
            if (PagerProtocol.hasSameNotificationContent(sent, item)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sentEventIds(Context context) {
        List<String> result = new ArrayList<>();
        String raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(SENT_EVENT_IDS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                String eventId = array.optString(index);
                if (PagerProtocol.isValidEventId(eventId)) {
                    result.add(eventId);
                }
            }
        } catch (JSONException ignored) {
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(SENT_EVENT_IDS).apply();
        }
        return result;
    }

    private static List<PagerProtocol.NotificationItem> sentEventContents(Context context) {
        List<PagerProtocol.NotificationItem> result = new ArrayList<>();
        String raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(SENT_EVENT_CONTENTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject value = array.getJSONObject(index);
                String eventId = value.optString("id");
                if (!PagerProtocol.isValidEventId(eventId)) {
                    continue;
                }
                result.add(new PagerProtocol.NotificationItem(eventId, "", value.optString("title"),
                        value.optString("message")));
            }
        } catch (JSONException ignored) {
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(SENT_EVENT_CONTENTS).apply();
        }
        return result;
    }

    private static void storePendingEvents(Context context, List<PagerProtocol.NotificationItem> pending) {
        JSONArray array = new JSONArray();
        for (PagerProtocol.NotificationItem item : pending) {
            JSONObject value = new JSONObject();
            try {
                value.put("id", item.eventId);
                value.put("time", item.time);
                value.put("title", item.title);
                value.put("message", item.message);
                value.put("queued_at_ms", item.queuedAtMs);
                array.put(value);
            } catch (JSONException ignored) {
                // String values cannot fail JSON encoding; skip a malformed item defensively.
            }
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString(PENDING_EVENTS, array.toString()).apply();
    }

    private static void storeSentEventIds(Context context, List<String> sent) {
        JSONArray array = new JSONArray();
        for (String eventId : sent) {
            array.put(eventId);
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString(SENT_EVENT_IDS, array.toString()).apply();
    }

    private static void storeSentEventContents(Context context, List<PagerProtocol.NotificationItem> sent) {
        JSONArray array = new JSONArray();
        for (PagerProtocol.NotificationItem item : sent) {
            JSONObject value = new JSONObject();
            try {
                value.put("id", item.eventId);
                value.put("title", item.title);
                value.put("message", item.message);
                array.put(value);
            } catch (JSONException ignored) {
                // String values cannot fail JSON encoding; skip a malformed item defensively.
            }
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString(SENT_EVENT_CONTENTS, array.toString()).apply();
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
                .remove(NOTIFICATION_APP_FILTER_CONFIGURED)
                .remove(TRACKED_NOTIFICATION_PACKAGES)
                .remove(PENDING_EVENTS)
                .remove(SENT_EVENT_IDS)
                .remove(SENT_EVENT_CONTENTS)
                .remove(MAILBOX_INTERVAL_MS)
                .remove(MAILBOX_WINDOW_MS)
                .remove(MAILBOX_NEXT_WINDOW_WALL_CLOCK_MS);
    }
}
