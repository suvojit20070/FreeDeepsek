package com.deepsek.suvo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * DeepSeek ke liye configured WebView.
 * Isko hamesha MutableContextWrapper ke saath banaya jata hai (WebViewManager dekho),
 * taaki Activity aane-jaane par sirf base context badle aur Activity leak na ho.
 */
public class DeepSeekWebView extends WebView {

    public static final String ORIGIN = "https://chat.deepseek.com";
    private static final String ASSET = "deepseek_inject.js";
    private static String cachedScript;

    private boolean docStartInjected = false;

    public DeepSeekWebView(Context context) {
        super(context);
    }

    /** assets/deepseek_inject.js ka content (cache ke saath). */
    public static synchronized String injectScript(Context c) {
        if (cachedScript != null) return cachedScript;
        try (InputStream in = c.getApplicationContext().getAssets().open(ASSET)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            cachedScript = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppState.log("Inject script load failed: " + e);
            cachedScript = "";
        }
        return cachedScript;
    }

    private static boolean isDeepSeek(String url) {
        return url != null && url.contains("deepseek.com");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void setup() {
        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // "; wv" hatane se kuch login pages WebView ko block nahi karte
        String ua = s.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("; wv)", ")").replaceAll("Version/\\d+\\.\\d+ ", "");
            s.setUserAgentString(ua);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(this, true);

        // Background/invisible hone par bhi renderer ko kill hone se bachao
        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false);

        addJavascriptInterface(new DeepSeekJsBridge(), "FDSBridge");

        final String script = injectScript(getContext());
        if (!script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // Page ke apne scripts se pehle chalegi, taaki fetch hook pehle lag jaye
                WebViewCompat.addDocumentStartJavaScript(this, script, Collections.singleton(ORIGIN));
                docStartInjected = true;
            } catch (Throwable t) {
                AppState.log("Document-start script unavailable: " + t.getMessage());
            }
        }

        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl() == null ? null : request.getUrl().getScheme();
                // http/https WebView mein hi khule; intent:// waghaira ignore
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!docStartInjected && isDeepSeek(url) && !script.isEmpty()) {
                    view.evaluateJavascript(script, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Script khud guard karti hai, dobara chalana safe hai
                if (isDeepSeek(url) && !script.isEmpty()) view.evaluateJavascript(script, null);
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                AppState.log("WebView renderer gone (crash=" + detail.didCrash() + "), recreating");
                WebViewManager.get().onRendererGone(view);
                return true;
            }
        });

        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (!hasActivity()) {
                    result.confirm();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (!hasActivity()) {
                    result.confirm();
                    return true;
                }
                return super.onJsConfirm(view, url, message, result);
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                if (!hasActivity()) {
                    result.cancel();
                    return true;
                }
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }
        });
    }

    /** Dialog dikhane ke liye Activity context chahiye; background mein nahi hota. */
    public boolean hasActivity() {
        Context c = getContext();
        if (c instanceof MutableContextWrapper) {
            return ((MutableContextWrapper) c).getBaseContext() instanceof Activity;
        }
        return c instanceof Activity;
    }
}
