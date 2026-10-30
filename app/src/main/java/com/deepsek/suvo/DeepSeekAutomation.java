package com.deepsek.suvo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ek job ke steps (queue thread par chalte hain, WebView ka kaam main thread par post hota hai):
 * fresh chat -> page ready -> DeepThink/Search set -> prompt insert + send
 * -> stream start ka wait -> stream end ka wait -> finish.
 */
public final class DeepSeekAutomation {

    public static final String HOME_URL = "https://chat.deepseek.com/";

    private static final long READY_TIMEOUT_MS = 45_000;
    private static final long START_TIMEOUT_MS = 12_000;
    private static final long START_RETRY_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 180_000;
    private static final long MAX_JOB_MS = 25 * 60_000;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    public DeepSeekAutomation(Context app) {
        this.app = app.getApplicationContext();
    }

    public void run(ChatJob job) throws InterruptedException {
        // 1. Fresh chat: home URL reload = naya conversation, purana context nahi
        AppState.log("New chat for request " + job.id.substring(0, 8));
        CountDownLatch loaded = new CountDownLatch(1);
        main.post(() -> {
            try {
                WebView w = WebViewManager.get().obtain(app);
                w.stopLoading();
                w.loadUrl(HOME_URL);
            } finally {
                loaded.countDown();
            }
        });
        loaded.await(5, TimeUnit.SECONDS);
        Thread.sleep(800);

        // 2. Page ready?
        String state = waitReady(job);
        if (job.isClosed()) return;
        if ("login".equals(state)) {
            job.fail("DeepSeek login required. Free Deepsek app kholo aur WebView mein login karo.");
            return;
        }
        if (!"ready".equals(state)) {
            job.fail("DeepSeek page ready nahi hua (" + state + "). App kholke page check karo.");
            return;
        }

        // 3. DeepThink / Search toggles
        String modes = unquote(eval("__fds.setModes(" + job.think + "," + job.search + ")", 5000));
        AppState.log("Modes: " + modes);
        Thread.sleep(modes.contains("toggled") ? 700 : 250);

        // 4. Prompt insert + send
        String sent = unquote(eval("__fds.send(" + JSONObject.quote(job.id) + ","
                + JSONObject.quote(job.prompt) + ")", 10_000));
        if (!"ok".equals(sent)) {
            job.fail("Prompt insert failed: " + (sent.isEmpty() ? "no result" : sent));
            return;
        }

        // 5. Request start hone ka wait (fetch hook onStart bhejega)
        if (!job.awaitStart(START_TIMEOUT_MS)) {
            if (job.isClosed()) return;
            String r = unquote(eval("__fds.clickSend()", 5000));
            AppState.log("Send retry: " + r);
            if (!job.awaitStart(START_RETRY_MS)) {
                eval("__fds.cancel()", 3000);
                if (!job.isClosed()) {
                    job.fail("DeepSeek par message send nahi hua (completion request detect nahi hui).");
                }
                return;
            }
        }
        if (job.isClosed()) return;

        // 6. Stream end ka wait
        long begin = System.currentTimeMillis();
        String finish = "stop";
        while (!job.awaitEnd(1000)) {
            long now = System.currentTimeMillis();
            if (job.cancelled) {
                eval("__fds.stop()", 3000);
                job.finish("stop");
                AppState.log("Client disconnected, generation stopped");
                return;
            }
            if (now - job.lastActivity() > IDLE_TIMEOUT_MS) {
                finish = "length";
                AppState.log("Idle timeout");
                break;
            }
            if (now - begin > MAX_JOB_MS) {
                finish = "length";
                AppState.log("Max job time reached");
                break;
            }
        }
        if (job.isClosed()) return;

        // 7. Stream se kuch nahi mila toh DOM se last answer (fallback)
        if (job.isEmpty()) {
            Thread.sleep(800);
            String dom = unquote(eval("__fds.lastAnswer()", 5000));
            if (!dom.isEmpty()) {
                AppState.log("Stream empty, used DOM fallback");
                job.addChunk(false, dom);
            }
        }

        if (job.isEmpty()) {
            String err = job.endError();
            job.fail(err != null ? "DeepSeek error: " + err : "DeepSeek ne empty response diya");
        } else {
            job.finish(finish);
        }
    }

    private String waitReady(ChatJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        long injectedAt = 0;
        String last = "timeout";
        while (System.currentTimeMillis() < deadline) {
            if (job.cancelled) {
                job.fail("Client disconnected");
                return "cancelled";
            }
            String s = unquote(eval("window.__fds ? __fds.ready() : 'noscript'", 4000));
            if (!s.isEmpty()) last = s;
            if ("ready".equals(s)) {
                Thread.sleep(700); // React hydrate hone do
                return "ready";
            }
            if ("login".equals(s)) return "login";
            if ("noscript".equals(s) && System.currentTimeMillis() - injectedAt > 3000) {
                injectedAt = System.currentTimeMillis();
                String script = DeepSeekWebView.injectScript(app);
                if (!script.isEmpty()) evalRaw(script, 4000);
            }
            Thread.sleep(500);
        }
        return last;
    }

    /** try/catch wrapper ke saath JS expression evaluate. */
    private String eval(String expr, long timeoutMs) throws InterruptedException {
        String js = "(function(){try{return " + expr + ";}catch(e){return 'err:'+e;}})()";
        return evalRaw(js, timeoutMs);
    }

    private String evalRaw(String js, long timeoutMs) throws InterruptedException {
        final String[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            WebView w = WebViewManager.get().current();
            if (w == null) {
                latch.countDown();
                return;
            }
            try {
                w.evaluateJavascript(js, value -> {
                    out[0] = value;
                    latch.countDown();
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        return out[0];
    }

    /** evaluateJavascript JSON-encoded string deta hai: "\"ready\"" -> ready */
    private static String unquote(String v) {
        if (v == null || "null".equals(v) || "undefined".equals(v)) return "";
        try {
            Object o = new JSONTokener(v).nextValue();
            if (o instanceof String) return (String) o;
            return String.valueOf(o);
        } catch (Exception e) {
            return v;
        }
    }
}
