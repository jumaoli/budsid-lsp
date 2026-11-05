package com.mi.budsid;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 代号库：内置 assets/devices.json + 本机 fc_resources 实时同步的补充。 */
public final class Devices {

    public static final class Dev {
        public final String id;
        public final String name;
        public final boolean anim;
        public final boolean tws;

        Dev(String id, String name, boolean anim, boolean tws) {
            this.id = id;
            this.name = name;
            this.anim = anim;
            this.tws = tws;
        }

        public String line() {
            StringBuilder sb = new StringBuilder(id);
            sb.append("  ").append(name);
            if (anim) sb.append("  ●");
            return sb.toString();
        }
    }

    private static final String LOCAL = "local_devices.json";
    private static final String CLOUD_FILE = "devices_cloud.json";
    private static List<Dev> cache;
    private static final java.util.Map<String, String> ALIAS = new java.util.HashMap<>();

    static {
        ALIAS.put("01013304", "小米 AI 眼镜");
        ALIAS.put("00000100", "小米 AI 眼镜（测试）");
        ALIAS.put("00000101", "小米 AI 眼镜（测试 2）");
    }

    private static String alias(String id, String fallback) {
        String a = ALIAS.get(id);
        return a != null ? a : fallback;
    }
    private static java.util.Set<String> animIds;

    private static synchronized java.util.Set<String> animSet(Context c) {
        if (animIds != null) return animIds;
        animIds = new java.util.HashSet<>();
        try {
            JSONArray a = new JSONArray(readAsset(c, "anim_ids.json"));
            for (int i = 0; i < a.length(); i++) animIds.add(a.optString(i));
        } catch (Throwable ignored) {
        }
        return animIds;
    }

    private static boolean hasAnim(Context c, String id) {
        if (animSet(c).contains(id)) return true;
        return false;
    }

    private Devices() {
    }

    public static synchronized List<Dev> all(Context c) {
        if (cache != null) return cache;
        List<Dev> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String json = readAsset(c, "devices.json");
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (!id.matches("[0-9A-Fa-f]{8,12}")) continue;
                if ("00000000".equals(id)) continue;
                if (o.optBoolean("disabled", false)) continue;
                if (!seen.add(id)) continue;
                boolean anim = !"00000000".equals(id);
                out.add(new Dev(id, alias(id, o.optString("name", "")), anim,
                        o.optBoolean("tws", false)));
            }
        } catch (Throwable ignored) {
        }
        java.util.Map<String, String> cloudNames = new java.util.HashMap<>();
        try {
            JSONArray a = new JSONArray(readCloud(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) cloudNames.put(o.optString("id", ""), o.optString("name", ""));
            }
        } catch (Throwable ignored) {
        }
        List<Dev> merged = new ArrayList<>();
        for (Dev d : out) {
            String cn = cloudNames.remove(d.id);
            merged.add(cn != null && cn.length() > 0
                    ? new Dev(d.id, cn, !"00000000".equals(d.id), d.tws) : d);
        }
        for (java.util.Map.Entry<String, String> e : cloudNames.entrySet()) {
            if (e.getKey().length() != 8 || !seen.add(e.getKey())) continue;
            merged.add(new Dev(e.getKey(), e.getValue(), !"00000000".equals(e.getKey()), false));
        }
        out = merged;
        try {
            JSONArray a = new JSONArray(readLocal(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.length() != 8 || !seen.add(id)) continue;
                String name = o.optString("name", "");
                if (name.length() == 0) name = "本机设备 " + id;
                name = alias(id, name);
                out.add(new Dev(id, name, o.optBoolean("anim", false), false));
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new java.util.Comparator<Dev>() {
            @Override
            public int compare(Dev x, Dev y) {
                boolean xe = x.id.length() == 8;
                boolean ye = y.id.length() == 8;
                if (xe != ye) return xe ? -1 : 1;
                if (x.anim != y.anim) return x.anim ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        cache = out;
        return out;
    }

    public static Dev byId(Context c, String id) {
        if (id == null) return null;
        for (Dev d : all(c)) {
            if (d.id.equalsIgnoreCase(id)) return d;
        }
        return null;
    }

    public static String nameOf(Context c, String id) {
        Dev d = byId(c, id);
        return d == null ? "" : d.name;
    }

    /**
     * 从本机 com.xiaomi.bluetooth 的 fc_resources 目录实时同步代号：
     * 系统每次连接新耳机都会在这里建目录，所以这比内置表新。
     * 只读目录名，不碰任何文件。返回新增数量。
     */
    public static int syncFromDevice(Context c) {
        String cmd = "for d in /data/user_de/0/com.xiaomi.bluetooth/files/fc_resources/*/; "
                + "do n=$(basename $d); c=$(ls $d 2>/dev/null | wc -l); "
                + "if [ ${#n} -eq 8 ]; then echo \"$n $c\"; fi; done";
        List<Dev> found = new ArrayList<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 1 || parts[0].length() != 8) continue;
                if (!parts[0].matches("[0-9A-Fa-f]{8}")) continue;
                int count = parts.length > 1 ? parseInt(parts[1]) : 0;
                found.add(new Dev(parts[0].toUpperCase(), "", count > 60, false));
            }
            r.close();
            p.waitFor();
        } catch (Throwable ignored) {
            if (p != null) p.destroy();
        }
        if (found.isEmpty()) return 0;
        Set<String> known = new HashSet<>();
        for (Dev d : all(c)) known.add(d.id);
        JSONArray out = new JSONArray();
        int added = 0;
        try {
            JSONArray old = new JSONArray(readLocal(c));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null) out.put(o);
            }
        } catch (Throwable ignored) {
        }
        Set<String> localIds = new HashSet<>();
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.optJSONObject(i);
            if (o != null) localIds.add(o.optString("id", ""));
        }
        for (Dev d : found) {
            if (known.contains(d.id) || !localIds.add(d.id)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", d.id);
                o.put("name", "本机设备 " + d.id);
                o.put("anim", d.anim);
                out.put(o);
                added++;
            } catch (Throwable ignored) {
            }
        }
        try {
            File f = new File(c.getFilesDir(), LOCAL);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(out.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
        cache = null;
        return added;
    }

    /** 合并模块从 com.xiaomi.bluetooth 进程发来的代号表（无需 root）。 */
    public static synchronized int mergeCatalog(Context c, String json) {
        if (json == null || json.length() < 3) return 0;
        Set<String> known = new HashSet<>();
        for (Dev d : all(c)) known.add(d.id);
        JSONArray out = new JSONArray();
        try {
            JSONArray old = new JSONArray(readLocal(c));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null) out.put(o);
            }
        } catch (Throwable ignored) {
        }
        Set<String> localIds = new HashSet<>();
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.optJSONObject(i);
            if (o != null) localIds.add(o.optString("id", ""));
        }
        int added = 0;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").toUpperCase();
                if (id.length() != 8 || known.contains(id) || !localIds.add(id)) continue;
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("name", alias(id, "本机设备 " + id));
                item.put("anim", o.optInt("count", 0) > 60);
                out.put(item);
                added++;
            }
        } catch (Throwable ignored) {
        }
        try {
            FileOutputStream fos = new FileOutputStream(new File(c.getFilesDir(), LOCAL));
            fos.write(out.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
        cache = null;
        return added;
    }

    /** 从小米 CDN 拉 00000000.zip 里的 resource_record.xml，拿到全部 455 个代号。 */
    public static int updateFromCloud(Context c) {
        String xml = null;
        HttpURLConnection conn = null;
        ZipInputStream zin = null;
        try {
            URL url = new URL("https://cdn.cnbj1.fds.api.mi-img.com/"
                    + "bluetoothencrypt/fast_connect/00000000.zip");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) return -1;
            zin = new ZipInputStream(conn.getInputStream());
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".xml")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                xml = new String(bos.toByteArray(), "UTF-8");
                break;
            }
        } catch (Throwable t) {
            android.util.Log.i("BudsId", "cloud fetch failed: " + t);
            return -1;
        } finally {
            try { if (zin != null) zin.close(); } catch (Throwable ignored) { }
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
        }
        if (xml == null || xml.length() < 100) {
            android.util.Log.i("BudsId", "cloud xml empty");
            return -1;
        }
        JSONArray out = new JSONArray();
        int count = 0;
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            NodeList list = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                Node n = list.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                if (!el.getTagName().startsWith("device_")) continue;
                String id = text(el, "deviceId");
                if (id == null || !id.matches("[0-9A-Fa-f]{8,12}")) continue;
                if ("true".equalsIgnoreCase(text(el, "disabled"))) continue;
                String name = text(el, "deviceName");
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id.toUpperCase());
                    o.put("name", name == null ? "" : name);
                    out.put(o);
                    count++;
                } catch (Throwable ignored) {
                }
            }
            FileOutputStream fos = new FileOutputStream(new File(c.getFilesDir(), CLOUD_FILE));
            fos.write(out.toString().getBytes("UTF-8"));
            fos.close();
            cache = null;
        } catch (Throwable t) {
            android.util.Log.i("BudsId", "cloud parse failed: " + t);
            return -1;
        }
        android.util.Log.i("BudsId", "cloud parsed " + count);
        return count;
    }

    private static String text(Element el, String tag) {
        NodeList l = el.getElementsByTagName(tag);
        if (l.getLength() == 0) return null;
        return l.item(0).getTextContent();
    }

    private static String readCloud(Context c) {
        try {
            File f = new File(c.getFilesDir(), CLOUD_FILE);
            if (!f.exists()) return "[]";
            InputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "[]";
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readLocal(Context c) {
        try {
            File f = new File(c.getFilesDir(), LOCAL);
            if (!f.exists()) return "[]";
            InputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "[]";
        }
    }

    private static String readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }
}
