package com.mi.budsid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Miuix 风格的底部弹层。 */
public final class Sheet {

    private Sheet() {
    }

    public static Dialog show(Activity a, String title, View content) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.card(a));
        float r = Theme.dp(a, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(Theme.dp(a, 20), Theme.dp(a, 10), Theme.dp(a, 20), Theme.dp(a, 28));

        View handle = new View(a);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Theme.dark(a) ? 0x66FFFFFF : 0x33000000);
        hb.setCornerRadius(Theme.dp(a, 2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Theme.dp(a, 36), Theme.dp(a, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Theme.dp(a, 2);
        root.addView(handle, hlp);

        if (!TextUtils.isEmpty(title)) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(20);
            t.setTextColor(Theme.text(a));
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Theme.dp(a, 16);
            tlp.bottomMargin = Theme.dp(a, 6);
            root.addView(t, tlp);
        }

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(Theme.dark(a) ? 0.6f : 0.35f);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    w.setBackgroundBlurRadius(Theme.dp(a, 80));
                } catch (Throwable ignored) {
                }
            }
        }
        d.setCanceledOnTouchOutside(true);
        d.show();
        return d;
    }

    public static TextView rowTitle(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(17);
        t.setTextColor(Theme.text(a));
        t.setSingleLine(false);
        return t;
    }

    public static TextView rowSummary(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.sub(a));
        return t;
    }
}
