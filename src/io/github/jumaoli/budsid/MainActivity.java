package io.github.jumaoli.budsid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_BT = 41;

    private static final int TAB_DEVICES = 0;
    private static final int TAB_RULES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_ABOUT = 3;

    private int tab = TAB_DEVICES;

    private FrameLayout contentHost;
    private final View[] pages = new View[4];
    private NavBar bottomBar;
    private boolean syncing;
    private boolean detailPreview;
    private BroadcastReceiver catalogReceiver;
    private boolean catalogArrived;
    private GlassBar topBar;
    private final ImageView[] navIcon = new ImageView[4];
    private final TextView[] navLabel = new TextView[4];
    private final TextView[] navBadge = new TextView[4];
        private boolean floatingBar = true;
    private boolean glass = true;
    private boolean showBadge = true;

    private LinearLayout deviceBox;
    private LinearLayout ruleBox;
    private MiuixSwitch enableSwitch;
    private TextView statusView;

    private boolean blur;
    private boolean fullStatus;
    private boolean predBack;
    private boolean edgeBack;
    private boolean swipePages;
    private boolean crossAxis;

    private int cPrimary;
    private int cSecondary;
    private int cAccent;
    private int cBg;
    private int cCard;

    private float downX;
    private float downY;
    private long downTime;

    private interface OnPick {
        void onPick(Devices.Dev d);
    }

    @Override
    protected void attachBaseContext(Context base) {
        int scale = Prefs.getInt(base, "ui_scale", 100);
        if (scale != 100) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.densityDpi = Math.round(base.getResources().getDisplayMetrics().densityDpi
                    * scale / 100f);
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPrefs();
        resolveColors();
        View root = buildUi();
        setContentView(root);
        applyInsets(root);
        registerCatalogReceiver();
        Thumbs.init(this);
        autoUpdateCatalog();
        requestBtPermission();
        showTab(TAB_DEVICES, false);
        registerBack();
        try {
            int t = getIntent().getIntExtra("tab", -1);
            android.util.Log.i("BudsId", "onCreate tab extra=" + t);
            if (t >= 0 && t < pages.length) showTab(t, false);
        } catch (Throwable th) {
            android.util.Log.i("BudsId", "tab extra failed: " + th, th);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        prefetchTargets();
        requestLocalCatalog();
    }

    private void requestLocalCatalog() {
        try {
            Intent req = new Intent("io.github.jumaoli.budsid.CATALOG_REQ");
            req.setPackage("com.xiaomi.bluetooth");
            sendBroadcast(req);
        } catch (Throwable ignored) {
        }
    }

    private void restartHookedApps() {
        toast("正在重启…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "am force-stop com.xiaomi.bluetooth; "
                                    + "am force-stop com.milink.service; "
                                    + "am force-stop com.android.settings"});
                    p.waitFor();
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("已重启，重新连接耳机后生效");
                    }
                });
            }
        }).start();
    }

    private void prefetchTargets() {
        try {
            for (Rule r : Prefs.getRules(this)) {
                if (r.to != null && r.to.matches("[0-9A-Fa-f]{8}")) sendPrefetch(r.to);
            }
        } catch (Throwable ignored) {
        }
    }

    private void sendPrefetch(String id) {
        try {
            Intent i = new Intent("io.github.jumaoli.budsid.PREFETCH");
            i.setPackage("com.xiaomi.bluetooth");
            i.putExtra("id", id);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private void loadPrefs() {
        blur = Prefs.getBool(this, "ui_blur", true);
        floatingBar = Prefs.getBool(this, "ui_floating", true);
        glass = Prefs.getBool(this, "ui_glass", true);
        detailPreview = Prefs.getBool(this, "ui_detail_preview", true);
        showBadge = Prefs.getBool(this, "ui_badge", true);
        fullStatus = Prefs.getBool(this, "ui_full_status", false);
        predBack = Prefs.getBool(this, "gesture_pred", true);
        edgeBack = Prefs.getBool(this, "gesture_edge", true);
        swipePages = Prefs.getBool(this, "gesture_swipe", true);
        crossAxis = Prefs.getBool(this, "gesture_cross", true);
    }

    // ------------------------------------------------------------ 系统返回

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleBack();
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleBack() {
        if (tab != TAB_DEVICES) {
            showTab(TAB_DEVICES, true);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ------------------------------------------------------------ 手势

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!(swipePages || edgeBack)) return super.dispatchTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;
                boolean onBar = downY > getResources().getDisplayMetrics().heightPixels - dp(150);
                if (!onBar && Math.abs(dx) > dp(64) && dt < 600) {
                    boolean crossed = crossAxis
                            ? Math.abs(dx) > Math.abs(dy) * 1.4f
                            : Math.abs(dy) < dp(28);
                    if (crossed) {
                        if (edgeBack && downX < dp(26) && dx > 0 && tab == TAB_DEVICES) {
                            finish();
                            return true;
                        }
                        if (swipePages) {
                            if (dx < 0 && tab < TAB_ABOUT) {
                                showTab(tab + 1, true);
                                return true;
                            }
                            if (dx > 0 && tab > TAB_DEVICES) {
                                showTab(tab - 1, true);
                                return true;
                            }
                        }
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ 骨架

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cBg);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = buildTopBar();
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(topBar, topLp);

        pages[TAB_DEVICES] = buildDevicesPage();
        pages[TAB_RULES] = buildRulesPage();
        pages[TAB_SETTINGS] = buildSettingsPage();
        pages[TAB_ABOUT] = buildAboutPage();
        for (View p : pages) {
            p.setVisibility(View.GONE);
            contentHost.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        bottomBar = buildBottomBar();
        boolean night = cBg == 0xFF000000;
        topBar.setBaseColor(cBg);
        bottomBar.setBaseColor(cBg);
        topBar.setTintColor(night ? 0xF2000000 : 0xF2FFFFFF);
        bottomBar.setTintColor(night ? 0x33000000 : 0x33FFFFFF);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barLp.bottomMargin = dp(26);
        root.addView(bottomBar, barLp);

        return root;
    }

    private void applyInsets(final View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    private void watchScroll(ScrollView sv) {
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                refreshBars();
            }
        });
    }

    private final Runnable backdropTask = new Runnable() {
        @Override
        public void run() {
            if (contentHost == null) return;
            Backdrop.capture(contentHost, cBg);
            if (topBar != null) topBar.invalidate();
            if (bottomBar != null) bottomBar.invalidateGlass();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastBackdrop;

    void scheduleBackdrop() {
        long now = SystemClock.uptimeMillis();
        long wait = 90 - (now - lastBackdrop);
        handler.removeCallbacks(backdropTask);
        if (wait <= 0) {
            lastBackdrop = now;
            backdropTask.run();
        } else {
            handler.postDelayed(backdropTask, wait);
        }
    }

    private void refreshBars() {
        scheduleBackdrop();
    }

    private GlassBar buildTopBar() {
        GlassBar bar = new GlassBar(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(22), dp(10), dp(22), dp(10));
        bar.setCornerRadius(0);
        bar.setBlurEnabled(blur);
        bar.setGlassEnabled(glass);

        TextView title = new TextView(this);
        title.setText("小米耳机代号替换");
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(cPrimary);
        bar.addView(title);

        TextView sub = new TextView(this);
        sub.setText("弹窗 · 超级岛 · 设备图");
        sub.setTextSize(12);
        sub.setTextColor(cSecondary);
        bar.addView(sub);
        return bar;
    }

    private NavBar buildBottomBar() {
        final NavBar bar = new NavBar(this);
        bar.setTabCount(4);
        String[] labels = {"设备", "规则", "设置", "关于"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_rules, R.drawable.ic_gear,
                R.drawable.ic_info};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6), dp(6), dp(6), dp(6));

            FrameLayout iconHost = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setColorFilter(cPrimary);
            icon.setImageResource(icons[i]);
            iconHost.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.CENTER));

            TextView badge = new TextView(this);
            badge.setTextSize(9);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFF453A);
            bg.setCornerRadius(dp(7));
            badge.setBackground(bg);
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(15), dp(15));
            bLp.gravity = Gravity.TOP | Gravity.END;
            badge.setVisibility(View.GONE);
            iconHost.addView(badge, bLp);

            item.addView(iconHost, new LinearLayout.LayoutParams(dp(28), dp(26)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(10);
            label.setTextColor(cSecondary);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(1);
            item.addView(label, lLp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(index, true);
                }
            });
            bar.items().addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            navIcon[i] = icon;
            navLabel[i] = label;
            navBadge[i] = badge;
        }
        bar.setOnTabSelected(new NavBar.OnTabSelected() {
            @Override
            public void onSelected(int index) {
                showTab(index, true);
            }
        });
        return bar;
    }

    private void applyGlass(View view, float alpha) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(cCard, alpha), withAlpha(cCard, alpha * 0.94f)});
        bg.setCornerRadius(dp(floatingBar ? 26 : 0));
        if (glass) {
            bg.setStroke(dp(1), 0x33FFFFFF);
        }
        view.setBackground(bg);
        view.setElevation(dp(floatingBar ? 14 : 0));
    }

    private void applyBottomStyle() {
        if (bottomBar == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
        lp.gravity = floatingBar ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.BOTTOM;
        lp.leftMargin = floatingBar ? dp(24) : 0;
        lp.rightMargin = floatingBar ? dp(24) : 0;
        lp.bottomMargin = floatingBar ? dp(26) : 0;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        bottomBar.setLayoutParams(lp);
        bottomBar.setCornerRadius(floatingBar ? 26 : 0);
        bottomBar.setBlurEnabled(blur);
        bottomBar.refresh();
    }

    // ------------------------------------------------------------ 页面切换

    private void showTab(int index, boolean animate) {
        if (index < 0 || index >= pages.length) return;
        for (int i = 0; i < pages.length; i++) {
            if (i == index || pages[i] == null) continue;
            pages[i].setVisibility(View.GONE);
            pages[i].setAlpha(1f);
            pages[i].setTranslationX(0f);
        }
        View in = pages[index];
        View out = pages[tab];
        if (in == out) {
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            in.setTranslationX(0f);
            updateNav();
            return;
        }
        int dir = index > tab ? 1 : -1;
        tab = index;

        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(dir * dp(28));
        in.animate().alpha(1f).translationX(0f).setDuration(animate ? 220 : 0).start();
        if (out != null) {
            final View old = out;
            old.animate().alpha(0f).translationX(-dir * dp(20))
                    .setDuration(animate ? 180 : 0)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (old != pages[tab]) {
                                old.setVisibility(View.GONE);
                                old.setAlpha(1f);
                                old.setTranslationX(0f);
                            }
                        }
                    }).start();
        }
        updateNav();
        if (bottomBar != null) bottomBar.setSelected(tab, animate);
        refreshBars();
    }

    private void updateNav() {
        if (navIcon[0] == null) return;
        for (int i = 0; i < 4; i++) {
            boolean active = i == tab;
            navIcon[i].setColorFilter(active ? cAccent : cPrimary);
            navLabel[i].setTextColor(active ? cAccent : cSecondary);
        }
        setBadge(1, String.valueOf(Prefs.getRules(this).size()));
        setBadge(2, "2");
    }

    private void setBadge(int index, String text) {
        TextView b = navBadge[index];
        if (b == null) return;
        if (!showBadge || text == null || "0".equals(text)) {
            b.setVisibility(View.GONE);
        } else {
            b.setText(text);
            b.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------ 设备页

    private View buildDevicesPage() {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(108), 0, dp(150));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(6), dp(18), dp(8));
        sv.addView(root);
        watchScroll(sv);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(cSecondary);
        statusView.setLineSpacing(dp(3), 1f);
        statusView.setPadding(dp(6), 0, dp(6), dp(4));
        root.addView(statusView);

        root.addView(section("已配对耳机"));
        LinearLayout devCard = card();
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        devCard.addView(deviceBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dcLp.topMargin = dp(8);
        root.addView(devCard, dcLp);

        root.addView(section("启用"));
        LinearLayout enableCard = card();
        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        enableRow.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView t = new TextView(this);
        t.setText("启用替换");
        t.setTextSize(17);
        t.setTextColor(cPrimary);
        enableRow.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        enableSwitch = new MiuixSwitch(this);
        enableSwitch.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(MiuixSwitch v, boolean checked) {
                if (syncing) return;
                Prefs.setEnabled(MainActivity.this, checked);
                updateStatus();
            }
        });
        enableRow.addView(enableSwitch);
        enableCard.addView(enableRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ecLp.topMargin = dp(8);
        root.addView(enableCard, ecLp);
        return sv;
    }

    private void updateStatus() {
        if (statusView == null) return;
        List<Rule> rules = Prefs.getRules(this);
        StringBuilder sb = new StringBuilder();
        sb.append(Prefs.isEnabled(this) ? "已开启" : "已暂停")
                .append(" · ").append(rules.size()).append(" 条规则");
        if (fullStatus) {
            sb.append("\n作用域：com.xiaomi.bluetooth · com.milink.service");
            sb.append("\n目标：");
            if (rules.isEmpty()) {
                sb.append("未设置");
            } else {
                Rule r = rules.get(0);
                sb.append(r.to).append(TextUtils.isEmpty(r.name) ? "" : "（" + r.name + "）");
            }
            sb.append("\n模块：BudsId 1.0.0");
        }
        statusView.setText(sb.toString());
    }

    // ------------------------------------------------------------ 规则页

    private View buildRulesPage() {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(108), 0, dp(150));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(6), dp(18), dp(8));
        sv.addView(root);
        watchScroll(sv);

        root.addView(section("替换规则"));
        LinearLayout card = card();
        ruleBox = new LinearLayout(this);
        ruleBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(ruleBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(card, lp);

        TextView help = new TextView(this);
        help.setText("选好型号后，强制停止「小米蓝牙」或断开重连耳机即可生效。\n"
                + "长按代号库里的型号可以复制弹窗动画 / 超级岛视频链接。");
        help.setTextSize(12);
        help.setTextColor(cSecondary);
        help.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(20);
        hLp.leftMargin = dp(6);
        root.addView(help, hLp);
        return sv;
    }

    // ------------------------------------------------------------ 设置页

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(108), 0, dp(150));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(6), dp(18), dp(8));
        sv.addView(root);
        watchScroll(sv);

        root.addView(section("界面"));
        LinearLayout ui = card();
        addSwitchRow(ui, R.drawable.ic_blur, "界面模糊", "启用顶栏和底栏的模糊效果", "ui_blur", blur,
                new Runnable() {
                    @Override
                    public void run() {
                        blur = Prefs.getBool(MainActivity.this, "ui_blur", true);
                        topBar.setBlurEnabled(blur);
                        bottomBar.setBlurEnabled(blur);
                        refreshBars();
                    }
                });
        addSwitchRow(ui, R.drawable.ic_float, "悬浮底栏", "使用 Apple 风格的悬浮底栏", "ui_floating", floatingBar,
                new Runnable() {
                    @Override
                    public void run() {
                        floatingBar = Prefs.getBool(MainActivity.this, "ui_floating", true);
                        applyBottomStyle();
                    }
                });
        addSwitchRow(ui, R.drawable.ic_glass, "液态玻璃", "启用悬浮底栏的液态玻璃效果", "ui_glass", glass,
                new Runnable() {
                    @Override
                    public void run() {
                        glass = Prefs.getBool(MainActivity.this, "ui_glass", true);
                        topBar.setGlassEnabled(glass);
                        bottomBar.setGlassEnabled(glass);
                        applyBottomStyle();
                    }
                });
        addSwitchRow(ui, R.drawable.ic_badge, "导航栏角标", "在导航栏显示已授权应用和已启用模块数量", "ui_badge", showBadge,
                new Runnable() {
                    @Override
                    public void run() {
                        showBadge = Prefs.getBool(MainActivity.this, "ui_badge", true);
                        updateNav();
                    }
                });
        addSwitchRow(ui, R.drawable.ic_info, "显示完整状态", "在首页信息卡中显示设备的完整状态", "ui_full_status",
                fullStatus, new Runnable() {
                    @Override
                    public void run() {
                        fullStatus = Prefs.getBool(MainActivity.this, "ui_full_status", false);
                        updateStatus();
                        renderDevices();
                    }
                });
        addValueRow(ui, R.drawable.ic_scale, "界面缩放", "调整整个界面的显示大小", "ui_scale");
        addValueRow(ui, R.drawable.ic_moon, "明暗模式", "跟随系统 / 浅色 / 深色", "ui_mode");
        addSwitchRow(ui, R.drawable.ic_badge, "详情页预览",
                "在设备详情弹层显示目标型号的大图预览", "ui_detail_preview",
                Prefs.getBool(this, "ui_detail_preview", true), new Runnable() {
                    @Override
                    public void run() {
                        detailPreview = Prefs.getBool(MainActivity.this, "ui_detail_preview", true);
                    }
                });
        root.addView(ui);

        root.addView(section("维护"));
        LinearLayout maint = card();
        addActionRow(maint, R.drawable.ic_gear, "重启 Hook 应用",
                "强制停止蓝牙 / 设置 / 设备互联，让新 hook 立刻生效", new Runnable() {
                    @Override
                    public void run() {
                        restartHookedApps();
                    }
                });
        root.addView(maint);

        root.addView(section("Hook 界面"));
        LinearLayout surf = card();
        addSwitchRow(surf, R.drawable.ic_float, "连接弹窗",
                "替换弹窗里的耳机图片和逐帧动画", "hook_popup",
                Prefs.getBool(this, "hook_popup", true), null);
        addSwitchRow(surf, R.drawable.ic_badge, "超级岛",
                "替换超级岛通知里的设备代号与图标", "hook_island",
                Prefs.getBool(this, "hook_island", true), null);
        addSwitchRow(surf, R.drawable.ic_info, "详情页",
                "替换设置 / 详情页里的设备图和代号", "hook_detail",
                Prefs.getBool(this, "hook_detail", true), null);
        root.addView(surf);


        root.addView(section("手势"));
        LinearLayout gesture = card();
        addSwitchRow(gesture, R.drawable.ic_gesture, "预测性返回手势", "启用对预测性返回手势的支持", "gesture_pred",
                predBack, null);
        addSwitchRow(gesture, R.drawable.ic_gesture, "横移返回手势", "从屏幕边缘横移返回上一页", "gesture_edge",
                edgeBack, null);
        addSwitchRow(gesture, R.drawable.ic_gesture, "页面切换手势", "左右滑动在设备 / 规则 / 设置之间切换",
                "gesture_swipe", swipePages, null);
        addSwitchRow(gesture, R.drawable.ic_gesture, "跨轴拦截", "斜向滑动时优先响应横向翻页", "gesture_cross",
                crossAxis, null);
        root.addView(gesture);

        root.addView(section("代号库"));
        LinearLayout lib = card();
        addActionRow(lib, R.drawable.ic_rules, "更新小米代号库",
                "从小米 CDN 拉取全部型号（455 个）并合并", new Runnable() {
                    @Override
                    public void run() {
                        syncDevicesFromPhone();
                    }
                });
        root.addView(lib);

        TextView about = new TextView(this);
        about.setText("BudsId 1.0.0 · 小米耳机代号替换\n"
                + "作用域：com.xiaomi.bluetooth、com.milink.service");
        about.setTextSize(11);
        about.setTextColor(cSecondary);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(24);
        root.addView(about, aLp);
        return sv;
    }

    private void addActionRow(LinearLayout parent, int iconRes, String title, String summary,
                              final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(cPrimary);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iLp.rightMargin = dp(14);
        row.addView(icon, iLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(cPrimary);
        TextView sub = new TextView(this);
        sub.setText(summary);
        sub.setTextSize(12);
        sub.setTextColor(cSecondary);
        texts.addView(t);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(18);
        chevron.setTextColor(cSecondary);
        row.addView(chevron);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.util.Log.i("BudsId", "action row clicked");
                action.run();
            }
        });
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 启动时自动从小米 CDN 更新代号库（一天一次）。 */
    private void autoUpdateCatalog() {
        final java.io.File f = new java.io.File(getFilesDir(), "devices_cloud.json");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86400000L) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = Devices.updateFromCloud(MainActivity.this);
                android.util.Log.i("BudsId", "auto catalog: " + n);
            }
        }, "budsid-catalog").start();
    }

    private void syncDevicesFromPhone() {
        android.util.Log.i("BudsId", "sync start");
        Toast.makeText(this, "正在从小米服务器拉取代号库…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = Devices.updateFromCloud(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (n < 0) {
                            Toast.makeText(MainActivity.this, "更新失败，检查网络",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MainActivity.this, "已同步 " + n + " 个型号",
                                Toast.LENGTH_SHORT).show();
                        applyCatalogResult(n);
                    }
                });
            }
        }).start();
    }

    private void applyCatalogResult(int added) {
        View old = pages[TAB_DEVICES];
        if (old != null) contentHost.removeView(old);
        pages[TAB_DEVICES] = buildDevicesPage();
        contentHost.addView(pages[TAB_DEVICES], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showTab(tab, false);
        refreshAll();
    }

    private void registerCatalogReceiver() {
        catalogReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String json = intent.getStringExtra("json");
                catalogArrived = true;
                final int added = Devices.mergeCatalog(MainActivity.this, json);
                applyCatalogResult(added);
            }
        };
        IntentFilter filter = new IntentFilter("io.github.jumaoli.budsid.CATALOG");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(catalogReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(catalogReceiver, filter);
        }
    }

    private void addSwitchRow(LinearLayout parent, int iconRes, String title, String summary,
                              final String key, boolean value, final Runnable after) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(cPrimary);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(12);
        s.setTextColor(cSecondary);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2);
        texts.addView(s, sLp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(cPrimary);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iLp.rightMargin = dp(16);
        row.addView(icon, iLp);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MiuixSwitch sw = new MiuixSwitch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(new MiuixSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(MiuixSwitch v, boolean checked) {
                Prefs.setBool(MainActivity.this, key, checked);
                if (after != null) after.run();
            }
        });
        row.addView(sw);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addValueRow(LinearLayout parent, int iconRes, String title, String summary,
                             final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(cPrimary);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(12);
        s.setTextColor(cSecondary);
        texts.addView(s);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(cPrimary);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iLp.rightMargin = dp(16);
        row.addView(icon, iLp);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTextColor(cAccent);
        value.setText(valueOf(key));
        row.addView(value);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(cSecondary);
        chevron.setPadding(dp(8), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showValueDialog(key, value);
            }
        });

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String valueOf(String key) {
        if ("ui_scale".equals(key)) {
            return Prefs.getInt(this, key, 100) + "%";
        }
        int mode = Prefs.getInt(this, "ui_mode", 0);
        if (mode == 1) return "浅色";
        if (mode == 2) return "深色";
        return "跟随系统";
    }

    private void showValueDialog(final String key, final TextView value) {
        if ("ui_scale".equals(key)) {
            final String[] items = {"85%", "100%", "115%"};
            final int[] values = {85, 100, 115};
            int current = Prefs.getInt(this, key, 100);
            int checked = current == 85 ? 0 : (current == 115 ? 2 : 1);
            new AlertDialog.Builder(this)
                    .setTitle("界面缩放")
                    .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            Prefs.setInt(MainActivity.this, key, values[which]);
                            d.dismiss();
                            recreate();
                        }
                    })
                    .show();
        } else {
            final String[] items = {"跟随系统", "浅色", "深色"};
            int current = Prefs.getInt(this, key, 0);
            new AlertDialog.Builder(this)
                    .setTitle("明暗模式")
                    .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            Prefs.setInt(MainActivity.this, key, which);
                            d.dismiss();
                            recreate();
                        }
                    })
                    .show();
        }
    }

    // ------------------------------------------------------------ 关于页

























    private View buildAboutPage() {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(108), 0, dp(150));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(6), dp(18), dp(8));
        sv.addView(root);
        watchScroll(sv);

        root.addView(section("关于"));
        LinearLayout head = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(18), dp(18), dp(18));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iLp.rightMargin = dp(14);
        row.addView(icon, iLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("BudsId · 小米耳机代号替换");
        t1.setTextSize(17);
        t1.setTextColor(cPrimary);
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("1.0.0 · LSPosed 模块");
        t2.setTextSize(12);
        t2.setTextColor(cSecondary);
        texts.addView(t2);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        root.addView(head, hLp);

        root.addView(section("作用域"));
        LinearLayout scope = card();
        addInfoRow(scope, "com.xiaomi.bluetooth", "连接弹窗 · 超级岛 · 设备图", true);
        addInfoRow(scope, "com.milink.service", "融合设备卡 · 设备详情", true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(8);
        root.addView(scope, sLp);

        root.addView(section("说明"));
        TextView help = new TextView(this);
        help.setText("代号来自广播解析、HeadsetInfo、DeviceIdCached 与 deviceinfo 数据库四处，"
                + "模块会同时替换；弹窗图片按代号读 fc_resources，存在才换、不改系统文件。\n\n"
                + "长按代号库里的型号可复制弹窗动画 / 超级岛视频 CDN 链接。");
        help.setTextSize(12);
        help.setTextColor(cSecondary);
        help.setLineSpacing(dp(4), 1f);
        help.setPadding(dp(6), dp(4), dp(6), dp(4));
        root.addView(help);
        return sv;
    }

    private void addInfoRow(LinearLayout parent, String pkg, String desc, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(enabled ? 0xFF34C759 : 0xFFBBBBBB);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dLp.rightMargin = dp(12);
        row.addView(dot, dLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(pkg);
        t1.setTextSize(15);
        t1.setTextColor(cPrimary);
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextSize(12);
        t2.setTextColor(cSecondary);
        texts.addView(t2);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ 通用

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(cSecondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.leftMargin = dp(6);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);
        box.setClipToOutline(true);
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.divider(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(18);
        v.setLayoutParams(lp);
        return v;
    }

    private void refreshAll() {
        if (enableSwitch != null) {
            syncing = true;
            enableSwitch.setChecked(Prefs.isEnabled(this));
            syncing = false;
        }
        updateStatus();
        renderDevices();
        renderRules();
        updateNav();
        applyBottomStyle();
        refreshBars();
    }

    // ------------------------------------------------------------ 设备

    private void requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) refreshAll();
    }

    private List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return out;
            }
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return out;
            BluetoothAdapter ad = bm.getAdapter();
            if (ad == null) return out;
            out.addAll(ad.getBondedDevices());
            android.util.Log.i("BudsId", "bonded count=" + out.size() + " perm="
                    + checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT));
            Collections.sort(out, new Comparator<BluetoothDevice>() {
                @Override
                public int compare(BluetoothDevice a, BluetoothDevice b) {
                    String x = a.getName() == null ? "" : a.getName();
                    String y = b.getName() == null ? "" : b.getName();
                    return x.compareToIgnoreCase(y);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    private Rule ruleForMac(String mac) {
        if (mac == null) return null;
        for (Rule r : Prefs.getRules(this)) {
            if (mac.equalsIgnoreCase(r.mac)) return r;
        }
        return null;
    }

    private void renderDevices() {
        if (deviceBox == null) return;
        deviceBox.removeAllViews();
        List<BluetoothDevice> list = bonded();
        android.util.Log.i("BudsId", "renderDevices size=" + list.size());
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有可用的已配对设备（或还没给蓝牙权限）");
            empty.setTextSize(14);
            empty.setTextColor(cSecondary);
            empty.setPadding(dp(18), dp(18), dp(18), dp(18));
            deviceBox.addView(empty);
            return;
        }
        boolean first = true;
        for (final BluetoothDevice d : list) {
            String mac;
            String name;
            try {
                mac = d.getAddress();
                name = d.getName();
            } catch (Throwable t) {
                continue;
            }
            if (mac == null) continue;
            if (TextUtils.isEmpty(name)) name = "未命名设备";
            final String fmac = mac;
            final String fname = name;
            Rule r = ruleForMac(mac);

            if (!first) deviceBox.addView(divider());
            first = false;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(14), dp(14), dp(14));

            {
                ImageView rowThumb = new ImageView(this);
                rowThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable tb = new GradientDrawable();
                tb.setColor(Theme.dark(this) ? 0x1FFFFFFF : 0x0F000000);
                tb.setCornerRadius(dp(9));
                rowThumb.setBackground(tb);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(40), dp(40));
                tlp.rightMargin = dp(12);
                row.addView(rowThumb, tlp);
                if (r != null && !TextUtils.isEmpty(r.to)) {
                    Thumbs.load(this, r.to, rowThumb);
                } else {
                    rowThumb.setImageResource(R.drawable.ic_earbud);
                    rowThumb.setAlpha(0.35f);
                    rowThumb.setPadding(dp(7), dp(7), dp(7), dp(7));
                }
            }

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(name);
            t1.setTextSize(16);
            t1.setTextColor(cPrimary);
            texts.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(mac + (fullStatus && r != null ? " · " + r.to : ""));
            t2.setTextSize(12);
            t2.setTextColor(cSecondary);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView value = new TextView(this);
            value.setTextSize(13);
            if (r == null) {
                value.setText("原始");
                value.setTextColor(cSecondary);
            } else {
                value.setText(TextUtils.isEmpty(r.name) ? r.to : r.name);
                value.setTextColor(cAccent);
                value.setMaxWidth(dp(140));
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
            }
            row.addView(value);

            TextView chevron = new TextView(this);
            chevron.setText("›");
            chevron.setTextSize(20);
            chevron.setTextColor(cSecondary);
            chevron.setPadding(dp(8), 0, 0, 0);
            row.addView(chevron);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTargetSheet(fname, fmac);
                }
            });
            deviceBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ------------------------------------------------------------ 规则

    private void renderRules() {
        if (ruleBox == null) return;
        ruleBox.removeAllViews();
        List<Rule> rules = Prefs.getRules(this);
        boolean first = true;
        for (int i = 0; i < rules.size(); i++) {
            final Rule r = rules.get(i);
            final int index = i;
            if (!first) ruleBox.addView(divider());
            first = false;

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(14), dp(14), dp(14));

            ImageView ruleThumb = new ImageView(this);
            ruleThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(Theme.dark(this) ? 0x1FFFFFFF : 0x0F000000);
            rb.setCornerRadius(dp(9));
            ruleThumb.setBackground(rb);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(40), dp(40));
            rlp.rightMargin = dp(12);
            row.addView(ruleThumb, rlp);
            if (!TextUtils.isEmpty(r.to)) Thumbs.load(this, r.to, ruleThumb);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(r.isWildcard() ? "全部设备 → " + r.to : r.from + " → " + r.to);
            t1.setTextSize(15);
            t1.setTextColor(cPrimary);
            texts.addView(t1);
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(r.name)) sb.append(r.name);
            if (!TextUtils.isEmpty(r.mac)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(r.mac);
            }
            if (sb.length() > 0) {
                TextView t2 = new TextView(this);
                t2.setText(sb.toString());
                t2.setTextSize(12);
                t2.setTextColor(cSecondary);
                texts.addView(t2);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(Theme.red(this));
            del.setPadding(dp(10), dp(6), 0, dp(6));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<Rule> list = Prefs.getRules(MainActivity.this);
                    if (index < list.size()) {
                        list.remove(index);
                        Prefs.setRules(MainActivity.this, list);
                        refreshAll();
                    }
                }
            });
            row.addView(del);
            ruleBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!first) ruleBox.addView(divider());
        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView plus = new TextView(this);
        plus.setText("＋  添加规则");
        plus.setTextSize(15);
        plus.setTextColor(cAccent);
        add.addView(plus);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRuleSheet(null);
            }
        });
        ruleBox.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ 选型号

    private void showTargetSheet(final String devName, final String mac) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        final Rule existing = ruleForMac(mac);

        final ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable bgb = new GradientDrawable();
        bgb.setColor(Theme.dark(this) ? 0x14FFFFFF : 0x08000000);
        bgb.setCornerRadius(dp(18));
        big.setBackground(bgb);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, detailPreview ? dp(150) : 0);
        content.addView(big, blp);

        final TextView bigName = new TextView(this);
        bigName.setTextSize(15);
        bigName.setTextColor(cPrimary);
        bigName.setGravity(Gravity.CENTER);
        bigName.setPadding(0, dp(6), 0, 0);
        content.addView(bigName);

        final TextView bigCode = new TextView(this);
        bigCode.setTextSize(12);
        bigCode.setTextColor(cSecondary);
        bigCode.setGravity(Gravity.CENTER);
        bigCode.setPadding(0, 0, 0, dp(8));
        content.addView(bigCode);

        final Devices.Dev[] picked = new Devices.Dev[]{null};
        final Runnable refreshPreview = new Runnable() {
            @Override
            public void run() {
                Devices.Dev d = picked[0];
                if (d == null) {
                    big.setImageResource(R.drawable.ic_earbud);
                    big.setAlpha(0.3f);
                    big.setPadding(dp(40), dp(40), dp(40), dp(40));
                    bigName.setText("未选择型号");
                    bigCode.setText("点下面的型号预览，再点确定");
                } else {
                    big.setAlpha(1f);
                    big.setPadding(0, 0, 0, 0);
                    bigName.setText(TextUtils.isEmpty(d.name) ? d.id : d.name);
                    bigCode.setText(d.id);
                    Thumbs.loadBig(MainActivity.this, d.id, big);
                }
            }
        };
        if (existing != null && !TextUtils.isEmpty(existing.to)) {
            for (Devices.Dev d : Devices.all(this)) {
                if (d.id.equalsIgnoreCase(existing.to)) {
                    picked[0] = d;
                    break;
                }
            }
        }
        refreshPreview.run();
        if (existing != null) {
            TextView reset = new TextView(this);
            reset.setText("恢复原始代号");
            reset.setTextSize(15);
            reset.setTextColor(Theme.red(this));
            reset.setPadding(0, dp(14), 0, dp(14));
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<Rule> list = Prefs.getRules(MainActivity.this);
                    for (int i = list.size() - 1; i >= 0; i--) {
                        if (mac != null && mac.equalsIgnoreCase(list.get(i).mac)) list.remove(i);
                    }
                    Prefs.setRules(MainActivity.this, list);
                    refreshAll();
                }
            });
            content.addView(reset);
        }

        final EditText search = field("搜索型号或代号");
        content.addView(search);

        final List<Devices.Dev> all = Devices.all(this);
        final List<Devices.Dev> filtered = new ArrayList<>(all);
        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        final DevAdapter adapter = new DevAdapter(filtered);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView pickTip = new TextView(this);
        pickTip.setText("长按任意型号，可复制它的弹窗动画 / 超级岛视频链接。");
        pickTip.setTextSize(12);
        pickTip.setTextColor(cSecondary);
        pickTip.setPadding(0, dp(10), 0, 0);
        content.addView(pickTip);

        final Dialog dialog = Sheet.show(this,
                TextUtils.isEmpty(devName) ? "选择显示型号" : devName, content);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim().toLowerCase(Locale.US);
                filtered.clear();
                for (Devices.Dev d : all) {
                    if (q.isEmpty()
                            || d.id.toLowerCase(Locale.US).contains(q)
                            || d.name.toLowerCase(Locale.US).contains(q)) {
                        filtered.add(d);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) return;
                picked[0] = filtered.get(position);
                refreshPreview.run();
            }
        });
        final TextView confirm = new TextView(this);
        confirm.setText("确定");
        confirm.setTextSize(16);
        confirm.setTextColor(0xFFFFFFFF);
        confirm.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0xFF3482FF);
        cb.setCornerRadius(dp(14));
        confirm.setBackground(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        clp.topMargin = dp(12);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picked[0] == null) {
                    toast("先选一个型号");
                    return;
                }
                saveMapping(mac, picked[0]);
                dialog.dismiss();
            }
        });
        content.addView(confirm, clp);

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) return false;
                showLinks(filtered.get(position));
                return true;
            }
        });
    }

    private void saveMapping(String mac, Devices.Dev d) {
        List<Rule> list = Prefs.getRules(this);
        if (!TextUtils.isEmpty(mac)) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (mac.equalsIgnoreCase(list.get(i).mac)) list.remove(i);
            }
            if (d != null) list.add(new Rule("*", d.id, mac, d.name));
        } else if (d != null) {
            list.add(new Rule("*", d.id, "", d.name));
        }
        Prefs.setRules(this, list);
        refreshAll();
        toast("已保存，重连耳机后生效");
    }

    private void showLinks(final Devices.Dev d) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView idLine = new TextView(this);
        idLine.setText(d.id + "  " + d.name);
        idLine.setTextSize(13);
        idLine.setTextColor(cSecondary);
        content.addView(idLine);

        final String popup = "https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect/"
                + d.id + ".zip";
        final String island = "https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/"
                + "fast_connect_video_new/" + d.id + ".zip";

        TextView p = new TextView(this);
        p.setText("复制弹窗动画链接（fc_resources）");
        p.setTextSize(15);
        p.setTextColor(cPrimary);
        p.setPadding(0, dp(16), 0, dp(16));
        p.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(popup);
                toast("已复制弹窗动画链接");
            }
        });
        content.addView(p);

        TextView i = new TextView(this);
        i.setText("复制超级岛视频链接（fast_connect_video_new）");
        i.setTextSize(15);
        i.setTextColor(cPrimary);
        i.setPadding(0, dp(8), 0, dp(16));
        i.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(island);
                toast("已复制超级岛视频链接");
            }
        });
        content.addView(i);

        TextView tip = new TextView(this);
        tip.setText("代号没有动画包时，系统会回退到内置通用动画。");
        tip.setTextSize(12);
        tip.setTextColor(cSecondary);
        content.addView(tip);

        Sheet.show(this, "动画链接", content);
    }

    private void copy(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text));
    }

    // ------------------------------------------------------------ 手动规则

    private void showRuleSheet(final Rule editing) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        final EditText etFrom = field("真实代号，如 01013004；填 * 表示全部");
        etFrom.setText(editing == null ? "" : editing.from);
        content.addView(etFrom);

        final EditText etMac = field("MAC（可选），只对这副耳机生效");
        etMac.setText(editing == null ? "" : editing.mac);
        content.addView(etMac);

        final String[] target = new String[]{editing == null ? "" : editing.to};
        final String[] targetName = new String[]{editing == null ? "" : editing.name};

        final TextView pick = new TextView(this);
        pick.setTextSize(15);
        pick.setTextColor(cAccent);
        pick.setPadding(0, dp(16), 0, dp(16));
        pick.setText(TextUtils.isEmpty(target[0]) ? "选择目标型号 ›"
                : "目标：" + target[0] + "  " + targetName[0]);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker(new OnPick() {
                    @Override
                    public void onPick(Devices.Dev dev) {
                        target[0] = dev.id;
                        targetName[0] = dev.name;
                        pick.setText("目标：" + dev.id + "  " + dev.name);
                    }
                });
            }
        });
        content.addView(pick);

        Button save = new Button(this);
        save.setText("保存");
        save.setAllCaps(false);
        save.setTextColor(0xFFFFFFFF);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(cAccent);
        bb.setCornerRadius(dp(14));
        save.setBackground(bb);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(20);
        content.addView(save, slp);

        final Dialog[] holder = new Dialog[1];
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String from = etFrom.getText().toString().trim().toUpperCase(Locale.US);
                String mac = etMac.getText().toString().trim().toUpperCase(Locale.US);
                if (from.isEmpty()) from = "*";
                if (!from.equals("*") && !from.matches("[0-9A-F]{8}")) {
                    toast("真实代号要 8 位十六进制，或填 *");
                    return;
                }
                if (!target[0].matches("[0-9A-F]{8}")) {
                    toast("请先选择目标型号");
                    return;
                }
                if (!mac.isEmpty() && !mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                    toast("MAC 格式应为 AA:BB:CC:DD:EE:FF");
                    return;
                }
                List<Rule> list = Prefs.getRules(MainActivity.this);
                if (editing != null) {
                    for (int i = 0; i < list.size(); i++) {
                        Rule r = list.get(i);
                        if (r.from.equals(editing.from) && r.to.equals(editing.to)
                                && r.mac.equals(editing.mac)) {
                            list.set(i, new Rule(from, target[0], mac, targetName[0]));
                            break;
                        }
                    }
                } else {
                    list.add(new Rule(from, target[0], mac, targetName[0]));
                }
                Prefs.setRules(MainActivity.this, list);
                if (holder[0] != null) holder[0].dismiss();
                refreshAll();
            }
        });

        holder[0] = Sheet.show(this, editing == null ? "添加规则" : "编辑规则", content);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(cPrimary);
        e.setHintTextColor(cSecondary);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(Theme.field(this));
        fb.setCornerRadius(dp(12));
        e.setBackground(fb);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    private void showPicker(final OnPick cb) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        final EditText search = field("搜索型号或代号");
        content.addView(search);

        final List<Devices.Dev> all = Devices.all(this);
        final List<Devices.Dev> filtered = new ArrayList<>(all);
        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        final DevAdapter adapter = new DevAdapter(filtered);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        final Dialog dialog = Sheet.show(this, "选择目标型号", content);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim().toLowerCase(Locale.US);
                filtered.clear();
                for (Devices.Dev d : all) {
                    if (q.isEmpty()
                            || d.id.toLowerCase(Locale.US).contains(q)
                            || d.name.toLowerCase(Locale.US).contains(q)) {
                        filtered.add(d);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) return;
                cb.onPick(filtered.get(position));
                dialog.dismiss();
                sendPrefetch(filtered.get(position).id);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) return false;
                showLinks(filtered.get(position));
                return true;
            }
        });
    }

    // ------------------------------------------------------------ 适配器

    private class DevAdapter extends BaseAdapter {
        private final List<Devices.Dev> items;

        DevAdapter(List<Devices.Dev> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(12), dp(4), dp(12));

            Devices.Dev d = items.get(position);
            LinearLayout line = new LinearLayout(MainActivity.this);
            line.setGravity(Gravity.CENTER_VERTICAL);

            ImageView thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable tb = new GradientDrawable();
            tb.setColor(Theme.dark(MainActivity.this) ? 0x1FFFFFFF : 0x0F000000);
            tb.setCornerRadius(dp(8));
            thumb.setBackground(tb);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(44), dp(44));
            tlp.rightMargin = dp(12);
            line.addView(thumb, tlp);
            Thumbs.load(MainActivity.this, d.id, thumb);

            TextView name = new TextView(MainActivity.this);
            name.setText(d.name);
            name.setTextSize(16);
            name.setTextColor(cPrimary);
            line.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (d.anim) {
                TextView badge = new TextView(MainActivity.this);
                badge.setText("有动画");
                badge.setTextSize(11);
                badge.setTextColor(cAccent);
                GradientDrawable b = new GradientDrawable();
                b.setColor(0x1A3482FF);
                b.setCornerRadius(dp(6));
                badge.setBackground(b);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                line.addView(badge);
            }
            row.addView(line);

            TextView sub = new TextView(MainActivity.this);
            sub.setText(d.id);
            sub.setTextSize(12);
            sub.setTextColor(cSecondary);
            row.addView(sub);
            return row;
        }
    }

    // ------------------------------------------------------------ 颜色

    private void resolveColors() {
        int mode = Prefs.getInt(this, "ui_mode", 0);
        boolean dark;
        if (mode == 1) dark = false;
        else if (mode == 2) dark = true;
        else dark = Theme.dark(this);
        if (dark) {
            cBg = 0xFF000000;
            cCard = 0xFF1C1C1E;
            cPrimary = 0xFFF5F5F7;
            cSecondary = 0xFF9A9AA0;
        } else {
            cBg = 0xFFF6F6F8;
            cCard = 0xFFFFFFFF;
            cPrimary = 0xFF0D0D0D;
            cSecondary = 0xFF8A8A8E;
        }
        cAccent = 0xFF3482FF;
    }

    private int withAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
