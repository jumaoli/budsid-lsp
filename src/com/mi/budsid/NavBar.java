package com.mi.budsid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 悬浮底栏：玻璃背景 + 可跟随手指拖动的选中气泡（松手回弹到最近 tab）。
 * 交互照 SukiSU 的 FloatingBottomBar（按住拖动、弹簧回位）。
 */
public class NavBar extends FrameLayout implements SensorEventListener {

    public interface OnTabSelected {
        void onSelected(int index);
    }

    private final GlassBar bg;
    private final GlassDot indicator;
    private final LinearLayout items;

    private int tabCount = 4;
    private int selected = 0;
    private float tabWidth;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean laidOut;
    private OnTabSelected listener;
    private ValueAnimator anim;
    private SensorManager sensorManager;
    private float highlightAngle = (float) (-Math.PI / 2);

    public NavBar(Context c) {
        super(c);
        bg = new GlassBar(c);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        indicator = new GlassDot(c);
        LayoutParams ilp = new LayoutParams(0, 0);
        addView(indicator, ilp);

        items = new LinearLayout(c);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setGravity(android.view.Gravity.CENTER);
        addView(items, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public LinearLayout items() {
        return items;
    }

    public void setTabCount(int n) {
        tabCount = Math.max(1, n);
    }

    public void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    public void setSelected(int index, boolean animate) {
        selected = index;
        layoutIndicator(animate);
    }

    // 转发给玻璃背景
    public void setTintColor(int color) {
        bg.setTintColor(color);
    }

    public void setBlurEnabled(boolean enabled) {
        bg.setBlurEnabled(enabled);
    }

    public void setGlassEnabled(boolean enabled) {
        bg.setGlassEnabled(enabled);
    }

    public void setCornerRadius(float dp) {
        bg.setCornerRadius(dp);
    }

    public void setBaseColor(int color) {
        bg.setBaseColor(color);
    }

    public void refresh() {
        bg.refresh();
        indicator.invalidate();
    }

    public void invalidateGlass() {
        bg.invalidate();
        indicator.invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                Sensor g = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (g != null) {
                    sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            if (sensorManager != null) sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GRAVITY) return;
        float gx = event.values[0];
        float gy = event.values[1];
        float angle;
        if (gx * gx + gy * gy > 0.01f) {
            angle = (float) Math.atan2(gy, gx);
            float step = (float) (3.0 * Math.PI / 180.0);
            angle = Math.round(angle / step) * step;
        } else {
            angle = (float) (-Math.PI / 2);
        }
        if (Math.abs(angle - highlightAngle) > 0.01f) {
            highlightAngle = angle;
            indicator.setHighlightAngle(angle);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void pressScale(boolean down) {
        indicator.animate().cancel();
        indicator.animate().scaleX(down ? 1.25f : 1f).scaleY(down ? 1.25f : 1f)
                .setDuration(down ? 120 : 220).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) pressScale(true);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP
                || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) pressScale(false);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        laidOut = true;
        layoutIndicator(false);
    }

    private void layoutIndicator(boolean animate) {
        if (!laidOut || getWidth() <= 0) return;
        tabWidth = getWidth() / (float) tabCount;
        int h = Math.max(1, getHeight() - Theme.dp(getContext(), 6));
        LayoutParams lp = (LayoutParams) indicator.getLayoutParams();
        lp.width = (int) tabWidth;
        lp.height = h;
        lp.topMargin = Theme.dp(getContext(), 3);
        indicator.setLayoutParams(lp);
        indicator.setVisibility(View.VISIBLE);
        float target = selected * tabWidth;
        if (animate) {
            animateTo(target);
        } else {
            indicator.setTranslationX(target);
            indicator.invalidate();
        }
    }

    private void animateTo(float x) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(indicator.getTranslationX(), x);
        anim.setDuration(320);
        anim.setInterpolator(new OvershootInterpolator(0.9f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                indicator.setTranslationX((float) a.getAnimatedValue());
            }
        });
        anim.start();
    }

    private int indexAt(float x) {
        if (tabWidth <= 0) return selected;
        int i = Math.round(x / tabWidth);
        return Math.max(0, Math.min(tabCount - 1, i));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && Math.abs(dx) > Theme.dp(getContext(), 8)
                        && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    if (anim != null) anim.cancel();
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                if (!dragging && Math.abs(dx) > Theme.dp(getContext(), 8)) dragging = true;
                if (dragging) {
                    float x = ev.getX() - tabWidth / 2f;
                    float max = (tabCount - 1) * tabWidth;
                    x = Math.max(0, Math.min(max, x));
                    indicator.setTranslationX(x);
                    indicator.invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    int index = indexAt(ev.getX() - tabWidth / 2f);
                    selected = index;
                    if (listener != null) listener.onSelected(index);
                    animateTo(index * tabWidth);
                }
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }
}
