package com.deepsek.suvo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ek job ke steps (queue thread par chalte hain, WebView ka kaam main thread par post hota hai):
 * account chuno (round-robin) -> fresh chat -> page ready -> DeepThink/Search -> files
 * -> prompt + send -> stream -> (Continue) -> finish.
 * Account fail ho (login, limit, send fail, empty error) aur client ko abhi kuch content nahi gaya ho,
 * toh request agle account par retry hoti hai aur fail account ko cooldown milta hai.
 */
public final class DeepSeekAutomation {

    public static final String HOME_URL = "https://chat.deepseek.com/";

    private static final long READY_TIMEOUT_MS = 45_000;
    private static final long START_TIMEOUT_MS = 12_000;
    private static final long START_RETRY_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 180_000;
    private static final long MAX_JOB_MS = 25 * 60_000;
    private static final long UPLOAD_TIMEOUT_MS = 120_000;
    private static final int MAX_CONTINUES = 3;

    private static final long COOLDOWN_LOGIN_MS = 30 * 60_000L;
    private static final long COOLDOWN_LIMIT_MS = 10 * 60_000L;
    private static final long COOLDOWN_SHORT_MS = 60_000L;

    /** Is account par ye attempt fail hua; agla account try karo. */
    private static final class AccountFailure extends Exception {
        final long cooldownMs;

        AccountFailure(String msg, long cooldownMs) {
            super(msg);
            this.cooldownMs = cooldownMs;
        }
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Reload se pehle purane page par set kiya token; naya page aane tak "old" milega. */
    private volatile String navToken;
    /** Is attempt ka account (eval/navigate isi ke WebView par). */
    private volatile String acc = Config.DEFAULT_ACCOUNT;

    private int rr = 0;
    private final Map<String, Long> cooldownUntil = new HashMap<>();

    public DeepSeekAutomation(Context app) {
        this.app = app.getApplicationContext();
    }

    /* ---------------------------------------------------------------- rotation */

    /** Round-robin order: available accounts pehle, cooldown wale baad mein (jo jaldi free ho pehle). */
    private List<String> pickOrder() {
        List<String> ids = Config.accountIds(app);
        int n = ids.size();
        int start = Math.floorMod(rr++, n);
        long now = System.currentTimeMillis();
        List<String> ready = new ArrayList<>();
        List<String> cooling = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = ids.get((start + i) % n);
            Long until = cooldownUntil.get(id);
            if (until == null || until <= now) ready.add(id);
            else cooling.add(id);
        }
        cooling.sort((a, b) -> Long.compare(cooldownUntil.get(a), cooldownUntil.get(b)));
        ready.addAll(cooling);
        return ready;
    }

    public void run(ChatJob job) throws InterruptedException {
        List<String> order = pickOrder();
        String lastError = null;
        for (int attempt = 0; attempt < order.size(); attempt++) {
            acc = order.get(attempt);
            String label = Config.accountLabel(app, acc);
            if (attempt > 0) {
                job.resetForRetry();
                AppState.log("Retry on " + label);
            }
            AppState.setJob("Working · " + label + " · " + job.model);
            try {
                runSteps(job, label);
                cooldownUntil.remove(acc);
                return;
            } catch (AccountFailure f) {
                lastError = label + ": " + f.getMessage();
                cooldownUntil.put(acc, System.currentTimeMillis() + f.cooldownMs);
                AppState.log(lastError + " → cooldown " + (f.cooldownMs / 60_000) + " min");
                if (job.isClosed() || job.cancelled || !job.isEmpty()) break;
            } finally {
                // Is account par chat bani thi toh delete; agli request ke liye page pre-warm
                if (job.isStarted() && Config.deleteChats(app)) deleteChat(job);
                prewarm(acc);
            }
        }
        if (!job.isClosed()) {
            job.fail(lastError != null ? "Sab accounts fail: " + lastError : "Koi account available nahi");
        }
    }

    /* ---------------------------------------------------------------- web helpers */

    private WebView web() {
        return WebViewManager.get().obtain(app, acc);
    }

    /** Next request ke liye account ka home page background mein load. */
    public void prewarm(String accId) {
        main.post(() -> WebViewManager.get().obtain(app, accId).loadUrl(HOME_URL));
    }

    /** loadUrl ke saath navigation token, taaki waitReady purane page ko "ready" na samjhe. */
    private void navigate(String url) throws InterruptedException {
        String token = "n" + System.nanoTime();
        evalRaw("window.__fdsNav=" + JSONObject.quote(token) + ";", 2000);
        CountDownLatch loaded = new CountDownLatch(1);
        main.post(() -> {
            try {
                WebView w = web();
                w.stopLoading();
                w.loadUrl(url);
            } finally {
                loaded.countDown();
            }
        });
        loaded.await(5, TimeUnit.SECONDS);
        navToken = token;
    }

    private static boolean looksLikeLimit(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        return e.contains("429") || e.contains("limit") || e.contains("too many") || e.contains("busy")
                || e.contains("频繁") || e.contains("上限");
    }

    private static boolean looksLikeAuth(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        return e.contains("401") || e.contains("token") || e.contains("login") || e.contains("unauthor")
                || e.contains("40003");
    }

    /* ---------------------------------------------------------------- steps */

    private void runSteps(ChatJob job, String label) throws InterruptedException, AccountFailure {
        long t0 = System.currentTimeMillis();
        boolean reloaded = false;

        // 1. Fresh chat: pre-warmed page pehle se khaali home par ho toh reload skip
        String fresh = unquote(eval("window.__fds ? __fds.isFresh() : 'no'", 3000));
        if ("yes".equals(fresh)) {
            navToken = null;
            AppState.log(label + ": new chat (pre-warmed)");
        } else {
            AppState.log(label + ": new chat (reload)");
            navigate(HOME_URL);
            reloaded = true;
        }

        // 2. Page ready?
        String state = waitReady(job);
        if (job.isClosed()) return;
        if ("login".equals(state)) throw new AccountFailure("login required", COOLDOWN_LOGIN_MS);
        if (!"ready".equals(state)) throw new AccountFailure("page ready nahi hua (" + state + ")", COOLDOWN_SHORT_MS);

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
                throw new AccountFailure("file attach failed: " + (att.isEmpty() ? "no result" : att), COOLDOWN_SHORT_MS);
            }
            AppState.log("Uploading " + job.files.length() + " file(s)…");
            String up = waitUpload(job);
            if (job.isClosed()) return;
            if (up.startsWith("failed")) {
                String err = up.substring(Math.min(up.length(), 7));
                throw new AccountFailure("file upload failed: " + err,
                        looksLikeLimit(err) ? COOLDOWN_LIMIT_MS : COOLDOWN_SHORT_MS);
            }
            AppState.log("Upload: " + up);
        }

        // 4. Prompt insert, phir Send button (JS timers nahi: background mein throttle hote hain)
        String filled = unquote(eval("__fds.fill(" + JSONObject.quote(job.id) + ","
                + JSONObject.quote(job.prompt) + ")", 10_000));
        if (!"ok".equals(filled)) {
            throw new AccountFailure("prompt insert failed: " + (filled.isEmpty() ? "no result" : filled),
                    COOLDOWN_SHORT_MS);
        }
        Thread.sleep(60); // React ko send button enable karne do
        String how = unquote(eval("__fds.submit()", 3000));
        long tSent = System.currentTimeMillis();

        // 5. Request start hone ka wait (fetch hook onStart bhejega). Na ho toh jaldi retry.
        if (!job.awaitStart(600)) {
            if (job.isClosed()) return;
            String r2 = unquote(eval("__fds.clickSend()", 3000));
            if (!job.awaitStart(800) && !job.isClosed()) {
                String r3 = unquote(eval("__fds.submit('enter')", 3000));
                AppState.log("Send retries: " + how + " -> " + r2 + " -> " + r3);
            }
        }
        if (!job.isStarted() && !job.awaitStart(START_TIMEOUT_MS)) {
            if (job.isClosed()) return;
            String r = unquote(eval("__fds.clickSend()", 5000));
            AppState.log("Send retry: " + r);
            if (!job.awaitStart(START_RETRY_MS)) {
                eval("__fds.cancel()", 3000);
                if (job.isClosed()) return;
                throw new AccountFailure("message send nahi hua", COOLDOWN_SHORT_MS);
            }
        }
        if (job.isClosed()) return;

        // 6. Stream end ka wait
        long begin = System.currentTimeMillis();
        String finish = "stop";
        boolean stalled = false;
        while (!job.awaitEnd(1000)) {
            long now = System.currentTimeMillis();
            // Stream khuli hai par 3s se kuch nahi aaya: DeepSeek ruk gaya ho toh Continue button dikhta hai
            if (now - job.lastActivity() > 3000 && "yes".equals(unquote(eval("__fds.hasContinue()", 2000)))) {
                AppState.log("Stream stalled, Continue button mila");
                stalled = true;
                break;
            }
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

        // 6b. DeepSeek ne beech mein roka (INCOMPLETE / Continue button) -> Continue click, max 3 baar
        for (int round = 1; round <= MAX_CONTINUES && !job.isClosed() && !job.cancelled; round++) {
            String er = job.endReason();
            boolean abnormal = stalled || "incomplete".equals(er) || "unfinished".equals(er);
            stalled = false;
            String has;
            // Beech mein ruka ho toh button render hone ka wait (3s), normal FINISHED par sirf quick check
            long until = System.currentTimeMillis() + (abnormal ? 3000 : 0);
            do {
                has = unquote(eval("__fds.hasContinue()", 2000));
                if ("yes".equals(has) || System.currentTimeMillis() >= until) break;
                Thread.sleep(250);
            } while (true);
            if (!"yes".equals(has)) break;

            job.prepareContinue();
            String c = unquote(eval("__fds.continueGen(" + JSONObject.quote(job.id) + ")", 3000));
            AppState.log("Continue #" + round + ": " + c);
            if (!"clicked".equals(c) || !job.awaitRestart(15_000)) {
                AppState.log("Continue stream start nahi hui");
                break;
            }
            long cBegin = System.currentTimeMillis();
            while (!job.awaitEnd(1000)) {
                long now = System.currentTimeMillis();
                if (now - job.lastActivity() > 3000 && "yes".equals(unquote(eval("__fds.hasContinue()", 2000)))) {
                    AppState.log("Continue stream bhi ruki, phir Continue");
                    stalled = true;
                    break;
                }
                if (job.cancelled || now - job.lastActivity() > IDLE_TIMEOUT_MS || now - cBegin > MAX_JOB_MS) break;
            }
            if (job.cancelled) {
                eval("__fds.stop()", 3000);
                job.finish("stop");
                return;
            }
        }
        if (job.isClosed()) return;
        if (stalled || "incomplete".equals(job.endReason())) finish = "length";

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
            // Is account se kuch nahi mila: limit/auth/other -> agla account
            String err = job.endError();
            long cd = looksLikeAuth(err) ? COOLDOWN_LOGIN_MS : looksLikeLimit(err) ? COOLDOWN_LIMIT_MS : COOLDOWN_SHORT_MS;
            throw new AccountFailure(err != null ? "DeepSeek error: " + err : "empty response", cd);
        }
        job.finish(finish);

        long tEnd = System.currentTimeMillis();
        long first = job.firstChunkAt();
        AppState.log("⏱ " + label + " · ready " + (tReady - t0) + "ms · send " + (tSent - tReady)
                + "ms · first token " + (first > 0 ? (first - tSent) + "ms" : "-") + " · total " + (tEnd - t0) + "ms");
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

    /** try/catch wrapper ke saath JS expression evaluate (is attempt ke account par). */
    private String eval(String expr, long timeoutMs) throws InterruptedException {
        String js = "(function(){try{return " + expr + ";}catch(e){return 'err:'+e;}})()";
        return evalRaw(js, timeoutMs);
    }

    private String evalRaw(String js, long timeoutMs) throws InterruptedException {
        final String[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                web().evaluateJavascript(js, value -> {
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
