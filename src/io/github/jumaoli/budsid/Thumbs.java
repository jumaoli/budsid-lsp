package io.github.jumaoli.budsid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 型号缩略图：从 fast_connect/<id>.zip 里取 fc_<id>_hs_ok.webp，
 * 内存 LRU + 本地磁盘缓存，没网/没有图就留空。
 */
public final class Thumbs {

    private static final LruCache<String, Bitmap> MEM = new LruCache<>(48);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final java.util.Set<String> INFLIGHT = new java.util.HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.Map<String, java.util.List<ImageView>> WAIT =
            new java.util.HashMap<>();
    private static boolean receiverReady;

    private Thumbs() {
    }

    public static void init(Context ctx) {
        if (receiverReady) return;
        receiverReady = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    String id = i.getStringExtra("id");
                    boolean big = i.getBooleanExtra("big", false);
                    String b64 = i.getStringExtra("data");
                    if (id == null || b64 == null) return;
                    String key = big ? id + "#big" : id;
                    byte[] data = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp == null) return;
                    MEM.put(key, bmp);
                    java.io.File dir = new java.io.File(c.getFilesDir(), "thumbs");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(
                            new java.io.File(dir, big ? id + "_big.webp" : id + ".webp"));
                    out.write(data);
                    out.close();
                    java.util.List<ImageView> views = WAIT.remove(key);
                    if (views != null) {
                        for (final ImageView v : views) {
                            if (!key.equals(v.getTag())) continue;
                            v.setAlpha(1f);
                            v.setPadding(0, 0, 0, 0);
                            v.setImageBitmap(bmp);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        IntentFilter f = new IntentFilter("io.github.jumaoli.budsid.THUMB");
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(r, f);
        }
    }

    private static void request(final Context ctx, final String id, ImageView view, boolean big) {
        String key = big ? id + "#big" : id;
        java.util.List<ImageView> list = WAIT.get(key);
        boolean first = list == null;
        if (list == null) {
            list = new java.util.ArrayList<>();
            WAIT.put(key, list);
        }
        list.add(view);
        if (!first) return;
        try {
            Intent i = new Intent("io.github.jumaoli.budsid.THUMB_REQ");
            i.setPackage("com.xiaomi.bluetooth");
            i.putExtra("id", id);
            i.putExtra("big", big);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    public static void load(final Context ctx, final String id, final ImageView view) {
        load(ctx, id, view, false);
    }

    public static void loadBig(final Context ctx, final String id, final ImageView view) {
        if (id == null || id.length() < 8) return;
        view.setTag(id + "#big");
        Bitmap cached = MEM.get(id + "#big");
        if (cached != null) {
            view.setAlpha(1f);
            view.setPadding(0, 0, 0, 0);
            view.setImageBitmap(cached);
            return;
        }
        String key = id + "#big";
        java.util.List<ImageView> list = WAIT.get(key);
        boolean first = list == null;
        if (list == null) {
            list = new java.util.ArrayList<>();
            WAIT.put(key, list);
        }
        list.add(view);
        if (!first) return;
        try {
            Intent i = new Intent("io.github.jumaoli.budsid.THUMB_BIG_REQ");
            i.setPackage("com.xiaomi.bluetooth");
            i.putExtra("id", id);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    public static void load(final Context ctx, final String id, final ImageView view, boolean big) {
        if (id == null || id.length() != 8) return;
        view.setTag(big ? id + "#big" : id);
        view.setImageResource(R.drawable.ic_earbud);
        view.setAlpha(0.35f);
        int pad = (int) (view.getResources().getDisplayMetrics().density * 7);
        view.setPadding(pad, pad, pad, pad);
        Bitmap cached = MEM.get(id);
        if (cached != null) {
            view.setAlpha(1f);
            view.setPadding(0, 0, 0, 0);
            view.setImageBitmap(cached);
            return;
        }
        // 先问蓝牙模块（它能拿到 CDN 私有对象），App 直连经常 403
        request(ctx, id, view, big);
        if (true) return;
        final File f = new File(new File(ctx.getFilesDir(), "thumbs"), id + ".webp");
        synchronized (INFLIGHT) {
            if (!INFLIGHT.add(id)) return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    if (f.exists()) {
                        bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    }
                    if (bmp == null) {
                        bmp = fetch(ctx, id, f);
                    }
                } catch (Throwable ignored) {
                }
                synchronized (INFLIGHT) {
                    INFLIGHT.remove(id);
                }
                if (bmp == null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            request(ctx, id, view, big);
                        }
                    });
                    return;
                }
                MEM.put(id, bmp);
                final Bitmap result = bmp;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id.equals(view.getTag())) {
                            view.setAlpha(1f);
                            view.setPadding(0, 0, 0, 0);
                            view.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap save(File f, byte[] data) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) return null;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap fetch(Context ctx, String id, File f) {
        HttpURLConnection conn = null;
        ZipInputStream zin = null;
        try {
            URL url = new URL("https://cdn.cnbj1.fds.api.mi-img.com/"
                    + "bluetoothencrypt/fast_connect/" + id + ".zip");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.i("BudsId", "thumb " + id + " http " + code);
                return null;
            }
            zin = new ZipInputStream(conn.getInputStream());
            ZipEntry e;
            byte[] buf = new byte[16384];
            String exact = "fc_" + id + "_hs_ok";
            byte[] fallback = null;
            byte[] box = null;
            byte[] hero = null;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith("fc_" + id + "_")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                if (name.startsWith(exact)) {
                    return save(f, data);
                }
                if (name.contains("_hs") && fallback == null) {
                    fallback = data;
                } else if (name.contains("_box") && box == null) {
                    box = data;
                } else if (name.contains("_5000") && hero == null) {
                    hero = data;
                }
            }
            if (fallback != null) return save(f, fallback);
            if (box != null) return save(f, box);
            if (hero != null) return save(f, hero);
        } catch (Throwable t) {
            android.util.Log.i("BudsId", "thumb fetch " + id + " failed: " + t);
        } finally {
            try { if (zin != null) zin.close(); } catch (Throwable ignored) { }
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
        }
        return null;
    }
}
