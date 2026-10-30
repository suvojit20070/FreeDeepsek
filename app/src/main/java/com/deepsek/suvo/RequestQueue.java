package com.deepsek.suvo;

import android.content.Context;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** WebView ek hi hai, isliye requests ek-ek karke (single thread) process hoti hain. */
public final class RequestQueue {

    private static final RequestQueue INSTANCE = new RequestQueue();

    public static RequestQueue get() {
        return INSTANCE;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fds-queue");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger waiting = new AtomicInteger();

    private volatile ChatJob active;
    private DeepSeekAutomation automation;
    private PowerManager.WakeLock wakeLock;

    private RequestQueue() {
    }

    public synchronized void init(Context c) {
        if (automation != null) return;
        Context app = c.getApplicationContext();
        automation = new DeepSeekAutomation(app);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeDeepsek:job");
            wakeLock.setReferenceCounted(false);
        }
    }

    public void submit(ChatJob job) {
        final DeepSeekAutomation auto;
        synchronized (this) {
            auto = automation;
        }
        if (auto == null) {
            job.fail("Service ready nahi hai");
            return;
        }
        int w = waiting.incrementAndGet();
        AppState.setJob(active == null ? "Starting…" : "Busy · " + w + " waiting");

        worker.execute(() -> {
            waiting.decrementAndGet();
            if (job.cancelled) {
                job.fail("Client disconnected before start");
                return;
            }
            active = job;
            AppState.setJob("Working · " + job.model + (waiting.get() > 0 ? " · " + waiting.get() + " waiting" : ""));
            if (wakeLock != null) wakeLock.acquire(30 * 60 * 1000L);
            try {
                auto.run(job);
            } catch (Throwable t) {
                job.fail("Internal error: " + t);
            } finally {
                if (!job.isClosed()) job.fail("Job ended unexpectedly");
                active = null;
                AppState.served++;
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                int left = waiting.get();
                AppState.setJob(left > 0 ? "Busy · " + left + " waiting" : "Idle");
            }
        });
    }

    /** JS bridge isse id se active job dhoondta hai. */
    public ChatJob find(String id) {
        ChatJob a = active;
        return (a != null && a.id.equals(id)) ? a : null;
    }

    public int waiting() {
        return waiting.get();
    }

    public boolean isBusy() {
        return active != null;
    }
}
