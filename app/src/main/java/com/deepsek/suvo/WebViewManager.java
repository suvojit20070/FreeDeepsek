package com.deepsek.suvo;

import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Har DeepSeek account ka apna WebView (alag WebView Profile = alag cookies/storage/login).
 *
 * - WebViews MutableContextWrapper se bane hain: Activity attach par base = Activity, detach par appContext.
 * - Activity par saare WebViews ek ke upar ek (stack) lagte hain, dikhne wala account sabse upar.
 *   Neeche wale bhi "visible" rehte hain, toh Chromium unhe throttle nahi karta.
 * - Activity band hone par saare 1x1 px overlay window mein park hote hain (andar full size layout).
 */
public final class WebViewManager {

    private static final WebViewManager INSTANCE = new WebViewManager();

    public static WebViewManager get() {
        return INSTANCE;
    }

    private static final class Holder {
        MutableContextWrapper wrapper;
        DeepSeekWebView web;
    }

    private Context app;
    private final Map<String, Holder> views = new LinkedHashMap<>();
    private ViewGroup activityHost;
    private Activity activity;
    private String shownId = Config.DEFAULT_ACCOUNT;
    private FrameLayout keeper;
    private boolean keeperAdded;

    private WebViewManager() {
    }

    private static void assertMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebViewManager must be used on the main thread");
        }
    }

    public static boolean multiProfileSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String profileName(String accId) {
        return "fds_" + accId;
    }

    /* ---------------------------------------------------------------- obtain */

    /** Account ka WebView lo, na ho toh banao (aur abhi wale parent mein laga do). */
    public DeepSeekWebView obtain(Context c, String accId) {
        assertMain();
        if (app == null) app = c.getApplicationContext();
        Holder h = views.get(accId);
        if (h != null) return h.web;

        h = new Holder();
        h.wrapper = new MutableContextWrapper(activity != null ? activity : app);
        h.web = new DeepSeekWebView(h.wrapper);
        if (!Config.DEFAULT_ACCOUNT.equals(accId)) {
            if (multiProfileSupported()) {
                // Profile WebView use karne se PEHLE set hona chahiye
                ProfileStore.getInstance().getOrCreateProfile(profileName(accId));
                WebViewCompat.setProfile(h.web, profileName(accId));
            } else {
                AppState.log("Multi-profile support nahi (WebView update karo): "
                        + Config.accountLabel(app, accId) + " default login share karega");
            }
        }
        h.web.setup();
        h.web.loadUrl(DeepSeekAutomation.HOME_URL);
        views.put(accId, h);
        AppState.log("WebView created: " + Config.accountLabel(app, accId));

        // Jahan baaki WebViews hain wahin lagao
        if (activityHost != null) {
            addTo(activityHost, h.web, null);
            bringShownToFront();
        } else if (keeperAdded && keeper != null) {
            addTo(keeper, h.web, fullSize());
        }
        return h.web;
    }

    /** Config ke saare accounts ke WebViews bana do; hataye gaye accounts ke destroy. */
    public void ensureAll(Context c) {
        assertMain();
        if (app == null) app = c.getApplicationContext();
        List<String> ids = Config.accountIds(app);
        for (String id : ids) obtain(app, id);
        for (String id : new ArrayList<>(views.keySet())) {
            if (!ids.contains(id)) remove(id);
        }
        if (!views.containsKey(shownId)) shownId = ids.get(0);
    }

    public DeepSeekWebView get(String accId) {
        Holder h = views.get(accId);
        return h == null ? null : h.web;
    }

    /** Screen par dikhne wala account. */
    public DeepSeekWebView current() {
        return get(shownId);
    }

    public String shownId() {
        return shownId;
    }

    public boolean isAttachedToActivity() {
        return activityHost != null;
    }

    /* ---------------------------------------------------------------- activity */

    public void attach(Activity a, ViewGroup host) {
        assertMain();
        if (app == null) app = a.getApplicationContext();
        activity = a;
        ensureAll(a);
        removeKeeper();
        for (Holder h : views.values()) {
            h.wrapper.setBaseContext(a);
            detachFromParent(h.web);
            addTo(host, h.web, null);
        }
        activityHost = host;
        bringShownToFront();
        wakeAll();
        AppState.setKeepAlive("activity");
    }

    public void detach() {
        assertMain();
        for (Holder h : views.values()) {
            detachFromParent(h.web);
            h.wrapper.setBaseContext(app);
        }
        activityHost = null;
        activity = null;
        park();
    }

    /** Kaunsa account screen par dikhe (login ke liye). */
    public void show(String accId) {
        assertMain();
        if (!views.containsKey(accId)) obtain(app, accId);
        shownId = accId;
        bringShownToFront();
    }

    private void bringShownToFront() {
        DeepSeekWebView w = current();
        if (w == null || activityHost == null || w.getParent() != activityHost) return;
        activityHost.bringChildToFront(w);
        activityHost.requestLayout();
        activityHost.invalidate();
    }

    /* ---------------------------------------------------------------- background */

    public void park() {
        assertMain();
        if (app == null || views.isEmpty() || activityHost != null) return;
        boolean canOverlay = Settings.canDrawOverlays(app);
        if (DeepSeekService.isRunning() && Config.keepAlive(app) && canOverlay) {
            addKeeper();
        } else {
            removeKeeper();
            for (Holder h : views.values()) detachFromParent(h.web);
            AppState.setKeepAlive(canOverlay || !Config.keepAlive(app)
                    ? "detached" : "detached (overlay permission nahi)");
        }
        wakeAll();
    }

    public void unpark() {
        assertMain();
        removeKeeper();
        if (activityHost == null) for (Holder h : views.values()) detachFromParent(h.web);
    }

    /* ---------------------------------------------------------------- remove / reload */

    public void remove(String accId) {
        assertMain();
        Holder h = views.remove(accId);
        if (h == null) return;
        detachFromParent(h.web);
        try {
            h.web.destroy();
        } catch (Throwable ignored) {
        }
        if (!Config.DEFAULT_ACCOUNT.equals(accId) && multiProfileSupported()) {
            try {
                ProfileStore.getInstance().deleteProfile(profileName(accId));
            } catch (Throwable t) {
                AppState.log("Profile delete baad mein hoga: " + t.getMessage());
            }
        }
        if (accId.equals(shownId)) {
            shownId = views.isEmpty() ? Config.DEFAULT_ACCOUNT : views.keySet().iterator().next();
            bringShownToFront();
        }
    }

    public void reload() {
        assertMain();
        DeepSeekWebView w = current();
        if (w != null) w.loadUrl(DeepSeekAutomation.HOME_URL);
    }

    /** Renderer crash: us account ka WebView dobara banao. */
    public void onRendererGone(WebView dead) {
        assertMain();
        String deadId = null;
        for (Map.Entry<String, Holder> e : views.entrySet()) {
            if (e.getValue().web == dead) deadId = e.getKey();
        }
        if (deadId == null) return;
        Holder h = views.remove(deadId);
        detachFromParent(h.web);
        try {
            h.web.destroy();
        } catch (Throwable ignored) {
        }
        if (app != null) obtain(app, deadId);
    }

    /* ---------------------------------------------------------------- helpers */

    private void wakeAll() {
        for (Holder h : views.values()) {
            h.web.onResume();
            h.web.resumeTimers();
        }
    }

    private FrameLayout.LayoutParams fullSize() {
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        return new FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels);
    }

    private static void addTo(ViewGroup parent, View v, FrameLayout.LayoutParams lp) {
        detachFromParent(v);
        parent.addView(v, lp != null ? lp : new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addKeeper() {
        if (keeperAdded) return;
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        try {
            keeper = new FrameLayout(app);
            for (Holder h : views.values()) addTo(keeper, h.web, fullSize());

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(keeper, lp);
            keeperAdded = true;
            AppState.setKeepAlive("overlay · " + views.size() + " account(s)");
        } catch (Throwable t) {
            AppState.log("Overlay keep-alive failed: " + t.getMessage());
            if (keeper != null) keeper.removeAllViews();
            keeper = null;
            keeperAdded = false;
            AppState.setKeepAlive("detached");
        }
    }

    private void removeKeeper() {
        if (keeper == null) return;
        if (keeperAdded) {
            try {
                WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                wm.removeViewImmediate(keeper);
            } catch (Throwable ignored) {
            }
        }
        keeper.removeAllViews();
        keeper = null;
        keeperAdded = false;
    }

    private static void detachFromParent(View v) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
    }
}
