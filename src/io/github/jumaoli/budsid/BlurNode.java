package io.github.jumaoli.budsid;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 用 RenderNode + RenderEffect 做 GPU 高斯模糊：把低分辨率快照按位置画进节点，
 * 给节点加 blur 效果后直接 drawRenderNode，滚动时也能实时模糊。
 */
final class BlurNode {

    private RenderNode node;
    private int nodeW = -1;
    private int nodeH = -1;
    private int pad = -1;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrix matrix = new ColorMatrix();

    void draw(Canvas canvas, Bitmap bmp, View host, int width, int height, float blurPx,
              float zoom, boolean vibrancy) {
        if (bmp == null || width <= 0 || height <= 0) return;
        int p = Math.max(1, Math.round(blurPx * 2f));
        if (Build.VERSION.SDK_INT >= 31) {
            if (node == null || nodeW != width || nodeH != height || pad != p) {
                node = new RenderNode("glass-blur");
                node.setPosition(0, 0, width + p * 2, height + p * 2);
                nodeW = width;
                nodeH = height;
                pad = p;
            }
            android.graphics.RecordingCanvas rc = node.beginRecording(
                    width + p * 2, height + p * 2);
            rc.translate(p, p);
            drawBitmap(rc, bmp, host, width, height, zoom, vibrancy);
            node.endRecording();
            node.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx,
                    Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(-p, -p);
            canvas.drawRenderNode(node);
            canvas.restore();
        } else {
            drawBitmap(canvas, bmp, host, width, height, zoom, vibrancy);
        }
    }

    private void drawBitmap(Canvas canvas, Bitmap bmp, View host, int width, int height,
                            float zoom, boolean vibrancy) {
        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        float k = 1f / Backdrop.SCALE;
        float cx = width / 2f;
        float cy = height / 2f;
        m.setScale(k * zoom, k * zoom);
        m.postTranslate((host.getLeft() + cx * (1f - zoom)) * k,
                (host.getTop() + cy * (1f - zoom)) * k);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        if (vibrancy) {
            matrix.setSaturation(1.2f);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        } else {
            paint.setColorFilter(null);
        }
        canvas.drawRect(0, 0, width, height, paint);
    }
}
