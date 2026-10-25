package de.heim.tvstart;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;

public class MainActivity extends Activity {

    static final String HOST = "tvstart.local";
    static final String HOME = "https://" + HOST + "/index.html";

    FrameLayout root;
    WebView web;
    CursorView cursor;
    TextView loading;
    View customView;
    WebChromeClient.CustomViewCallback customCb;

    boolean cursorMode = false;
    boolean overlayMode = false;
    boolean tapAllowed = false;
    String tvview = "";
    boolean hintShown = false;
    boolean backLong = false, okDown = false, okLong = false;
    boolean clearHistoryOnLoad = false;
    float density;
    final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(14, 15, 19));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        loading = new TextView(this);
        loading.setText("Lädt …");
        loading.setTextColor(Color.rgb(154, 160, 173));
        loading.setTextSize(22);
        loading.setGravity(android.view.Gravity.CENTER);
        loading.setBackgroundColor(Color.rgb(14, 15, 19));
        loading.setVisibility(View.GONE);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Vorschauvideos sollen nicht alle von selbst loslaufen (bremst stark)
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        tvview = readAsset("tvview.js");
        web.addJavascriptInterface(new Bridge(), "TVApp");
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Nur Webseiten innerhalb der App öffnen, keine fremden Apps/Stores starten
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (!(url.startsWith("http://") || url.startsWith("https://"))) return true;
                if (!r.isForMainFrame() || r.isRedirect()) return false;
                String cur = v.getUrl();
                if (cur == null || isHome(cur) || isHome(url)) return false;
                if (!sameSite(cur, url)) {
                    // Werbe-Weiterleitung auf eine fremde Seite blockieren
                    Toast.makeText(MainActivity.this, "Weiterleitung blockiert", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isAdHost(u.getHost()) || isPreviewClip(u)) {
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                if (p == null || p.equals("/") || p.isEmpty()) p = "/index.html";
                p = p.substring(1);
                String mime = "text/html";
                if (p.endsWith(".js")) mime = "application/javascript";
                else if (p.endsWith(".css")) mime = "text/css";
                else if (p.endsWith(".png")) mime = "image/png";
                try {
                    InputStream in = getAssets().open(p);
                    return new WebResourceResponse(mime, "UTF-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), null);
                }
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                overlayMode = false;
                tapAllowed = true;
                updateMode(url);
                showLoading(!isHome(url));
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                updateMode(url);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                // TV-Ansicht so früh wie möglich einbinden (wartet selbst, bis die Seite bereit ist)
                if (!isHome(url)) { js(AUTO_FULLSCREEN); js(tvview); }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) showLoading(false);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                updateMode(url);
                if (!isHome(url)) { js(AUTO_FULLSCREEN); js(tvview); }
                if (isHome(url)) {
                    if (clearHistoryOnLoad) {
                        clearHistoryOnLoad = false;
                        web.clearHistory();
                    }
                    flushPending();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                root.addView(view, 1, new FrameLayout.LayoutParams(-1, -1));
                cursor.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message msg) {
                // Popups: ohne Klick immer blockieren; mit Klick nur Links derselben Seite im Hauptfenster öffnen
                if (!isUserGesture) return false;
                final WebView tmp = new WebView(MainActivity.this);
                final boolean[] done = {false};
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, String url) {
                        handlePopup(url, done, tmp);
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView w, String url, android.graphics.Bitmap f) {
                        handlePopup(url, done, tmp);
                    }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) msg.obj;
                t.setWebView(tmp);
                msg.sendToTarget();
                return true;
            }
        });
        s.setSupportMultipleWindows(true);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.loadUrl(HOME);
        web.requestFocus();
    }

    boolean isHome(String url) {
        return url != null && url.startsWith("https://" + HOST);
    }

    static final String MV = "(function(){var vs=document.querySelectorAll('video'),v=null,m=-1;for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),a=r.width*r.height;if(a>m){m=a;v=vs[i]}}return v})()";

    static final String AUTO_FULLSCREEN =
            "(function(){if(window.__tvfs)return;window.__tvfs=1;" +
            "document.addEventListener('play',function(e){var v=e.target;" +
            "if(!v||v.tagName!=='VIDEO')return;" +
            "if(document.fullscreenElement||document.webkitFullscreenElement)return;" +
            "try{var f=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen;" +
            "if(f){var p=f.call(v);if(p&&p.catch)p.catch(function(){});}}catch(x){}},true);})()";

    static final String[] AD_HOSTS = {
            "exoclick.com", "exosrv.com", "exdynsrv.com", "trafficjunky.com", "trafficjunky.net",
            "juicyads.com", "popads.net", "popcash.net", "propellerads.com", "realsrv.com",
            "magsrv.com", "tsyndicate.com", "trafficstars.com", "adsterra.com", "hilltopads.net",
            "clickadu.com", "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "adxpansion.com", "plugrush.com", "trafficfactory.biz", "ero-advertising.com",
            "a-ads.com", "onclickads.net", "adcash.com", "twinrdsrv.com", "pemsrv.com",
            "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "hotjar.com",
            "quantserve.com", "criteo.com", "taboola.com", "outbrain.com", "adspyglass.com"
    };

    static boolean isAdHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase();
        for (String d : AD_HOSTS) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    static String baseDomain(String url) {
        String h = Uri.parse(url).getHost();
        if (h == null) return "";
        String[] p = h.toLowerCase().split("\\.");
        if (p.length < 2) return h;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    static boolean sameSite(String a, String b) {
        return baseDomain(a).equals(baseDomain(b));
    }

    void handlePopup(String url, boolean[] done, final WebView tmp) {
        if (done[0] || url == null || url.startsWith("about:")) return;
        done[0] = true;
        String cur = web.getUrl();
        if (cur != null && sameSite(cur, url)) web.loadUrl(url);
        else Toast.makeText(this, "Popup blockiert", Toast.LENGTH_SHORT).show();
        handler.post(new Runnable() {
            @Override
            public void run() {
                tmp.stopLoading();
                tmp.destroy();
            }
        });
    }

    String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Schnittstelle für die TV-Ansicht (tvview.js) */
    class Bridge {
        @android.webkit.JavascriptInterface
        public void overlay(final boolean on) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    overlayMode = on;
                    updateMode(web.getUrl());
                    showLoading(false);
                    if (on) web.requestFocus();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void playAt(final float x, final float y) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    // Nur einmal pro Seite: Player der Seite antippen, damit das Video startet
                    if (!tapAllowed || overlayMode || isHome(web.getUrl())) return;
                    tapAllowed = false;
                    @SuppressWarnings("deprecation")
                    float sc = web.getScale();
                    tapAt(x * sc, y * sc);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void ready() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    showLoading(false);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void saveTile() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (overlayMode) askSaveTile();
                }
            });
        }
    }

    void showMenu() {
        if (isHome(web.getUrl())) return;
        final String[] items = {"TV-Ansicht anzeigen", "Diese Seite als Kachel speichern", "Zur Startseite"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) js("window.__tvv_show&&window.__tvv_show()");
                        else if (w == 1) askSaveTile();
                        else goHome(false);
                    }
                })
                .show();
    }

    final Runnable loadingTimeout = new Runnable() {
        @Override
        public void run() {
            loading.setVisibility(View.GONE);
        }
    };

    void showLoading(boolean on) {
        handler.removeCallbacks(loadingTimeout);
        loading.setVisibility(on ? View.VISIBLE : View.GONE);
        // Spätestens nach 8 Sekunden die Seite zeigen, egal was passiert
        if (on) handler.postDelayed(loadingTimeout, 8000);
    }

    /** Kleine Vorschau-Clips der Vorschaubilder (Hover-Videos) gar nicht erst laden */
    static boolean isPreviewClip(Uri u) {
        String p = u.getPath();
        if (p == null) return false;
        p = p.toLowerCase();
        if (!(p.endsWith(".mp4") || p.endsWith(".webm"))) return false;
        String all = (u.getHost() + p).toLowerCase();
        return all.contains("preview") || all.contains("thumb") || all.contains("trailer")
                || all.contains("teaser") || all.contains("vidthumb") || p.endsWith("_fb.mp4");
    }

    final Runnable hideCursorR = new Runnable() {
        @Override
        public void run() {
            if (customView != null) cursor.setVisibility(View.GONE);
        }
    };

    void showCursorBriefly() {
        if (customView == null) return;
        cursor.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCursorR);
        handler.postDelayed(hideCursorR, 3000);
    }

    void updateMode(String url) {
        boolean c = !isHome(url) && !overlayMode;
        if (c == cursorMode) return;
        cursorMode = c;
        cursor.setVisibility(c ? View.VISIBLE : View.GONE);
        if (c && !hintShown) {
            hintShown = true;
            Toast.makeText(this,
                    "Steuerkreuz = Zeiger · OK = Klick\nOK lange = Menü (TV-Ansicht, Kachel speichern)\nZURÜCK lange = Startseite",
                    Toast.LENGTH_LONG).show();
        }
    }

    void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        handler.removeCallbacks(hideCursorR);
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    void goHome(boolean lock) {
        hideCustom();
        showLoading(false);
        clearHistoryOnLoad = true;
        web.loadUrl(lock ? HOME + "?lock=1" : HOME);
    }

    void doBack() {
        if (customView != null) { hideCustom(); return; }
        if (isHome(web.getUrl())) {
            web.evaluateJavascript("(window.tvBack?window.tvBack():false)", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    if ("true".equals(v)) return;
                    if (web.canGoBack()) web.goBack(); else finish();
                }
            });
            return;
        }
        if (web.canGoBack()) web.goBack(); else goHome(false);
    }

    void js(String code) {
        web.evaluateJavascript(code, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        int a = e.getAction();
        int rep = e.getRepeatCount();

        if (k == KeyEvent.KEYCODE_BACK) {
            if (a == KeyEvent.ACTION_DOWN) {
                if (rep > 0 && !backLong) { backLong = true; goHome(false); }
            } else if (a == KeyEvent.ACTION_UP) {
                if (backLong) backLong = false; else doBack();
            }
            return true;
        }

        if (a == KeyEvent.ACTION_DOWN && rep == 0) {
            switch (k) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    js("(function(){var v=" + MV + ";if(v){if(v.paused)v.play();else v.pause();}})()");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    js("(function(){var v=" + MV + ";if(v)v.currentTime+=10;})()");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    js("(function(){var v=" + MV + ";if(v)v.currentTime-=10;})()");
                    return true;
            }
        }

        if (customView != null) {
            // Vollbild-Video: OK = Pause/Weiter, links/rechts = 10 s spulen
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (a == KeyEvent.ACTION_DOWN && rep == 0)
                        js("(function(){var v=" + MV + ";if(v){if(v.paused)v.play();else v.pause();}})()");
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (a == KeyEvent.ACTION_DOWN) {
                        int sec = (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10) * (rep > 3 ? 3 : 1);
                        js("(function(){var v=" + MV + ";if(v)v.currentTime=Math.max(0,v.currentTime+(" + sec + "));})()");
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
            }
        }

        if (overlayMode && !cursorMode && customView == null && !isHome(web.getUrl())
                && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A)) {
            // TV-Ansicht: OK kurz = öffnen, OK lange = Menü
            if (a == KeyEvent.ACTION_DOWN) {
                if (rep == 0) { okDown = true; okLong = false; }
                else if (okDown && !okLong) { okLong = true; showMenu(); }
            } else if (a == KeyEvent.ACTION_UP) {
                if (okDown && !okLong) {
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
                okDown = false;
                okLong = false;
            }
            return true;
        }

        if (cursorMode) {
            if (a == KeyEvent.ACTION_DOWN) showCursorBriefly();
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (a == KeyEvent.ACTION_DOWN) move(0, -1, rep);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (a == KeyEvent.ACTION_DOWN) move(0, 1, rep);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (a == KeyEvent.ACTION_DOWN) move(-1, 0, rep);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (a == KeyEvent.ACTION_DOWN) move(1, 0, rep);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (a == KeyEvent.ACTION_DOWN) {
                        if (rep == 0) { okDown = true; okLong = false; }
                        else if (okDown && !okLong) { okLong = true; showMenu(); }
                    } else if (a == KeyEvent.ACTION_UP) {
                        if (okDown && !okLong) click();
                        okDown = false;
                        okLong = false;
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    void move(int dx, int dy, int rep) {
        float step = Math.min(3 + rep * 1.5f, 28) * density;
        float w = cursor.getWidth(), h = cursor.getHeight();
        float nx = cursor.cx + dx * step, ny = cursor.cy + dy * step;
        float sx = 0, sy = 0;
        if (nx < 0) { sx = nx; nx = 0; }
        if (nx > w - 1) { sx = nx - (w - 1); nx = w - 1; }
        if (ny < 0) { sy = ny; ny = 0; }
        if (ny > h - 1) { sy = ny - (h - 1); ny = h - 1; }
        cursor.cx = nx;
        cursor.cy = ny;
        cursor.invalidate();
        if ((sx != 0 || sy != 0) && customView == null) {
            int jx = (int) (sx * 4 / density), jy = (int) (sy * 4 / density);
            js("window.scrollBy(" + jx + "," + jy + ")");
        }
    }

    void click() {
        tapAt(cursor.cx, cursor.cy);
    }

    void tapAt(final float x, final float y) {
        final long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(down);
        down.recycle();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                root.dispatchTouchEvent(up);
                up.recycle();
            }
        }, 60);
    }

    void askSaveTile() {
        final String url = web.getUrl();
        if (url == null || isHome(url)) return;
        String host = Uri.parse(url).getHost();
        if (host == null) host = "";
        if (host.startsWith("www.")) host = host.substring(4);
        String title = web.getTitle();
        if (title == null || title.trim().isEmpty()) title = host;
        if (title.length() > 40) title = title.substring(0, 40);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView l1 = new TextView(this); l1.setText("Gruppe");
        final EditText g = new EditText(this); g.setSingleLine(); g.setText(host);
        g.setInputType(InputType.TYPE_CLASS_TEXT);
        TextView l2 = new TextView(this); l2.setText("Name der Kachel");
        final EditText n = new EditText(this); n.setSingleLine(); n.setText(title);
        n.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(l1); box.addView(g); box.addView(l2); box.addView(n);

        new AlertDialog.Builder(this)
                .setTitle("Diese Seite als Kachel speichern?")
                .setView(box)
                .setPositiveButton("Speichern", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        savePending(g.getText().toString().trim(), n.getText().toString().trim(), url);
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    void savePending(String group, String name, String url) {
        try {
            SharedPreferences p = getSharedPreferences("tv", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("pending", "[]"));
            JSONObject o = new JSONObject();
            o.put("group", group);
            o.put("name", name.isEmpty() ? url : name);
            o.put("url", url);
            arr.put(o);
            p.edit().putString("pending", arr.toString()).apply();
            Toast.makeText(this, "Gespeichert – erscheint auf der Startseite", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    void flushPending() {
        SharedPreferences p = getSharedPreferences("tv", Context.MODE_PRIVATE);
        String s = p.getString("pending", "[]");
        if (s.equals("[]")) return;
        s = s.replace(" ", " ").replace(" ", " ");
        js("window.tvAddTiles&&window.tvAddTiles(" + s + ")");
        p.edit().remove("pending").apply();
    }

    @Override
    protected void onStart() {
        super.onStart();
        web.onResume();
    }

    @Override
    protected void onStop() {
        // Beim Verlassen der App sofort sperren: zurück zur Startseite mit PIN-Abfrage
        goHome(true);
        web.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }

    static class CursorView extends View {
        float cx = -1, cy = -1;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        CursorView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            fill.setColor(Color.argb(170, 255, 255, 255));
            ring.setColor(Color.argb(220, 20, 20, 20));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * d);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (cx < 0) { cx = w / 2f; cy = h / 2f; }
            if (cx > w) cx = w - 1;
            if (cy > h) cy = h - 1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = 11 * d;
            c.drawCircle(cx, cy, r, fill);
            c.drawCircle(cx, cy, r, ring);
            c.drawCircle(cx, cy, 2 * d, ring);
        }
    }
}
