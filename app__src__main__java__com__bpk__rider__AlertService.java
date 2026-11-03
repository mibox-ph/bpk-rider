package com.bpk.rider;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Keeps checking the server while the rider is on duty and sounds an alarm when an assigned order is ready. */
public class AlertService extends Service {
    private static final String CH_ONGOING = "ongoing";
    private static final String CH_ORDERS = "orders_v1";
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wl;
    private final Set<Integer> known = new HashSet<>();
    private final Map<Integer, Long> lastAlert = new HashMap<>();

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        makeChannels();
        Notification n = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("BPK Rider: on duty")
                .setContentText("Waiting for orders. Keep this on while you work.")
                .setContentIntent(openApp())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bpkrider:poll"); wl.acquire(); }
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try { poll(); } catch (Exception ignored) { }
                        try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    }
                }
            });
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void makeChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel on = new NotificationChannel(CH_ONGOING, "On duty", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(on);
        NotificationChannel oc = new NotificationChannel(CH_ORDERS, "Order alerts", NotificationManager.IMPORTANCE_HIGH);
        Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        oc.setSound(alarm, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        oc.enableVibration(true);
        oc.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
        oc.setBypassDnd(true);
        nm.createNotificationChannel(oc);
    }

    private void alert(int id, String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(this, CH_ORDERS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp())
                .setAutoCancel(false)
                .build();
        nm.notify(id, n);
    }

    private void poll() throws Exception {
        SharedPreferences prefs = getSharedPreferences("bpk_rider", MODE_PRIVATE);
        String token = prefs.getString("token", "");
        if (token.isEmpty()) { stopSelf(); return; }
        HttpURLConnection c = (HttpURLConnection) new URL(MainActivity.SERVER + "/api/rider/orders").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code == 401) { prefs.edit().remove("token").putBoolean("duty", false).apply(); stopSelf(); return; }
        if (code != 200) return;
        InputStream in = c.getInputStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        JSONArray arr = new JSONObject(sb.toString()).getJSONArray("active");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Set<Integer> now = new HashSet<>();
        Set<Integer> waiting = new HashSet<>();
        long t = System.currentTimeMillis();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            int id = o.getInt("id");
            now.add(id);
            String no = o.optString("order_no");
            String desc = no + " · " + o.optString("name") + "\n" + o.optString("address");
            boolean ready = o.optBoolean("ready");
            boolean assigned = "assigned".equals(o.optString("rider_status"));
            if (!known.contains(id) && !(ready && assigned)) alert(1000 + id, "New order for you: " + no, desc);
            if (ready && assigned) {
                waiting.add(id);
                Long last = lastAlert.get(id);
                if (last == null || t - last > 30000) {
                    alert(2000 + id, "ORDER READY: " + no, desc + "\nOpen the app and tap Received.");
                    lastAlert.put(id, t);
                }
            }
        }
        Iterator<Integer> it = lastAlert.keySet().iterator();
        while (it.hasNext()) {
            int id = it.next();
            if (!waiting.contains(id)) { nm.cancel(2000 + id); it.remove(); }
        }
        for (int id : known) if (!now.contains(id)) nm.cancel(1000 + id);
        known.clear();
        known.addAll(now);
    }
}
