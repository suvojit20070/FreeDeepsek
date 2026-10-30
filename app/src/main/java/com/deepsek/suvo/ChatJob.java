package com.deepsek.suvo;

import org.json.JSONArray;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ek API request = ek ChatJob.
 * JS bridge chunks daalta hai, HTTP thread events padhta hai,
 * aur automation thread job ko finish/fail karta hai.
 */
public final class ChatJob {

    public static final int EV_START = 1;
    public static final int EV_CHUNK = 2;
    public static final int EV_DONE = 3;
    public static final int EV_ERROR = 4;

    public static final class Event {
        public final int type;
        public final boolean think;
        public final String text;

        Event(int type, boolean think, String text) {
            this.type = type;
            this.think = think;
            this.text = text;
        }
    }

    public final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    public final String prompt;
    public final String model;
    public final boolean think;
    public final boolean search;
    public final boolean stream;
    /** Attach karne wali files: [{name, mime, data(base64)}], khaali ho sakta hai. */
    public final JSONArray files;
    public final long created = System.currentTimeMillis() / 1000L;

    public final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final CountDownLatch startLatch = new CountDownLatch(1);
    // Continue ke baad dobara wait karna padta hai, isliye ye latches reset ho sakte hain
    private volatile CountDownLatch endLatch = new CountDownLatch(1);
    private volatile CountDownLatch restartLatch = new CountDownLatch(1);
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    public volatile boolean cancelled = false;
    private volatile boolean started = false;
    private volatile boolean closed = false;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long firstChunkAt = 0;
    private volatile String endReason;
    private volatile String endError;
    private volatile String finishReason = "stop";
    private volatile String errorMessage;

    /** Same-chat mode: last assistant ke baad ka sirf naya hissa (null = continue possible nahi). */
    public final String followUp;
    /** Client ke aakhri assistant message ka fingerprint (humare pichhle reply se match karne ke liye). */
    public final String lastAssistantFp;

    public ChatJob(String prompt, String model, boolean think, boolean search, boolean stream, JSONArray files) {
        this(prompt, model, think, search, stream, files, null, null);
    }

    public ChatJob(String prompt, String model, boolean think, boolean search, boolean stream, JSONArray files,
                   String followUp, String lastAssistantFp) {
        this.followUp = followUp;
        this.lastAssistantFp = lastAssistantFp;
        this.prompt = prompt;
        this.model = model;
        this.think = think;
        this.search = search;
        this.stream = stream;
        this.files = files == null ? new JSONArray() : files;
    }

    private void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /* ------------------------------------------------ bridge se (JavaBridge thread) */

    /** Bridge onStart: pehli baar = start, baad mein = continue wali stream shuru hui. */
    public void onStreamStart() {
        if (!started) markStarted();
        else {
            touch();
            restartLatch.countDown();
        }
    }

    /** Continue click se pehle: naye stream ke liye end/restart latches reset. */
    public synchronized void prepareContinue() {
        if (closed) return;
        endReason = null;
        endError = null;
        endLatch = new CountDownLatch(1);
        restartLatch = new CountDownLatch(1);
    }

    public boolean awaitRestart(long ms) throws InterruptedException {
        return restartLatch.await(ms, TimeUnit.MILLISECONDS);
    }

    public synchronized void markStarted() {
        if (started || closed) return;
        started = true;
        touch();
        events.offer(new Event(EV_START, false, null));
        startLatch.countDown();
    }

    public synchronized void addChunk(boolean isThink, String text) {
        if (closed || text == null || text.isEmpty()) return;
        if (isThink) reasoning.append(text);
        else content.append(text);
        touch();
        if (firstChunkAt == 0) firstChunkAt = lastActivity;
        events.offer(new Event(EV_CHUNK, isThink, text));
    }

    public void streamEnded(String reason, String error) {
        endReason = reason;
        endError = (error == null || error.isEmpty()) ? null : error;
        touch();
        endLatch.countDown();
    }

    /* ------------------------------------------------ automation se */

    public synchronized void finish(String reason) {
        if (closed) return;
        closed = true;
        finishReason = reason == null ? "stop" : reason;
        events.offer(new Event(EV_DONE, false, finishReason));
        release();
    }

    public synchronized void fail(String message) {
        if (closed) return;
        closed = true;
        errorMessage = message == null ? "Unknown error" : message;
        events.offer(new Event(EV_ERROR, false, errorMessage));
        release();
    }

    private void release() {
        startLatch.countDown();
        endLatch.countDown();
        restartLatch.countDown();
        closedLatch.countDown();
    }

    public boolean awaitStart(long ms) throws InterruptedException {
        startLatch.await(ms, TimeUnit.MILLISECONDS);
        return started;
    }

    public boolean awaitEnd(long ms) throws InterruptedException {
        return endLatch.await(ms, TimeUnit.MILLISECONDS);
    }

    public boolean awaitClosed(long ms) throws InterruptedException {
        return closedLatch.await(ms, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------ getters */

    public boolean isStarted() {
        return started;
    }

    public boolean isClosed() {
        return closed;
    }

    public long lastActivity() {
        return lastActivity;
    }

    /** Pehla token kab aaya (0 = abhi nahi). */
    public long firstChunkAt() {
        return firstChunkAt;
    }

    public String endReason() {
        return endReason;
    }

    public String endError() {
        return endError;
    }

    public String finishReason() {
        return finishReason;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public synchronized String content() {
        return content.toString();
    }

    public synchronized String reasoning() {
        return reasoning.toString();
    }

    public synchronized boolean isEmpty() {
        return content.length() == 0 && reasoning.length() == 0;
    }
}
