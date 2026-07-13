package org.crosspointreader.pagerrelay;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

public final class NotificationRelayService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (!RelayPreferences.isEnabled(this)
                || notification.isOngoing()
                || getPackageName().equals(notification.getPackageName())) {
            return;
        }

        Notification source = notification.getNotification();
        CharSequence title = source.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = source.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(text)) {
            text = source.extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) {
            return;
        }
        String appName = applicationLabel(notification.getPackageName());
        String payload = PagerProtocol.notificationPayload(
                TextUtils.isEmpty(title) ? appName : title.toString(),
                TextUtils.isEmpty(text) ? "" : text.toString(),
                appName);
        PagerRelayService.send(this, payload);
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
