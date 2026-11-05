package com.mi.budsid;

import org.json.JSONObject;

/** 一条替换规则：把识别到的真实代号换成目标代号。 */
public final class Rule {

    /** 真实代号（8 位十六进制）；"*" 表示所有设备。 */
    public String from;
    /** 目标代号（8 位十六进制）。 */
    public String to;
    /** 可选：只对某个 MAC 生效，形如 AA:BB:CC:DD:EE:FF。 */
    public String mac;
    /** 目标型号名称，仅用于界面展示。 */
    public String name;

    public Rule(String from, String to, String mac, String name) {
        this.from = from == null ? "" : from.trim().toUpperCase();
        this.to = to == null ? "" : to.trim().toUpperCase();
        this.mac = mac == null ? "" : mac.trim().toUpperCase();
        this.name = name == null ? "" : name;
    }

    public static Rule of(String from, String to, String mac, String name) {
        return new Rule(from, to, mac, name);
    }

    public boolean isWildcard() {
        return "*".equals(from);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("from", from);
            o.put("to", to);
            if (mac != null && !mac.isEmpty()) o.put("mac", mac);
            if (name != null && !name.isEmpty()) o.put("name", name);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static Rule fromJson(JSONObject o) {
        if (o == null) return null;
        return new Rule(o.optString("from", ""), o.optString("to", ""),
                o.optString("mac", ""), o.optString("name", ""));
    }

    public String title() {
        String left = isWildcard() ? "全部设备" : (from.isEmpty() ? "未知" : from);
        String right = to.isEmpty() ? "未设置" : to;
        return left + "  →  " + right;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) sb.append(name);
        if (mac != null && !mac.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(mac);
        }
        return sb.toString();
    }
}
