package com.deepsek.suvo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * WebView sirf display hota hai yahan; owner Service/WebViewManager hai.
 * onStart -> attach, onStop -> detach + background park.
 */
public class MainActivity extends Activity implements AppState.Listener {

    private FrameLayout webContainer;
    private TextView tvStatus;
    private TextView tvEndpoint;
    private TextView tvLog;
    private Button btnService;
    private Button btnOverlay;
    private Button btnBattery;
    private Button btnAccounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webContainer = findViewById(R.id.webContainer);
        tvStatus = findViewById(R.id.tvStatus);
        tvEndpoint = findViewById(R.id.tvEndpoint);
        tvLog = findViewById(R.id.tvLog);
        btnService = findViewById(R.id.btnService);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnBattery = findViewById(R.id.btnBattery);
        Button btnSettings = findViewById(R.id.btnSettings);
        Button btnReload = findViewById(R.id.btnReload);
        btnAccounts = findViewById(R.id.btnAccounts);

        btnService.setOnClickListener(v -> toggleService());
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnBattery.setOnClickListener(v -> requestBatteryExemption());
        btnSettings.setOnClickListener(v -> showSettings());
        btnReload.setOnClickListener(v -> WebViewManager.get().reload());
        btnAccounts.setOnClickListener(v -> showAccounts());
        tvEndpoint.setOnClickListener(v -> copyEndpoint());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }

        if (!DeepSeekService.isRunning()) DeepSeekService.start(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        WebViewManager.get().attach(this, webContainer);
        AppState.addListener(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // overlay/battery permission wapas aakar update
    }

    @Override
    protected void onStop() {
        AppState.removeListener(this);
        WebViewManager.get().detach();
        super.onStop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        DeepSeekWebView w = WebViewManager.get().current();
        if (w != null && w.canGoBack()) {
            w.goBack();
        } else {
            moveTaskToBack(true); // Activity band mat karo, sirf background
        }
    }

    @Override
    public void onStateChanged() {
        render();
    }

    private String endpoint() {
        String host = Config.allowLan(this) ? "<device-ip>" : "127.0.0.1";
        return "http://" + host + ":" + Config.port(this) + "/v1";
    }

    private void render() {
        boolean running = DeepSeekService.isRunning();
        tvStatus.setText((running ? AppState.serverStatus : "Service stopped")
                + "\n" + AppState.jobStatus + " · served " + AppState.served
                + " · keep-alive: " + AppState.keepAliveMode);
        tvEndpoint.setText(endpoint() + (Config.apiKey(this).isEmpty() ? "" : "   (API key on)"));
        tvLog.setText(AppState.lastLog);
        btnService.setText(running ? "Stop service" : "Start service");
        btnAccounts.setText("👤 " + Config.accountLabel(this, WebViewManager.get().shownId())
                + " (" + Config.accounts(this).size() + ")");

        boolean overlay = Settings.canDrawOverlays(this);
        btnOverlay.setText(overlay ? "Overlay ✓" : "Allow overlay");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        btnBattery.setText(exempt ? "Battery ✓" : "Battery unrestricted");
    }

    private void toggleService() {
        if (DeepSeekService.isRunning()) {
            stopService(new Intent(this, DeepSeekService.class));
        } else {
            DeepSeekService.start(this);
        }
        tvStatus.postDelayed(this::render, 400);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "Overlay settings nahi khule", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("BatteryLife")
    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Battery settings nahi khule", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void copyEndpoint() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("endpoint", endpoint()));
            Toast.makeText(this, "Base URL copied", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------------------------------------------------------- accounts dialog */

    /**
     * Account chuno = woh WebView screen par aata hai (usme login/logout karo).
     * Requests saare accounts par bari-bari (round-robin) chalti hain.
     */
    private void showAccounts() {
        java.util.List<Config.Account> list = Config.accounts(this);
        String[] labels = new String[list.size()];
        int checked = 0;
        String shown = WebViewManager.get().shownId();
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).label;
            if (list.get(i).id.equals(shown)) checked = i;
        }
        final int[] sel = {checked};

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Accounts · requests bari-bari se")
                .setSingleChoiceItems(labels, checked, (d, which) -> sel[0] = which)
                .setPositiveButton("Show", (d, w) -> {
                    WebViewManager.get().show(list.get(sel[0]).id);
                    render();
                })
                .setNeutralButton("+ Add", (d, w) -> {
                    String id = Config.addAccount(this);
                    WebViewManager.get().ensureAll(this);
                    WebViewManager.get().show(id);
                    Toast.makeText(this, Config.accountLabel(this, id)
                            + " bana. Ab neeche WebView mein is account se login karo.", Toast.LENGTH_LONG).show();
                    if (!WebViewManager.multiProfileSupported()) {
                        Toast.makeText(this, "Is phone ka WebView multi-account support nahi karta. "
                                + "Play Store se 'Android System WebView' update karo.", Toast.LENGTH_LONG).show();
                    }
                    render();
                });
        if (list.size() > 1) {
            b.setNegativeButton("Remove", (d, w) -> confirmRemove(list.get(sel[0])));
        }
        b.show();
    }

    private void confirmRemove(Config.Account a) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + a.label + "?")
                .setMessage("Is account ka login aur data is app se hat jayega.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    Config.removeAccount(this, a.id);
                    WebViewManager.get().ensureAll(this);
                    render();
                })
                .show();
    }

    /* ---------------------------------------------------------------- settings dialog */

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams marginParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(8), dp(20), dp(4));
        return lp;
    }

    private void showSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView lPort = new TextView(this);
        lPort.setText("Port");
        root.addView(lPort, marginParams());

        EditText etPort = new EditText(this);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(Config.port(this)));
        root.addView(etPort, marginParams());

        TextView lKey = new TextView(this);
        lKey.setText("API key (khaali = no auth)");
        root.addView(lKey, marginParams());

        EditText etKey = new EditText(this);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etKey.setText(Config.apiKey(this));
        etKey.setHint("sk-anything");
        root.addView(etKey, marginParams());

        CheckBox cbLan = new CheckBox(this);
        cbLan.setText("Allow LAN (0.0.0.0) – same WiFi devices");
        cbLan.setChecked(Config.allowLan(this));
        root.addView(cbLan, marginParams());

        CheckBox cbKeep = new CheckBox(this);
        cbKeep.setText("Background keep-alive (1px overlay)");
        cbKeep.setChecked(Config.keepAlive(this));
        root.addView(cbKeep, marginParams());

        CheckBox cbDelete = new CheckBox(this);
        cbDelete.setText("Response ke baad DeepSeek chat delete karo");
        cbDelete.setChecked(Config.deleteChats(this));
        LinearLayout.LayoutParams last = marginParams();
        last.bottomMargin = dp(12);
        root.addView(cbDelete, last);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    int port;
                    try {
                        port = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        port = -1;
                    }
                    if (port < 1024 || port > 65535) {
                        Toast.makeText(this, "Port 1024–65535 hona chahiye", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (cbLan.isChecked() && etKey.getText().toString().trim().isEmpty()) {
                        Toast.makeText(this, "LAN on hai: API key set karna better hai", Toast.LENGTH_LONG).show();
                    }
                    Config.save(this, port, cbLan.isChecked(), etKey.getText().toString(), cbKeep.isChecked(),
                            cbDelete.isChecked());
                    if (DeepSeekService.isRunning()) {
                        startService(new Intent(this, DeepSeekService.class)
                                .setAction(DeepSeekService.ACTION_RESTART_SERVER));
                    }
                    render();
                })
                .show();
    }
}
