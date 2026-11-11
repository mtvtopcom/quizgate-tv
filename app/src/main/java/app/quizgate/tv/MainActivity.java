package app.quizgate.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 设置页：遥控器可完成全部操作，不依赖输入法。 */
public class MainActivity extends Activity {

    /** 开始计时前检查的三项授权。卸载留痕是可选功能，刻意不在这一层提示。 */
    private static final String AUTH_OVERLAY = "overlay";
    private static final String AUTH_NOTIF = "notif";
    private static final String AUTH_BATTERY = "battery";

    private Prefs prefs;
    private FrameLayout root;
    private ScrollView contentScroll;
    private LinearLayout content;

    private TextView valueDuration, valueExtend, valueGrade, valuePin, valueRecovery, valueGuard, btnPerm;
    private TextView valueVersion, valueAnchor, valueLog;
    private TextView anchorWarn;
    /** 设置页的第一项（本次时长）。顶部返回按钮在 1.2.9 删掉后，焦点默认落在这里。 */
    private View firstRow;
    /** 刚从哪一行打开过面板：关掉面板后把焦点还给它，别让家长重新一路按下去。 */
    private View settingsFocus;
    private TextView banner;
    /** 首页（只看运行状态）；设置页是原来的 contentScroll，两者在同一个 Activity 里切换。 */
    private View homeRoot;
    private TextView homePhase, homeTime, homeGuard, homeAuth, homeAnchor, homeBanner, homeBtnStart;
    /** 首页状态读数左侧的色条：待机灰、计时黄、锁定红。 */
    private View homeBar;
    private LinearLayout authBox;
    /** 设置页里的「运行日志」子页；null = 没打开。 */
    private FrameLayout logPanel;
    private TextView logBody;
    /** 选时长 / 选年级的自绘面板。null = 没打开。 */
    private OptionPanel optionPanel;
    /** 开始计时前的授权检查面板。null = 没打开。 */
    private AuthView authPanel;
    private boolean showingSettings = false;
    private boolean settingsUnlocked = false;
    private long lastRestoreAt = 0L;
    /** 卸载留痕判定；warning 只要卸过就一直显示，不是一次性提示。 */
    private Anchor.Report anchorReport;
    private final Handler h = new Handler(Looper.getMainLooper());

    private PinPadView gate;
    /** 口令面板的三种用途，见 PIN_* 常量。 */
    private static final int PIN_SETUP = 0, PIN_UNLOCK = 1, PIN_CONFIRM = 2;
    private int pinMode = PIN_SETUP;
    /** PIN_CONFIRM 通过后要执行的动作（如「结束本次会话」）。 */
    private Runnable pinConfirm;
    /** true = 首次必须设口令（返回=退出应用）；false = 从设置页改口令（返回=回设置页）。 */
    private boolean gateRequired = true;
    private RecoveryFlow recovery;
    private FrameLayout codeOverlay;
    /** 在线升级弹层。null = 没在显示。 */
    private UpgradeView upgrade;
    /** 检查到的新版本。null = 没有更新（或检查失败，两者处理一样）。 */
    private Updater.Result pendingUpdate;
    private boolean focusInited;
    private String firstPin = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        // 必须在 buildContent / showSettings（都会 refresh）之前拿到判定结果
        anchorReport = Anchor.sync(this, prefs);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        buildContent();   // 设置页：参数、授权自检、版本号
        buildHome();      // 首页：只放运行状态
        setContentView(root);
        showHome();
        // 会话没结束但守护服务不在（系统强杀 / 重装后缺授权）→ 立刻按快照恢复，不让倒计时冻结
        autoRestore();
        // 首次安装（含卸载重装）：直接进设置页让家长自己设 6 位口令
        if (!prefs.hasPin()) showSettings();
        checkUpdate();
    }

    // ---------- UI ----------

    private void buildContent() {
        ScrollView sv = new ScrollView(this);
        contentScroll = sv;
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 88), Ui.dp(this, 44), Ui.dp(this, 88), Ui.dp(this, 44));
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        // 1.2.9：原来最上面那块（← 返回首页 / 「答对再看」/ 一句说明 / 「未开始 · 待机」状态行）
        // 整块删掉，抬头直接是「设置」。返回入口仍在页底，「BACK」也照旧可回首页。
        content.addView(Ui.title(this, "设置", 34));

        // 熔断横幅不是「顶部模块」，是故障态的唯一显眼出口：正常时 GONE，只在连续崩溃后出现。
        banner = Ui.label(this, "", 20, Ui.BG);
        banner.setBackgroundColor(Ui.BAD);
        banner.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 14));
        banner.setFocusable(true);
        banner.setClickable(true);
        banner.setOnClickListener(v -> promptClearSafeMode());
        banner.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp0 = new LinearLayout.LayoutParams(-1, -2);
        blp0.topMargin = Ui.dp(this, 16);
        content.addView(banner, blp0);

        // 卸载留痕：只要系统设置锚点里记过卸载，这里就一直红字，孩子重装也抹不掉
        anchorWarn = Ui.label(this, "", 18, Ui.BAD);
        LinearLayout.LayoutParams awlp = new LinearLayout.LayoutParams(-1, -2);
        awlp.topMargin = Ui.dp(this, 16);
        content.addView(anchorWarn, awlp);
        anchorWarn.setVisibility(View.GONE);

        valueDuration = addRow("本次时长", this::openDurationPanel);
        valueExtend = addRow("答对延长", this::openExtendPanel);
        valueGrade = addRow("题目年级", this::openStagePanel);
        valuePin = addRow("家长口令", this::promptSetPin);
        valueRecovery = addRow("恢复码", this::onRecoveryRow);
        valueGuard = addRow("守护状态", this::promptClearSafeMode);
        valueVersion = addRow("当前版本", () -> toast(versionText()));
        valueAnchor = addRow("卸载留痕",
                () -> toast(anchorReport == null ? "未检测" : anchorReport.detail()));

        // 顶部返回按钮删掉后，进设置页的默认焦点落在这里
        firstRow = (View) valueDuration.getParent();

        TextView perm = Ui.key(this, "授权：显示在其他应用上层", 18);
        btnPerm = perm;
        perm.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = Ui.dp(this, 22);
        content.addView(perm, plp);
        perm.setOnClickListener(v -> requestOverlay());

        // 授权自检：缺哪项就红字给出可照抄的 adb 命令
        authBox = new LinearLayout(this);
        authBox.setOrientation(LinearLayout.VERTICAL);
        authBox.setPadding(Ui.dp(this, 8), Ui.dp(this, 26), Ui.dp(this, 8), Ui.dp(this, 6));
        content.addView(authBox, new LinearLayout.LayoutParams(-1, -2));

        // 授权自检后面原本是一大段日志文字，直接铺在设置页上又长又像报错。
        // 现在只在自检下面留一个入口，点进去是独立子页。
        valueLog = addRow("运行日志", this::showLogPanel);

        // 底部再给一个返回：滚到最后一项时不用再往上找
        TextView backBottom = Ui.key(this, "← 返回首页", 22);
        backBottom.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        bblp.topMargin = Ui.dp(this, 22);
        content.addView(backBottom, bblp);
        backBottom.setOnClickListener(v -> showHome());
    }

    // ---------- 首页：电视风格，只放运行状态 ----------

    private void buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setBackgroundColor(Ui.BG);
        // 1080p 电视的有效视口只有 960×540dp，而且固件普遍还有 overscan。
        // 1.2.8 之前这里的固定尺寸加起来超过 540dp，最下面那行「方向键选择…」在真机上
        // 被整行裁掉（用户报的「开始计时下面的字看不见」）。全部收紧一档，留出约 150dp 余量。
        home.setPadding(Ui.dp(this, 72), Ui.dp(this, 24), Ui.dp(this, 72), Ui.dp(this, 16));

        LinearLayout brand = Ui.row(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, 0, 0, Ui.dp(this, 14));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.home_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60)));
        LinearLayout titles = Ui.col(this);
        titles.setGravity(Gravity.START);
        titles.setPadding(Ui.dp(this, 18), 0, 0, 0);
        titles.addView(Ui.title(this, "答对再看", 30));
        titles.addView(Ui.label(this, "到点一题 · 答对续时", 17, Ui.DIM));
        brand.addView(titles, new LinearLayout.LayoutParams(-2, -2));
        home.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        // 状态读数：左侧色条 + 上下细线。刻意不用圆角描边卡片 ——
        // 原来的黄框圆角块和首页按钮长得一样，家长会以为「待机」是可以按的选项。
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        View topRule = new View(this);
        topRule.setBackgroundColor(0xFF1F1F1F);
        card.addView(topRule, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        LinearLayout readout = Ui.row(this);
        readout.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        homeBar = new View(this);
        // 色条给固定高度，不跟文字做 MATCH_PARENT：卡片改成 wrap 之后，
        // 横向 LinearLayout 里的 -1 会退化成 0，色条会整条消失（模拟器实测）。
        readout.addView(homeBar, new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 78)));

        LinearLayout info = Ui.col(this);
        info.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        info.setPadding(Ui.dp(this, 32), Ui.dp(this, 14), Ui.dp(this, 28), Ui.dp(this, 14));
        readout.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(readout, new LinearLayout.LayoutParams(-1, -2));

        View bottomRule = new View(this);
        bottomRule.setBackgroundColor(0xFF1F1F1F);
        card.addView(bottomRule, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        homePhase = Ui.label(this, "未开始 · 待机", 21, Ui.DIM);
        homePhase.setGravity(Gravity.START);
        info.addView(homePhase);

        homeTime = Ui.label(this, "--:--", 62, 0xFF6B7075);
        // 等宽字体：倒计时数字不会跳动，待机时的 --:-- 也不会散成「- - : - -」
        homeTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        homeTime.setGravity(Gravity.START);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 2);
        info.addView(homeTime, tlp);

        homeGuard = Ui.label(this, "", 18, Ui.ACCENT);
        homeGuard.setGravity(Gravity.START);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = Ui.dp(this, 8);
        info.addView(homeGuard, glp);

        homeBanner = Ui.label(this, "", 19, Ui.BLACK);
        homeBanner.setBackgroundColor(Ui.BAD);
        homeBanner.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        homeBanner.setFocusable(true);
        homeBanner.setClickable(true);
        homeBanner.setOnClickListener(v -> promptClearSafeMode());
        homeBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams hblp = new LinearLayout.LayoutParams(-1, -2);
        hblp.topMargin = Ui.dp(this, 16);
        info.addView(homeBanner, hblp);

        home.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // 告警行放在卡片**外面**：卡片高度按内容走，告警多两行也不会把最后一行裁掉半截。
        homeAuth = Ui.label(this, "", 16, Ui.BAD);
        homeAuth.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams walp = new LinearLayout.LayoutParams(-1, -2);
        walp.topMargin = Ui.dp(this, 10);
        home.addView(homeAuth, walp);
        homeAuth.setVisibility(View.GONE);

        homeAnchor = Ui.label(this, "", 16, Ui.BAD);
        homeAnchor.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wanlp = new LinearLayout.LayoutParams(-1, -2);
        wanlp.topMargin = Ui.dp(this, 8);
        home.addView(homeAnchor, wanlp);
        homeAnchor.setVisibility(View.GONE);

        // 弹性空白：状态读数按内容高度走，剩下的空间全给这里，按钮仍然贴着底部
        View spacer = new View(this);
        home.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout btns = Ui.row(this);
        btns.setGravity(Gravity.CENTER);
        homeBtnStart = Ui.homeKey(this, "开始计时", true);
        homeBtnStart.setMinHeight(Ui.dp(this, 56));
        homeBtnStart.setOnClickListener(v -> startSession());
        btns.addView(homeBtnStart);

        TextView end = Ui.homeKey(this, "结束本次会话", false);
        end.setMinHeight(Ui.dp(this, 56));
        end.setOnClickListener(v -> exitSession());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = Ui.dp(this, 20);
        btns.addView(end, elp);

        TextView settings = Ui.homeKey(this, "设置", false);
        settings.setMinHeight(Ui.dp(this, 56));
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = Ui.dp(this, 20);
        btns.addView(settings, slp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 16);
        home.addView(btns, blp);

        TextView hint = Ui.label(this, "方向键选择 · OK 确认 · 时长、口令、恢复码都在「设置」里", 16, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(this, 10);
        home.addView(hint, hlp);

        homeRoot = home;
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showHome() {
        showingSettings = false;
        contentScroll.setVisibility(View.GONE);
        homeRoot.setVisibility(View.VISIBLE);
        focusInited = false;
        refresh();
        blockFocusBelow();
        maybeInitFocus();
    }

    /** 设置页受口令保护；本次 Activity 里解锁过一次就不再反复问。 */
    private void showSettings() {
        showingSettings = true;
        settingsFocus = null;      // 每次进设置页都从第一项开始
        homeRoot.setVisibility(View.GONE);
        contentScroll.setVisibility(View.VISIBLE);
        focusInited = false;
        refresh();
        if (!settingsUnlocked) gate();
        blockFocusBelow();
        if (settingsUnlocked) maybeInitFocus();
    }

    // ---------- 授权自检与自愈 ----------

    private boolean notifOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean batteryOk() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private String versionText() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 会话还在、守护却不在：按磁盘快照恢复计时，别让设置页停在一个不动的数字上。 */
    private void autoRestore() {
        if (prefs.safeMode() || prefs.phase() == Prefs.IDLE) return;
        if (!Settings.canDrawOverlays(this)) return;
        if (App.gateAlive) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRestoreAt < 3000L) return;
        lastRestoreAt = now;
        try {
            startForegroundService(new Intent(this, GateService.class).setAction(GateService.ACTION_RESTORE));
            android.util.Log.i("QuizGate", "auto_restore phase=" + prefs.phase());
        } catch (Throwable t) {
            android.util.Log.w("QuizGate", "auto_restore failed: " + t);
        }
    }

    private void refreshAuth() {
        if (authBox == null) return;
        authBox.removeAllViews();
        boolean notif = notifOn(), battery = batteryOk();
        boolean needGuard = prefs.phase() != Prefs.IDLE;
        boolean alive = App.gateAlive;
        authBox.addView(Ui.title(this, "授权自检", 24));
        authBox.addView(authLine("悬浮窗权限", Settings.canDrawOverlays(this),
                Settings.canDrawOverlays(this) ? null
                        : "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"));
        authBox.addView(authLine("通知使用权", notif, notif ? null
                : "adb shell cmd notification allow_listener " + getPackageName() + "/" + getPackageName() + ".NotifProbe"));
        authBox.addView(authLine("电池优化白名单", battery, battery ? null
                : "adb shell dumpsys deviceidle whitelist +" + getPackageName()));
        authBox.addView(authLine("守护服务", needGuard ? alive : true, null));
        boolean anchorOk = anchorReport != null && anchorReport.enabled;
        // pm grant 放第一条：D1(Android 11)/D2(Android 14) 实测都靠它写进 Secure；
        // Settings.System 自定义键在这两套固件上都会被拒，appops 那条只留给别的固件兜底。
        authBox.addView(authLine("卸载留痕", anchorOk,
                anchorOk ? null : Anchor.adbSecure(getPackageName())));
        if (!anchorOk) {
            authBox.addView(mono(Anchor.adbSystem(getPackageName())));
            TextView alt = Ui.label(this,
                    "首选 pm grant（双机实测生效）；个别固件若拒绝，再补 appops 那条。"
                            + "补完重进本页会自动变 ✓。记录只含时间戳与次数，不含口令。",
                    16, Ui.DIM);
            authBox.addView(alt);
        }
        if (!notif || !battery || (needGuard && !alive)) {
            TextView tip = Ui.label(this,
                    "卸载重装或系统回收后，通知使用权 / 电池白名单可能被清掉；按上面命令补齐，再重进应用即可。",
                    16, Ui.BAD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 12);
            authBox.addView(tip, lp);
        }
    }

    /** 首页一行红字 + 设置页完整说明；只要锚点里累计过卸载就常驻，孩子改不掉。 */
    private void refreshAnchorWarn() {
        Anchor.Report r = anchorReport;
        String line = r == null ? null : r.warning;
        if (homeAnchor != null) {
            homeAnchor.setText(line == null ? "" : line);
            homeAnchor.setVisibility(line == null ? View.GONE : View.VISIBLE);
        }
        if (anchorWarn != null) {
            String detail = (r == null || r.uninstalls <= 0) ? null : "⚠ 卸载留痕：" + r.detail();
            anchorWarn.setText(detail == null ? "" : detail);
            anchorWarn.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        }
    }

    private View authLine(String name, boolean ok, String adb) {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.START);
        TextView t = Ui.label(this, (ok ? "✓ " : "✗ ") + name + "：" + (ok ? "正常" : "缺失"),
                19, ok ? Ui.ACCENT : Ui.BAD);
        box.addView(t);
        if (adb != null) box.addView(mono(adb));
        return box;
    }

    private void refreshHomeAuth() {
        if (homeAuth == null) return;
        StringBuilder miss = new StringBuilder();
        if (!Settings.canDrawOverlays(this)) miss.append("悬浮窗 ");
        if (!notifOn()) miss.append("通知使用权 ");
        if (!batteryOk()) miss.append("电池白名单 ");
        if (miss.length() == 0) {
            homeAuth.setVisibility(View.GONE);
        } else {
            homeAuth.setText("授权缺失：" + miss.toString().trim() + " → 进「设置」查看修复命令");
            homeAuth.setVisibility(View.VISIBLE);
        }
    }

    private TextView addRow(String label, Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setBackgroundResource(R.drawable.opt_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16));

        TextView l = Ui.label(this, label, 24, Ui.TEXT);
        TextView v = Ui.label(this, "", 24, Ui.ACCENT);
        v.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 14);
        content.addView(row, rlp);

        row.setOnClickListener(x -> {
            settingsFocus = row;
            action.run();
        });
        return v;
    }

    // ---------- 参数面板（自绘，不依赖焦点树） ----------

    private void mountOption(OptionPanel p) {
        hideOptionPanel();
        optionPanel = p;
        root.addView(p, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    private void hideOptionPanel() {
        if (optionPanel != null && optionPanel.getParent() != null) root.removeView(optionPanel);
        optionPanel = null;
        blockFocusBelow();
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
    }

    /** 本次时长：1 / 5 / 10 / 15 / 30 分钟，或自定义（数字键盘）。 */
    private void openDurationPanel() {
        String[] labels = {"1", "5", "10", "15", "30"};
        int[] vals = {1, 5, 10, 15, 30};
        mountOption(new OptionPanel(this, "本次时长（分钟）", labels, vals, prefs.durationMin(),
                "分钟", 1, 240,
                v -> {
                    prefs.setDurationMin(v);
                    hideOptionPanel();
                    refresh();
                    toast("本次时长：" + v + " 分钟");
                }, this::hideOptionPanel));
    }

    /** 答对延长：1 / 2 / 3 / 5 / 10 分钟，或自定义。 */
    private void openExtendPanel() {
        String[] labels = {"1", "2", "3", "5", "10"};
        int[] vals = {1, 2, 3, 5, 10};
        mountOption(new OptionPanel(this, "答对延长（分钟）", labels, vals, prefs.extendMin(),
                "分钟", 1, 60,
                v -> {
                    prefs.setExtendMin(v);
                    hideOptionPanel();
                    refresh();
                    toast("答对延长：+ " + v + " 分钟");
                }, this::hideOptionPanel));
    }

    /** 年级两级：先选小学 / 初中，再选具体年级。 */
    private void openStagePanel() {
        String[] labels = {"小学", "初中"};
        int[] vals = {Prefs.STAGE_PRIMARY, Prefs.STAGE_JUNIOR};
        mountOption(new OptionPanel(this, "题目年级 · 选学段", labels, vals, prefs.stage(),
                "", 0, 0,
                this::openGradePanel, this::hideOptionPanel));
    }

    private void openGradePanel(int stage) {
        boolean junior = stage == Prefs.STAGE_JUNIOR;
        String[] labels = junior
                ? new String[]{"初一", "初二", "初三"}
                : new String[]{"1 年级", "2 年级", "3 年级", "4 年级", "5 年级", "6 年级"};
        int[] vals = junior ? new int[]{7, 8, 9} : new int[]{1, 2, 3, 4, 5, 6};
        int cur = prefs.grade();
        if (junior && cur < 7) cur = 7;
        if (!junior && cur > 6) cur = 3;
        mountOption(new OptionPanel(this, "题目年级 · " + (junior ? "初中" : "小学"),
                labels, vals, cur, "", 0, 0,
                g -> {
                    prefs.setStage(stage);
                    prefs.setGrade(g);
                    hideOptionPanel();
                    refresh();
                    toast("题目年级：" + gradeText());
                },
                this::openStagePanel));   // BACK 退回上一步选学段
    }

    private String gradeText() {
        int g = prefs.grade();
        if (prefs.stage() == Prefs.STAGE_JUNIOR) {
            String[] cn = {"初一", "初二", "初三"};
            return "初中 · " + cn[Math.max(0, Math.min(2, g - 7))];
        }
        return "小学 · " + Math.max(1, Math.min(6, g)) + " 年级";
    }

    // ---------- 会话 ----------

    private void startSession() {
        if (prefs.safeMode()) {
            toast("守护处于安全模式，先在「守护状态」里恢复");
            promptClearSafeMode();
            return;
        }
        if (!prefs.hasPin()) {
            toast("先设置 6 位家长口令");
            promptSetPin();
            return;
        }
        int p = prefs.phase();
        if (p != Prefs.IDLE) {
            toast("已有会话在进行，请先结束");
            return;
        }
        // 授权检查放在最后：前面几项是「能不能开始」，授权决定的是「开始了会不会停表」。
        // 缺授权时静默开始，家长会看到「退出应用后时间不走」——那正是 1.2.8 被报的故障。
        if (!authComplete()) {
            showAuthGate();
            return;
        }
        doStart();
    }

    /** 三项都齐才直接开始；缺任一项都先摆出来让家长选。 */
    private boolean authComplete() {
        return Settings.canDrawOverlays(this) && notifOn() && batteryOk();
    }

    private void doStart() {
        Intent i = new Intent(this, GateService.class).setAction(GateService.ACTION_START);
        startForegroundService(i);
        toast("已开始：" + prefs.durationMin() + " 分钟后出题");
        refresh();
        // 回到电视桌面；右下角由守护服务亮 5 秒「本次时长」提示，5 秒后自动收起
        finish();
    }

    // ---------- 开始前的授权检查 ----------

    /**
     * 三项必需授权。卸载留痕（WRITE_SECURE_SETTINGS）明确不列：它只影响「能不能看出被卸载过」，
     * 不影响计时出题，按用户要求「不需要就不要提示」。
     */
    private List<AuthView.Item> authItems() {
        List<AuthView.Item> out = new ArrayList<>();
        String pkg = getPackageName();

        boolean ov = Settings.canDrawOverlays(this);
        out.add(new AuthView.Item(AUTH_OVERLAY, "悬浮窗权限", ov, ov ? "" :
                "到点要盖住整屏出题，缺了它题根本出不来。\n"
                        + "设置 → 应用 → 答对再看 → 权限；或用电脑执行：\n"
                        + "adb shell appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"));

        boolean notif = notifOn();
        out.add(new AuthView.Item(AUTH_NOTIF, "通知使用权", notif, notif ? "" :
                "退出应用后靠它把被系统杀掉的守护拉回来，不开就会停表。\n"
                        + "用电脑执行：\n"
                        + "adb shell cmd notification allow_listener " + pkg + "/" + pkg + ".NotifProbe"));

        boolean battery = batteryOk();
        out.add(new AuthView.Item(AUTH_BATTERY, "电池优化白名单", battery, battery ? "" :
                "Android 14 的电视回桌面会强停没进白名单的应用，\n"
                        + "表现就是「时间不走了」。用电脑执行：\n"
                        + "adb shell dumpsys deviceidle whitelist +" + pkg));
        return out;
    }

    private void showAuthGate() {
        if (authPanel != null) {
            authPanel.refresh();
            return;
        }
        authPanel = new AuthView(this, new AuthView.Host() {
            @Override public List<AuthView.Item> items() { return authItems(); }
            @Override public void fix(String id) { fixAuth(id); }
            @Override public void startAnyway() {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    toast("还缺悬浮窗权限：没有它到点出不来题");
                    return;
                }
                // 留一行日志：以后家长反馈「时间不走」时，能立刻看出他是点了「仍然开始」
                android.util.Log.i("QuizGate", "start_anyway notif=" + notifOn()
                        + " battery=" + batteryOk() + " " + prefs.debugLine());
                hideAuthGate();
                doStart();
            }
            @Override public void cancel() { hideAuthGate(); }
        });
        root.addView(authPanel, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    private void hideAuthGate() {
        if (authPanel != null && authPanel.getParent() != null) root.removeView(authPanel);
        authPanel = null;
        blockFocusBelow();
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
    }

    /** 跳对应的系统设置页；全都打不开就退回面板上那条 adb 命令。 */
    private void fixAuth(String id) {
        String pkg = getPackageName();
        if (AUTH_OVERLAY.equals(id)) {
            requestOverlay();
            return;
        }
        if (AUTH_NOTIF.equals(id)) {
            if (jump(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
            if (jump(new Intent(Settings.ACTION_SETTINGS))) return;
            toast("这台电视没有通知使用权页面，请按面板上的 adb 命令开启");
            return;
        }
        if (AUTH_BATTERY.equals(id)) {
            if (jump(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + pkg)))) return;
            if (jump(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
            if (jump(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)))) return;
            toast("这台电视没有电池优化页面，请按面板上的 adb 命令开启");
        }
    }

    private boolean jump(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 结束会话必须过口令。
     * <p>否则「谁能按到遥控器谁就能停」—— 孩子只要打开应用点一下，前面设的时长就白设了。
     * 会话本来就没开始时不必校验（没有东西可结束），设口令的流程本身会拦一次。
     */
    private void exitSession() {
        if (prefs.phase() == Prefs.IDLE) {
            toast("当前没有进行中的会话");
            return;
        }
        if (!prefs.hasPin()) {
            toast("先设置 6 位家长口令");
            promptSetPin();
            return;
        }
        promptPinConfirm("结束本次会话", "输入 6 位家长口令才能结束计时", () -> {
            startService(new Intent(this, GateService.class).setAction(GateService.ACTION_EXIT));
            prefs.resetSession();
            refresh();
            toast("会话已结束");
        });
    }

    /**
     * 电视固件经常把「显示在其他应用上层」藏起来、甚至整个入口都没有。
     * 所以不能只发一个 Intent 就完事：按可靠性依次尝试 4 个入口，全失败就给一张可照抄的说明页。
     */
    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("已授权，可以开始计时");
            refresh();
            return;
        }
        Intent[] tries = new Intent[]{
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (Throwable ignore) {
                // 该入口在这台机器上不存在，继续试下一个
            }
        }
        showGrantGuide();
    }

    /** 应用内授权说明页：能照抄、能量、不依赖任何系统菜单。 */
    private void showGrantGuide() {
        Dialog d = new Dialog(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        box.addView(Ui.title(this, "怎么开启「显示在其他应用上层」", 28));

        box.addView(section("① 用 ADB（最可靠，不需要 root）"));
        box.addView(mono("adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"));
        box.addView(section("验证是否生效："));
        box.addView(mono("adb shell appops get " + getPackageName() + " SYSTEM_ALERT_WINDOW"));
        box.addView(section("看到 SYSTEM_ALERT_WINDOW: allow 即成功，回到本页会自动变「已授予」。"));

        box.addView(section("② 在电视设置里找下面任意一个词"));
        box.addView(section("显示在其他应用上层 · 悬浮窗 · 显示悬浮窗 · 在其他应用上层显示"));
        box.addView(section("常见位置：设置 → 应用 → 应用管理 → 答对再看 → 权限；或 电视管家 → 权限管理。"));

        box.addView(section("③ 两条都找不到"));
        box.addView(section("说明这台固件彻底隐藏了该权限，请把日志发我，我改成「Activity 兜底锁屏」模式。"));

        TextView retry = Ui.key(this, "已开启，重新检测", 22);
        retry.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 62));
        rlp.topMargin = Ui.dp(this, 26);
        box.addView(retry, rlp);
        retry.setOnClickListener(v -> {
            d.dismiss();
            refresh();
            if (Settings.canDrawOverlays(this)) toast("检测到已授权");
            else toast("仍未检测到授权");
        });

        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(sv);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            // 用屏幕比例算，不要写死 dp：1080p 电视的可用高度只有 540dp
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) (dm.widthPixels * 0.86f), (int) (dm.heightPixels * 0.82f));
        }
        d.show();
        retry.requestFocus();
    }

    private TextView section(String s) {
        TextView t = Ui.label(this, s, 19, Ui.TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView mono(String s) {
        TextView t = Ui.label(this, s, 19, Ui.ACCENT);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        // 电视上不需要选中复制；开成可选反而会自己抢焦点，挡住遥控器继续往下走
        t.setTextIsSelectable(false);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        t.setBackgroundColor(0xFF10151A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------- 运行日志子页 ----------

    /**
     * 授权自检后面原本直接铺着一段 diag 文本，混着版本号、日志命令和相位快照，
     * 家长读不懂、开发者又嫌少。单独开一页：状态一屏看全，抓日志的命令放最下面。
     */
    private void showLogPanel() {
        hideLogPanel();
        logPanel = new FrameLayout(this);
        logPanel.setBackgroundColor(Ui.BG);
        logPanel.setClickable(true);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 88), Ui.dp(this, 44), Ui.dp(this, 88), Ui.dp(this, 44));

        TextView back = Ui.key(this, "← 返回设置", 22);
        back.setBackgroundResource(R.drawable.opt_bg);
        back.setMinWidth(Ui.dp(this, 190));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 56));
        box.addView(back, blp);
        back.setOnClickListener(v -> hideLogPanel());

        TextView title = Ui.title(this, "运行日志", 30);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 16);
        box.addView(title, tlp);

        box.addView(Ui.label(this, "只读页面，不会改设置。反馈问题时拍照发来即可。", 16, Ui.DIM));

        logBody = Ui.label(this, logText(), 18, Ui.TEXT);
        logBody.setTypeface(android.graphics.Typeface.MONOSPACE);
        logBody.setLineSpacing(Ui.dp(this, 3), 1f);
        // 不要开 textIsSelectable：它会让这段文字自己进焦点树，遥控器按 DOWN 停在这里，
        // 下面的 adb 命令和返回按钮就再也够不着了（模拟器实测）。
        logBody.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16));
        logBody.setBackgroundColor(0xFF10151A);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(this, 14);
        box.addView(logBody, llp);

        TextView cap = Ui.label(this, "在电脑上抓完整日志：", 17, Ui.DIM);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 16);
        box.addView(cap, clp);
        box.addView(mono("adb logcat -s QuizGate"));
        box.addView(Ui.label(this,
                "Android 11 上过滤不到时：adb logcat -d | grep -i quizgate", 16, Ui.DIM));

        TextView backBottom = Ui.key(this, "← 返回设置", 22);
        backBottom.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        bblp.topMargin = Ui.dp(this, 16);
        box.addView(backBottom, bblp);
        backBottom.setOnClickListener(v -> hideLogPanel());

        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));
        logPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(logPanel, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
        back.post(back::requestFocus);
    }

    private void hideLogPanel() {
        if (logPanel != null && logPanel.getParent() != null) root.removeView(logPanel);
        logPanel = null;
        logBody = null;
        blockFocusBelow();
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
    }

    // ---------- 口令门禁 ----------

    private void gate() {
        if (prefs.hasPin()) showUnlockPin();
        else promptSetPin();
    }

    private void promptSetPin() {
        pinMode = PIN_SETUP;
        gateRequired = !prefs.hasPin();
        firstPin = null;
        showPinOverlay("设置 6 位家长口令", "请牢记，忘记只能用恢复码重置");
    }

    /** 结束会话这类不可逆操作的二次确认：口令通过才执行。 */
    private void promptPinConfirm(String title, String sub, Runnable onOk) {
        pinMode = PIN_CONFIRM;
        pinConfirm = onOk;
        gateRequired = false;
        showPinOverlay(title, sub);
    }

    private void showUnlockPin() {
        pinMode = PIN_UNLOCK;
        gateRequired = true;
        showPinOverlay("家长口令", "输入 6 位数字进入设置");
    }

    private void showPinOverlay(String title, String sub) {
        if (gate != null && gate.getParent() != null) root.removeView(gate);
        gate = new PinPadView(this, title, new PinPadView.Listener() {
            @Override public void onPinSubmit(String s) {
                if (pinMode == PIN_SETUP) {
                    if (firstPin == null) {
                        firstPin = s;
                        gate.fail("再输入一次确认");
                    } else if (firstPin.equals(s)) {
                        if (prefs.setPin(s)) {
                            pinMode = PIN_UNLOCK;
                            settingsUnlocked = true;
                            hidePinOverlay();
                            toast("口令已设置");
                            // 设完口令立刻出码：这是全流程里最容易「码丢了都不知道」的一刻
                            showRecoveryCode(prefs.newRecoveryCode());
                            refresh();
                        } else {
                            firstPin = null;
                            gate.fail("设置失败，请重试");
                        }
                    } else {
                        firstPin = null;
                        gate.fail("两次不一致，请重新设置");
                    }
                    return;
                }
                long r = prefs.verifyPin(s);
                if (r > 0) {
                    gate.fail("尝试次数过多，请 " + (r / 1000) + " 秒后再试");
                    return;
                }
                if (r < 0) {
                    gate.fail("口令不正确");
                    return;
                }
                boolean confirm = (pinMode == PIN_CONFIRM);
                Runnable act = pinConfirm;
                pinConfirm = null;
                hidePinOverlay();
                if (confirm) {
                    // 结束会话：口令通过才执行，别把「谁能按到遥控器谁就能停」留在首页
                    if (act != null) act.run();
                } else {
                    settingsUnlocked = true;
                }
            }
            @Override public void onPinCancel() {
                pinConfirm = null;       // 取消 = 不执行任何待确认动作
                if (!prefs.hasPin()) { toast("必须先设置家长口令"); return; }
                // 取消进设置的门禁 = 回首页；从设置页改口令的返回 = 留在设置页
                if (gateRequired) { hidePinOverlay(); showHome(); }
                else hidePinOverlay();
            }
        });
        gate.setBackgroundColor(Ui.BG);
        // 原来 sub 参数只传不用，面板上只有标题。结束会话这种「不可逆操作」必须写明后果。
        if (sub != null && !sub.isEmpty()) gate.hint(sub);
        // 重装后第一屏是全屏口令面板，会把首页/设置页的红字全盖住。告警必须直接挂在面板里，
        // 否则孩子「卸载 → 重装 → 自己设个新口令」这一段就完全看不出痕迹。
        if (anchorReport != null && anchorReport.uninstalls > 0) {
            gate.setNotice("⚠ 检测到曾被卸载 " + anchorReport.uninstalls + " 次 · 请家长确认");
        }
        if (pinMode != PIN_SETUP) gate.setFooter("忘记口令？用恢复码重置", this::startRecovery);
        root.addView(gate, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
        gate.focusFirst();
    }

    private void hidePinOverlay() {
        if (gate != null && gate.getParent() != null) root.removeView(gate);
        blockFocusBelow();
        refresh();
        // 解锁后的下一步几乎总是「开始计时」，别让家长再去摸焦点。
        // 但安全模式下该先看到的是横幅 —— 否则家长看到「开始计时」会以为守护在正常工作。
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
        maybeShowUpdate();
    }

    /**
     * 只让最上面那层能吃焦点，下面的全部封锁。
     * 实测（MiTV-MFTR0）：口令面板在焦点走到最底一行后再按 DOWN，焦点会「掉」到面板背后
     * 的设置页，接着按 OK 就会误触「开始计时」——面板看着盖住了，键却打在背后。
     */
    /**
     * 最上层弹层：按 addView 顺序倒着找。恢复码面板是后加在升级提示之上的，
     * 写死「upgrade 优先」会把焦点和按键都送到看不见的那一层（D2 实测）。
     */
    private View topOverlay() {
        if (root == null) return null;
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View c = root.getChildAt(i);
            if (c == upgrade || c == codeOverlay || c == recovery || c == gate
                    || c == logPanel || c == optionPanel || c == authPanel) return c;
        }
        return null;
    }

    private void blockFocusBelow() {
        View top = topOverlay();
        // 首页和设置页是两个并列的子 View，只让当前显示的那一个吃焦点
        View active = showingSettings ? contentScroll : homeRoot;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            boolean isTop = (c == top);
            boolean isActive = (top == null && c == active);
            int mode = (isTop || isActive) ? ViewGroup.FOCUS_AFTER_DESCENDANTS
                    : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
            c.setFocusable(isTop);
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(mode);
        }
        if (top == null && active != null) {
            // AFTER 而不是 BEFORE：熔断横幅在 ScrollView 里，
            // 用 BEFORE 的话焦点会先落在容器自己身上，家长按半天方向键才能看到提示
            if (active == contentScroll) active.setFocusable(true);
            if (active instanceof ViewGroup) {
                ((ViewGroup) active).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        }
    }

    // ---------- 刷新 ----------

    private void refresh() {
        if (valueDuration == null) return;
        valueDuration.setText(prefs.durationMin() + " 分钟");
        valueExtend.setText("+ " + prefs.extendMin() + " 分钟");
        valueGrade.setText(gradeText());
        valuePin.setText(prefs.hasPin() ? "已设置" : "未设置");
        // 只存哈希：应用里看不到已生成的码，想再看只能重新生成（旧码立即作废）
        valueRecovery.setText(!prefs.hasRecoveryCode() ? "未生成"
                : (prefs.recoverySaved() ? "已保存" : "待拍照"));
        valueVersion.setText(versionText());
        if (valueLog != null) {
            valueLog.setText("查看 ›");
            valueLog.setTextColor(Ui.ACCENT);
        }
        if (valueAnchor != null) {
            boolean bad = anchorReport == null || anchorReport.uninstalls > 0 || !anchorReport.enabled;
            valueAnchor.setText(anchorReport == null ? "未知"
                    : (anchorReport.uninstalls > 0 ? "⚠ " + anchorReport.uninstalls + " 次"
                    : (anchorReport.enabled ? "已启用 · 无记录" : "未启用")));
            valueAnchor.setTextColor(bad ? Ui.BAD : Ui.ACCENT);
        }
        refreshAnchorWarn();
        refreshAuth();
        refreshHomeAuth();
        refreshGuard();
        tickUi();
        maybeInitFocus();
    }

    /** 熔断状态的展示：横幅 + 设置页那一行。安全模式下要让家长知道「现在没在防」。 */
    private void refreshGuard() {
        boolean safe = prefs.safeMode();
        boolean needGuard = prefs.phase() != Prefs.IDLE;
        boolean alive = App.gateAlive;
        boolean authMissing = !Settings.canDrawOverlays(this) || !notifOn() || !batteryOk();
        String txt;
        int color;
        if (safe) { txt = "已停止（安全模式）"; color = Ui.BAD; }
        else if (needGuard && !alive) { txt = "守护已停止 · 正在恢复"; color = Ui.BAD; }
        else if (authMissing) { txt = "授权不完整 · 见设置自检"; color = Ui.BAD; }
        else if (needGuard) { txt = "守护中"; color = Ui.ACCENT; }
        else { txt = "待机 · 授权齐全"; color = Ui.ACCENT; }

        String bannerTxt = "⚠ 守护已停止（安全模式）：连续崩溃 " + Prefs.MAX_CRASH
                + " 次后不再挂遮罩。点这里恢复";
        if (banner != null) {
            banner.setVisibility(safe ? View.VISIBLE : View.GONE);
            if (safe) banner.setText(bannerTxt);
        }
        if (homeBanner != null) {
            homeBanner.setVisibility(safe ? View.VISIBLE : View.GONE);
            if (safe) homeBanner.setText(bannerTxt);
        }
        if (valueGuard != null) {
            valueGuard.setText(txt);
            valueGuard.setTextColor(color);
        }
        if (homeGuard != null) {
            homeGuard.setText("守护状态：" + txt);
            homeGuard.setTextColor(color);
        }
    }

    /** 当前页面该先给谁焦点。1.2.9 起设置页顶部没有返回按钮了，落在第一项「本次时长」上。 */
    private View focusTarget() {
        if (showingSettings) {
            if (prefs.safeMode()) return banner;
            return settingsFocus != null ? settingsFocus : firstRow;
        }
        return prefs.safeMode() ? homeBanner : homeBtnStart;
    }

    /** 首次把焦点放到该看的东西上（安全模式下是横幅，不是「开始计时」）。 */
    private void maybeInitFocus() {
        if (focusInited) return;
        if (gate != null && gate.getParent() != null) return;
        if (recovery != null || codeOverlay != null || upgrade != null || logPanel != null) return;
        if (optionPanel != null || authPanel != null) return;
        View t = focusTarget();
        if (t == null) return;
        focusInited = true;
        t.post(t::requestFocus);
    }

    /**
     * 解除熔断。设置页本身已在口令门禁之后，所以这里只做后果说明，不再多问一次口令。
     */
    private void promptClearSafeMode() {
        if (!prefs.safeMode()) {
            toast("守护运行正常");
            return;
        }
        Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        box.addView(Ui.title(this, "守护已停止（安全模式）", 26));
        TextView body = Ui.label(this,
                "应用连续启动崩溃 " + Math.max(Prefs.MAX_CRASH, prefs.crashCount()) + " 次后，会自动停止挂遮罩。"
                        + "这不是故障，是故意做的熔断 —— 保证最坏情况是「没守护」，而不是「电视反复闪屏开不了机」。\n\n"
                        + "最后一次崩溃：" + (prefs.crashInfo().isEmpty() ? "（无记录）" : prefs.crashInfo()) + "\n\n"
                        + "恢复后重新开始计时拦截。如果再次连续崩溃，会重新进入安全模式。\n"
                        + "要彻底避开这个崩溃，请把上面这行崩溃信息反馈出来。",
                18, Ui.TEXT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 16);
        box.addView(body, blp);

        LinearLayout btns = Ui.row(this);
        LinearLayout.LayoutParams btnlp = new LinearLayout.LayoutParams(-1, -2);
        btnlp.topMargin = Ui.dp(this, 24);
        box.addView(btns, btnlp);

        TextView heal = Ui.key(this, "恢复守护", 22);
        heal.setBackgroundResource(R.drawable.opt_bg);
        heal.setMinWidth(Ui.dp(this, 200));
        heal.setOnClickListener(v -> {
            prefs.clearSafeMode();
            d.dismiss();
            refresh();
            toast("守护已恢复，请重新开始计时");
        });
        btns.addView(heal);

        TextView keep = Ui.key(this, "继续停用", 22);
        keep.setBackgroundResource(R.drawable.opt_bg);
        keep.setMinWidth(Ui.dp(this, 200));
        keep.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.leftMargin = Ui.dp(this, 20);
        btns.addView(keep, klp);

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) (dm.widthPixels * 0.78f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        heal.requestFocus();
    }

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            tickUi();
            // 守护状态每秒重判：服务被系统强杀后立刻变红并自动 RESTORE，
            // 不能等家长点一下按钮才发现「正常」是假的
            refreshGuard();
            refreshHomeAuth();
            autoRestore();
            h.postDelayed(this, 1000);
        }
    };

    private void tickUi() {
        int p = prefs.phase();
        boolean alive = App.gateAlive;
        String phase, big;
        if (p == Prefs.IDLE) {
            phase = "未开始 · 待机";
            big = "--:--";
        } else if (!alive) {
            // 服务不在时 prefs.remainingMs() 是冻结的旧快照，绝不拿来冒充当前剩余时间
            phase = "守护恢复中…";
            big = "--:--";
        } else if (p == Prefs.LOCKED) {
            phase = "已锁定 · 等待答题";
            big = "0:00";
        } else {
            phase = "计时中";
            big = Core.fmt(prefs.remainingMs());
        }
        if (homePhase != null) homePhase.setText(phase);
        if (homeTime != null) homeTime.setText(big);
        styleHomeReadout(p, alive);
        boolean ov = Settings.canDrawOverlays(this);
        if (btnPerm != null) {
            btnPerm.setText(ov ? "显示权限：已授予（点击重新检测）" : "显示权限：未授予（点击查看开启方法）");
            btnPerm.setTextColor(ov ? Ui.OK : Ui.BAD);
        }
        if (logBody != null && logPanel != null && logPanel.getVisibility() == View.VISIBLE) {
            logBody.setText(logText());
        }
    }

    /**
     * 首页状态读数配色。待机时刻意一个黄色都不留：黄框 + 圆角在原界面上和按钮无从区分，
     * 家长真机上按过「待机」那一块。灰条 = 没在守着，黄条 = 计时中，红条 = 已锁定。
     */
    private void styleHomeReadout(int p, boolean alive) {
        if (homeBar == null || homeTime == null || homePhase == null) return;
        int bar, time, phase;
        if (p == Prefs.IDLE) {
            bar = 0xFF3A3A3A;                 // 灰：待机，不抢注意力
            time = 0xFF6B7075;
            phase = Ui.DIM;
        } else if (p == Prefs.LOCKED) {
            bar = Ui.BAD;
            time = Ui.TEXT;
            phase = Ui.BAD;
        } else if (!alive) {
            bar = Ui.BAD;
            time = 0xFF6B7075;
            phase = Ui.BAD;
        } else {
            bar = Ui.ACCENT;
            time = Ui.TEXT;
            phase = Ui.ACCENT;
        }
        homeBar.setBackgroundColor(bar);
        homeTime.setTextColor(time);
        homePhase.setTextColor(phase);
    }

    /** 运行日志子页的全部内容；每次打开和每秒刷新都重算，避免显示过期状态。 */
    private String logText() {
        StringBuilder sb = new StringBuilder();
        sb.append("悬浮窗权限：").append(Settings.canDrawOverlays(this) ? "已授予" : "未授予")
                .append('\n');
        sb.append("守护服务：").append(App.gateAlive ? "运行中" : "未运行").append('\n');
        sb.append("会话：").append(prefs.debugLine()).append('\n');
        sb.append("版本：").append(versionText()).append('\n');
        sb.append("卸载留痕：")
                .append(anchorReport == null ? "未检测"
                        : (anchorReport.enabled ? "已启用 · " + anchorReport.detail() : "未启用"))
                .append('\n');
        sb.append("崩溃计数：").append(prefs.crashCount()).append(" / ").append(Prefs.MAX_CRASH);
        if (!prefs.crashInfo().isEmpty()) sb.append("（最近：").append(prefs.crashInfo()).append("）");
        sb.append('\n');
        sb.append("服务计时缺口：").append(prefs.serviceGapMs() / 1000).append(" 秒");
        return sb.toString();
    }

    /** 遥控器数字键 / USB 小键盘直接输入（很多电视遥控器带数字键，别浪费）。 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        View top = topOverlay();
        if (top != null && top == upgrade) {
            // 返回 = 「以后再说」。不直接 finish()：家长可能只是想先看看设置页。
            if (upgrade.consumeKey(e)) return true;
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                pendingUpdate = null;
                hideUpgrade();
            }
            return true;   // BACK 抬键也吞，别让系统把 Activity finish 掉
        }
        if (top != null && top == recovery) {
            if (recovery.consumeKey(e)) return true;
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) return true;
            return super.dispatchKeyEvent(e);
        }
        if (top != null && top == codeOverlay) {
            // 恢复码面板自路由：D2(Android 14) 上焦点被 overlay 根容器抢走，
            // 按钮永远拿不到焦点，交给焦点树会让遥控器完全失灵。
            if (codeOverlay instanceof RecoveryFlow.CodePanel
                    && ((RecoveryFlow.CodePanel) codeOverlay).consumeKey(e)) return true;
            // BACK 只是收起（码已落库，设置页会标「待拍照」催家长再拍）
            if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    hideRecoveryCode();
                    refresh();
                }
                return true;   // DOWN/UP 都吞
            }
            return true;
        }
        if (top != null && top == logPanel) {
            // 日志页是只读子页：BACK 只收起它，别顺手把整个应用关掉。
            // 其余键继续走焦点树，返回按钮才能按得到。
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                hideLogPanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (top != null && top == optionPanel) {
            // 选值 / 数字键盘：整个面板自路由，按键一个都不许漏给背后的设置页
            if (optionPanel.consumeKey(e)) return true;
            return true;
        }
        if (top != null && top == authPanel) {
            if (authPanel.consumeKey(e)) return true;
            return true;
        }
        // 返回键：设置页 → 首页。口令门禁盖在上面时也要能退回首页，
        // 否则系统默认 BACK 会直接 finish 掉应用（首装还没设口令时禁止退出）。
        if (showingSettings
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (gate != null && gate.getParent() != null) {
                if (!prefs.hasPin()) { toast("必须先设置家长口令"); return true; }
                hidePinOverlay();
            }
            showHome();
            return true;
        }
        if (gate != null && gate.getParent() != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int d = PinPadView.digitOf(e);
            if (d >= 0) {
                gate.append((char) ('0' + d));
                return true;
            }
            if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_DEL) {
                gate.backspace();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- 恢复码 ----------

    private void onRecoveryRow() {
        showRecoveryCode(prefs.newRecoveryCode());
    }

    private void showRecoveryCode(String code) {
        hideRecoveryCode();
        codeOverlay = RecoveryFlow.codePanel(this, code,
                () -> {
                    prefs.markRecoverySaved();
                    hideRecoveryCode();
                    refresh();
                    toast("恢复码已确认保存");
                },
                () -> showRecoveryCode(prefs.newRecoveryCode()));
        root.addView(codeOverlay, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    private void hideRecoveryCode() {
        if (codeOverlay != null && codeOverlay.getParent() != null) root.removeView(codeOverlay);
        codeOverlay = null;
        blockFocusBelow();
    }

    /** 从口令门禁进入：忘口令的家长只能走到这里，所以这里必须能重置。 */
    private void startRecovery() {
        if (recovery != null) return;
        recovery = new RecoveryFlow(this, prefs, ok -> {
            if (recovery != null && recovery.getParent() != null) root.removeView(recovery);
            recovery = null;
            blockFocusBelow();
            if (ok) {
                // 恢复 = 家长接管：当前会话按「已被家长结束」处理，不保留剩余时间
                if (prefs.phase() != Prefs.IDLE) {
                    startService(new Intent(this, GateService.class).setAction(GateService.ACTION_EXIT));
                    prefs.resetSession();
                }
                settingsUnlocked = true;
                hidePinOverlay();
                toast("口令已重设，恢复码已换新，请重新拍照");
            }
            refresh();
        });
        root.addView(recovery, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    // ---------- 在线升级 ----------

    /**
     * 每次进入应用检查一次。三个原则：
     * <ul>
     *   <li>没配置升级源 → 直接返回，一个字节都不发</li>
     *   <li>检查失败（没网 / DNS 挂了 / 服务器 500）→ 当成「没有更新」，静默放弃</li>
     *   <li>只有真的拿到更高 versionCode 才弹</li>
     * </ul>
     * 第二条是刻意的：电视没网是常态，绝不能让升级检查变成每次开机的扰民弹窗。
     */
    private void checkUpdate() {
        if (!Updater.enabled()) return;
        Updater.check(r -> {
            if (!r.ok()) {
                android.util.Log.i("QuizGate", "update_check_fail: " + r.error);
                return;
            }
            int self = Updater.selfVersionCode(this);
            android.util.Log.i("QuizGate", "update_check self=" + self
                    + " remote=" + r.versionCode + " (" + r.versionName + ")");
            if (r.versionCode <= self) return;
            pendingUpdate = r;
            maybeShowUpdate();
        });
    }

    /** 门禁没过之前不能弹：家长正在输口令，盖上去就把键盘挡住了。 */
    private void maybeShowUpdate() {
        if (pendingUpdate == null || upgrade != null) return;
        if (gate != null && gate.getParent() != null) return;
        if (recovery != null || codeOverlay != null) return;
        upgrade = new UpgradeView(this, pendingUpdate, () -> {
            // 本次不再弹（避免反复打断）；下次冷启动 onCreate 会重新检查、重新弹
            pendingUpdate = null;
            hideUpgrade();
        });
        root.addView(upgrade, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    private void hideUpgrade() {
        if (upgrade != null && upgrade.getParent() != null) root.removeView(upgrade);
        upgrade = null;
        blockFocusBelow();
        refresh();
    }

    /** 首页一次性提示：上次守护被系统终止过（小米待机时会强停第三方应用）。 */
    private void maybeShowInterruptNotice() {
        long gap = prefs.interruptedGapMs();
        if (gap <= 0) return;
        if (gate != null && gate.getParent() != null) return;   // 口令键盘还盖着，等它关掉再说
        prefs.clearInterrupted();

        Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        box.addView(Ui.title(this, "上次守护被系统终止了", 26));
        TextView body = Ui.label(this,
                "检测到约 " + Math.max(1, gap / 60_000) + " 分钟的计时缺口。\n\n"
                        + "这台小米电视在按电源键待机时，会强制停止所有第三方应用"
                        + "（系统日志：killAllThirdApp）。任何应用都无法在这种强制停止后自行恢复，"
                        + "所以那段时间等于没有守护。\n\n"
                        + "本次已按原剩余时间继续。如果孩子会自己按电源键，请改成只用主页键退回桌面，"
                        + "或在电视设置里把「短按电源键」改成息屏而不是待机。",
                18, Ui.TEXT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 16);
        box.addView(body, blp);

        TextView ok = Ui.key(this, "知道了", 22);
        ok.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        olp.topMargin = Ui.dp(this, 24);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> d.dismiss());

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) (dm.widthPixels * 0.78f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        ok.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        Anchor.touch(this);   // 刷新「最后在线」，卸载时才能算出失联多久
        autoRestore();
        refresh();
        // 从系统授权页回来：授权面板还在就重新查一遍，缺的项会自动变 ✓
        if (authPanel != null) authPanel.refresh();
        h.removeCallbacks(uiTick);
        h.post(uiTick);
        maybeShowUpdate();
        h.postDelayed(this::maybeShowInterruptNotice, 600);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(uiTick);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
