package br.uerj.centrocultural.plataformalivre;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2001;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.addJavascriptInterface(new RadioBridge(), "AndroidRadio");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return external(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return external(Uri.parse(url));
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncWebState();
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void syncWebState() {
        if (webView == null) return;
        boolean active = RadioService.isActive();
        String message = active
                ? "Transmissão ativa. Você pode bloquear a tela."
                : "Toque no botão para iniciar a transmissão.";
        String js = "window.setNativeState && window.setNativeState(" + active + "," + quoteJs(message) + ");";
        webView.evaluateJavascript(js, null);
    }

    private String quoteJs(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private boolean external(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!web) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {}
        return true;
    }

    public class RadioBridge {
        @JavascriptInterface
        public void play(double volume) {
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_PLAY);
            intent.putExtra(RadioService.EXTRA_VOLUME, (float) Math.max(0.0, Math.min(1.0, volume)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        }

        @JavascriptInterface
        public void stop() {
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_STOP);
            startService(intent);
        }

        @JavascriptInterface
        public void setVolume(double volume) {
            if (!RadioService.isActive()) return;
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_SET_VOLUME);
            intent.putExtra(RadioService.EXTRA_VOLUME, (float) Math.max(0.0, Math.min(1.0, volume)));
            startService(intent);
        }

        @JavascriptInterface
        public boolean isActive() {
            return RadioService.isActive();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) syncWebState();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
