package io.github.jumaoli.budsid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** HyperOS 风格的胶囊开关。 */
public class MiuixSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(MiuixSwitch v, boolean checked);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private boolean checked;
    private float progress;
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;

    public MiuixSwitch(Context c) {
        this(c, null);
    }

    public MiuixSwitch(Context c, AttributeSet a) {
        super(c, a);
        thumbPaint.setColor(0xFFFFFFFF);
        setClickable(true);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean v) {
        setChecked(v, false);
    }

    public void setChecked(boolean v, boolean animate) {
        if (checked == v) {
            progress = v ? 1f : 0f;
            invalidate();
            return;
        }
        checked = v;
        if (animate) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, v ? 1f : 0f);
            animator.setDuration(220);
            animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    progress = (float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        } else {
            progress = v ? 1f : 0f;
            invalidate();
        }
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Theme.dp(getContext(), 52);
        int h = Theme.dp(getContext(), 32);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        int accent = Theme.accent(getContext());
        int off = Theme.trackOff(getContext());
        int color = blend(off, accent, progress);
        trackPaint.setColor(color);
        trackRect.set(0, 0, w, h);
        canvas.drawRoundRect(trackRect, r, r, trackPaint);

        float pad = Theme.dp(getContext(), 3);
        float radius = r - pad;
        float cx = pad + radius + (w - 2 * pad - 2 * radius) * progress;
        canvas.drawCircle(cx, h / 2f, radius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                setChecked(!checked, true);
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private static int blend(int from, int to, float t) {
        int a = (int) ((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
