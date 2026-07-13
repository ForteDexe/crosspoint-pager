package org.crosspointreader.pagerrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public final class PagerRelayService extends Service {
    static final String ACTION_START_RELAY = "org.crosspointreader.pagerrelay.START_RELAY";
    static final String ACTION_STOP_RELAY = "org.crosspointreader.pagerrelay.STOP_RELAY";
    static final String ACTION_SEND = "org.crosspointreader.pagerrelay.SEND";
    static final String ACTION_STATUS = "org.crosspointreader.pagerrelay.STATUS";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_STATUS = "status";
    private static final String CHANNEL_ID = "pager_relay";
    private static final int FOREGROUND_NOTIFICATION_ID = 101;

    private PagerGattClient client;

    static void startRelay(Context context) {
        RelayPreferences.setEnabled(context, true);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_START_RELAY));
    }

    static void stopRelay(Context context) {
        RelayPreferences.setEnabled(context, false);
        context.stopService(new Intent(context, PagerRelayService.class));
    }

    static void send(Context context, String payload) {
        Intent intent = new Intent(context, PagerRelayService.class)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_PAYLOAD, payload);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        client = new PagerGattClient(this, this::publishStatus);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification());
        String action = intent == null ? ACTION_START_RELAY : intent.getAction();
        if (ACTION_STOP_RELAY.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SEND.equals(action)) {
            String payload = intent.getStringExtra(EXTRA_PAYLOAD);
            if (payload != null && PagerProtocol.utf8Length(payload) <= PagerProtocol.MAX_PAYLOAD_BYTES) {
                client.send(payload);
            } else {
                publishStatus("Pager payload is invalid.");
            }
        } else {
            publishStatus(RelayPreferences.isEnabled(this) ? "Pager relay is ready." : "Pager test sender is ready.");
        }
        return RelayPreferences.isEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        client.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification foregroundNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.relay_notification_title))
                .setContentText(getString(R.string.relay_notification_text))
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.relay_channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void publishStatus(String status) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, status));
    }
}
