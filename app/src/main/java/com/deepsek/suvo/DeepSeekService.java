package com.deepsek.suvo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;

/**
 * Foreground service: WebView ka session zinda rakhta hai, local API server chalata hai,
 * aur Activity band hone par WebView ko overlay keeper mein park karwata hai.
 */
public class DeepSeekService extends Service implements AppState.Listener {

    public static final String ACTION_STOP = "com.deepsek.suvo.action.STOP";
    public static final String ACTION_RESTART_SERVER = "com.deepsek.suvo.action.RESTART_SERVER";

    private static final String CHANNEL_ID = "fds_service";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running = false;

    private final LocalApiServer server = new LocalApiServer();
    private String lastNotificationText = "";

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, DeepSeekService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        goForeground();

        RequestQueue.get().init(this);
        WebViewManager wm = WebViewManager.get();
        wm.obtain(this);
        if (!wm.isAttachedToActivity()) wm.park();

        startServer();
        AppState.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART_SERVER.equals(action)) {
            startServer();
            WebViewManager wm = WebViewManager.get();
            if (!wm.isAttachedToActivity()) wm.park();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        AppState.removeListener(this);
        server.stop();
        AppState.setServer(false, "Server stopped");
        WebViewManager.get().unpark();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        int port = Config.port(this);
        boolean lan = Config.allowLan(this);
        try {
            server.start(port, lan, Config.apiKey(this));
            String host = lan ? "<device-ip>" : "127.0.0.1";
            AppState.setServer(true, "Running · http://" + host + ":" + port + "/v1");
        } catch (IOException e) {
            AppState.setServer(false, "Port " + port + " start failed: " + e.getMessage());
        }
    }

    /* ---------------------------------------------------------------- notification */

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Free Deepsek server",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Local API server status");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private String notificationText() {
        return AppState.serverStatus + " · " + AppState.jobStatus;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, DeepSeekService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getForegroundService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stopPi).build();

        lastNotificationText = notificationText();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(lastNotificationText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(stopAction)
                .build();
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public void onStateChanged() {
        if (!running) return;
        if (notificationText().equals(lastNotificationText)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }
}
