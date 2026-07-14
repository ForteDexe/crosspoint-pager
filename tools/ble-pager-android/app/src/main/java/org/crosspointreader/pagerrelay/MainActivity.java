package org.crosspointreader.pagerrelay;

import android.Manifest;
import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
    private TextView statusLog;
    private Button send;
    private BroadcastReceiver statusReceiver;
    private final List<String> statusLines = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                recordStatus(intent.getStringExtra(PagerRelayService.EXTRA_STATUS));
            }
        };
        requestBluetoothPermissions();
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
    }

    @Override
    protected void onStop() {
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
        content.addView(text("Forwards new Android notifications to the opt-in BLE Pager service. It does not pair, store, or send notifications over the internet.", 15, false));

        Button permissions = button("Grant Bluetooth permissions");
        permissions.setOnClickListener(view -> requestBluetoothPermissions());
        content.addView(permissions);

        Button notificationAccess = button("Open notification access settings");
        notificationAccess.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        content.addView(notificationAccess);

        TextView connectionHeading = text("Connection mode", 20, true);
        connectionHeading.setPadding(0, dp(16), 0, 0);
        content.addView(connectionHeading);
        content.addView(text("Connect per message queues the latest update for known mailbox windows. Use Keep connected only with X3 Always Available mode when measuring advertising idle versus connected idle.", 15, false));
        content.addView(connectionModePicker());

        Button startRelay = button("Start notification relay");
        startRelay.setOnClickListener(view -> {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions();
                return;
            }
            if (!hasNotificationAccess()) {
                status.setText(R.string.notification_access_required);
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return;
            }
            PagerRelayService.startRelay(this);
        });
        content.addView(startRelay);

        Button stopRelay = button("Stop notification relay");
        stopRelay.setOnClickListener(view -> PagerRelayService.stopRelay(this));
        content.addView(stopRelay);

        Button startBeat = button("Start beat mode");
        startBeat.setOnClickListener(view -> {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions();
                return;
            }
            PagerRelayService.startBeat(this);
        });
        content.addView(startBeat);

        Button stopBeat = button("Stop beat mode");
        stopBeat.setOnClickListener(view -> PagerRelayService.stopBeat(this));
        content.addView(stopBeat);

        TextView testHeading = text("Test page", 20, true);
        testHeading.setPadding(0, dp(16), 0, 0);
        content.addView(testHeading);
        content.addView(text("This uses the same title, message, footer payload and policy read as tools/ble-pager-test. If X3 setup is open, Refresh pager policy stores its setup token locally.", 15, false));

        Button refreshPolicy = button("Refresh pager policy");
        refreshPolicy.setOnClickListener(view -> readPagerStatus());
        content.addView(refreshPolicy);

        title = field("Title", false);
        message = field("Message", true);
        footer = field("Footer", false);
        content.addView(title);
        content.addView(message);
        content.addView(footer);

        byteCount = text("", 14, false);
        content.addView(byteCount);
        send = button("Send pager update");
        send.setOnClickListener(view -> sendTestPayload());
        content.addView(send);

        status = text("Grant Bluetooth permissions, put the reader in Pager standby, then send a test update.", 15, false);
        status.setPadding(0, dp(8), 0, 0);
        content.addView(status);

        TextView logHeading = text("Event log", 20, true);
        logHeading.setPadding(0, dp(16), 0, 0);
        content.addView(logHeading);
        statusLog = text("", 13, false);
        content.addView(statusLog);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePayloadState(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        title.addTextChangedListener(watcher);
        message.addTextChangedListener(watcher);
        footer.addTextChangedListener(watcher);
        updatePayloadState();

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void recordStatus(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        status.setText(value);
        statusLines.add(0, String.format(java.util.Locale.US, "%tT  %s", new java.util.Date(), value));
        while (statusLines.size() > MAX_STATUS_LINES) {
            statusLines.remove(statusLines.size() - 1);
        }
        if (statusLog != null) {
            statusLog.setText(String.join("\n\n", statusLines));
        }
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
            if (status != null) {
                status.setText(shouldKeepConnected
                        ? "Keep-connected mode will hold the BLE link after Start notification relay."
                        : "Connection mode: connect per message.");
            }
            if (RelayPreferences.isEnabled(this) && hasBluetoothPermissions()) {
                PagerRelayService.applyConnectionMode(this);
            }
        });
        return group;
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
        PagerRelayService.send(this, payload);
    }

    private void readPagerStatus() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        PagerRelayService.readStatus(this);
    }

    private void updatePayloadState() {
        String payload = currentPayload();
        int bytes = PagerProtocol.utf8Length(payload);
        byteCount.setText(getString(R.string.payload_byte_count, bytes, PagerProtocol.MAX_DISPLAY_PAYLOAD_BYTES));
        send.setEnabled(PagerProtocol.isValidTestPayload(payload));
    }

    private String currentPayload() {
        return PagerProtocol.testPayload(title == null ? "" : title.getText().toString(),
                message == null ? "" : message.getText().toString(),
                footer == null ? "" : footer.getText().toString());
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
