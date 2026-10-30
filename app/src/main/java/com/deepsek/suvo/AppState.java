package com.deepsek.suvo;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** UI aur notification ke liye shared status. Listeners hamesha main thread par call hote hain. */
public final class AppState {

    public interface Listener {
        void onStateChanged();
    }

    private static final String TAG = "FreeDeepsek";
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static volatile boolean serverRunning = false;
    public static volatile String serverStatus = "Server stopped";
    public static volatile String jobStatus = "Idle";
    public static volatile String keepAliveMode = "-";
    public static volatile String lastLog = "";
    public static volatile int served = 0;

    private AppState() {
    }

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void setServer(boolean running, String status) {
        serverRunning = running;
        serverStatus = status;
        log(status);
    }

    public static void setJob(String status) {
        jobStatus = status;
        dispatch();
    }

    public static void setKeepAlive(String mode) {
        keepAliveMode = mode;
        dispatch();
    }

    public static void log(String msg) {
        Log.d(TAG, msg);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        lastLog = time + "  " + msg;
        dispatch();
    }

    private static void dispatch() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) {
                try {
                    l.onStateChanged();
                } catch (Throwable t) {
                    Log.w(TAG, "listener failed", t);
                }
            }
        });
    }
}
