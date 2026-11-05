package io.github.jumaoli.budsid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 液态玻璃栏：共享低分辨率背景快照 -> AGSL 镜头折射（SukiSU/Backdrop 的
 * LiquidGlassLensDispersion）-> 苹果那种高光边 + 斜向反光。
 */
public class GlassBar extends LinearLayout {

    private static final String LENS_SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n" +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5;\n" +
            "    color.a += red.a / 7.0;\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5;\n" +
            "    color.g += orange.g / 7.0;\n" +
            "    color.a += orange.a / 7.0;\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5;\n" +
            "    color.g += yellow.g / 3.5;\n" +
            "    color.a += yellow.a / 7.0;\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5;\n" +
            "    color.a += green.a / 7.0;\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5;\n" +
            "    color.b += cyan.b / 3.0;\n" +
            "    color.a += cyan.a / 7.0;\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0;\n" +
            "    color.a += blue.a / 7.0;\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0;\n" +
            "    color.b += purple.b / 3.0;\n" +
            "    color.a += purple.a / 7.0;\n" +
            "    return color;\n" +
            "}";

    private final BlurNode blurNode = new BlurNode();
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint();
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF inner = new RectF();
    private final Path path = new Path();

    private boolean blurEnabled = true;
    private boolean glassEnabled = true;
    private float radiusDp = 26f;
    private int tintColor = 0x3DFFFFFF;

    public GlassBar(Context context) {
        super(context);
        setWillNotDraw(false);
        tintPaint.setColor(tintColor);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Theme.dp(context, 1.2f));
    }

    public void setSource(View v) {
        // 背景快照由 Backdrop 统一管理
    }

    public void setBaseColor(int color) {
    }

    public void setTintColor(int color) {
        tintColor = color;
        invalidate();
    }

    public void setBlurEnabled(boolean enabled) {
        blurEnabled = enabled;
        invalidate();
        refresh();
    }

    public void setGlassEnabled(boolean enabled) {
        glassEnabled = enabled;
        invalidate();
    }

    public void setCornerRadius(float dp) {
        radiusDp = dp;
        invalidate();
    }

    public void refresh() {
        Context c = getContext();
        if (c instanceof MainActivity) {
            ((MainActivity) c).scheduleBackdrop();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refresh();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawGlass(canvas);
        super.onDraw(canvas);
    }

    private void drawGlass(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = Theme.dp(getContext(), radiusDp);
        rect.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);

        Bitmap bmp = blurEnabled ? Backdrop.get() : null;
        if (bmp != null) {
            blurNode.draw(canvas, bmp, this, w, h,
                    Theme.dp(getContext(), 4), 1f, glassEnabled);
            tintPaint.setColor(tintColor);
            canvas.drawRect(rect, tintPaint);
        } else {
            tintPaint.setColor(Theme.dark(getContext()) ? 0xE61C1C1E : 0xE6FFFFFF);
            canvas.drawRect(rect, tintPaint);
        }

        if (glassEnabled) {
            glossPaint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0x40FFFFFF, 0x00FFFFFF, 0x14FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, glossPaint);
            glossPaint.setShader(null);

            inner.set(1.2f, 1.2f, w - 1.2f, h - 1.2f);
            rimPaint.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                    new int[]{0xE6FFFFFF, 0x33FFFFFF, 0x99FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(inner, Math.max(0, radius - 1.2f),
                    Math.max(0, radius - 1.2f), rimPaint);
            rimPaint.setShader(null);

            glossPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                    new int[]{0x00000000, 0x22000000}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, glossPaint);
            glossPaint.setShader(null);
        }
        canvas.restore();
    }

    /** 把整屏低分辨率快照映射到当前栏的位置。 */
    private BitmapShader shaderFor(Bitmap bmp) {
        BitmapShader s = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        float k = 1f / Backdrop.SCALE;
        m.setScale(k, k);
        m.postTranslate(getLeft() * k, getTop() * k);
        s.setLocalMatrix(m);
        return s;
    }
}
