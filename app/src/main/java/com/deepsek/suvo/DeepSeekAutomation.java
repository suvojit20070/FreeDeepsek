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
    private static final long UPLOAD_TIMEOUT_MS = 120_000;
    private static final long MAX_JOB_MS = 25 * 60_000;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Reload se pehle purane page par set kiya token; naya page aane tak "old" milega. */
    private volatile String navToken;

    public DeepSeekAutomation(Context app) {
        this.app = app.getApplicationContext();
    }

    public void run(ChatJob job) throws InterruptedException {
        try {
            runSteps(job);
        } finally {
            // Request DeepSeek tak gayi thi -> account mein chat bani -> delete karo
            if (job.isStarted() && Config.deleteChats(app)) deleteChat(job);
            // Agli request ke liye fresh page abhi se load kar do (pre-warm)
            prewarm();
        }
    }

    /** Next request ke liye home page background mein load kar do. */
    public void prewarm() {
        main.post(() -> {
            WebView w = WebViewManager.get().obtain(app);
            w.loadUrl(HOME_URL);
        });
    }

    private void runSteps(ChatJob job) throws InterruptedException {
        long t0 = System.currentTimeMillis();

        // 1. Fresh chat: pre-warmed page pehle se khaali home par ho toh reload skip
        String fresh = unquote(eval("window.__fds ? __fds.isFresh() : 'no'", 3000));
        boolean reloaded = false;
        if (!"yes".equals(fresh)) {
            AppState.log("New chat (reload) for " + job.id.substring(0, 8));
            String token = "n" + System.nanoTime();
            evalRaw("window.__fdsNav=" + JSONObject.quote(token) + ";", 2000);
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
            reloaded = true;
            navToken = token;
        } else {
            navToken = null;
            AppState.log("New chat (pre-warmed) for " + job.id.substring(0, 8));
        }

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

        if (reloaded) Thread.sleep(300); // React hydrate
        long tReady = System.currentTimeMillis();

        // 3. DeepThink / Search toggles
        String modes = unquote(eval("__fds.setModes(" + job.think + "," + job.search + ")", 5000));
        if (modes.contains("toggled")) {
            AppState.log("Modes: " + modes);
            Thread.sleep(350);
        }

        // 3b. Files attach + upload complete hone ka wait
        if (job.files.length() > 0) {
            String att = unquote(eval("__fds.attach(" + job.files.toString() + ")", 30_000));
            if (!"ok".equals(att)) {
                job.fail("File attach failed: " + (att.isEmpty() ? "no result" : att));
                return;
            }
            AppState.log("Uploading " + job.files.length() + " file(s)…");
            String up = waitUpload(job);
            if (job.isClosed()) return;
            if (up.startsWith("failed")) {
                job.fail("DeepSeek file upload failed: " + up.substring(Math.min(up.length(), 7)));
                return;
            }
            AppState.log("Upload: " + up);
        }

        // 4. Prompt insert, phir Enter (JS timers nahi: background mein throttle hote hain)
        String filled = unquote(eval("__fds.fill(" + JSONObject.quote(job.id) + ","
                + JSONObject.quote(job.prompt) + ")", 10_000));
        if (!"ok".equals(filled)) {
            job.fail("Prompt insert failed: " + (filled.isEmpty() ? "no result" : filled));
            return;
        }
        Thread.sleep(120);
        eval("__fds.submit()", 3000);
        long tSent = System.currentTimeMillis();

        // 5. Request start hone ka wait (fetch hook onStart bhejega)
        if (!job.awaitStart(1500)) {
            if (job.isClosed()) return;
            unquote(eval("__fds.clickSend()", 3000));
        }
        if (!job.isStarted() && !job.awaitStart(START_TIMEOUT_MS)) {
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

        long tEnd = System.currentTimeMillis();
        long first = job.firstChunkAt();
        AppState.log("\u23F1 ready " + (tReady - t0) + "ms · send " + (tSent - tReady) + "ms · first token "
                + (first > 0 ? (first - tSent) + "ms" : "-") + " · total " + (tEnd - t0) + "ms");
    }

    private String waitUpload(ChatJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + UPLOAD_TIMEOUT_MS;
        String last = "timeout";
        while (System.currentTimeMillis() < deadline) {
            if (job.cancelled) {
                job.fail("Client disconnected");
                return "cancelled";
            }
            String s = unquote(eval("__fds.uploadState()", 4000));
            if (!s.isEmpty()) last = s;
            if ("ready".equals(s)) {
                Thread.sleep(600);
                return "ready";
            }
            if (s.startsWith("failed")) return s;
            if ("unseen".equals(s)) {
                // Upload request detect nahi hui (endpoint naam alag ho sakta hai): thoda ruk ke aage badho
                Thread.sleep(3000);
                return "unseen (continuing)";
            }
            Thread.sleep(700);
        }
        return last + " (timeout, continuing)";
    }

    private void deleteChat(ChatJob job) throws InterruptedException {
        String r = unquote(eval("__fds.deleteChat(" + JSONObject.quote(job.id) + ")", 4000));
        if (!"started".equals(r)) {
            AppState.log("Chat delete skipped: " + r);
            return;
        }
        long deadline = System.currentTimeMillis() + 8000;
        String st = "pending";
        while (System.currentTimeMillis() < deadline) {
            st = unquote(eval("__fds.deleteState(" + JSONObject.quote(job.id) + ")", 3000));
            if (!"pending".equals(st)) break;
            Thread.sleep(300);
        }
        AppState.log("ok".equals(st) ? "Chat deleted" : "Chat delete: " + st);
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
            String tok = navToken;
            String expr = (tok != null ? "(window.__fdsNav===" + JSONObject.quote(tok) + ") ? 'old' : " : "")
                    + "(window.__fds ? __fds.ready() : 'noscript')";
            String s = unquote(eval(expr, 4000));
            if (!s.isEmpty()) last = s;
            if ("ready".equals(s)) return "ready";
            if ("login".equals(s)) return "login";
            if ("noscript".equals(s) && System.currentTimeMillis() - injectedAt > 3000) {
                injectedAt = System.currentTimeMillis();
                String script = DeepSeekWebView.injectScript(app);
                if (!script.isEmpty()) evalRaw(script, 4000);
            }
            Thread.sleep(150);
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
