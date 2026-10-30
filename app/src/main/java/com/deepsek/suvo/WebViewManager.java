package com.deepsek.suvo;

import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Single WebView ka owner.
 *
 * Fix 1: WebView MutableContextWrapper(appContext) se bana hai. Activity attach par
 *        base context = Activity, detach par wapas appContext. Activity leak nahi hoti.
 * Fix 2: Activity band hone par detached WebView ko Chromium throttle kar deta hai.
 *        Isliye use 1x1 px overlay window mein park karte hain. Andar WebView full
 *        screen size par layout hota hai, bas window 1px ki hai, toh page "visible"
 *        rehta hai aur JS/stream normal chalti hai.
 */
public final class WebViewManager {

    private static final WebViewManager INSTANCE = new WebViewManager();

    public static WebViewManager get() {
        return INSTANCE;
    }

    private Context app;
    private MutableContextWrapper wrapper;
    private DeepSeekWebView webView;
    private ViewGroup activityHost;
    private FrameLayout keeper;
    private boolean keeperAdded;

    private WebViewManager() {
    }

    private static void assertMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebViewManager must be used on the main thread");
        }
    }

    /** Existing WebView lo, null ho toh naya banao. */
    public DeepSeekWebView obtain(Context c) {
        assertMain();
        if (app == null) app = c.getApplicationContext();
        if (webView == null) {
            wrapper = new MutableContextWrapper(app);
            webView = new DeepSeekWebView(wrapper);
            webView.setup();
            webView.loadUrl(DeepSeekAutomation.HOME_URL);
            AppState.log("WebView created");
        }
        return webView;
    }

    public DeepSeekWebView current() {
        return webView;
    }

    public boolean isAttachedToActivity() {
        return activityHost != null;
    }

    /** Activity.onStart se: WebView ko screen par dikhao. */
    public void attach(Activity activity, ViewGroup host) {
        assertMain();
        DeepSeekWebView w = obtain(activity);
        wrapper.setBaseContext(activity);
        removeKeeper();
        detachFromParent(w);
        host.addView(w, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activityHost = host;
        wake();
        AppState.setKeepAlive("activity");
    }

    /** Activity.onStop se: Activity se hatao aur background mein park karo. */
    public void detach() {
        assertMain();
        if (webView == null) return;
        detachFromParent(webView);
        activityHost = null;
        wrapper.setBaseContext(app);
        park();
    }

    /** Activity nahi hai toh WebView ko overlay keeper mein rakho (agar allowed ho). */
    public void park() {
        assertMain();
        if (webView == null || activityHost != null) return;
        boolean canOverlay = Settings.canDrawOverlays(app);
        if (DeepSeekService.isRunning() && Config.keepAlive(app) && canOverlay) {
            addKeeper();
        } else {
            removeKeeper();
            detachFromParent(webView);
            AppState.setKeepAlive(canOverlay || !Config.keepAlive(app)
                    ? "detached" : "detached (overlay permission nahi)");
        }
        wake();
    }

    /** Service band hone par overlay hatao (WebView object zinda rehta hai). */
    public void unpark() {
        assertMain();
        removeKeeper();
        if (webView != null && activityHost == null) detachFromParent(webView);
    }

    public void reload() {
        assertMain();
        if (webView != null) webView.loadUrl(DeepSeekAutomation.HOME_URL);
    }

    /** Renderer crash: purana WebView destroy, naya banao, wahi jagah wapas lagao. */
    public void onRendererGone(android.webkit.WebView dead) {
        assertMain();
        if (dead != webView) return;
        ViewGroup host = activityHost;
        Context base = wrapper != null ? wrapper.getBaseContext() : null;
        removeKeeper();
        detachFromParent(dead);
        try {
            dead.destroy();
        } catch (Throwable ignored) {
        }
        webView = null;
        activityHost = null;
        if (host != null && base instanceof Activity && !((Activity) base).isFinishing()) {
            attach((Activity) base, host);
        } else if (app != null) {
            obtain(app);
            park();
        }
    }

    private void wake() {
        if (webView == null) return;
        webView.onResume();
        webView.resumeTimers();
    }

    private void addKeeper() {
        if (keeperAdded) return;
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        try {
            DisplayMetrics dm = app.getResources().getDisplayMetrics();
            keeper = new FrameLayout(app);
            detachFromParent(webView);
            keeper.addView(webView, new FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(keeper, lp);
            keeperAdded = true;
            AppState.setKeepAlive("overlay (background active)");
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

    private static void detachFromParent(android.view.View v) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
    }
}
