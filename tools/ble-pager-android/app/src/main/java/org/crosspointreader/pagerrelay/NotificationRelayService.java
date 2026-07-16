package org.crosspointreader.pagerrelay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class NotificationRelayService extends NotificationListenerService {
    private static final String ACTION_REFRESH_STACK =
            "org.crosspointreader.pagerrelay.REFRESH_NOTIFICATION_STACK";
    private static final long STACK_SETTLE_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sendStack = this::sendActiveStack;
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scheduleStackRefresh();
        }
    };

    static void requestStackRefresh(Context context) {
        context.sendBroadcast(new Intent(ACTION_REFRESH_STACK).setPackage(context.getPackageName()));
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION_REFRESH_STACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
    }

    @Override
    public void onListenerConnected() {
        scheduleStackRefresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (RelayPreferences.isEnabled(this)
                && !getPackageName().equals(notification.getPackageName())
                && RelayPreferences.isNotificationPackageTracked(this, notification.getPackageName())) {
            scheduleStackRefresh();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        // Pager is an append-only recent-event feed. Removing a phone
        // notification does not rewrite Xteink history.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(sendStack);
        unregisterReceiver(refreshReceiver);
        super.onDestroy();
    }

    private void scheduleStackRefresh() {
        if (!RelayPreferences.isEnabled(this)) {
            return;
        }
        handler.removeCallbacks(sendStack);
        handler.postDelayed(sendStack, STACK_SETTLE_MS);
    }

    private void sendActiveStack() {
        if (!RelayPreferences.isEnabled(this)) {
            return;
        }

        StatusBarNotification[] activeNotifications;
        try {
            activeNotifications = getActiveNotifications();
        } catch (SecurityException unavailable) {
            return;
        }
        if (activeNotifications == null) {
            activeNotifications = new StatusBarNotification[0];
        }
        Arrays.sort(activeNotifications, Comparator.comparingLong(StatusBarNotification::getPostTime));

        int maximum = RelayPreferences.maxNotifications(this);
        List<PagerProtocol.NotificationItem> items = new ArrayList<>();
        for (StatusBarNotification notification : activeNotifications) {
            PagerProtocol.NotificationItem item = notificationItem(notification);
            if (item == null) {
                continue;
            }
            items.add(item);
        }
        int first = Math.max(0, items.size() - maximum);
        boolean queuedAny = false;
        for (int index = first; index < items.size(); index++) {
            queuedAny |= RelayPreferences.enqueuePendingEvent(this, items.get(index));
        }
        if (queuedAny || !RelayPreferences.pendingEvents(this).isEmpty()) {
            PagerRelayService.sendRelay(this);
        }
    }

    private PagerProtocol.NotificationItem notificationItem(StatusBarNotification notification) {
        Notification source = notification.getNotification();
        if (notification.isOngoing()
                || getPackageName().equals(notification.getPackageName())
                || !RelayPreferences.isNotificationPackageTracked(this, notification.getPackageName())
                || (source.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }

        CharSequence title = source.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence message = source.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(message)) {
            message = source.extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(message)) {
            return null;
        }

        String appName = applicationLabel(notification.getPackageName());
        String displayTitle = TextUtils.isEmpty(title) ? appName : title.toString();
        String displayMessage = TextUtils.isEmpty(message) ? appName : message.toString();
        String time = DateFormat.getTimeFormat(this).format(new Date(notification.getPostTime()));
        return PagerTextFitter.fit(eventId(notification), time, displayTitle, displayMessage);
    }

    private String eventId(StatusBarNotification notification) {
        String identity = notification.getKey() + "|" + notification.getPostTime();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format(java.util.Locale.US, "%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return String.format(java.util.Locale.US, "%016x", identity.hashCode() & 0xffffffffL);
        }
    }

    private String applicationLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }
}
