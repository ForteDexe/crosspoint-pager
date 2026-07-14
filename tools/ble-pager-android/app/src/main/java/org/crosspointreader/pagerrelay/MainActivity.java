package org.crosspointreader.pagerrelay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7;
    private static final int MAX_STATUS_LINES = 40;

    private EditText title;
    private EditText message;
    private EditText footer;
    private TextView byteCount;
    private TextView status;
    private TextView policyStatus;
    private TextView statusLog;
    private TextView learnedMailboxTiming;
    private TextView pagerSummary;
    private TextView technicalStatus;
    private TextView bluetoothPermissionStatus;
    private TextView notificationPermissionStatus;
    private TextView enrollmentResetAdvice;
    private TextView testConnectionMode;
    private Button send;
    private Button refreshPolicy;
    private Button forgetPager;
    private Button choosePager;
    private Button bluetoothPermissionAction;
    private Button notificationPermissionAction;
    private Switch notificationRelaySwitch;
    private Switch beatModeSwitch;
    private Switch autoUpdatePolicySwitch;
    private BroadcastReceiver statusReceiver;
    private final List<LogEntry> statusLines = new ArrayList<>();
    private boolean updatingControlSwitches;
    private boolean sendRetryActive;
    private boolean policyRetryActive;
    private boolean policyRefreshMissed;
    private boolean policyReceivedThisSession;
    private PagerDeviceScanner deviceScanner;
    private AlertDialog deviceChooserDialog;
    private ArrayAdapter<String> deviceChoiceAdapter;
    private final List<PagerDeviceScanner.DeviceCandidate> discoveredDevices = new ArrayList<>();
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private String sendCountdownPrefix;
    private long sendCountdownAtMs;
    private String policyCountdownPrefix;
    private long policyCountdownAtMs;
    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            long nowMs = SystemClock.elapsedRealtime();
            boolean sendWaiting = updateCountdown(status, sendCountdownPrefix, sendCountdownAtMs, nowMs);
            boolean policyWaiting = updateCountdown(policyStatus, policyCountdownPrefix, policyCountdownAtMs, nowMs);
            if (sendWaiting || policyWaiting) {
                countdownHandler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateOperationButtons(
                        intent.getBooleanExtra(PagerRelayService.EXTRA_SEND_RETRY_ACTIVE, false),
                        intent.getBooleanExtra(PagerRelayService.EXTRA_POLICY_RETRY_ACTIVE, false));
                recordStatus(intent.getStringExtra(PagerRelayService.EXTRA_STATUS),
                        intent.getLongExtra(PagerRelayService.EXTRA_COUNTDOWN_AT_MS, 0L),
                        EventLogCategory.fromWireValue(
                                intent.getStringExtra(PagerRelayService.EXTRA_EVENT_CATEGORY)),
                        intent.getBooleanExtra(PagerRelayService.EXTRA_POLICY_STATUS, false));
            }
        };
        updatePermissionStatus();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 has no flags overload; sender is package-scoped.
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PagerRelayService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        syncControlSwitches();
        updateOperationButtons(RelayPreferences.isSendRetryActive(this),
                RelayPreferences.isPolicyRetryActive(this));
        updateLearnedMailboxTiming();
        updatePagerSummary();
        updateTechnicalStatus();
        updatePermissionStatus();
        syncControlSwitches();
        if (hasBluetoothPermissions()) {
            PagerRelayService.resumeEnabledModes(this);
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (sendCountdownAtMs > nowMs || policyCountdownAtMs > nowMs) {
            restartCountdownTicker();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            updatePermissionStatus();
        }
    }

    @Override
    protected void onStop() {
        countdownHandler.removeCallbacks(countdownRunnable);
        if (deviceScanner != null) {
            deviceScanner.stop();
        }
        if (deviceChooserDialog != null) {
            deviceChooserDialog.dismiss();
        }
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    private View createContent() {
        int padding = dp(16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView heading = text(getString(R.string.app_name), 24, true);
        content.addView(heading);
        content.addView(text("Supports Xteink models X3 and X4 running CrossPoint Pager firmware.", 15, true));
        content.addView(text("Forwards new Android notifications to one explicitly selected Xteink. It does not use traditional Bluetooth pairing, retain notification content, or send notifications over the internet.", 15, false));

        TextView pagerStatusHeading = text("Pager status", 20, true);
        pagerStatusHeading.setPadding(0, dp(16), 0, 0);
        content.addView(pagerStatusHeading);
        choosePager = button(getString(R.string.choose_xteink));
        choosePager.setOnClickListener(view -> showDeviceChooser());
        content.addView(choosePager);
        pagerSummary = text("", 15, false);
        content.addView(pagerSummary);
        TextView learnedTimingHeading = text("Stored mailbox timing", 17, true);
        content.addView(learnedTimingHeading);
        learnedMailboxTiming = text("", 15, false);
        content.addView(learnedMailboxTiming);
        refreshPolicy = button(getString(R.string.refresh_pager_policy));
        refreshPolicy.setOnClickListener(view -> {
            if (policyRetryActive) {
                updateOperationButtons(sendRetryActive, false);
                PagerRelayService.cancelPolicyRead(this);
            } else {
                readPagerStatus();
            }
        });
        content.addView(refreshPolicy);
        autoUpdatePolicySwitch = switchControl(getString(R.string.auto_update_pager_policy),
                RelayPreferences.isAutoUpdatePagerPolicy(this));
        autoUpdatePolicySwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (!updatingControlSwitches) {
                RelayPreferences.setAutoUpdatePagerPolicy(this, enabled);
            }
        });
        content.addView(autoUpdatePolicySwitch);
        content.addView(text(getString(R.string.auto_update_pager_policy_summary), 14, false));
        policyStatus = text("Pager policy refresh is idle.", 15, false);
        policyStatus.setPadding(0, dp(8), 0, 0);
        content.addView(policyStatus);
        enrollmentResetAdvice = text(getString(R.string.pager_enrollment_reset_advice), 14, false);
        enrollmentResetAdvice.setPadding(0, dp(8), 0, 0);
        content.addView(enrollmentResetAdvice);
        forgetPager = button(getString(R.string.forget_stored_xteink));
        forgetPager.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.forget_stored_xteink)
                .setMessage("This removes the enrolled reader from the app. Reset Enrolled Device on Xteink before syncing it again.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forget_stored_xteink,
                        (dialog, which) -> PagerRelayService.forgetPager(this))
                .show());
        content.addView(forgetPager);
        updateLearnedMailboxTiming();
        updatePagerSummary();

        TextView permissionsHeading = text("App access", 20, true);
        permissionsHeading.setPadding(0, dp(16), 0, 0);
        content.addView(permissionsHeading);
        bluetoothPermissionStatus = text("", 15, true);
        content.addView(bluetoothPermissionStatus);
        bluetoothPermissionAction = button("Grant Bluetooth access");
        bluetoothPermissionAction.setOnClickListener(view -> requestBluetoothPermissions());
        content.addView(bluetoothPermissionAction);
        notificationPermissionStatus = text("", 15, true);
        content.addView(notificationPermissionStatus);
        notificationPermissionAction = button("Open notification access");
        notificationPermissionAction.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        content.addView(notificationPermissionAction);
        updatePermissionStatus();

        TextView connectionHeading = text("Connection mode", 20, true);
        connectionHeading.setPadding(0, dp(16), 0, 0);
        content.addView(connectionHeading);
        content.addView(text("Connect per message queues the latest update for known mailbox windows. Use Keep connected only with Xteink Always Available mode when measuring advertising idle versus connected idle.", 15, false));
        content.addView(connectionModePicker());

        notificationRelaySwitch = switchControl(getString(R.string.notification_relay),
                RelayPreferences.isEnabled(this));
        notificationRelaySwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (updatingControlSwitches) {
                return;
            }
            if (!enabled) {
                PagerRelayService.stopRelay(this);
                return;
            }
            if (!RelayPreferences.isPagerReadyForUse(this)) {
                setControlSwitchChecked(notificationRelaySwitch, false);
                status.setText(R.string.refresh_policy_before_relay);
                return;
            }
            if (!hasBluetoothPermissions()) {
                setControlSwitchChecked(notificationRelaySwitch, false);
                requestBluetoothPermissions();
                return;
            }
            if (!hasNotificationAccess()) {
                setControlSwitchChecked(notificationRelaySwitch, false);
                status.setText(R.string.notification_access_required);
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return;
            }
            PagerRelayService.startRelay(this);
        });
        content.addView(notificationRelaySwitch);

        LinearLayout debugContent = new LinearLayout(this);
        debugContent.setOrientation(LinearLayout.VERTICAL);
        debugContent.setVisibility(View.GONE);
        Button debugToggle = button(getString(R.string.debug_collapsed));
        debugToggle.setOnClickListener(view -> {
            boolean expanded = debugContent.getVisibility() == View.VISIBLE;
            debugContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
            debugToggle.setText(expanded ? R.string.debug_collapsed : R.string.debug_expanded);
        });
        content.addView(debugToggle);
        content.addView(debugContent);

        beatModeSwitch = switchControl(getString(R.string.beat_mode), RelayPreferences.isBeatEnabled(this));
        beatModeSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (updatingControlSwitches) {
                return;
            }
            if (!enabled) {
                PagerRelayService.stopBeat(this);
                return;
            }
            if (!RelayPreferences.isPagerReadyForUse(this)) {
                setControlSwitchChecked(beatModeSwitch, false);
                status.setText(R.string.refresh_policy_before_relay);
                return;
            }
            if (!hasBluetoothPermissions()) {
                setControlSwitchChecked(beatModeSwitch, false);
                requestBluetoothPermissions();
                return;
            }
            PagerRelayService.startBeat(this);
        });
        debugContent.addView(beatModeSwitch);

        TextView technicalHeading = text("BLE details", 20, true);
        technicalHeading.setPadding(0, dp(16), 0, 0);
        debugContent.addView(technicalHeading);
        technicalStatus = text("", 14, false);
        debugContent.addView(technicalStatus);
        updateTechnicalStatus();

        TextView testHeading = text("Test page", 20, true);
        testHeading.setPadding(0, dp(16), 0, 0);
        debugContent.addView(testHeading);
        debugContent.addView(text("This uses the same title, message, footer payload and authenticated delivery check as tools/ble-pager-test. Refresh pager policy separately to complete enrollment or replace stored status.", 15, false));
        testConnectionMode = text("", 15, true);
        testConnectionMode.setPadding(0, dp(8), 0, dp(4));
        debugContent.addView(testConnectionMode);
        updateTestConnectionMode();

        title = field("Title", false);
        message = field("Message", true);
        footer = field("Footer", false);
        debugContent.addView(title);
        debugContent.addView(message);
        debugContent.addView(footer);

        byteCount = text("", 14, false);
        debugContent.addView(byteCount);
        send = button(getString(R.string.send_pager_update));
        send.setOnClickListener(view -> {
            if (sendRetryActive) {
                updateOperationButtons(false, policyRetryActive);
                PagerRelayService.cancelSend(this);
            } else {
                sendTestPayload();
            }
        });
        debugContent.addView(send);

        status = text("Grant Bluetooth permissions, put the reader in Pager standby, then send a test update.", 15, false);
        status.setPadding(0, dp(8), 0, 0);
        debugContent.addView(status);

        TextView logHeading = text("Event log", 20, true);
        logHeading.setPadding(0, dp(16), 0, 0);
        debugContent.addView(logHeading);
        debugContent.addView(text("Choose which events appear below.", 14, false));
        Switch notificationLogSwitch = switchControl(getString(R.string.notification_relay),
                RelayPreferences.showNotificationRelayLog(this));
        notificationLogSwitch.setOnCheckedChangeListener((view, show) -> {
            RelayPreferences.setShowNotificationRelayLog(this, show);
            renderEventLog();
        });
        debugContent.addView(notificationLogSwitch);
        Switch beatLogSwitch = switchControl(getString(R.string.beat_mode),
                RelayPreferences.showBeatModeLog(this));
        beatLogSwitch.setOnCheckedChangeListener((view, show) -> {
            RelayPreferences.setShowBeatModeLog(this, show);
            renderEventLog();
        });
        debugContent.addView(beatLogSwitch);
        Button clearLog = button(getString(R.string.clear_log));
        clearLog.setOnClickListener(view -> {
            statusLines.clear();
            renderEventLog();
        });
        debugContent.addView(clearLog);
        statusLog = text("", 13, false);
        debugContent.addView(statusLog);
        renderEventLog();

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePayloadState(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        title.addTextChangedListener(watcher);
        message.addTextChangedListener(watcher);
        footer.addTextChangedListener(watcher);
        updateOperationButtons(RelayPreferences.isSendRetryActive(this),
                RelayPreferences.isPolicyRetryActive(this));
        updatePayloadState();

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void recordStatus(String value, long targetCountdownAtMs, EventLogCategory category,
                              boolean policyMessage) {
        if (value == null || value.isEmpty()) {
            return;
        }
        updateLearnedMailboxTiming();
        updatePagerSummary();
        updateTechnicalStatus();
        updatePermissionStatus();
        if (policyMessage && value.startsWith("Pager policy\n")) {
            policyReceivedThisSession = true;
            policyRefreshMissed = false;
        } else if (policyMessage && value.contains("Continuing policy scan.")) {
            policyReceivedThisSession = false;
            policyRefreshMissed = true;
        }
        updateEnrollmentResetAdvice();
        if (targetCountdownAtMs > 0L) {
            startCountdown(value, targetCountdownAtMs, policyMessage);
            return;
        }
        stopCountdown(policyMessage);
        TextView destination = policyMessage ? policyStatus : status;
        if (destination != null) {
            destination.setText(value);
        }
        statusLines.add(0, new LogEntry(category,
                String.format(java.util.Locale.US, "%tT  %s", new java.util.Date(), value)));
        while (statusLines.size() > MAX_STATUS_LINES) {
            statusLines.remove(statusLines.size() - 1);
        }
        renderEventLog();
    }

    private void startCountdown(String prefix, long targetCountdownAtMs, boolean policyMessage) {
        if (policyMessage) {
            policyCountdownPrefix = prefix;
            policyCountdownAtMs = targetCountdownAtMs;
        } else {
            sendCountdownPrefix = prefix;
            sendCountdownAtMs = targetCountdownAtMs;
        }
        restartCountdownTicker();
    }

    private void stopCountdown(boolean policyMessage) {
        if (policyMessage) {
            policyCountdownPrefix = null;
            policyCountdownAtMs = 0L;
        } else {
            sendCountdownPrefix = null;
            sendCountdownAtMs = 0L;
        }
        restartCountdownTicker();
    }

    private void restartCountdownTicker() {
        countdownHandler.removeCallbacks(countdownRunnable);
        countdownHandler.post(countdownRunnable);
    }

    private boolean updateCountdown(TextView destination, String prefix, long targetAtMs, long nowMs) {
        if (destination == null || prefix == null || targetAtMs == 0L) {
            return false;
        }
        long remainingMs = Math.max(0L, targetAtMs - nowMs);
        destination.setText(getString(R.string.status_countdown, prefix, countdownText(remainingMs)));
        return remainingMs > 0L;
    }

    private String countdownText(long remainingMs) {
        if (remainingMs <= 0L) {
            return "Scanning now.";
        }
        long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
        if (seconds < 60L) {
            return "Scanning in " + seconds + " s.";
        }
        long minutes = seconds / 60L;
        long remainderSeconds = seconds % 60L;
        return remainderSeconds == 0L
                ? "Scanning in " + minutes + " min."
                : "Scanning in " + minutes + " min " + remainderSeconds + " s.";
    }

    private void renderEventLog() {
        if (statusLog == null) {
            return;
        }
        boolean showNotificationRelay = RelayPreferences.showNotificationRelayLog(this);
        boolean showBeatMode = RelayPreferences.showBeatModeLog(this);
        StringBuilder visibleLog = new StringBuilder();
        for (LogEntry entry : statusLines) {
            boolean visible = entry.category == EventLogCategory.NOTIFICATION_RELAY
                    ? showNotificationRelay
                    : showBeatMode;
            if (!visible) {
                continue;
            }
            if (visibleLog.length() > 0) {
                visibleLog.append("\n\n");
            }
            visibleLog.append(entry.text);
        }
        statusLog.setText(visibleLog);
        statusLog.setVisibility(visibleLog.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void updateLearnedMailboxTiming() {
        if (learnedMailboxTiming == null) {
            return;
        }
        long intervalMs = RelayPreferences.mailboxIntervalMs(this);
        long windowMs = RelayPreferences.mailboxWindowMs(this);
        long savedNextWindowMs = RelayPreferences.mailboxNextWindowWallClockMs(this);
        if ("always".equals(RelayPreferences.pagerAvailability(this))) {
            learnedMailboxTiming.setText(R.string.mailbox_timing_not_used);
            updateEnrollmentResetAdvice();
            return;
        }
        if (intervalMs <= 0L || windowMs <= 0L || savedNextWindowMs <= 0L) {
            learnedMailboxTiming.setText(R.string.learned_mailbox_timing_unavailable);
            updateEnrollmentResetAdvice();
            return;
        }
        long nextWindowMs = nextExpectedWindowWallClockMs(intervalMs, windowMs, savedNextWindowMs);
        String nextWindow = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
                .format(new java.util.Date(nextWindowMs));
        learnedMailboxTiming.setText(getString(R.string.learned_mailbox_timing_summary,
                durationText(intervalMs), durationText(windowMs), nextWindow));
        updateEnrollmentResetAdvice();
    }

    private void updatePagerSummary() {
        if (pagerSummary == null) {
            return;
        }
        String model = RelayPreferences.pagerModel(this);
        String deviceId = RelayPreferences.pagerDeviceId(this);
        String device;
        if (PagerProtocol.isValidDeviceId(deviceId)) {
            String modelSuffix = "X3".equals(model) || "X4".equals(model) ? " " + model : "";
            device = "Xteink" + modelSuffix + " · "
                    + deviceId.substring(deviceId.length() - 6).toUpperCase(java.util.Locale.US);
        } else {
            String selectedLabel = RelayPreferences.pagerSelectedLabel(this);
            device = RelayPreferences.hasPagerSelection(this)
                    ? (selectedLabel.isEmpty() ? "Selected Xteink · Not confirmed" : selectedLabel + " · Not confirmed")
                    : "Not selected";
        }

        String availability = RelayPreferences.pagerAvailability(this);
        String availabilityText;
        if ("mailbox".equals(availability)) {
            availabilityText = PagerProtocol.availabilityLabel(true,
                    RelayPreferences.pagerPolicyIntervalMs(this));
        } else if ("always".equals(availability)) {
            availabilityText = PagerProtocol.availabilityLabel(false, 0L);
        } else {
            availabilityText = "Unknown";
        }
        long lastSyncMs = RelayPreferences.pagerLastSyncWallClockMs(this);
        String lastSync = lastSyncMs > 0L
                ? java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                        .format(new java.util.Date(lastSyncMs))
                : "Never";
        boolean enrollmentReady = RelayPreferences.pagerEnrolled(this) && RelayPreferences.hasPagerIdentity(this);
        pagerSummary.setText("Device: " + device
                + "\nEnrollment: " + (enrollmentReady ? "Ready" : "Setup required")
                + "\nAvailability: " + availabilityText
                + "\nLast policy refresh: " + lastSync);
        if (forgetPager != null) {
            forgetPager.setVisibility(RelayPreferences.hasPagerSelection(this) ? View.VISIBLE : View.GONE);
        }
        if (choosePager != null) {
            choosePager.setText(RelayPreferences.hasPagerSelection(this)
                    ? R.string.change_xteink
                    : R.string.choose_xteink);
        }
    }

    private void updateTechnicalStatus() {
        if (technicalStatus == null) {
            return;
        }
        String savedStatus = RelayPreferences.pagerTechnicalStatus(this);
        technicalStatus.setText(savedStatus.isEmpty() ? getString(R.string.ble_details_unavailable) : savedStatus);
    }

    private void updatePermissionStatus() {
        boolean bluetoothReady = hasBluetoothPermissions();
        if (bluetoothPermissionStatus != null) {
            bluetoothPermissionStatus.setText(bluetoothReady
                    ? R.string.bluetooth_access_ready
                    : R.string.bluetooth_access_required);
        }
        if (bluetoothPermissionAction != null) {
            bluetoothPermissionAction.setVisibility(bluetoothReady ? View.GONE : View.VISIBLE);
        }

        boolean notificationsReady = hasNotificationAccess();
        if (notificationPermissionStatus != null) {
            notificationPermissionStatus.setText(notificationsReady
                    ? R.string.notification_access_ready
                    : R.string.notification_access_required_status);
        }
        if (notificationPermissionAction != null) {
            notificationPermissionAction.setVisibility(notificationsReady ? View.GONE : View.VISIBLE);
        }
    }

    private void updateEnrollmentResetAdvice() {
        if (enrollmentResetAdvice == null) {
            return;
        }
        String availability = RelayPreferences.pagerAvailability(this);
        boolean policyMissing = availability.isEmpty();
        boolean timingMissing = "mailbox".equals(availability) && (RelayPreferences.mailboxIntervalMs(this) <= 0L
                || RelayPreferences.mailboxWindowMs(this) <= 0L
                || RelayPreferences.mailboxNextWindowWallClockMs(this) <= 0L);
        boolean visible = RelayPreferences.hasPagerSelection(this)
                && !policyReceivedThisSession
                && (policyMissing || timingMissing || policyRefreshMissed);
        enrollmentResetAdvice.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private long nextExpectedWindowWallClockMs(long intervalMs, long windowMs, long savedNextWindowMs) {
        long nowMs = System.currentTimeMillis();
        if (savedNextWindowMs - nowMs > intervalMs) {
            return nowMs;
        }
        if (nowMs < savedNextWindowMs + windowMs) {
            return savedNextWindowMs;
        }
        long elapsedAfterWindowMs = nowMs - (savedNextWindowMs + windowMs);
        long elapsedIntervals = elapsedAfterWindowMs / intervalMs + 1L;
        return savedNextWindowMs + elapsedIntervals * intervalMs;
    }

    private String durationText(long durationMs) {
        if (durationMs % 60_000L == 0L) {
            long minutes = durationMs / 60_000L;
            return getResources().getQuantityString(R.plurals.duration_minutes, (int) minutes, minutes);
        }
        if (durationMs % 1000L == 0L) {
            long seconds = durationMs / 1000L;
            return getResources().getQuantityString(R.plurals.duration_seconds, (int) seconds, seconds);
        }
        return getString(R.string.duration_milliseconds, durationMs);
    }

    private void syncControlSwitches() {
        updatingControlSwitches = true;
        if (notificationRelaySwitch != null) {
            notificationRelaySwitch.setChecked(RelayPreferences.isEnabled(this));
        }
        if (beatModeSwitch != null) {
            beatModeSwitch.setChecked(RelayPreferences.isBeatEnabled(this));
        }
        if (autoUpdatePolicySwitch != null) {
            autoUpdatePolicySwitch.setChecked(RelayPreferences.isAutoUpdatePagerPolicy(this));
        }
        updatingControlSwitches = false;
    }

    private void setControlSwitchChecked(Switch control, boolean checked) {
        updatingControlSwitches = true;
        control.setChecked(checked);
        updatingControlSwitches = false;
    }

    private RadioGroup connectionModePicker() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, 0, 0, dp(8));

        RadioButton connectPerMessage = radioButton("Connect per message");
        RadioButton keepConnected = radioButton("Keep connected while relay is active");
        group.addView(connectPerMessage);
        group.addView(keepConnected);
        group.check(RelayPreferences.shouldKeepConnected(this) ? keepConnected.getId() : connectPerMessage.getId());
        group.setOnCheckedChangeListener((view, checkedId) -> {
            boolean shouldKeepConnected = checkedId == keepConnected.getId();
            RelayPreferences.setKeepConnected(this, shouldKeepConnected);
            updateTestConnectionMode();
            if (hasBluetoothPermissions()) {
                PagerRelayService.applyConnectionMode(this);
            }
        });
        return group;
    }

    private void updateTestConnectionMode() {
        if (testConnectionMode == null) {
            return;
        }
        testConnectionMode.setText(RelayPreferences.shouldKeepConnected(this)
                ? R.string.test_mode_keep_connected
                : R.string.test_mode_connect_per_message);
    }

    private void sendTestPayload() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        String payload = currentPayload();
        if (!PagerProtocol.isValidTestPayload(payload)) {
            return;
        }
        if (!RelayPreferences.isPagerReadyForUse(this)) {
            status.setText(R.string.refresh_policy_before_relay);
            return;
        }
        updateOperationButtons(true, policyRetryActive);
        PagerRelayService.send(this, payload);
    }

    private void readPagerStatus() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (!RelayPreferences.hasPagerSelection(this)) {
            policyStatus.setText(R.string.choose_xteink_before_refresh);
            return;
        }
        policyReceivedThisSession = false;
        policyRefreshMissed = false;
        updateEnrollmentResetAdvice();
        updateOperationButtons(sendRetryActive, true);
        PagerRelayService.readStatus(this);
    }

    private void updatePayloadState() {
        String payload = currentPayload();
        int bytes = PagerProtocol.utf8Length(payload);
        byteCount.setText(getString(R.string.payload_byte_count, bytes, PagerProtocol.MAX_DISPLAY_PAYLOAD_BYTES));
        send.setEnabled(sendRetryActive
                || PagerProtocol.isValidTestPayload(payload) && RelayPreferences.isPagerReadyForUse(this));
    }

    private void updateOperationButtons(boolean sendActive, boolean policyActive) {
        sendRetryActive = sendActive;
        policyRetryActive = policyActive;
        if (send != null) {
            send.setText(sendActive ? R.string.stop_pager_update_retry : R.string.send_pager_update);
        }
        if (refreshPolicy != null) {
            refreshPolicy.setText(policyActive ? R.string.stop_policy_retry : R.string.refresh_pager_policy);
        }
        if (send != null && title != null && message != null && footer != null) {
            updatePayloadState();
        }
    }

    private String currentPayload() {
        return PagerProtocol.testPayload(title == null ? "" : title.getText().toString(),
                message == null ? "" : message.getText().toString(),
                footer == null ? "" : footer.getText().toString());
    }

    private void showDeviceChooser() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (deviceChooserDialog != null) {
            deviceChooserDialog.dismiss();
        }

        LinearLayout chooserContent = new LinearLayout(this);
        chooserContent.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        chooserContent.setPadding(padding, 0, padding, 0);
        TextView scanStatus = text(getString(R.string.xteink_scan_starting), 14, false);
        chooserContent.addView(scanStatus);
        ListView deviceList = new ListView(this);
        deviceChoiceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        deviceList.setAdapter(deviceChoiceAdapter);
        chooserContent.addView(deviceList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        deviceScanner = new PagerDeviceScanner(this, new PagerDeviceScanner.Listener() {
            @Override
            public void onScanStarted() {
                scanStatus.setText(R.string.xteink_scan_running);
            }

            @Override
            public void onDevicesChanged(List<PagerDeviceScanner.DeviceCandidate> devices) {
                discoveredDevices.clear();
                discoveredDevices.addAll(devices);
                deviceChoiceAdapter.clear();
                String selectedAddress = RelayPreferences.pagerBluetoothAddress(MainActivity.this);
                for (PagerDeviceScanner.DeviceCandidate device : devices) {
                    deviceChoiceAdapter.add(device.displayLabel(device.address.equalsIgnoreCase(selectedAddress)));
                }
                deviceChoiceAdapter.notifyDataSetChanged();
                if (!devices.isEmpty()) {
                    scanStatus.setText(getResources().getQuantityString(
                            R.plurals.xteink_devices_found, devices.size(), devices.size()));
                }
            }

            @Override
            public void onScanStopped(String message) {
                scanStatus.setText(message);
            }
        });

        deviceChooserDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_xteink)
                .setView(chooserContent)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.scan_again, null)
                .create();
        deviceChooserDialog.setOnShowListener(dialog -> deviceChooserDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> deviceScanner.start()));
        deviceChooserDialog.setOnDismissListener(dialog -> {
            if (deviceScanner != null) {
                deviceScanner.stop();
            }
            deviceScanner = null;
            deviceChooserDialog = null;
        });
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < discoveredDevices.size()) {
                selectDiscoveredDevice(discoveredDevices.get(position));
            }
        });
        deviceChooserDialog.show();
        deviceScanner.start();
    }

    private void selectDiscoveredDevice(PagerDeviceScanner.DeviceCandidate candidate) {
        String storedAddress = RelayPreferences.pagerBluetoothAddress(this);
        if (candidate.address.equalsIgnoreCase(storedAddress)) {
            policyStatus.setText(R.string.xteink_already_selected);
            deviceChooserDialog.dismiss();
            return;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(storedAddress)) {
            applySelectedDevice(candidate);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.change_xteink)
                .setMessage(R.string.change_xteink_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.change_xteink,
                        (dialog, which) -> applySelectedDevice(candidate))
                .show();
    }

    private void applySelectedDevice(PagerDeviceScanner.DeviceCandidate candidate) {
        if (deviceChooserDialog != null) {
            deviceChooserDialog.dismiss();
        }
        policyReceivedThisSession = false;
        policyRefreshMissed = false;
        PagerRelayService.selectPager(this, candidate.address, candidate.storedLabel());
        syncControlSwitches();
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothPermissions()) {
            return;
        }
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private boolean hasBluetoothPermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationAccess() {
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return !TextUtils.isEmpty(listeners)
                && listeners.contains(new ComponentName(this, NotificationRelayService.class).flattenToString());
    }

    private TextView text(String value, int sizeSp, boolean heading) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (heading) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private EditText field(String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(16);
        if (multiline) {
            field.setMinLines(3);
            field.setGravity(Gravity.TOP | Gravity.START);
        } else {
            field.setSingleLine();
        }
        field.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private RadioButton radioButton(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(16);
        return button;
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch switchControl(String label, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(16);
        control.setChecked(checked);
        control.setPadding(0, dp(4), 0, dp(4));
        return control;
    }

    private static final class LogEntry {
        final EventLogCategory category;
        final String text;

        LogEntry(EventLogCategory category, String text) {
            this.category = category;
            this.text = text;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
