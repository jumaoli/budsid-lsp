package io.github.jumaoli.budsid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Prefs {

    private static final String FILE = "config";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_RULES = "rules";

    /** 首次打开时的默认规则：全部设备显示成 Buds 5 Pro Wi-Fi。 */
    public static final String DEFAULT_ID = "020103004D";
    public static final String DEFAULT_NAME = "Xiaomi Buds 5 Pro Wi-Fi";

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static List<Rule> getRules(Context c) {
        List<Rule> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_RULES, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Rule r = Rule.fromJson(a.optJSONObject(i));
                if (r != null && !r.to.isEmpty()) out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setRules(Context c, List<Rule> rules) {
        JSONArray a = new JSONArray();
        for (Rule r : rules) a.put(r.toJson());
        sp(c).edit().putString(KEY_RULES, a.toString()).apply();
    }


    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void setBool(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }

    public static String getString(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void setString(Context c, String key, String v) {
        sp(c).edit().putString(key, v).apply();
    }

    public static float uiScale(Context c) {
        return getInt(c, "ui_scale", 100) / 100f;
    }

    /** 供注入进程读取的完整配置。 */
    public static String configJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", isEnabled(c));
            o.put("popup", getBool(c, "hook_popup", true));
            o.put("island", getBool(c, "hook_island", true));
            o.put("detail", getBool(c, "hook_detail", true));
            JSONArray a = new JSONArray();
            for (Rule r : getRules(c)) a.put(r.toJson());
            o.put("rules", a);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }
}
