package com.bpk.rider;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static final String SERVER = "https://mybpk.com";
    private WebView web;
    private SharedPreferences prefs;
    private volatile String currentUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("bpk_rider", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(), "RiderNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (trusted(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) { currentUrl = url == null ? "" : url; }
        });
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(SERVER + "/rider/");
        if (prefs.getBoolean("duty", false) && !prefs.getString("token", "").isEmpty()) startAlertService();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private boolean trusted(Uri u) {
        return u != null && "https".equals(u.getScheme()) && u.getHost() != null && u.getHost().equalsIgnoreCase(Uri.parse(SERVER).getHost());
    }

    private void startAlertService() {
        Intent i = new Intent(this, AlertService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void setSession(String token) {
            if (!trusted(Uri.parse(currentUrl)) || token == null || !token.matches("^[a-fA-F0-9]{64}$")) return;
            prefs.edit().putString("token", token).apply();
        }

        @JavascriptInterface
        public void clearSession() {
            if (!trusted(Uri.parse(currentUrl))) return;
            prefs.edit().remove("token").putBoolean("duty", false).apply();
            stopService(new Intent(MainActivity.this, AlertService.class));
        }

        @JavascriptInterface
        public void setDuty(final boolean on) {
            if (!trusted(Uri.parse(currentUrl))) return;
            prefs.edit().putBoolean("duty", on).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on && !prefs.getString("token", "").isEmpty()) { askNotifications(); startAlertService(); }
                    else stopService(new Intent(MainActivity.this, AlertService.class));
                }
            });
        }

        @JavascriptInterface
        public boolean batteryOk() {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void batterySetup() {
            if (!trusted(Uri.parse(currentUrl))) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                    }
                }
            });
        }
    }
}
