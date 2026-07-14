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
    static final String ACTION_READ_STATUS = "org.crosspointreader.pagerrelay.READ_STATUS";
    static final String ACTION_APPLY_CONNECTION_MODE = "org.crosspointreader.pagerrelay.APPLY_CONNECTION_MODE";
    static final String ACTION_START_BEAT = "org.crosspointreader.pagerrelay.START_BEAT";
    static final String ACTION_STOP_BEAT = "org.crosspointreader.pagerrelay.STOP_BEAT";
    static final String ACTION_RESUME_ENABLED_MODES = "org.crosspointreader.pagerrelay.RESUME_ENABLED_MODES";
    static final String ACTION_CANCEL_SEND = "org.crosspointreader.pagerrelay.CANCEL_SEND";
    static final String ACTION_CANCEL_POLICY_READ = "org.crosspointreader.pagerrelay.CANCEL_POLICY_READ";
    static final String ACTION_FORGET_PAGER = "org.crosspointreader.pagerrelay.FORGET_PAGER";
    static final String ACTION_SELECT_PAGER = "org.crosspointreader.pagerrelay.SELECT_PAGER";
    static final String ACTION_STATUS = "org.crosspointreader.pagerrelay.STATUS";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_COUNTDOWN_AT_MS = "countdown_at_ms";
    static final String EXTRA_EVENT_CATEGORY = "event_category";
    static final String EXTRA_SEND_RETRY_ACTIVE = "send_retry_active";
    static final String EXTRA_POLICY_RETRY_ACTIVE = "policy_retry_active";
    static final String EXTRA_POLICY_STATUS = "policy_status";
    private static final String EXTRA_DEVICE_ADDRESS = "device_address";
    private static final String EXTRA_DEVICE_LABEL = "device_label";
    private static final String CHANNEL_ID = "pager_relay";
    private static final int FOREGROUND_NOTIFICATION_ID = 101;

    private PagerGattClient client;

    static void startRelay(Context context) {
        RelayPreferences.setEnabled(context, true);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_START_RELAY));
    }

    static void stopRelay(Context context) {
        RelayPreferences.setEnabled(context, false);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_STOP_RELAY));
    }

    static void send(Context context, String payload) {
        Intent intent = new Intent(context, PagerRelayService.class)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_PAYLOAD, payload);
        context.startForegroundService(intent);
    }

    static void readStatus(Context context) {
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_READ_STATUS));
    }

    static void cancelSend(Context context) {
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_CANCEL_SEND));
    }

    static void cancelPolicyRead(Context context) {
        context.startForegroundService(new Intent(context, PagerRelayService.class)
                .setAction(ACTION_CANCEL_POLICY_READ));
    }

    static void forgetPager(Context context) {
        RelayPreferences.setEnabled(context, false);
        RelayPreferences.setBeatEnabled(context, false);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_FORGET_PAGER));
    }

    static void selectPager(Context context, String address, String label) {
        RelayPreferences.setEnabled(context, false);
        RelayPreferences.setBeatEnabled(context, false);
        context.startForegroundService(new Intent(context, PagerRelayService.class)
                .setAction(ACTION_SELECT_PAGER)
                .putExtra(EXTRA_DEVICE_ADDRESS, address)
                .putExtra(EXTRA_DEVICE_LABEL, label));
    }

    static void applyConnectionMode(Context context) {
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_APPLY_CONNECTION_MODE));
    }

    static void startBeat(Context context) {
        RelayPreferences.setBeatEnabled(context, true);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_START_BEAT));
    }

    static void stopBeat(Context context) {
        RelayPreferences.setBeatEnabled(context, false);
        context.startForegroundService(new Intent(context, PagerRelayService.class).setAction(ACTION_STOP_BEAT));
    }

    static void resumeEnabledModes(Context context) {
        if (!RelayPreferences.isEnabled(context) && !RelayPreferences.isBeatEnabled(context)) {
            return;
        }
        context.startForegroundService(new Intent(context, PagerRelayService.class)
                .setAction(ACTION_RESUME_ENABLED_MODES));
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
        if (!ACTION_STOP_BEAT.equals(action) && RelayPreferences.isBeatEnabled(this)) {
            client.startBeat();
        }
        if (ACTION_STOP_RELAY.equals(action)) {
            client.setKeepConnected(false);
            RelayPreferences.setEnabled(this, false);
            publishStatus("Notification relay stopped.", 0L, EventLogCategory.NOTIFICATION_RELAY);
            if (!RelayPreferences.isBeatEnabled(this)) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        configureConnectionMode();
        if (ACTION_SEND.equals(action)) {
            String payload = intent.getStringExtra(EXTRA_PAYLOAD);
            if (payload != null && PagerProtocol.isValidTestPayload(payload)) {
                client.send(payload);
            } else {
                publishStatus("Pager payload is invalid.", 0L, EventLogCategory.NOTIFICATION_RELAY);
            }
        } else if (ACTION_READ_STATUS.equals(action)) {
            client.readStatus();
        } else if (ACTION_CANCEL_SEND.equals(action)) {
            client.cancelSend();
        } else if (ACTION_CANCEL_POLICY_READ.equals(action)) {
            client.cancelPolicyRead();
        } else if (ACTION_FORGET_PAGER.equals(action)) {
            client.forgetPager();
        } else if (ACTION_SELECT_PAGER.equals(action)) {
            client.selectPager(intent.getStringExtra(EXTRA_DEVICE_ADDRESS),
                    intent.getStringExtra(EXTRA_DEVICE_LABEL));
        } else if (ACTION_APPLY_CONNECTION_MODE.equals(action)) {
            if (shouldHoldRelayConnection()) {
                client.holdConnection();
            } else if (RelayPreferences.shouldKeepConnected(this)) {
                publishStatus("Keep-connected mode selected for test sends.", 0L,
                        EventLogCategory.NOTIFICATION_RELAY);
            } else {
                publishStatus("Connection mode: connect per message.", 0L,
                        EventLogCategory.NOTIFICATION_RELAY);
            }
        } else if (ACTION_START_BEAT.equals(action)) {
            client.startBeat();
        } else if (ACTION_STOP_BEAT.equals(action)) {
            client.stopBeat();
            if (!RelayPreferences.isEnabled(this)) {
                stopSelf();
            }
        } else if (ACTION_RESUME_ENABLED_MODES.equals(action)) {
            if (shouldHoldRelayConnection()) {
                client.holdConnection();
            }
        } else {
            if (shouldHoldRelayConnection()) {
                client.holdConnection();
            } else {
                publishStatus(RelayPreferences.isEnabled(this) ? "Pager relay is ready." : "Pager test sender is ready.",
                        0L, EventLogCategory.NOTIFICATION_RELAY);
            }
        }
        return RelayPreferences.isEnabled(this) || RelayPreferences.isBeatEnabled(this)
                || client.hasSendRetryActive() || client.hasPolicyRetryActive() || client.hasHeldConnection()
                ? START_STICKY
                : START_NOT_STICKY;
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

    private void publishStatus(String status, long countdownAtMs, EventLogCategory category) {
        publishStatus(status, countdownAtMs, category,
                RelayPreferences.isSendRetryActive(this), RelayPreferences.isPolicyRetryActive(this), false);
    }

    private void publishStatus(String status, long countdownAtMs, EventLogCategory category,
                               boolean sendRetryActive, boolean policyRetryActive, boolean policyStatus) {
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_COUNTDOWN_AT_MS, countdownAtMs)
                .putExtra(EXTRA_EVENT_CATEGORY, category.wireValue())
                .putExtra(EXTRA_SEND_RETRY_ACTIVE, sendRetryActive)
                .putExtra(EXTRA_POLICY_RETRY_ACTIVE, policyRetryActive)
                .putExtra(EXTRA_POLICY_STATUS, policyStatus));
        if (!RelayPreferences.isEnabled(this) && !RelayPreferences.isBeatEnabled(this)
                && !sendRetryActive && !policyRetryActive && !client.hasHeldConnection()) {
            stopSelf();
        }
    }

    private void configureConnectionMode() {
        client.setKeepConnected(RelayPreferences.shouldKeepConnected(this));
    }

    private boolean shouldHoldRelayConnection() {
        return RelayPreferences.isEnabled(this) && RelayPreferences.shouldKeepConnected(this);
    }
}
