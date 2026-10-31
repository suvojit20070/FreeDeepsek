package com.deepsek.suvo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** App settings (SharedPreferences). */
public final class Config {

    public static final int DEFAULT_PORT = 8080;

    /** Pehla account WebView ka default profile use karta hai (purana login wahi rehta hai). */
    public static final String DEFAULT_ACCOUNT = "Default";

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

    /* ---------------------------------------------------------------- accounts */

    public static final class Account {
        public final String id;
        public final String label;

        Account(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Accounts list (kam se kam ek: Default). */
    public static synchronized List<Account> accounts(Context c) {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("accounts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("id").isEmpty()) continue;
                out.add(new Account(o.optString("id"), o.optString("label", o.optString("id"))));
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) out.add(new Account(DEFAULT_ACCOUNT, "Account 1"));
        return out;
    }

    public static List<String> accountIds(Context c) {
        List<String> ids = new ArrayList<>();
        for (Account a : accounts(c)) ids.add(a.id);
        return ids;
    }

    public static String accountLabel(Context c, String id) {
        for (Account a : accounts(c)) if (a.id.equals(id)) return a.label;
        return id;
    }

    private static void saveAccounts(Context c, List<Account> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Account a : list) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("label", a.label);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("accounts", arr.toString()).apply();
    }

    /** Naya account banao; uska id return. */
    public static synchronized String addAccount(Context c) {
        List<Account> list = accounts(c);
        int n = list.size() + 1;
        String id = "acc" + Long.toString(System.currentTimeMillis(), 36);
        String label = "Account " + n;
        boolean taken;
        do {
            taken = false;
            for (Account a : list) if (a.label.equals(label)) taken = true;
            if (taken) label = "Account " + (++n);
        } while (taken);
        list.add(new Account(id, label));
        saveAccounts(c, list);
        return id;
    }

    public static synchronized void removeAccount(Context c, String id) {
        List<Account> list = accounts(c);
        if (list.size() <= 1) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        saveAccounts(c, list);
    }
}
