package com.deepsek.suvo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final int MAX_CONTINUES = 3;
    private static final long MAX_JOB_MS = 25 * 60_000;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Reload se pehle purane page par set kiya token; naya page aane tak "old" milega. */
    private volatile String navToken;
    /** Same-chat mode: abhi wali DeepSeek chat ka session id. */
    private volatile String curSid;
    /** Same-chat mode: reply fingerprint -> DeepSeek chat (insertion order = sabse purana pehle). */
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private static final int MAX_SESSIONS = 20;
    private static final long SESSION_IDLE_MS = 30 * 60_000L;

    public DeepSeekAutomation(Context app) {
        this.app = app.getApplicationContext();
    }

    public void run(ChatJob job) throws InterruptedException {
        boolean same = Config.sameChat(app);
        if (!same && !sessions.isEmpty()) dropAllSessions();   // mode badla
        if (same) expireSessions();

        // Har conversation ka apna DeepSeek chat: client ke aakhri assistant msg ka fingerprint = key
        Session s = null;
        boolean cont = false;
        if (same) {
            if (job.lastAssistantFp == null) {
                AppState.log("New chat: request mein pichhla assistant message nahi (nayi conversation)");
            } else if ((s = sessions.remove(job.lastAssistantFp)) == null) {
                AppState.log("New chat: history kisi saved chat se match nahi hui ["
                        + shorten(job.lastAssistantFp) + "] · saved " + sessions.size());
            } else if (job.followUp == null) {
                AppState.log("New chat: follow-up khaali");
                sessions.put(job.lastAssistantFp, s);
                s = null;
            } else {
                cont = true;
                curSid = s.sid;
            }
        }

        try {
            runSteps(job, cont);
        } finally {
            if (same) {
                // Chat zinda rakho; is reply ke fingerprint se session save karo
                String sid = job.isStarted() ? unquote(eval("__fds.lastSession()", 3000)) : "";
                if (!sid.isEmpty()) {
                    ToolCallParser.Result r = ToolCallParser.parse(job.content(), null);
                    String fp = r.hasCalls()
                            ? OpenAIResponse.fingerprint(r.content, r.toolCalls)
                            : OpenAIResponse.fingerprint(job.content(), null);
                    if (fp != null) {
                        sessions.put(fp, new Session(sid));
                        trimSessions();
                    }
                } else if (s != null) {
                    sessions.put(job.lastAssistantFp, s); // request gayi hi nahi: purana session wapas
                }
            } else {
                // Request DeepSeek tak gayi thi -> account mein chat bani -> delete karo
                if (job.isStarted() && Config.deleteChats(app)) deleteChat(job);
                // Agli request ke liye fresh page abhi se load kar do (pre-warm)
                prewarm();
            }
        }
    }

    /* ---------------------------------------------------------------- same-chat sessions */

    private static final class Session {
        final String sid;
        final long lastUsed = System.currentTimeMillis();

        Session(String sid) {
            this.sid = sid;
        }
    }

    private static String shorten(String fp) {
        return fp.length() > 40 ? fp.substring(0, 40) + "…" : fp;
    }

    private boolean sidInUse(String sid) {
        for (Session x : sessions.values()) if (x.sid.equals(sid)) return true;
        return false;
    }

    private void deleteSid(String sid) throws InterruptedException {
        if (!Config.deleteChats(app) || sidInUse(sid)) return;
        waitDelete("sid:" + sid, unquote(eval("__fds.deleteSid(" + JSONObject.quote(sid) + ")", 4000)));
    }

    /** MAX_SESSIONS se zyada ho toh sabse purane delete. */
    private void trimSessions() throws InterruptedException {
        while (sessions.size() > MAX_SESSIONS) {
            Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
            Session old = it.next().getValue();
            it.remove();
            deleteSid(old.sid);
        }
    }

    /** SESSION_IDLE_MS se purane sessions delete. */
    private void expireSessions() throws InterruptedException {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        java.util.List<String> dead = new java.util.ArrayList<>();
        while (it.hasNext()) {
            Session x = it.next().getValue();
            if (now - x.lastUsed > SESSION_IDLE_MS) {
                it.remove();
                dead.add(x.sid);
            }
        }
        for (String sid : dead) deleteSid(sid);
    }

    private void dropAllSessions() throws InterruptedException {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (Session x : sessions.values()) all.add(x.sid);
        sessions.clear();
        for (String sid : all) deleteSid(sid);
    }

    /** loadUrl ke saath navigation token, taaki waitReady purane page ko "ready" na samjhe. */
    private void navigate(String url) throws InterruptedException {
        String token = "n" + System.nanoTime();
        evalRaw("window.__fdsNav=" + JSONObject.quote(token) + ";", 2000);
        CountDownLatch loaded = new CountDownLatch(1);
        main.post(() -> {
            try {
                WebView w = WebViewManager.get().obtain(app);
                w.stopLoading();
                w.loadUrl(url);
            } finally {
                loaded.countDown();
            }
        });
        loaded.await(5, TimeUnit.SECONDS);
        navToken = token;
    }

    /** Next request ke liye home page background mein load kar do. */
    public void prewarm() {
        main.post(() -> {
            WebView w = WebViewManager.get().obtain(app);
            w.loadUrl(HOME_URL);
        });
    }

    private void runSteps(ChatJob job, boolean cont) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        String prompt = cont ? job.followUp : job.prompt;
        boolean reloaded = false;

        if (cont) {
            // 1a. Same chat: page pehle se usi chat par ho toh seedha bhejo, warna chat kholo
            String sid = curSid;
            String on = unquote(eval("window.__fds ? __fds.onChat(" + JSONObject.quote(sid) + ") : 'no'", 3000));
            if ("yes".equals(on)) {
                navToken = null;
                AppState.log("Same chat (follow-up only) for " + job.id.substring(0, 8));
            } else {
                AppState.log("Same chat (reopen) for " + job.id.substring(0, 8));
                navigate(HOME_URL + "a/chat/s/" + sid);
                reloaded = true;
            }
        } else {
            // 1b. Fresh chat: pre-warmed page pehle se khaali home par ho toh reload skip
            String fresh = unquote(eval("window.__fds ? __fds.isFresh() : 'no'", 3000));
            if ("yes".equals(fresh)) {
                navToken = null;
                AppState.log("New chat (pre-warmed) for " + job.id.substring(0, 8));
            } else {
                AppState.log("New chat (reload) for " + job.id.substring(0, 8));
                navigate(HOME_URL);
                reloaded = true;
            }
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
                + JSONObject.quote(prompt) + ")", 10_000));
        if (!"ok".equals(filled)) {
            job.fail("Prompt insert failed: " + (filled.isEmpty() ? "no result" : filled));
            return;
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

        // 6b. DeepSeek ne beech mein roka (INCOMPLETE / Continue button) -> Continue click, max 3 baar
        for (int round = 1; round <= MAX_CONTINUES && !job.isClosed() && !job.cancelled; round++) {
            boolean incomplete = "incomplete".equals(job.endReason());
            String has = "no";
            // INCOMPLETE mila ho toh button render hone ka thoda wait, warna ek quick check
            long until = System.currentTimeMillis() + (incomplete ? 2500 : 0);
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
                if (job.cancelled || now - job.lastActivity() > IDLE_TIMEOUT_MS || now - cBegin > MAX_JOB_MS) break;
            }
            if (job.cancelled) {
                eval("__fds.stop()", 3000);
                job.finish("stop");
                return;
            }
        }
        if (job.isClosed()) return;
        if ("incomplete".equals(job.endReason())) finish = "length";

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
        waitDelete(job.id, unquote(eval("__fds.deleteChat(" + JSONObject.quote(job.id) + ")", 4000)));
    }

    /** startResult = JS deleteChat/deleteSid ka return; "started" ho toh result ka wait. */
    private void waitDelete(String key, String startResult) throws InterruptedException {
        if (!"started".equals(startResult)) {
            AppState.log("Chat delete skipped: " + startResult);
            return;
        }
        long deadline = System.currentTimeMillis() + 8000;
        String st = "pending";
        while (System.currentTimeMillis() < deadline) {
            st = unquote(eval("__fds.deleteState(" + JSONObject.quote(key) + ")", 3000));
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
