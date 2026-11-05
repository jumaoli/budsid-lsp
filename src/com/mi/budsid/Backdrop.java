package com.mi.budsid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * 共享的低分辨率背景快照：整屏只抓一次、只模糊一次，顶栏和底栏都从这张图上采样。
 * 1/8 缩放 + 半径 5 的栈模糊，开销很小。
 */
final class Backdrop {

    static final int SCALE = 4;

    private static Bitmap bitmap;

    private Backdrop() {
    }

    static void capture(View content, int baseColor) {
        if (content == null) return;
        int w = content.getWidth();
        int h = content.getHeight();
        if (w <= 0 || h <= 0) return;
        int bw = Math.max(1, w / SCALE);
        int bh = Math.max(1, h / SCALE);
        Bitmap b = bitmap;
        try {
            if (b == null || b.getWidth() != bw || b.getHeight() != bh || b.isRecycled()) {
                b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bitmap = b;
            }
            Canvas c = new Canvas(b);
            c.drawColor(baseColor);
            c.scale(bw / (float) w, bh / (float) h);
            content.draw(c);
        } catch (Throwable t) {
            return;
        }
    }

    static Bitmap get() {
        Bitmap b = bitmap;
        return (b == null || b.isRecycled()) ? null : b;
    }

    private static void blur(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int n = w * h;
        int[] pix = new int[n];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int[] r = new int[n];
        int[] g = new int[n];
        int[] bl = new int[n];
        for (int i = 0; i < n; i++) {
            int p = pix[i];
            r[i] = (p >> 16) & 0xFF;
            g[i] = (p >> 8) & 0xFF;
            bl[i] = p & 0xFF;
        }
        box(r, w, h, radius);
        box(g, w, h, radius);
        box(bl, w, h, radius);
        for (int i = 0; i < n; i++) {
            pix[i] = 0xFF000000 | (r[i] << 16) | (g[i] << 8) | bl[i];
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static void box(int[] v, int w, int h, int radius) {
        int div = radius * 2 + 1;
        int[] tmp = new int[v.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sum = 0;
            for (int i = -radius; i <= radius; i++) sum += v[row + clamp(i, w)];
            for (int x = 0; x < w; x++) {
                tmp[row + x] = sum / div;
                sum += v[row + clamp(x + radius + 1, w)] - v[row + clamp(x - radius, w)];
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int i = -radius; i <= radius; i++) sum += tmp[clamp(i, h) * w + x];
            for (int y = 0; y < h; y++) {
                v[y * w + x] = sum / div;
                sum += tmp[clamp(y + radius + 1, h) * w + x] - tmp[clamp(y - radius, h) * w + x];
            }
        }
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }
}
