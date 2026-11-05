package io.github.jumaoli.budsid.hook;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 注入 com.xiaomi.bluetooth。
 *
 * 小米蓝牙里的「耳机代号」（deviceId）来自两处：
 *   1. 广播解析：MiuiAdvDataInfo / MiuiHeadsetInfo 的 getDeviceId()；
 *   2. DeviceIdCached 里的 saved&lt;MAC&gt; 缓存。
 * 弹窗动画、超级岛视频、设置页设备图、功能页都按这个代号去取资源，
 * 所以只要在读代号的地方换成目标代号，音频链路完全不用动。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "BudsId: ";
    private static final String PKG = "com.xiaomi.bluetooth";
    private static final String FC = "com.android.bluetooth.ble.app.fastconnect.";

    private static final String[] ID_CLASSES = {
            FC + "MiuiAdvDataInfo",
            FC + "MiuiAdvDataInfoV2",
            FC + "MiuiHeadsetInfo",
            FC + "MiuiHeadsetInfoV2",
    };

    private static final Set<String> HOOKED = new HashSet<>();
    private static int replaced = 0;
    private static int cursorLogs = 0;
    private static int imgLogs = 0;
    private static final java.util.Set<String> PREFETCHING = new java.util.HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("com.android.settings".equals(lp.packageName)) {
            log("loaded in settings " + lp.processName);
            try {
                hookSettingsImages();
            } catch (Throwable t) {
                log("settings hook failed: " + t);
            }
            return;
        }
        if ("com.milink.service".equals(lp.packageName)) {
            log("loaded in MiLink " + lp.processName);
            try {
                hookMiLink(lp.classLoader);
            } catch (Throwable t) {
                log("milink hook failed: " + t);
            }
            return;
        }
        if (!PKG.equals(lp.packageName)) return;
        log("loaded in " + lp.processName + " (first=" + lp.isFirstApplication + ")");
        try {
            hookPrefs(lp.classLoader);
        } catch (Throwable t) {
            log("prefs hook failed: " + t);
        }
        try {
            hookBase(lp.classLoader);
        } catch (Throwable t) {
            log("base hook failed: " + t);
        }
        try {
            hookHeadsetProvider(lp.classLoader);
        } catch (Throwable t) {
            log("provider hook failed: " + t);
        }
        try {
            scheduleHeadsetOpenFile(lp.classLoader);
        } catch (Throwable t) {
            log("openFile schedule failed: " + t);
        }
        try {
            for (String name : ID_CLASSES) {
                try {
                    hookIdClass(XposedHelpers.findClass(name, lp.classLoader));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("known class hook failed: " + t);
        }
        try {
            hookFastResourceImages(lp.classLoader);
        } catch (Throwable t) {
            log("image sync hook failed: " + t);
        }
        try {
            hookClassLoader();
        } catch (Throwable t) {
            log("classloader hook failed: " + t);
        }
        try {
            registerThumbReceiver();
        } catch (Throwable t) {
            log("thumb receiver failed: " + t);
        }
        try {
            registerPrefetchReceiver();
        } catch (Throwable t) {
            log("prefetch receiver failed: " + t);
        }
        try {
            registerCatalogReceiver();
        } catch (Throwable t) {
            log("catalog receiver failed: " + t);
        }
        try {
            scheduleFastResourceHook(lp.classLoader);
        } catch (Throwable t) {
            log("schedule image hook failed: " + t);
        }
    }

    /** 弹窗图标/盒子图是按 deviceId 命名读文件的，这里把读文件的代号一起换掉。 */
    private static File findImage(File dir, String id, String index, String suffix) {
        String[] exts = {suffix, "webp", "png"};
        String[] idxs = {index, "0"};
        for (String ext : exts) {
            if (ext == null || ext.isEmpty()) continue;
            for (String idx : idxs) {
                File f = new File(dir, "fc_" + id + "_500" + idx + "." + ext);
                if (f.exists()) return f;
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("fc_" + id + "_500")) return f;
            }
        }
        return null;
    }

    private static boolean firstFramework = true;

    private static void scheduleHeadsetOpenFile(final ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < 40; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        hookHeadsetOpenFile(cl);
                    } catch (Throwable ignored) {
                    }
                    try {
                        hookProxyOpenFile(cl);
                    } catch (Throwable ignored) {
                    }
                    if (firstFramework) {
                        firstFramework = false;
                        try {
                            hookFrameworkProvider();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, 300L * i);
        }
    }

    private static void hookHeadsetOpenFile(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.android.bluetooth.ble.app.headset.HeadsetProvider", cl);
            hookOpenFileMethods(c, "headset");
        } catch (Throwable ignored) {
        }
    }

    private static void hookProxyOpenFile(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.iqiyi.android.qigsaw.core.extension.ContentProviderProxy", cl);
            hookOpenFileMethods(c, "proxy");
            synchronized (HOOKED) {
                if (!HOOKED.add(c.getName() + "#oncreate")) return;
            }
            for (final Method m : c.getDeclaredMethods()) {
                if (!"onCreate".equals(m.getName())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object real = null;
                                for (java.lang.reflect.Field f : param.thisObject.getClass()
                                        .getDeclaredFields()) {
                                    if (!f.getName().toLowerCase().contains("provider")) continue;
                                    f.setAccessible(true);
                                    Object v = f.get(param.thisObject);
                                    if (v instanceof android.content.ContentProvider) {
                                        real = v;
                                        break;
                                    }
                                }
                                if (real == null) return;
                                log("proxy real provider " + real.getClass().getName());
                                hookOpenFileMethods(real.getClass(), "plugin");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookFrameworkProvider() {
        try {
            Class<?> c = Class.forName("android.content.ContentProvider");
            hookOpenFileMethods(c, "fw");
        } catch (Throwable ignored) {
        }
    }

    private static void hookOpenFileMethods(final Class<?> c, final String tag) {
        if (c == null) return;
        synchronized (HOOKED) {
            if (!HOOKED.add(c.getName() + "#of-" + tag)) return;
        }
        int n = 0;
        for (final Method m : c.getDeclaredMethods()) {
            String mn = m.getName();
            if (!"openFile".equals(mn) && !"openAssetFile".equals(mn)
                    && !"openTypedAssetFile".equals(mn)) continue;
            if (m.getParameterTypes().length < 2
                    || m.getParameterTypes()[0] != android.net.Uri.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object a0 = param.args[0];
                            if (!(a0 instanceof android.net.Uri)) return;
                            String uri = a0.toString();
                            if (!uri.contains("headset_resource_update")) return;
                            String[] segs = uri.split("/");
                            if (segs.length < 4) return;
                            String index = segs[segs.length - 1];
                            String suffix = segs[segs.length - 2];
                            String devId = segs[segs.length - 3];
                            if (!devId.matches("[0-9A-Fa-f]{8,12}")) return;
                            String mapped = Mapper.mapAny(devId);
                            if (mapped == null || mapped.equalsIgnoreCase(devId)) return;
                            Context ctx = appContext();
                            if (ctx == null) return;
                            File dir = new File(ctx.getFilesDir(), "fc_resources/" + mapped);
                            File f = findImage(dir, mapped, index, suffix);
                            if (f == null) return;
                            param.setResult(android.os.ParcelFileDescriptor.open(
                                    f, android.os.ParcelFileDescriptor.MODE_READ_ONLY));
                            log(tag + " img -> " + f.getName());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n > 0) log("hooked " + tag + " openFile x" + n);
    }

    private static void hookProviderOpenFile(final Class<?> c) {
        hookOpenFileMethods(c, "provider");
    }

    private static void hookSettingsImages() {
        try {
            Class<?> cr = Class.forName("android.content.ContentResolver");
            int n = 0;
            for (final Method m : cr.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"openInputStream".equals(mn) && !"openAssetFileDescriptor".equals(mn)
                        && !"openFileDescriptor".equals(mn)
                        && !"openTypedAssetFileDescriptor".equals(mn)) continue;
                if (m.getParameterTypes().length < 1
                        || m.getParameterTypes()[0] != android.net.Uri.class) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object a0 = param.args[0];
                                if (!(a0 instanceof android.net.Uri)) return;
                                String uri = a0.toString();
                                if (!uri.contains("headset") && !uri.contains("fc_resources")) return;
                                log("settings uri " + uri);
                                if (!Mapper.surface("detail")) return;
                                String[] segs = uri.split("/");
                                String replaced = uri;
                                boolean changed = false;
                                for (String seg : segs) {
                                    if (!seg.matches("[0-9A-Fa-f]{8,12}")) continue;
                                    String mapped = Mapper.mapAny(seg);
                                    if (mapped == null || mapped.equalsIgnoreCase(seg)) continue;
                                    replaced = replaced.replace("/" + seg + "/", "/" + mapped + "/");
                                    changed = true;
                                }
                                if (changed) {
                                    param.args[0] = android.net.Uri.parse(replaced);
                                    log("settings uri -> " + replaced);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            log("settings hooks x" + n);
        } catch (Throwable t) {
            log("settings hook failed: " + t);
        }
    }

    private static void hookFastResource(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    FC + "MiuiFastConnectResource", cl);
            String name = c.getName();
            synchronized (HOOKED) {
                if (!HOOKED.add(name + "#drawable")) return;
            }
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"getDrawableFromFile".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[0] != String.class || ps[1] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object a0 = param.args[0];
                            Object a1 = param.args[1];
                            if (!(a0 instanceof String) || !(a1 instanceof String)) return;
                            String id = (String) a0;
                            String file = (String) a1;
                            String mapped = Mapper.map(id, null);
                            if (mapped == null || mapped.equals(id)) return;
                            param.args[0] = mapped;
                            param.args[1] = file.replace(id, mapped);
                        } catch (Throwable t) {
                            log("resource swap error: " + t);
                        }
                    }
                });
                n++;
            }
            if (n > 0) log("hooked " + name + ".getDrawableFromFile x" + n);
        } catch (Throwable t) {
            log("fast resource hook unavailable: " + t);
        }
    }

    /** UI 进程点「从本机更新」时发广播过来，这里把 fc_resources 的目录名回传。 */
    /** App 改目标型号时发广播过来，立刻把目标资源包下下来，避免首次弹窗是占位。 */
    /** UI 拉不到 CDN 图（403）时，回传本机 fc_resources 里的图。 */
    private static final java.util.concurrent.ExecutorService THUMB_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private static void downloadThumbAndSend(final Context ctx, final String id) {
        THUMB_POOL.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                ZipInputStream zin = null;
                try {
                    URL url = new URL("https://cdn.cnbj1.fds.api.mi-img.com/"
                            + "bluetoothencrypt/fast_connect/" + id + ".zip");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        log("thumb dl " + id + " http " + code);
                        return;
                    }
                    zin = new ZipInputStream(conn.getInputStream());
                    ZipEntry e;
                    byte[] buf = new byte[16384];
                    byte[] hit = null;
                    String exact = "fc_" + id + "_hs_ok";
                    while ((e = zin.getNextEntry()) != null) {
                        String name = e.getName();
                        if (!name.startsWith("fc_" + id + "_")) continue;
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        int n;
                        while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                        if (name.startsWith(exact)) {
                            hit = bos.toByteArray();
                            break;
                        }
                        if (hit == null && (name.contains("_hs") || name.contains("_box")
                                || name.contains("_5000"))) {
                            hit = bos.toByteArray();
                        }
                    }
                    if (hit == null) return;
                    String b64 = android.util.Base64.encodeToString(hit, android.util.Base64.NO_WRAP);
                    Intent out = new Intent("io.github.jumaoli.budsid.THUMB");
                    out.putExtra("id", id);
                    out.putExtra("data", b64);
                    out.setPackage("io.github.jumaoli.budsid");
                    ctx.sendBroadcast(out);
                    log("thumb dl sent " + id + " " + hit.length);
                } catch (Throwable t) {
                    log("thumb dl failed " + id + ": " + t);
                } finally {
                    try { if (zin != null) zin.close(); } catch (Throwable ignored) { }
                    try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static void registerThumbReceiver() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Context ctx = appContext();
                if (ctx == null) {
                    handler.postDelayed(this, 300);
                    return;
                }
                BroadcastReceiver r = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        try {
                            String id = i.getStringExtra("id");
                            boolean big = i.getBooleanExtra("big", false)
                                    || "io.github.jumaoli.budsid.THUMB_BIG_REQ".equals(i.getAction());
                            log("thumb req " + id + " big=" + big);
                            if (id == null) return;
                            File dir = new File(ctx.getFilesDir(), "fc_resources/" + id);
                            File[] files = dir.listFiles();
                            if (files == null) return;
                            File best = null;
                            if (big) {
                                for (File f : files) {
                                    if (f.getName().startsWith("fc_" + id + "_5000")) { best = f; break; }
                                }
                            }
                            if (best == null) {
                                for (File f : files) {
                                    String n = f.getName();
                                    if (n.startsWith("fc_" + id + "_hs_ok")) { best = f; break; }
                                    if (best == null && n.contains("_hs")) best = f;
                                    if (best == null && n.contains("_box")) best = f;
                                }
                            }
                            if (best == null) {
                                downloadThumbAndSend(ctx, id);
                                return;
                            }
                            byte[] data = new byte[(int) best.length()];
                            java.io.FileInputStream in = new java.io.FileInputStream(best);
                            int off = 0;
                            while (off < data.length) {
                                int n = in.read(data, off, data.length - off);
                                if (n <= 0) break;
                                off += n;
                            }
                            in.close();
                            String b64 = android.util.Base64.encodeToString(data,
                                    android.util.Base64.NO_WRAP);
                            Intent out = new Intent("io.github.jumaoli.budsid.THUMB");
                            out.putExtra("id", id);
                            out.putExtra("big", big);
                            out.putExtra("data", b64);
                            out.setPackage("io.github.jumaoli.budsid");
                            ctx.sendBroadcast(out);
                            log("thumb sent " + id + " " + data.length);
                        } catch (Throwable t) {
                            log("thumb send failed: " + t);
                        }
                    }
                };
                IntentFilter f = new IntentFilter("io.github.jumaoli.budsid.THUMB_REQ");
                f.addAction("io.github.jumaoli.budsid.THUMB_BIG_REQ");
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
                    } else {
                        ctx.registerReceiver(r, f);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 300);
    }

    private static void registerPrefetchReceiver() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Context ctx = appContext();
                if (ctx == null) {
                    handler.postDelayed(this, 300);
                    return;
                }
                BroadcastReceiver r = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        try {
                            String id = i.getStringExtra("id");
                            if (id != null && id.matches("[0-9A-Fa-f]{8}")) {
                                log("prefetch request " + id);
                                prefetchResources(id.toUpperCase());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                };
                IntentFilter f = new IntentFilter("io.github.jumaoli.budsid.PREFETCH");
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
                    } else {
                        ctx.registerReceiver(r, f);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 300);
    }

    private static void registerCatalogReceiver() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            private int tries;

            @Override
            public void run() {
                Context ctx = appContext();
                if (ctx == null) {
                    if (++tries < 40) handler.postDelayed(this, 250);
                    return;
                }
                try {
                    doRegisterCatalog(ctx);
                } catch (Throwable t) {
                    log("catalog receiver failed: " + t);
                }
            }
        }, 300);
    }

    private static void doRegisterCatalog(final Context ctx) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    File root = new File(ctx.getFilesDir(), "fc_resources");
                    File[] dirs = root.listFiles();
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    if (dirs != null) {
                        for (File d : dirs) {
                            String name = d.getName();
                            if (!d.isDirectory() || name.length() != 8) continue;
                            if (!name.matches("[0-9A-Fa-f]{8}")) continue;
                            File[] files = d.listFiles();
                            int count = files == null ? 0 : files.length;
                            if (!first) sb.append(',');
                            first = false;
                            sb.append("{\"id\":\"").append(name.toUpperCase())
                                    .append("\",\"count\":").append(count).append('}');
                        }
                    }
                    sb.append(']');
                    Intent out = new Intent("io.github.jumaoli.budsid.CATALOG");
                    out.putExtra("json", sb.toString());
                    out.setPackage("io.github.jumaoli.budsid");
                    ctx.sendBroadcast(out);
                    log("catalog sent n=" + (sb.length()));
                } catch (Throwable t) {
                    log("catalog send failed: " + t);
                }
            }
        };
        IntentFilter filter = new IntentFilter("io.github.jumaoli.budsid.CATALOG_REQ");
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        log("catalog receiver registered");
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object o = at.getMethod("currentApplication").invoke(null);
            if (o instanceof Context) return (Context) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 目标型号第一次被使用时，系统还没下载它的 fc_resources，弹窗会先显示占位图。
     * 这里在连接开始就后台把 CDN 的弹窗包下下来解压到 fc_resources/&lt;目标代号&gt;/，
     * 只新建目标目录，不覆盖任何原有文件。
     */
    private static void prefetchResources(final String deviceId) {
        if (deviceId == null || deviceId.length() != 8) return;
        final Context ctx = appContext();
        if (ctx == null) return;
        final File dir = new File(ctx.getFilesDir(), "fc_resources/" + deviceId);
        File[] existing = dir.listFiles();
        if (existing != null && existing.length > 60) return;
        synchronized (PREFETCHING) {
            if (!PREFETCHING.add(deviceId)) return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                ZipInputStream zin = null;
                try {
                    URL url = new URL("https://cdn.cnbj1.fds.api.mi-img.com/"
                            + "bluetoothencrypt/fast_connect/" + deviceId + ".zip");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                    if (conn.getResponseCode() != 200) return;
                    if (!dir.exists() && !dir.mkdirs()) return;
                    zin = new ZipInputStream(conn.getInputStream());
                    ZipEntry entry;
                    byte[] buf = new byte[8192];
                    int copied = 0;
                    while ((entry = zin.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        String name = entry.getName();
                        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                        if (!(name.endsWith(".webp") || name.endsWith(".png"))) continue;
                        FileOutputStream out = new FileOutputStream(new File(dir, name));
                        try {
                            int n;
                            while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                        } finally {
                            out.close();
                        }
                        copied++;
                    }
                    log("prefetch " + deviceId + " files=" + copied);
                } catch (Throwable t) {
                    log("prefetch failed " + deviceId + ": " + t);
                } finally {
                    try { if (zin != null) zin.close(); } catch (Throwable ignored) { }
                    try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }, "budsid-prefetch").start();
    }

    /**
     * 插件类由 Qigsaw 在 Application 创建期间才挂到 classloader，handleLoadPackage 时
     * 往往还找不到。这里每 250ms 重试一次，最多 10 秒，确保图片 hook 一定能装上。
     */
    private static void scheduleFastResourceHook(final ClassLoader cl) {
        final Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < 40; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> c = XposedHelpers.findClass(
                                FC + "MiuiFastConnectResource", cl);
                        hookFastResourceImages(c.getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
            }, 250L * i);
        }
    }

    /**
     * 弹窗里的产品图/逐帧动画是按 deviceId 命名的文件：
     *   files/fc_resources/<deviceId>/fc_<deviceId>_hs_ok.webp
     * 这里只改「读哪个代号的文件」，存在才改，找不到就保持原样，不会动系统文件。
     */
    private static void hookFastResourceImages(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(FC + "MiuiFastConnectResource", cl);
            String name = c.getName();
            synchronized (HOOKED) {
                if (!HOOKED.add(name + "#imgread")) return;
            }
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"getDrawableFromFile".equals(m.getName())) continue;
                if (m.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(m, TWO_ARG);
                    n++;
                } else if (m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, ONE_ARG);
                    n++;
                }
            }
            if (n > 0) log("hooked " + name + ".getDrawableFromFile(read path) x" + n);
        } catch (Throwable t) {
            log("fast image hook unavailable: " + t);
        }
    }

    private static java.io.File resDir(Object thisObject, String deviceId) {
        try {
            Object ctx = XposedHelpers.getObjectField(thisObject, "mContext");
            if (!(ctx instanceof Context)) return null;
            return new java.io.File(((Context) ctx).getFilesDir(),
                    "fc_resources/" + deviceId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pickExisting(java.io.File dir, String fileName) {
        if (dir == null || fileName == null) return null;
        if (new java.io.File(dir, fileName).exists()) return fileName;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        for (String ext : new String[]{".webp", ".png"}) {
            String candidate = stem + ext;
            if (new java.io.File(dir, candidate).exists()) return candidate;
        }
        return null;
    }

    private static final XC_MethodHook TWO_ARG = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Mapper.surface("popup")) return;
            try {
                Object a0 = param.args[0];
                Object a1 = param.args[1];
                if (!(a0 instanceof String) || !(a1 instanceof String)) return;
                String real = (String) a0;
                String mapped = Mapper.mapAny(real);
                if (mapped == null || mapped.equals(real)) return;
                String fileName = ((String) a1).replace(real, mapped);
                String existing = pickExisting(resDir(param.thisObject, mapped), fileName);
                if (imgLogs < 12) {
                    imgLogs++;
                    log("img2 " + real + "->" + mapped + " " + fileName
                            + (existing == null ? " MISS" : " HIT"));
                }
                if (existing == null) return;
                param.args[0] = mapped;
                param.args[1] = existing;
            } catch (Throwable t) {
                log("image read error: " + t);
            }
        }
    };

    private static final ThreadLocal<String> SAVED_FOLDER = new ThreadLocal<>();

    private static final XC_MethodHook ONE_ARG = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Mapper.surface("popup")) return;
            try {
                Object a0 = param.args[0];
                if (!(a0 instanceof String)) return;
                Object folderObj = XposedHelpers.getObjectField(param.thisObject, "mFolderName");
                if (!(folderObj instanceof String)) return;
                String folder = (String) folderObj;
                String mapped = Mapper.mapAny(folder);
                if (mapped == null || mapped.equals(folder)) return;
                String fileName = ((String) a0).replace(folder, mapped);
                String existing = pickExisting(resDir(param.thisObject, mapped), fileName);
                if (imgLogs < 12) {
                    imgLogs++;
                    log("img1 " + folder + "->" + mapped + " " + fileName
                            + (existing == null ? " MISS" : " HIT"));
                }
                if (existing == null) return;
                SAVED_FOLDER.set(folder);
                XposedHelpers.setObjectField(param.thisObject, "mFolderName", mapped);
                param.args[0] = existing;
            } catch (Throwable t) {
                log("image read error: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            String saved = SAVED_FOLDER.get();
            if (saved == null) return;
            SAVED_FOLDER.remove();
            try {
                XposedHelpers.setObjectField(param.thisObject, "mFolderName", saved);
            } catch (Throwable ignored) {
            }
        }
    };

    // ------------------------------------------------------------ MiLink

    /**
     * MiLink（融合设备中心 / 耳机详情卡）自己通过 MxBluetooth SDK 读代号：
     *   MxBluetoothManager.getDeviceId(BluetoothDevice)
     *   MxBluetoothService.getDeviceId(BluetoothDevice)
     * 把这里也换成目标代号，MiLink 的原生卡片、设备详情图就会跟着换。
     */
    private static void hookMiLink(ClassLoader cl) {
        String[] classes = {
                "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
                "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        };
        for (String name : classes) {
            try {
                Class<?> c = XposedHelpers.findClass(name, cl);
                hookMiLinkDeviceId(c);
            } catch (Throwable t) {
                log("milink class " + name + " unavailable: " + t);
            }
        }
    }

    private static void hookMiLinkDeviceId(Class<?> c) {
        String name = c.getName();
        synchronized (HOOKED) {
            if (!HOOKED.add(name + "#milink")) return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"getDeviceId".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != BluetoothDevice.class) continue;
            if (m.getReturnType() != String.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Mapper.surface("detail")) return;
                        try {
                            Object r = param.getResult();
                            if (!(r instanceof String)) return;
                            Object a = param.args[0];
                            String mac = null;
                            if (a instanceof BluetoothDevice) {
                                try {
                                    mac = ((BluetoothDevice) a).getAddress();
                                } catch (Throwable ignored) {
                                }
                            }
                            String mapped = Mapper.map((String) r, mac);
                            if (mapped != null && !mapped.equals(r)) {
                                param.setResult(mapped);
                                countAndLog((String) r, mapped, mac);
                            }
                        } catch (Throwable t) {
                            log("milink id hook error: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook " + name + ".getDeviceId failed: " + t);
            }
        }
        if (n > 0) log("hooked " + name + ".getDeviceId(BluetoothDevice) x" + n);
    }

    // ------------------------------------------------------------ 代号读取

    private static final XC_MethodHook ID_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Mapper.surface("popup")) return;
            try {
                Object res = param.getResult();
                if (!(res instanceof String)) return;
                String real = (String) res;
                String mac = macOf(param.thisObject);
                String mapped = Mapper.map(real, mac);
                if (mapped != null && !mapped.equals(real)) {
                    param.setResult(mapped);
                    countAndLog(real, mapped, mac);
                    prefetchResources(mapped);
                }
            } catch (Throwable t) {
                log("id hook error: " + t);
            }
        }
    };

    private static void hookIdClass(Class<?> c) {
        if (c == null) return;
        String name = c.getName();
        synchronized (HOOKED) {
            if (!HOOKED.add(name)) return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"getDeviceId".equals(m.getName())) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getParameterTypes().length != 0) continue;
            try {
                XposedBridge.hookMethod(m, ID_HOOK);
                n++;
            } catch (Throwable t) {
                log("hook " + name + "#getDeviceId failed: " + t);
            }
        }
        if (n > 0) log("hooked " + name + ".getDeviceId x" + n);
    }

    /** 插件 APK 由 Qigsaw 用独立的 ClassLoader 加载，这里守株待兔。 */




    private static void hookClassLoader() {
        XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                String.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        if (!(r instanceof Class)) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof String)) return;
                        String name = (String) a0;
                        if (!name.startsWith(FC)) return;
                        try {
                            if (name.endsWith(".BluetoothReflect")) {
                                hookReflect((Class<?>) r);
                            } else if (name.endsWith(".MiuiFastConnectResource")) {
                                hookFastResourceImages(((Class<?>) r).getClassLoader());
                            } else {
                                for (String target : ID_CLASSES) {
                                    if (target.equals(name)) {
                                        hookIdClass((Class<?>) r);
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            log("dynamic hook " + name + " failed: " + t);
                        }
                    }
                });
    }

    /** com.android.bluetooth.ble.app.fastconnect.BluetoothReflect#getDeviceId(String, Context) */
    private static void hookReflect(Class<?> c) {
        String name = c.getName();
        synchronized (HOOKED) {
            if (!HOOKED.add(name + "#reflect")) return;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!"getDeviceId".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || ps[0] != String.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object res = param.getResult();
                            if (!(res instanceof String)) return;
                            Object a0 = param.args[0];
                            String mac = a0 instanceof String ? (String) a0 : null;
                            String mapped = Mapper.map((String) res, mac);
                            if (mapped != null && !mapped.equals(res)) {
                                param.setResult(mapped);
                                countAndLog((String) res, mapped, mac);
                            }
                        } catch (Throwable t) {
                            log("reflect hook error: " + t);
                        }
                    }
                });
                log("hooked " + name + ".getDeviceId(String,Context)");
            } catch (Throwable t) {
                log("hook reflect failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------ 缓存

    /** DeviceIdCached 里的 saved&lt;MAC&gt; 也参与代号读取，一并替换。 */
    private static void hookPrefs(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                "getString", String.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object k = param.args[0];
                            Object v = param.getResult();
                            if (!(k instanceof String) || !(v instanceof String)) return;
                            String key = (String) k;
                            if (!key.startsWith("saved")) return;
                            String mac = key.substring(5);
                            if (mac.length() != 17) return;
                            String mapped = Mapper.map((String) v, mac);
                            if (mapped != null && !mapped.equals(v)) {
                                param.setResult(mapped);
                                countAndLog((String) v, mapped, mac);
                            }
                        } catch (Throwable t) {
                            log("prefs hook error: " + t);
                        }
                    }
                });
        // 有些代码路径直接 getAll() 整表读取，这里同样替换 saved<MAC>
        XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                "getAll", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            if (!(r instanceof java.util.Map)) return;
                            java.util.Map map = (java.util.Map) r;
                            java.util.Map copy = null;
                            for (Object o : map.entrySet()) {
                                java.util.Map.Entry e = (java.util.Map.Entry) o;
                                Object k = e.getKey();
                                Object v = e.getValue();
                                if (!(k instanceof String) || !(v instanceof String)) continue;
                                String key = (String) k;
                                if (!key.startsWith("saved")) continue;
                                String mac = key.substring(5);
                                if (mac.length() != 17) continue;
                                String mapped = Mapper.map((String) v, mac);
                                if (mapped != null && !mapped.equals(v)) {
                                    if (copy == null) copy = new java.util.HashMap();
                                    copy.put(k, mapped);
                                    countAndLog((String) v, mapped, mac);
                                }
                            }
                            if (copy != null) map.putAll(copy);
                        } catch (Throwable t) {
                            log("getAll hook error: " + t);
                        }
                    }
                });
    }

    // ------------------------------------------------------------ 数据库 Provider

    /**
     * content://com.android.bluetooth.ble.app.headsetdata.provider/deviceinfo
     * 里的 deviceid 是存在数据库里的真实代号，弹窗/更多设置会查它。
     * 这里把查询结果里的 deviceid 列改写掉。
     */
    private static void hookHeadsetProvider(ClassLoader cl) {
        hookProviderClass(cl, "com.android.bluetooth.ble.app.headset.HeadsetProvider");
        hookProviderClass(cl,
                "com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider");
    }

    /** 系统设置详情页的大图走 HeadsetProvider.openFile，这里把 URI 里的真实代号换成目标代号。 */

    /** Settings 详情页：把 content:// 里的真实代号换成目标代号，provider 自己就会读目标图。 */




    /** 直接 hook 框架 ContentProvider，覆盖 Qigsaw 代理和插件 provider。 */






    private static void hookProviderClass(ClassLoader cl, String className) {
        Class<?> c;
        try {
            c = XposedHelpers.findClass(className, cl);
        } catch (Throwable t) {
            return;
        }
        String name = c.getName();
        synchronized (HOOKED) {
            if (!HOOKED.add(name + "#query")) return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"query".equals(m.getName())) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Mapper.surface("detail")) return;
                        Object r = param.getResult();
                        if (!(r instanceof android.database.Cursor)) return;
                        String hint = macHint(param.args);
                        param.setResult(mapCursor((android.database.Cursor) r, hint));
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook provider query failed: " + t);
            }
        }
        if (n > 0) log("hooked " + name + ".query x" + n);
    }

    private static String macHint(Object[] args) {
        try {
            for (Object a : args) {
                if (a instanceof String[]) {
                    for (String s : (String[]) a) {
                        if (s != null && s.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) return s;
                    }
                } else if (a instanceof String) {
                    String s = (String) a;
                    if (s.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static android.database.Cursor mapCursor(android.database.Cursor cur, String hint) {
        try {
            String[] cols = cur.getColumnNames();
            int idIdx = -1;
            int macIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if ("deviceid".equalsIgnoreCase(cols[i])) idIdx = i;
                if ("devicemac".equalsIgnoreCase(cols[i])) macIdx = i;
            }
            if (idIdx < 0) return cur;
            if (cursorLogs < 5) {
                cursorLogs++;
                log("cursor cols=" + java.util.Arrays.toString(cols)
                        + " idIdx=" + idIdx + " macIdx=" + macIdx);
            }
            android.database.MatrixCursor out =
                    new android.database.MatrixCursor(cols, cur.getCount());
            cur.moveToPosition(-1);
            while (cur.moveToNext()) {
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    switch (cur.getType(i)) {
                        case android.database.Cursor.FIELD_TYPE_INTEGER:
                            row[i] = cur.getLong(i);
                            break;
                        case android.database.Cursor.FIELD_TYPE_FLOAT:
                            row[i] = cur.getDouble(i);
                            break;
                        case android.database.Cursor.FIELD_TYPE_BLOB:
                            row[i] = cur.getBlob(i);
                            break;
                        case android.database.Cursor.FIELD_TYPE_NULL:
                            row[i] = null;
                            break;
                        default:
                            row[i] = cur.getString(i);
                    }
                }
                String id = row[idIdx] == null ? null : String.valueOf(row[idIdx]);
                String mac = (macIdx >= 0 && row[macIdx] != null)
                        ? String.valueOf(row[macIdx]) : hint;
                String mapped = Mapper.map(id, mac);
                if (mapped != null && !mapped.equals(id)) {
                    row[idIdx] = mapped;
                    countAndLog(id, mapped, mac);
                }
                out.addRow(row);
            }
            return out;
        } catch (Throwable t) {
            log("mapCursor failed: " + t);
            return cur;
        }
    }

    // ------------------------------------------------------------ 资源路径兜底

    /** 超级岛的 mp4 由 base 里的 MiuiBluetoothNotification#getResourceUri(name, deviceId) 拼路径。 */
    private static void hookBase(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.android.bluetooth.ble.app.MiuiBluetoothNotification", cl);
            String name = c.getName();
            synchronized (HOOKED) {
                if (!HOOKED.add(name + "#res")) return;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"getResourceUri".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Mapper.surface("island")) return;
                        try {
                            Object id = param.args[1];
                            if (!(id instanceof String)) return;
                            String mapped = Mapper.mapAny((String) id);
                            if (mapped != null && !mapped.equals(id)) param.args[1] = mapped;
                        } catch (Throwable t) {
                            log("resource hook error: " + t);
                        }
                    }
                });
                log("hooked " + name + ".getResourceUri");
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 工具

    private static String macOf(Object obj) {
        if (obj == null) return null;
        try {
            Object dev = XposedHelpers.getObjectField(obj, "mBleDevice");
            if (dev instanceof BluetoothDevice) {
                Object addr = XposedHelpers.getObjectField(dev, "mAddress");
                if (addr instanceof String) return (String) addr;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object addr = XposedHelpers.getObjectField(obj, "mCurrentDeviceAddress");
            if (addr instanceof String) return (String) addr;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void countAndLog(String from, String to, String mac) {
        int n;
        synchronized (HOOKED) {
            n = ++replaced;
        }
        if (n <= 12 || n % 100 == 0) {
            log("#" + n + " " + from + " -> " + to + (mac == null ? "" : " @" + mac));
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + s);
    }
}
