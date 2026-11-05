package io.github.jumaoli.budsid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 导航栏选中项后面的「液态玻璃气泡」：圆形裁切背景快照 + 镜面亮边 + 内阴影。
 */
public class GlassDot extends View {

    private final BlurNode blurNode = new BlurNode();
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float highlightAngle = (float) (-Math.PI / 2);
    private final Path path = new Path();

    /** 高光方向跟随重力（SukiSU 的 BloomStroke LightSource）。 */
    public void setHighlightAngle(float radians) {
        highlightAngle = radians;
        invalidate();
    }

    public GlassDot(Context c) {
        super(c);
        setWillNotDraw(false);
        tintPaint.setColor(0x1FFFFFFF);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Theme.dp(c, 1.4f));
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(Theme.dp(c, 3f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;
        android.graphics.RectF r = new android.graphics.RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f);
        path.reset();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);

        Bitmap b = Backdrop.get();
        if (b != null) {
            blurNode.draw(canvas, b, this, w, h, Theme.dp(getContext(), 3), 0.90f, true);
            tintPaint.setColor(Theme.dark(getContext()) ? 0x33000000 : 0x33FFFFFF);
            canvas.drawRoundRect(r, radius, radius, tintPaint);
            canvas.restore();
            rimPaint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xF2FFFFFF, 0x33FFFFFF, 0xCCFFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, radius, radius, rimPaint);
            rimPaint.setShader(null);
            return;
        }
        if (false) {
            BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float k = 1f / Backdrop.SCALE;
            float zoom = 0.90f; // 放大 1.11x，做出透镜感
            m.setScale(k * zoom, k * zoom);
            m.postTranslate((getLeft() + cx * (1f - zoom)) * k,
                    (getTop() + cy * (1f - zoom)) * k);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.18f);
            shaderPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            shaderPaint.setShader(sh);
            canvas.drawRoundRect(r, radius, radius, shaderPaint);
            tintPaint.setColor(Theme.dark(getContext()) ? 0x33000000 : 0x33FFFFFF);
            canvas.drawRoundRect(r, radius, radius, tintPaint);
        } else {
            tintPaint.setColor(Theme.dark(getContext()) ? 0xCC2C2C2E : 0xCCFFFFFF);
            canvas.drawRoundRect(r, radius, radius, tintPaint);
        }

        shadowPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                new int[]{0x00000000, 0x40000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, radius, radius, shadowPaint);
        shadowPaint.setShader(null);
        canvas.restore();

        float dx = (float) Math.cos(highlightAngle) * w * 0.5f;
        float dy = (float) Math.sin(highlightAngle) * h * 0.5f;
        rimPaint.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                new int[]{0xF2FFFFFF, 0x1FFFFFFF, 0xB3FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, radius, radius, rimPaint);
        rimPaint.setShader(null);
    }
}