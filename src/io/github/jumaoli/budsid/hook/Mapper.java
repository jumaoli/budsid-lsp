package io.github.jumaoli.budsid.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取模块配置，并在注入进程里完成「真实代号 → 目标代号」的查表。
 * 配置通过 ContentProvider 读取，1.5 秒缓存一次。
 */
final class Mapper {

    private static final String URL = "content://io.github.jumaoli.budsid.config/config";
    private static final long TTL_MS = 1500L;

    private static final class R {
        String from;
        String to;
        String mac;
    }

    private static volatile List<R> rules = new ArrayList<>();
    private static volatile boolean enabled = true;
    private static volatile boolean popup = true;
    private static volatile boolean island = true;
    private static volatile boolean detail = true;
    private static volatile long lastLoad = 0L;
    private static Context appContext;

    private Mapper() {
    }

    static synchronized void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (lastLoad != 0L && now - lastLoad < TTL_MS) return;
        lastLoad = now;
        try {
            Context c = app();
            if (c == null) return;
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(Uri.parse(URL), null, null, null, null);
                if (cur == null || !cur.moveToFirst()) return;
                String json = cur.getString(0);
                if (json == null) return;
                JSONObject o = new JSONObject(json);
                enabled = o.optBoolean("enabled", true);
                popup = o.optBoolean("popup", true);
                island = o.optBoolean("island", true);
                detail = o.optBoolean("detail", true);
                JSONArray a = o.optJSONArray("rules");
                List<R> list = new ArrayList<>();
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject jo = a.optJSONObject(i);
                        if (jo == null) continue;
                        R r = new R();
                        r.from = upper(jo.optString("from", ""));
                        r.to = upper(jo.optString("to", ""));
                        r.mac = upper(jo.optString("mac", ""));
                        if (r.from.isEmpty()) r.from = "*";
                        if (!r.to.isEmpty()) list.add(r);
                    }
                }
                rules = list;
            } finally {
                if (cur != null) cur.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 返回替换后的代号；没有匹配时原样返回。 */
    static String map(String deviceId, String mac) {
        return map(deviceId, mac, false);
    }

    /**
     * 某些调用点（弹窗取图、资源记录）拿不到 MAC，这时忽略 MAC 限定直接套用规则。
     * 只有一条目标规则时最可靠；多条按 MAC 区分的规则请尽量在能拿到 MAC 的调用点使用。
     */
    static String mapAny(String deviceId) {
        return map(deviceId, null, true);
    }

    private static String map(String deviceId, String mac, boolean ignoreMac) {
        if (deviceId == null || deviceId.isEmpty()) return deviceId;
        refresh();
        if (!enabled) return deviceId;
        String real = upper(deviceId);
        String best = null;
        int score = -1;
        for (R r : rules) {
            if (!"*".equals(r.from) && !r.from.equals(real)) continue;
            if (!r.mac.isEmpty()) {
                if (!ignoreMac && (mac == null || !r.mac.equalsIgnoreCase(mac))) continue;
                if (score < 2) {
                    best = r.to;
                    score = 2;
                }
            } else if (score < 1) {
                best = r.to;
                score = 1;
            }
        }
        return best != null ? best : deviceId;
    }

    /** 某个界面是否启用 hook：popup / island / detail。 */
    static boolean surface(String name) {
        refresh();
        if (!enabled) return false;
        if ("popup".equals(name)) return popup;
        if ("island".equals(name)) return island;
        if ("detail".equals(name)) return detail;
        return true;
    }

    static boolean isEnabled() {
        refresh();
        return enabled;
    }

    private static Context app() {
        if (appContext != null) return appContext;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object o = m.invoke(null);
            if (o instanceof Context) appContext = (Context) o;
        } catch (Throwable ignored) {
        }
        return appContext;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
