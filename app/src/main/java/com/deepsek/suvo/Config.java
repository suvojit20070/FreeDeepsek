package com.deepsek.suvo;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings (SharedPreferences). */
public final class Config {

    public static final int DEFAULT_PORT = 8080;

    private Config() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("fds_config", Context.MODE_PRIVATE);
    }

    public static int port(Context c) {
        return prefs(c).getInt("port", DEFAULT_PORT);
    }

    /** true = 0.0.0.0 par bind (same WiFi ke devices bhi use kar sakte hain). */
    public static boolean allowLan(Context c) {
        return prefs(c).getBoolean("allow_lan", false);
    }

    /** Khaali = koi API key check nahi. */
    public static String apiKey(Context c) {
        return prefs(c).getString("api_key", "");
    }

    /** Background mein WebView ko 1px overlay window mein zinda rakho. */
    public static boolean keepAlive(Context c) {
        return prefs(c).getBoolean("keep_alive", true);
    }

    /** Response ke baad DeepSeek account se chat delete karo (temp-chat jaisa). */
    public static boolean deleteChats(Context c) {
        return prefs(c).getBoolean("delete_chats", true);
    }

    public static void save(Context c, int port, boolean allowLan, String apiKey, boolean keepAlive,
                            boolean deleteChats) {
        prefs(c).edit()
                .putInt("port", port)
                .putBoolean("allow_lan", allowLan)
                .putString("api_key", apiKey == null ? "" : apiKey.trim())
                .putBoolean("keep_alive", keepAlive)
                .putBoolean("delete_chats", deleteChats)
                .apply();
    }
}
