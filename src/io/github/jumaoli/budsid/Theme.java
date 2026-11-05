package io.github.jumaoli.budsid;

import android.content.Context;
import android.content.res.Configuration;

/** HyperOS / Miuix 配色。 */
public final class Theme {

    private Theme() {
    }

    public static boolean dark(Context c) {
        int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context c) {
        return dark(c) ? 0xFF000000 : 0xFFF6F6F8;
    }

    public static int card(Context c) {
        return dark(c) ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    public static int text(Context c) {
        return dark(c) ? 0xFFF5F5F7 : 0xFF0D0D0D;
    }

    public static int sub(Context c) {
        return dark(c) ? 0xFF9A9AA0 : 0xFF8A8A8E;
    }

    public static int divider(Context c) {
        return dark(c) ? 0x1FFFFFFF : 0x12000000;
    }

    public static int accent(Context c) {
        return 0xFF3482FF;
    }

    public static int red(Context c) {
        return 0xFFFF453A;
    }

    public static int trackOff(Context c) {
        return dark(c) ? 0xFF3A3A3C : 0xFFE3E3E8;
    }

    public static int field(Context c) {
        return dark(c) ? 0xFF2C2C2E : 0xFFF0F0F3;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
