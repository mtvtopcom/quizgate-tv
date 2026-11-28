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

    /** 开始计时前检查的授权项。电池白名单 1.3.0 起已移除（实测不需要）。 */
    private static final String AUTH_OVERLAY = "overlay";
    private static final String AUTH_NOTIF = "notif";

    private Prefs prefs;
    private FrameLayout root;
    private ScrollView contentScroll;
    private LinearLayout content;

    private TextView valueDuration, valueExtend, valueGrade, valuePin, valueRecovery, valueGuard;
    private TextView valueVersion, valueLog;
    /** 1.6.0 规则引擎设置行（只在「答题方式」页里按方式显示）。 */
    private TextView valueFixedNeed, valueFirstNeed, valueStepAdd,
            valuePer, valueMinBank, valueWindow, valueRest,
            valueReset, valuePause, valueCarryOver;
    private TextView anchorWarn;
    /** 设置页的第一项（本次时长）。顶部返回按钮在 1.2.9 删掉后，焦点默认落在这里。 */
    private View firstRow;
    /** 刚从哪一行打开过面板：关掉面板后把焦点还给它，别让家长重新一路按下去。 */
    private View settingsFocus;
    private TextView banner;
    /** 设置页标题：全局设置 / 答题方式配置两种页面共用同一个容器。 */
    private TextView settingsTitle;
    /** 是否正在显示「答题方式专属配置」页。 */
    private boolean showingModeConfig = false;
    /** 首页（只看运行状态）；设置页是原来的 contentScroll，两者在同一个 Activity 里切换。 */
    private View homeRoot;
    private TextView homePhase, homeTime, homeGuard, homeAuth, homeAnchor, homeBanner;
    private TextView homeBtnGate, homeBtnMode, homeBtnSet;
    /** 答题积累专属：进软件页面手动答题攒时间，与锁屏遮罩同一套答题界面。 */
    private TextView homeBtnEarn;
    /** 加时间按钮独占一行：隐藏时整行 GONE，不挤占三颗核心按钮的位置。 */
    private View homeEarnRow;
    private TextView homeModeLabel;
    /** 首页状态读数左侧的色条：待机灰、计时黄、锁定红。 */
    private View homeBar;
    /**
     * 「答题方式」页：竖直排的 5 条单选（4 种方式 + 答题关）。
     * 单选行自己记一份「●/○」和摘要，refresh 时按 prefs 重画，不做 RadioGroup
     * （电视遥控器上 RadioGroup 的焦点跨行行为不可控，D2 上还踩过假焦点）。
     */
    private LinearLayout modePickBox;
    private TextView modeIntro, modeSectionLabel, modeOffNote, modeBankNote;
    private final java.util.List<TextView> modeBullets = new ArrayList<>();
    private final java.util.List<TextView> modeNames = new ArrayList<>();
    private final java.util.List<TextView> modeSummaries = new ArrayList<>();
    private final int[] modeIds = {GateRules.MODE_DELAY, GateRules.MODE_STREAK,
            GateRules.MODE_UNLOCK, GateRules.MODE_BANK, GateRules.MODE_OFF};
    /** 设置页里的「运行日志」子页；null = 没打开。 */
    private FrameLayout logPanel;
    private TextView logBody;
    /** 设置页里的「守护状态」子页（含授权自检）；null = 没打开。 */
    private FrameLayout guardPanel;
    private LinearLayout guardBody;
    /** 选时长 / 选年级的自绘面板。null = 没打开。 */
    private OptionPanel optionPanel;
    /** 题目科目多选面板。null = 没打开。 */
    private SubjectPanel subjectPanel;
    /** 题库管理页。null = 没打开。 */
    private BankManagerView bankManager;
    private BankStore bankStore;
    private TextView valueBank;
    /** 开始计时前的授权检查面板。null = 没打开。 */
    private AuthView authPanel;
    private boolean showingSettings = false;
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
        bankStore = new BankStore(this);
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

        // 1.2.9：原来最上面那块（← 返回首页 / 「答题续时」/ 一句说明 / 「未开始 · 待机」状态行）
        // 整块删掉，抬头直接是「设置」。返回入口仍在页底，「BACK」也照旧可回首页。
        settingsTitle = Ui.title(this, "设置", 34);
        content.addView(settingsTitle);

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

        // 答题方式页的选择区（只在「答题方式」页显示）。放在参数行之前：
        // 家长点进来先看到「有哪几种方式、现在用哪种」，再往下才是这一种的参数。
        buildModePicker();

        valueDuration = addRow("本次时长", this::openDurationPanel);
        valueExtend = addRow("答对延长", this::openExtendPanel);
        valueFixedNeed = addRow("连对题数", this::openFixedNeedPanel);
        valueFirstNeed = addRow("首次解锁题数", this::openFirstNeedPanel);
        valueStepAdd = addRow("之后每次题数", this::openStepAddPanel);
        valuePer = addRow("每题攒分钟", this::openPerAnswerPanel);
        valueMinBank = addRow("起看门槛", this::openMinBankPanel);
        valueWindow = addRow("看电视时间", this::openWindowPanel);
        // 「连续观看上限」和「强制休息」原本是同一个键（watchLimitMin）的两个入口，
        // 改一个另一个跟着变，家长以为是两条设置打架。1.6.2 只留这一行。
        valueRest = addRow("看多久歇多久", this::openRestPanel);
        valueReset = addRow("每日重置", this::openResetPanel);
        valuePause = addRow("答题暂停播放", this::togglePauseOnQuiz);
        valueCarryOver = addRow("余额跨天", this::toggleBalanceCarryOver);
        valueGrade = addRow("题目年级", this::openStagePanel);
        valueBank = addRow("题库管理", this::showBankManager);
        valuePin = addRow("家长口令", this::promptSetPin);
        valueRecovery = addRow("恢复码", this::onRecoveryRow);
        // 1.3.0：授权自检、显示权限按钮、卸载留痕全部收进「守护状态」子页，
        // 设置页只留这一个入口。家长按进去能看到每一项的状态与修复命令。
        valueGuard = addRow("守护状态", this::showGuardPanel);
        valueVersion = addRow("当前版本", () -> toast(versionText()));

        // 顶部返回按钮删掉后，进设置页的默认焦点落在「看电视时间」上
        firstRow = (View) valueWindow.getParent();

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

    // ---------- 答题方式页：5 条单选 + 按需参数 ----------

    /**
     * 答题方式页的选择区。一页式：5 条单选在上，参数在下面只出这一种方式要用的行，
     * 选完立刻生效，不需要「保存」，也不再跳第二个页面。
     */
    private void buildModePicker() {
        modeIntro = Ui.label(this, "选一种答题方式，选中即生效；下面的设置只显示这种方式要用的参数。",
                18, Ui.DIM);
        modeIntro.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = Ui.dp(this, 12);
        content.addView(modeIntro, ilp);

        modePickBox = Ui.col(this);
        modePickBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 10);
        content.addView(modePickBox, blp);

        for (int i = 0; i < modeIds.length; i++) {
            final int mode = modeIds[i];
            LinearLayout row = Ui.row(this);
            row.setBackgroundResource(R.drawable.opt_bg);
            row.setFocusable(true);
            row.setClickable(true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 24), Ui.dp(this, 13), Ui.dp(this, 26), Ui.dp(this, 13));

            // 自绘单选符号：RadioGroup 在电视遥控器上跨行换焦点不可控，D2 还踩过假焦点
            TextView bullet = Ui.label(this, "○", 24, Ui.DIM);
            row.addView(bullet, new LinearLayout.LayoutParams(Ui.dp(this, 32), -2));

            TextView name = Ui.label(this, GateRules.modeName(mode), 24, Ui.TEXT);
            row.addView(name, new LinearLayout.LayoutParams(-2, -2));

            TextView sum = Ui.label(this, "", 18, Ui.DIM);
            sum.setGravity(Gravity.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
            slp.leftMargin = Ui.dp(this, 18);
            row.addView(sum, slp);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(this, 10);
            modePickBox.addView(row, rlp);

            row.setOnClickListener(v -> {
                settingsFocus = v;
                onModePicked(mode);
            });

            modeBullets.add(bullet);
            modeNames.add(name);
            modeSummaries.add(sum);
        }

        // 答题积累专属说明：余额跨会话保留，家长需要一处能看到「现在攒了多少、
        // 门槛是多少、答一题加多少」。只在选中该方式时显示，文本由 refresh() 按当前值填。
        modeBankNote = Ui.label(this, "", 19, Ui.TEXT);
        modeBankNote.setBackgroundResource(R.drawable.opt_bg);
        modeBankNote.setPadding(Ui.dp(this, 26), Ui.dp(this, 18), Ui.dp(this, 26), Ui.dp(this, 18));
        modeBankNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(this, 14);
        content.addView(modeBankNote, nlp);

        modeSectionLabel = Ui.label(this, "", 18, Ui.DIM);
        modeSectionLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 22);
        content.addView(modeSectionLabel, mlp);

        modeOffNote = Ui.label(this,
                "答题关：不再出题，也不扣观看时长；看电视时间与休息规则照旧生效。\n"
                        + "首页大字显示「距休息」的倒计时；设了「不休息」就改成「本次已看」。",
                19, Ui.TEXT);
        modeOffNote.setBackgroundResource(R.drawable.opt_bg);
        modeOffNote.setPadding(Ui.dp(this, 26), Ui.dp(this, 18), Ui.dp(this, 26), Ui.dp(this, 18));
        modeOffNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = Ui.dp(this, 14);
        content.addView(modeOffNote, olp);
    }

    /** 点某一条方式：已经是这个状态就不重复写盘，避免无谓的 commit + 重排。 */
    private void onModePicked(int mode) {
        boolean active = mode == GateRules.MODE_OFF
                ? !prefs.quizOn()
                : (prefs.quizOn() && prefs.mode() == mode);
        if (active) {
            toast("已经是「" + GateRules.modeName(mode) + "」");
            return;
        }
        applyModeChoice(mode);
    }

    /**
     * 切换答题方式。本地先写盘再通知服务：startService 是异步的，
     * 先写才能让本页立刻按新状态重排；服务那边只负责「按新方式重算本局时长」。
     */
    private void applyModeChoice(int mode) {
        boolean running = prefs.gateOn();
        if (mode == GateRules.MODE_OFF) {
            prefs.setQuizOn(false);
            if (running) startService(new Intent(this, GateService.class)
                    .setAction(GateService.ACTION_QUIZ_OFF));
        } else {
            prefs.setMode(mode);
            prefs.setQuizOn(true);
            if (running) startService(new Intent(this, GateService.class)
                    .setAction(GateService.ACTION_APPLY_MODE));
        }
        if (!running) {
            // 没在守护时切方式 = 下一次「启动软件」是新会话：把上次的剩余快照作废，
            // 否则「答题积累」剩下的 5 分钟会被「答题延时」当成自己的本次时长。
            // （答题积累的余额存在 ruleState 里，不受这行影响，下次启动照样能用。）
            prefs.saveSnapshot(Prefs.IDLE, Math.max(1, prefs.durationMin()) * 60_000L);
        }
        applyRows(true);
        refresh();
        toast("答题方式：" + GateRules.modeName(mode));
    }

    /** 单选行的 ●/○ 与右侧摘要，全部按当前 prefs 重画。 */
    private void refreshModePicker() {
        if (modePickBox == null) return;
        boolean on = prefs.quizOn();
        int cur = prefs.mode();
        for (int i = 0; i < modeIds.length; i++) {
            int m = modeIds[i];
            boolean active = on ? (m == cur && m != GateRules.MODE_OFF) : (m == GateRules.MODE_OFF);
            modeBullets.get(i).setText(active ? "●" : "○");
            modeBullets.get(i).setTextColor(active ? Ui.ACCENT : Ui.DIM);
            modeNames.get(i).setTextColor(active ? Ui.ACCENT : Ui.TEXT);
            modeSummaries.get(i).setText(modeSummary(m));
        }
    }

    /** 每条方式的当前参数摘要：家长不点进去也能看出现在是什么规则。 */
    private String modeSummary(int mode) {
        switch (mode) {
            case GateRules.MODE_STREAK:
                return "连对 " + prefs.fixedNeed() + " 题解锁";
            case GateRules.MODE_UNLOCK:
                return "首次 " + prefs.firstNeed() + " 题 · 之后每次 +" + prefs.stepAdd() + " 题";
            case GateRules.MODE_BANK:
                // 余额是这个模式的核心状态，摘要里直接写「已攒多少」，家长不用点进参数才知道。
                // 用 mm:ss 而不是整分钟：0:24 显示成「0 分钟」会让人以为余额是 0。
                return "已攒 " + Core.fmt(prefs.balanceMs()) + " · 满 "
                        + prefs.minBankMin() + " 分钟可看";
            case GateRules.MODE_OFF:
                return "不答题 · 只保留看电视时间与休息规则";
            default:
                return "锁屏答 1 题 · 答对 +" + prefs.extendMin() + " 分钟";
        }
    }

    /** 首页/状态卡里的方式文案：答题关时显示「答题关」，不显示上次的方式。 */
    private String modeText() {
        return GateRules.modeName(prefs.quizOn() ? prefs.mode() : GateRules.MODE_OFF);
    }

    /**
     * 首页的方式行。答题积累停着的时候把余额也写出来：这个模式的余额跨会话保留，
     * 家长在启动前就能看到孩子攒了多少，不用先进设置。运行中大字本身就是余额，不重复写。
     */
    private String homeModeLine() {
        String s = "答题方式：" + modeText();
        if (prefs.quizOn() && prefs.mode() == GateRules.MODE_BANK && !prefs.gateOn()) {
            s += " · 已攒 " + Core.fmt(prefs.balanceMs());
        }
        return s;
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
        titles.addView(Ui.title(this, "答题续时", 30));
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

        // 首页状态卡只显示当前模式；可点入口在底部「模式」按钮（D2 上顶部文字进不了焦点树）。
        homeModeLabel = Ui.label(this, "答题模式：答题延时 ›", 17, Ui.ACCENT);
        homeModeLabel.setGravity(Gravity.START);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.dp(this, 6);
        info.addView(homeModeLabel, mlp);

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

        // 按钮分两层：
        //   上排：答题加时间（只有答题积累模式才有，没有就整行不显示）
        //   下排：三颗等宽核心按钮 —— 启动/停止软件 · 答题方式 · 设置
        // 「答题开/停」不单独占按钮：开/停语义收在「答题方式」里的「答题关」。
        LinearLayout earnRow = Ui.row(this);
        earnRow.setGravity(Gravity.CENTER);
        homeBtnEarn = Ui.homeKey(this, "答题加时间", false);
        homeBtnEarn.setMinWidth(Ui.dp(this, 260));
        homeBtnEarn.setMinHeight(Ui.dp(this, 58));
        homeBtnEarn.setOnClickListener(v -> openEarnQuiz());
        earnRow.addView(homeBtnEarn, new LinearLayout.LayoutParams(
                Ui.dp(this, 260), Ui.dp(this, 58)));
        earnRow.setVisibility(View.GONE);
        homeEarnRow = earnRow;
        LinearLayout.LayoutParams erlp = new LinearLayout.LayoutParams(-1, -2);
        erlp.topMargin = Ui.dp(this, 12);
        home.addView(earnRow, erlp);

        LinearLayout coreRow = Ui.row(this);
        coreRow.setGravity(Gravity.CENTER);
        int coreW = Ui.dp(this, 250);
        int coreH = Ui.dp(this, 68);

        homeBtnGate = Ui.homeKey(this, "启动软件", true);
        homeBtnGate.setOnClickListener(v -> toggleGate());
        coreRow.addView(homeBtnGate, new LinearLayout.LayoutParams(coreW, coreH));

        homeBtnMode = Ui.homeKey(this, "答题方式", false);
        homeBtnMode.setOnClickListener(v -> showModeConfig());
        LinearLayout.LayoutParams modeBtnLp = new LinearLayout.LayoutParams(coreW, coreH);
        modeBtnLp.leftMargin = Ui.dp(this, 14);
        coreRow.addView(homeBtnMode, modeBtnLp);

        homeBtnSet = Ui.homeKey(this, "设置", false);
        homeBtnSet.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(coreW, coreH);
        slp.leftMargin = Ui.dp(this, 14);
        coreRow.addView(homeBtnSet, slp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 16);
        home.addView(coreRow, rlp);

        TextView hint = Ui.label(this, "方向键选择 · OK 确认 · 在本页面不计时，回电视继续",
                16, Ui.DIM);
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

    /** 设置页：软件已启动时受口令保护，未启动免密（见 gate()）；本次留在软件里验过就不再问。 */
    private void showSettings() {
        showingSettings = true;
        showingModeConfig = false;
        settingsFocus = null;      // 每次进设置页都从第一项开始
        homeRoot.setVisibility(View.GONE);
        contentScroll.setVisibility(View.VISIBLE);
        contentScroll.scrollTo(0, 0);
        applyRows(false);
        focusInited = false;
        refresh();
        boolean opened = gate();
        blockFocusBelow();
        if (opened) maybeInitFocus();
    }

    /**
     * 全局设置与模式配置共用同一个设置页容器：按页面类型切换行的可见性。
     * 行本身不删——Prefs key 不变，切模式时旧值还留着，切回来原样显示。
     */
    private void applyRows(boolean modeConfig) {
        int mode = prefs.mode();
        // 答题关 = 不出题（Prefs.quizOn == false），此时一条参数都不显示，只留一段说明。
        boolean off = modeConfig && !prefs.quizOn();
        boolean params = modeConfig && !off;
        boolean global = !modeConfig;
        row(valueDuration, params && GateRules.usesDuration(mode));
        row(valueExtend, params && GateRules.usesExtend(mode));
        row(valueFixedNeed, params && GateRules.usesFixedNeed(mode));
        row(valueFirstNeed, params && GateRules.usesLadder(mode));
        row(valueStepAdd, params && GateRules.usesLadder(mode));
        row(valuePer, params && GateRules.usesBankParams(mode));
        row(valueMinBank, params && GateRules.usesBankParams(mode));
        row(valueCarryOver, params && GateRules.usesBankParams(mode));
        // 题目年级与「答题暂停播放」是全局项（年级是孩子属性，暂停播放对所有方式都适用），
        // 所以留在设置页，不随模式显隐。
        row(valueGrade, global);
        row(valuePause, global);
        row(valueWindow, global);
        row(valueRest, global);
        row(valueReset, global);
        row(valueBank, global);
        row(valuePin, global);
        row(valueRecovery, global);
        row(valueGuard, global);
        row(valueVersion, global);
        row(valueLog, global);
        if (modeIntro != null) modeIntro.setVisibility(modeConfig ? View.VISIBLE : View.GONE);
        if (modePickBox != null) modePickBox.setVisibility(modeConfig ? View.VISIBLE : View.GONE);
        if (modeSectionLabel != null) {
            modeSectionLabel.setVisibility(params ? View.VISIBLE : View.GONE);
            modeSectionLabel.setText(GateRules.modeName(mode) + " · 设置");
        }
        if (modeBankNote != null) {
            modeBankNote.setVisibility(params && mode == GateRules.MODE_BANK
                    ? View.VISIBLE : View.GONE);
        }
        if (modeOffNote != null) modeOffNote.setVisibility(off ? View.VISIBLE : View.GONE);
        settingsTitle.setText(modeConfig ? "答题方式" : "设置");
        firstRow = modeConfig
                ? (View) (modePickBox != null ? modePickBox.getChildAt(0) : null)
                : (View) valueWindow.getParent();
    }

    private static void row(TextView value, boolean show) {
        View v = value == null ? null : (View) value.getParent();
        if (v != null) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 从首页「答题方式」进入。一页式：5 条单选在上，下面只显示选中那一种方式的参数，
     * 答题关时整块参数换成一段说明；选中即生效，不用回设置页再找。
     */
    private void showModeConfig() {
        showingSettings = true;
        showingModeConfig = true;
        settingsFocus = null;
        homeRoot.setVisibility(View.GONE);
        contentScroll.setVisibility(View.VISIBLE);
        contentScroll.scrollTo(0, 0);
        applyRows(true);
        focusInited = false;
        refresh();
        boolean opened = gate();
        blockFocusBelow();
        if (opened) maybeInitFocus();
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
        // 总开关关着就不能自动复活：点「停止软件」再打开应用不该又跑起来
        if (!prefs.gateOn()) return;
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

    // ---------- 守护状态子页（授权自检都收在这里） ----------

    /**
     * 设置页的「守护状态」入口。1.3.0 起，授权自检、显示权限按钮、卸载痕迹全部搬进这一页：
     * 设置页只留一个入口，家长按进去能一眼看到「到底哪一项缺、缺了怎么补」。
     */
    private void showGuardPanel() {
        hideGuardPanel();
        guardPanel = new FrameLayout(this);
        guardPanel.setBackgroundColor(Ui.BG);
        guardPanel.setClickable(true);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 88), Ui.dp(this, 44), Ui.dp(this, 88), Ui.dp(this, 44));

        TextView back = Ui.key(this, "← 返回设置", 22);
        back.setBackgroundResource(R.drawable.opt_bg);
        back.setMinWidth(Ui.dp(this, 190));
        box.addView(back, new LinearLayout.LayoutParams(-2, Ui.dp(this, 56)));
        back.setOnClickListener(v -> hideGuardPanel());

        TextView title = Ui.title(this, "守护状态", 30);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 16);
        box.addView(title, tlp);

        guardBody = new LinearLayout(this);
        guardBody.setOrientation(LinearLayout.VERTICAL);
        box.addView(guardBody, new LinearLayout.LayoutParams(-1, -2));

        TextView backBottom = Ui.key(this, "← 返回设置", 22);
        backBottom.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        bblp.topMargin = Ui.dp(this, 18);
        box.addView(backBottom, bblp);
        backBottom.setOnClickListener(v -> hideGuardPanel());

        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));
        guardPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(guardPanel, new FrameLayout.LayoutParams(-1, -1));

        blockFocusBelow();
        refreshGuardPanel();
        back.post(back::requestFocus);
    }

    private void hideGuardPanel() {
        if (guardPanel != null && guardPanel.getParent() != null) root.removeView(guardPanel);
        guardPanel = null;
        guardBody = null;
        blockFocusBelow();
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
    }

    // ---------- 题库管理 ----------

    /** 设置页显示当前题库名；题库被删或目录损坏时静默切回默认，别让抽题一直空转。 */
    private String bankName() {
        String id = prefs.activeBankId();
        if (BankStore.BUILTIN_ID.equals(id)) return "默认题库";
        BankStore.Meta m = bankStore == null ? null : bankStore.meta(id);
        if (m == null) {
            prefs.setActiveBankId(BankStore.BUILTIN_ID);
            return "默认题库";
        }
        return m.name;
    }

    private void showBankManager() {
        if (bankStore == null) bankStore = new BankStore(this);
        if (bankManager != null && bankManager.getParent() != null) root.removeView(bankManager);
        bankManager = null;
        bankManager = new BankManagerView(this, new BankManagerView.Host() {
            @Override public void closeBankManager() { hideBankManager(); }
            @Override public void toast(String msg) { MainActivity.this.toast(msg); }
            @Override public void onBankSwitched(String name) {
                // 换了题库，题量分布跟着变：不重新选年级就可能在新题库里抽不到题。
                hideBankManager();
                promptGradeAfterBankSwitch(name);
            }
        }, bankStore, prefs);
        root.addView(bankManager, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    /** 题库切换成功后的提醒：明确告诉家长「去重新设置题目年级」，可直接跳过去。 */
    private void promptGradeAfterBankSwitch(String bankName) {
        mountOption(new OptionPanel(this, "题库已切换",
                "已切换到「" + bankName + "」。不同题库的题目分布不一样，"
                        + "请重新设置一次题目年级，否则可能出不来题。",
                new String[]{"去设置题目年级", "稍后再说"},
                null, new int[]{1, 0}, -1, "", 0, 0,
                v -> {
                    hideOptionPanel();
                    if (v == 1) openStagePanel();
                },
                this::hideOptionPanel));
    }

    private void hideBankManager() {
        if (bankManager != null && bankManager.getParent() != null) root.removeView(bankManager);
        bankManager = null;
        blockFocusBelow();
        View t = focusTarget();
        if (t != null) t.post(t::requestFocus);
        refresh();
    }

    /** 重建守护状态页的内容（点「重新检测」或从系统页回来时调用）。 */
    private void refreshGuardPanel() {
        if (guardBody == null) return;
        guardBody.removeAllViews();

        boolean ov = Settings.canDrawOverlays(this);
        boolean notif = notifOn();
        boolean needGuard = prefs.phase() != Prefs.IDLE;
        boolean alive = App.gateAlive;
        boolean safe = prefs.safeMode();

        String status;
        int color;
        if (safe) { status = "已停止（安全模式）"; color = Ui.BAD; }
        else if (needGuard && !alive) { status = "守护已停止 · 正在恢复"; color = Ui.BAD; }
        else if (!ov || !notif) { status = "授权不完整"; color = Ui.BAD; }
        else if (needGuard) { status = "守护中"; color = Ui.ACCENT; }
        else { status = "待机 · 授权齐全"; color = Ui.ACCENT; }

        TextView st = Ui.title(this, status, 28);
        st.setTextColor(color);
        guardBody.addView(st);

        TextView sub = Ui.label(this,
                safe ? "连续崩溃后已停止挂遮罩，下面可以解除。"
                     : (needGuard ? "本次会话正在守着，退出应用后由通知监听负责复活。"
                                  : "还没开始计时。下面任一项缺了，到点都可能出不来题或停表。"),
                17, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(this, 6);
        guardBody.addView(sub, slp);

        TextView sec = Ui.label(this, "授权自检", 22, Ui.TEXT);
        sec.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams seclp = new LinearLayout.LayoutParams(-1, -2);
        seclp.topMargin = Ui.dp(this, 20);
        guardBody.addView(sec, seclp);

        guardBody.addView(authLine("悬浮窗权限", ov, ov ? null
                : "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"));
        guardBody.addView(authLine("通知使用权", notif, notif ? null
                : "adb shell cmd notification allow_listener " + getPackageName() + "/" + getPackageName() + ".NotifProbe"));
        guardBody.addView(authLine("守护服务", needGuard ? alive : true, null));

        // 卸载留痕：只有真的卸过才摆出来。没痕迹 = 什么都不显示，
        // 「未启用」这种需要 adb 才能处理的提示不再打扰家长（可选功能，装机时配）。
        Anchor.Report ar = anchorReport;
        if (ar != null && ar.uninstalls > 0) {
            TextView warn = Ui.label(this, "⚠ 检测到曾被卸载 " + ar.uninstalls + " 次\n" + ar.detail(),
                    17, Ui.BAD);
            warn.setLineSpacing(Ui.dp(this, 3), 1f);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
            wlp.topMargin = Ui.dp(this, 16);
            guardBody.addView(warn, wlp);
        }

        // 显示权限按钮也搬进来了：缺悬浮窗时它是最常用的入口
        TextView perm = Ui.key(this,
                ov ? "显示权限：已授予（点击重新检测）" : "显示权限：未授予（点此开启）", 19);
        perm.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        plp.topMargin = Ui.dp(this, 20);
        guardBody.addView(perm, plp);
        perm.setOnClickListener(v -> requestOverlay());

        TextView recheck = Ui.key(this, "重新检测", 19);
        recheck.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        rlp.topMargin = Ui.dp(this, 10);
        guardBody.addView(recheck, rlp);
        recheck.setOnClickListener(v -> {
            anchorReport = Anchor.sync(this, prefs);
            refresh();
            refreshGuardPanel();
            toast("已重新检测");
        });

        if (safe) {
            TextView fix = Ui.key(this, "解除安全模式（需要家长口令）", 19);
            fix.setBackgroundResource(R.drawable.opt_bg);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
            flp.topMargin = Ui.dp(this, 10);
            guardBody.addView(fix, flp);
            fix.setOnClickListener(v -> promptClearSafeMode());
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
        if (miss.length() == 0) {
            homeAuth.setVisibility(View.GONE);
        } else {
            homeAuth.setText("授权缺失：" + miss.toString().trim() + " → 进「设置 → 守护状态」");
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
        if (subjectPanel != null && subjectPanel.getParent() != null) root.removeView(subjectPanel);
        subjectPanel = null;
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

    private void mountSubject(SubjectPanel p) {
        hideOptionPanel();
        if (subjectPanel != null && subjectPanel.getParent() != null) root.removeView(subjectPanel);
        subjectPanel = p;
        root.addView(p, new FrameLayout.LayoutParams(-1, -1));
        blockFocusBelow();
    }

    private void hideSubjectPanel() {
        if (subjectPanel != null && subjectPanel.getParent() != null) root.removeView(subjectPanel);
        subjectPanel = null;
        blockFocusBelow();
    }

    /** 当前题库的题量统计；bankStore 没建过就建一次。 */
    private BankStats stats() {
        if (bankStore == null) bankStore = new BankStore(this);
        return bankStore.stats(prefs.activeBankId());
    }

    private static String countLabel(int n) {
        return n > 0 ? String.format(java.util.Locale.CHINA, "%,d 题", n) : "暂无数据";
    }

    private static String fmtNum(int n) {
        return String.format(java.util.Locale.CHINA, "%,d", n);
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

    // ---------- 1.6.0 规则引擎设置 ----------

    private void openFixedNeedPanel() {
        String[] labels = {"1", "2", "3", "5", "10"};
        int[] vals = {1, 2, 3, 5, 10};
        mountOption(new OptionPanel(this, "连对题数（固定）", labels, vals, prefs.fixedNeed(),
                "题", 1, 10,
                v -> { prefs.setFixedNeed(v); hideOptionPanel(); refresh(); toast("连对题数：" + v + " 题"); },
                this::hideOptionPanel));
    }

    private void openFirstNeedPanel() {
        String[] labels = {"3", "5", "8", "10"};
        int[] vals = {3, 5, 8, 10};
        mountOption(new OptionPanel(this, "首次解锁题数", labels, vals, prefs.firstNeed(),
                "题", 1, 20,
                v -> { prefs.setFirstNeed(v); hideOptionPanel(); refresh(); toast("首次解锁：" + v + " 题"); },
                this::hideOptionPanel));
    }

    private void openStepAddPanel() {
        String[] labels = {"1", "2", "3", "5"};
        int[] vals = {1, 2, 3, 5};
        mountOption(new OptionPanel(this, "之后每次题数", labels, vals, prefs.stepAdd(),
                "题", 1, 10,
                v -> { prefs.setStepAdd(v); hideOptionPanel(); refresh(); toast("之后每次：" + v + " 题"); },
                this::hideOptionPanel));
    }

    private void openPerAnswerPanel() {
        String[] labels = {"1", "2", "3", "5", "10"};
        int[] vals = {1, 2, 3, 5, 10};
        mountOption(new OptionPanel(this, "每答对一题攒多少分钟", labels, vals, prefs.perAnswerMin(),
                "分钟", 1, 60,
                v -> { prefs.setPerAnswerMin(v); hideOptionPanel(); refresh(); toast("每题攒：" + v + " 分钟"); },
                this::hideOptionPanel));
    }

    private void openMinBankPanel() {
        String[] labels = {"5", "10", "15", "20", "30"};
        int[] vals = {5, 10, 15, 20, 30};
        mountOption(new OptionPanel(this, "起看门槛（分钟）",
                "每次启动软件时，余额满这么多分钟才能直接看；不够会先出题，攒够才放行。"
                        + "当前已攒 " + Core.fmt(prefs.balanceMs()) + "。",
                labels, vals, prefs.minBankMin(),
                "分钟", 1, 240,
                v -> {
                    prefs.setMinBankMin(v);
                    hideOptionPanel();
                    // 运行中改门槛立刻按新值重判：调低后余额够就不用再答一题才解屏
                    if (prefs.gateOn()) startService(new Intent(this, GateService.class)
                            .setAction(GateService.ACTION_APPLY_MODE));
                    refresh();
                    toast("起看门槛：满 " + v + " 分钟才能开始看");
                }, this::hideOptionPanel));
    }

    private static String clockText(int min) {
        return String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    /**
     * 距看电视时间开始的倒计时。&lt; 1 小时沿用 m:ss；含小时补上小时位，
     * 否则「22:00 才可答题」在上午会显示成 600:00，家长读不出是十分钟还是十小时。
     */
    private static String waitText(long ms) {
        if (ms < 3_600_000L) return Core.fmt(ms);
        long s = (ms + 999) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * 设置行里的时间窗文本。开始=结束在规则里等同整天，但显示成「18:00 – 18:00」
     * 家长只会以为设坏了（实际电视整天都不锁），所以这里把语义标出来。
     * 正常路径已经不可能设出这种值（结束面板会拒绝），只有 1.6.1-dev 之前的旧数据会带。
     */
    private static String windowText(int start, int end) {
        String t = clockText(start) + " – " + clockText(end);
        return start == end ? t + "（等同全天）" : t;
    }

    /**
     * 设置行右侧：两个数字都要露出来。只显示「10 分钟」的话，家长看不出那是歇多久
     * 还是看多久——这正是原来两行设置互相打架的根源。
     */
    private String restText() {
        if (!prefs.restOn()) return "不休息";
        return "看 " + prefs.watchLimitMin() + " 分 · 歇 " + prefs.restMin() + " 分";
    }

    /** 预设值仍是分钟数，但显示成钟点；「自定义」走 OptionPanel 的 X 点 X 分输入。 */
    private void openWindowPanel() {
        int[] vals = {0, 360, 720, 1080, 1320};
        String[] labels = new String[vals.length];
        for (int i = 0; i < vals.length; i++) labels[i] = clockText(vals[i]);
        mountOption(new OptionPanel(this, "看电视时间 · 开始",
                "只有这段时间可以看电视，其它时间会全屏锁住\n"
                        + "当前 " + windowText(prefs.windowStart(), prefs.windowEnd()),
                labels, vals, prefs.windowStart(),
                "点分", 0, 1439, true, false,
                this::openWindowEndPanel, this::hideOptionPanel));
    }

    private void openWindowEndPanel(int start) {
        int[] vals = {0, 360, 720, 1080, 1320, 1440};
        String[] labels = new String[vals.length];
        for (int i = 0; i < vals.length; i++) labels[i] = clockText(vals[i]);
        mountOption(new OptionPanel(this, "看电视时间 · 结束（早于开始=跨午夜）",
                "开始 " + clockText(start) + " · 结束要晚于开始，最晚可设 24:00",
                labels, vals, prefs.windowEnd(), "点分", 0, 1440, true, true,
                end -> {
                    if (end == start) {
                        // 开始=结束在 GateRules 里等于「全天」，家长以为设了个空窗口，
                        // 实际电视整天不锁；保存了还会在设置页留下「18:00 – 18:00」的怪值。
                        toast("结束不能等于开始；要全天请设 00:00 – 24:00");
                        return;
                    }
                    prefs.setWindow(start, end);
                    // 看电视时间是天花板：刚设完就撤销「输口令后 5 分钟豁免」，
                    // 否则家长在软件里改完时间回电视，旧豁免还能把新时间挡住 5 分钟，
                    // 表现就是「设了 9:50 开始，9:40 还能看电视」。
                    // 锁屏遮罩上输口令的临时豁免能力保留，不受这里影响。
                    prefs.setParentUnlocked(false);
                    App.parentUnlocked = false;
                    hideOptionPanel();
                    refresh();
                    toast("看电视时间：" + clockText(start) + " – " + clockText(end));
                }, this::openWindowPanel));
    }

    /**
     * 「看多久歇多久」两级面板：先选看多久（含「不休息」），再选歇多久。
     *
     * <p>1.6.1 及以前这里是两行——「连续观看上限」和「强制休息」，可它们写的是同一个键
     * {@code watchLimitMin}：家长改上面那行，下面那行的「观看分钟」跟着变，看起来就是
     * 两条设置互相覆盖。合并成一行后，一个入口管两个数字，规则在标题里就说完了。
     */
    private void openRestPanel() {
        String[] labels = {"不休息", "10", "20", "30", "45", "60"};
        int[] vals = {0, 10, 20, 30, 45, 60};
        String cur = prefs.restOn()
                ? "现在：看满 " + prefs.watchLimitMin() + " 分钟，歇 " + prefs.restMin() + " 分钟"
                : "现在：不休息，只受「看电视时间」限制";
        mountOption(new OptionPanel(this, "看多久歇多久 · 看多久", cur,
                labels, vals, prefs.watchLimitMin(), "分钟", 1, 240, false, false,
                v -> {
                    if (v == 0) {
                        prefs.setWatchLimitMin(0);
                        hideOptionPanel();
                        refresh();
                        toast("已关闭休息：只受「看电视时间」限制");
                        return;
                    }
                    openRestMinutesPanel(v);
                }, this::hideOptionPanel));
    }

    private void openRestMinutesPanel(int watchMin) {
        String[] labels = {"5", "10", "15", "20", "30"};
        int[] vals = {5, 10, 15, 20, 30};
        mountOption(new OptionPanel(this, "看多久歇多久 · 歇多久",
                "看满 " + watchMin + " 分钟就锁屏，到点自动恢复；休息中不能答题",
                labels, vals, prefs.restMin(), "分钟", 1, 120, false, false,
                v -> {
                    prefs.setWatchLimitMin(watchMin);
                    prefs.setRestMin(v);
                    hideOptionPanel();
                    refresh();
                    toast("看满 " + watchMin + " 分钟，歇 " + v + " 分钟");
                }, this::openRestPanel));
    }

    private void openResetPanel() {
        String[] labels = {"跨天 0 点", "固定时刻"};
        int[] vals = {0, 1};
        mountOption(new OptionPanel(this, "每日计数重置", labels, vals,
                prefs.fixedResetMoment() ? 1 : 0, "", 0, 0,
                v -> { if (v == 0) { prefs.setResetMode(false, 0); hideOptionPanel(); refresh(); }
                       else openResetMomentPanel(); }, this::hideOptionPanel));
    }

    private void openResetMomentPanel() {
        int[] vals = {0, 120, 360, 480, 720};
        String[] labels = new String[vals.length];
        for (int i = 0; i < vals.length; i++) labels[i] = clockText(vals[i]);
        mountOption(new OptionPanel(this, "固定重置时刻", labels, vals, prefs.resetAtMin(),
                "分钟", 0, 1439,
                v -> { prefs.setResetMode(true, v); hideOptionPanel(); refresh(); toast("每日 " + clockText(v) + " 重置"); },
                this::openResetPanel));
    }

    private void togglePauseOnQuiz() {
        boolean v = !prefs.pauseOnQuiz();
        prefs.setPauseOnQuiz(v);
        refresh();
        toast("答题时暂停播放：" + (v ? "开启" : "关闭"));
    }

    private void toggleBalanceCarryOver() {
        boolean v = !prefs.balanceCarryOver();
        prefs.setBalanceCarryOver(v);
        refresh();
        toast("余额跨天：" + (v ? "保留" : "清零"));
    }

    /** 年级三步：学段 → 年级 → 科目（语文 / 数学 / 英语，可多选）。 */
    private void openStagePanel() {
        BankStats st = stats();
        String[] labels = {"小学", "初中"};
        String[] subs = {countLabel(st.stageTotal(Prefs.STAGE_PRIMARY)),
                countLabel(st.stageTotal(Prefs.STAGE_JUNIOR))};
        int[] vals = {Prefs.STAGE_PRIMARY, Prefs.STAGE_JUNIOR};
        mountOption(new OptionPanel(this, "题目年级 · 选学段",
                "当前题库共 " + fmtNum(st.total()) + " 题 · 0 题的选项显示「暂无数据」",
                labels, subs, vals, prefs.stage(), "", 0, 0,
                this::openGradePanel, this::hideOptionPanel));
    }

    private void openGradePanel(int stage) {
        boolean junior = stage == Prefs.STAGE_JUNIOR;
        String[] labels = junior
                ? new String[]{"初一", "初二", "初三"}
                : new String[]{"1 年级", "2 年级", "3 年级", "4 年级", "5 年级", "6 年级"};
        int[] vals = junior ? new int[]{7, 8, 9} : new int[]{1, 2, 3, 4, 5, 6};
        BankStats st = stats();
        String[] subs = new String[vals.length];
        for (int i = 0; i < vals.length; i++) subs[i] = countLabel(st.gradeTotal(vals[i]));
        int cur = prefs.grade();
        if (junior && cur < 7) cur = 7;
        if (!junior && cur > 6) cur = 3;
        mountOption(new OptionPanel(this, "题目年级 · " + (junior ? "初中" : "小学"),
                (junior ? "初中" : "小学") + "共 " + fmtNum(st.stageTotal(stage)) + " 题",
                labels, subs, vals, cur, "", 0, 0,
                g -> openSubjectPanel(stage, g),
                this::openStagePanel));   // BACK 退回上一步选学段
    }

    private void openSubjectPanel(int stage, int grade) {
        BankStats st = stats();
        String[] subs = new String[3];
        for (int i = 0; i < 3; i++) {
            subs[i] = countLabel(st.at(Prefs.SUBJECT_KEYS[i], grade));
        }
        String gradeName = stage == Prefs.STAGE_JUNIOR
                ? new String[]{"初一", "初二", "初三"}[Math.max(0, Math.min(2, grade - 7))]
                : grade + " 年级";
        mountSubject(new SubjectPanel(this, "题目科目 · " + gradeName,
                "本年级共 " + fmtNum(st.gradeTotal(grade)) + " 题 · 可多选，到点轮流出题",
                Prefs.SUBJECT_NAMES, subs, prefs.subjectMask(),
                new SubjectPanel.Listener() {
                    @Override public void onSave(int mask) {
                        prefs.setStage(stage);
                        prefs.setGrade(grade);
                        prefs.setSubjectMask(mask);
                        hideSubjectPanel();
                        refresh();
                        toast("出题：" + gradeText());
                    }
                    @Override public void onBack() {
                        hideSubjectPanel();
                        openGradePanel(stage);
                    }
                }));
    }

    private String gradeText() {
        int g = prefs.grade();
        String base;
        if (prefs.stage() == Prefs.STAGE_JUNIOR) {
            String[] cn = {"初一", "初二", "初三"};
            base = "初中 · " + cn[Math.max(0, Math.min(2, g - 7))];
        } else {
            base = "小学 · " + Math.max(1, Math.min(6, g)) + " 年级";
        }
        return base + " · " + prefs.subjectText();
    }

    // ---------- 会话 ----------

    /**
     * 软件总开关。
     *
     * <p>启动：免密（家长主动要开，没有需要防的人），但必须先有口令、授权齐全。
     * <p>停止：<b>要口令</b>——这是唯一的「彻底关掉」出口，孩子拿到遥控器就能停掉的话，
     * 整套限制等于没有。本次停留在软件界面里已经验过口令则不重复问；离开软件页面再回来
     * 就要重新验（见 {@link App#uiUnlocked}）。
     */
    private void toggleGate() {
        if (!prefs.hasPin()) {
            toast("先设置 6 位家长口令");
            promptSetPin();
            return;
        }
        if (prefs.gateOn()) {
            if (App.uiUnlocked) {
                doDisableGate();
                return;
            }
            promptPinConfirm("停止软件",
                    "停止后不再锁屏、不再出题，看电视时间与休息规则也一并失效，直到重新启动。"
                            + "下次启动时「看多久歇多久」从 0 重新计。",
                    this::doDisableGate);
            return;
        }
        if (!authComplete()) {
            showAuthGate();
            return;
        }
        startForegroundService(new Intent(this, GateService.class).setAction(GateService.ACTION_ENABLE));
        toast("软件已启动");
        refresh();
    }

    private void doDisableGate() {
        App.uiUnlocked = false;
        startService(new Intent(this, GateService.class).setAction(GateService.ACTION_DISABLE));
        toast("软件已停止，不再锁屏");
        refresh();
    }

    /**
     * 「答题加时间」按钮只在「答题积累 + 已启动 + 答题开」时出现：
     * 其他方式答对就直接续时，不存在余额这东西，摆一颗按钮只会让家长误解。
     * 休息中/看电视时间外也收起来——那两段是天花板，不允许靠答题绕过。
     */
    private void refreshEarnButton() {
        if (homeEarnRow == null) return;
        int p = prefs.phase();
        boolean show = prefs.gateOn() && prefs.quizOn()
                && prefs.mode() == GateRules.MODE_BANK
                && p != Prefs.REST && p != Prefs.OUT && p != Prefs.IDLE;
        // 整行 GONE：不是把按钮藏起来留一条空白，避免三颗核心按钮被顶偏。
        homeEarnRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 打开有偿答题遮罩：答对一题加固定分钟，返回键结束。 */
    private void openEarnQuiz() {
        if (!prefs.gateOn()) {
            toast("先启动软件");
            return;
        }
        if (!prefs.quizOn() || prefs.mode() != GateRules.MODE_BANK) {
            toast("只有「答题积累」方式可以答题加时间");
            return;
        }
        int p = prefs.phase();
        if (p == Prefs.REST) {
            toast("休息中，休息结束后再攒时间");
            return;
        }
        if (p == Prefs.OUT) {
            toast("当前不在看电视时间，先去设置里改「看电视时间」");
            return;
        }
        startService(new Intent(this, GateService.class).setAction(GateService.ACTION_EARN_QUIZ));
    }

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

    /** 两项都齐才直接开始；缺任一项都先摆出来让家长选。 */
    private boolean authComplete() {
        return Settings.canDrawOverlays(this) && notifOn();
    }

    private void doStart() {
        // 新一局开始 = 重新上锁：下次锁屏/进设置还要口令，别让这次解锁一直生效
        App.parentUnlocked = false;
        prefs.setParentUnlocked(false);
        App.uiUnlocked = false;
        Intent i = new Intent(this, GateService.class).setAction(GateService.ACTION_START);
        startForegroundService(i);
        toast("已开始：" + prefs.durationMin() + " 分钟后出题");
        refresh();
        // 回到电视桌面；右下角由守护服务亮 5 秒「本次时长」提示，5 秒后自动收起
        finish();
    }

    // ---------- 开始前的授权检查 ----------

    /**
     * 开始前要查的两项授权。
     *
     * <p>1.3.0 起不再查电池优化白名单：D2（Android 14）移出白名单后实测
     * 回桌面、到点出题、锁屏按 HOME 都正常，没有出现 Force stop；
     * 而「让家长去电脑上执行 adb」本身不现实，所以这一项直接去掉。
     * 卸载留痕同样是可选功能，也不在这一层提示。
     */
    private List<AuthView.Item> authItems() {
        List<AuthView.Item> out = new ArrayList<>();
        String pkg = getPackageName();

        boolean ov = Settings.canDrawOverlays(this);
        out.add(new AuthView.Item(AUTH_OVERLAY, "悬浮窗权限", ov, ov ? "" :
                "到点要盖住整屏出题，缺了它题根本出不来。\n"
                        + "设置 → 应用 → 答题续时 → 权限；或用电脑执行：\n"
                        + "adb shell appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"));

        boolean notif = notifOn();
        out.add(new AuthView.Item(AUTH_NOTIF, "通知使用权", notif, notif ? "" :
                "退出应用后靠它把被系统杀掉的守护拉回来，不开就会停表。\n"
                        + "用电脑执行：\n"
                        + "adb shell cmd notification allow_listener " + pkg + "/" + pkg + ".NotifProbe"));
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
                        + " " + prefs.debugLine());
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
        box.addView(section("常见位置：设置 → 应用 → 应用管理 → 答题续时 → 权限；或 电视管家 → 权限管理。"));

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

    /**
     * 首页三颗按钮共用的口令门禁：停止软件 / 答题方式 / 设置。
     * 返回 true 表示放行（免密或本次留在软件里已验过），false 表示已弹面板等口令。
     *
     * <p>软件没启动时不问口令——此时没有限制在生效。一旦启动，三颗按钮都要验。
     * 验过一次后只要人还在软件页面里（含子页来回切换）就不再问；Activity onPause
     * 即离开软件页面，标记清零，再回来点按钮必须重新验。
     * <p>免密分支<b>不</b>置 {@link App#uiUnlocked}：否则家长先免密进设置、再回首页点
     * 启动软件，三颗按钮就永久敞开了。
     * <p>锁屏遮罩上输对口令应急放行（{@link App#parentUnlocked}）走的是另一条规则，
     * 那是有 5 分钟时限的「看电视豁免」，不能当成这里的在页内解锁。
     */
    private boolean gate() {
        if (!prefs.hasPin()) { promptSetPin(); return false; }
        if (!prefs.gateOn()) return true;
        if (App.uiUnlocked) return true;
        showUnlockPin();
        return false;
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
        showPinOverlay("家长口令", "输对后本次留在软件里不再重复输入");
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
                            // 只解「在页内操作」这一层，不碰 parentUnlocked：
                            // 那个是锁屏遮罩上的 5 分钟看电视豁免，跟设口令无关。
                            App.uiUnlocked = true;
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
                    // 停止软件：口令通过才执行，别把「谁能按到遥控器谁就能停」留在首页
                    if (act != null) act.run();
                } else {
                    // 三颗按钮共用这一份解锁态，离开软件页面（onPause）即失效。
                    // 刻意不置 parentUnlocked：进设置输口令 ≠ 获得「看电视时间外」的 5 分钟豁免，
                    // 否则家长刚把看电视时间设成 9:50 开始，9:40 回电视还能再看 5 分钟。
                    App.uiUnlocked = true;
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
                    || c == logPanel || c == guardPanel || c == optionPanel || c == authPanel
                    || c == subjectPanel || c == bankManager) return c;
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
        if (homeModeLabel != null) homeModeLabel.setText(homeModeLine());
        if (homeBtnGate != null) homeBtnGate.setText(prefs.gateOn() ? "停止软件" : "启动软件");
        // 三颗核心按钮等宽，按钮上只写功能名；当前方式在状态卡里单独一行显示
        if (homeBtnMode != null) homeBtnMode.setText("答题方式");
        refreshEarnButton();
        refreshModePicker();
        valueFixedNeed.setText(prefs.fixedNeed() + " 题");
        valueFirstNeed.setText(prefs.firstNeed() + " 题");
        valueStepAdd.setText("+ " + prefs.stepAdd() + " 题");
        valuePer.setText(prefs.perAnswerMin() + " 分钟/题");
        valueMinBank.setText("满 " + prefs.minBankMin() + " 分钟");
        if (modeBankNote != null) {
            modeBankNote.setText("当前已攒 " + Core.fmt(prefs.balanceMs()) + "；余额满 "
                    + prefs.minBankMin() + " 分钟才能开始看。\n"
                    + "不到门槛会先出题，答对一题 +" + prefs.perAnswerMin()
                    + " 分钟，攒够才放行；余额不会清零，下次启动接着用。");
        }
        valueWindow.setText(windowText(prefs.windowStart(), prefs.windowEnd()));
        valueRest.setText(restText());
        valueReset.setText(prefs.fixedResetMoment()
                ? clockText(prefs.resetAtMin()) : "跨天 0 点");
        valuePause.setText(prefs.pauseOnQuiz() ? "暂停" : "不暂停");
        valueCarryOver.setText(prefs.balanceCarryOver() ? "保留" : "清零");
        valueGrade.setText(gradeText());
        if (valueBank != null) valueBank.setText(bankName());
        valuePin.setText(prefs.hasPin() ? "已设置" : "未设置");
        // 只存哈希：应用里看不到已生成的码，想再看只能重新生成（旧码立即作废）
        valueRecovery.setText(!prefs.hasRecoveryCode() ? "未生成"
                : (prefs.recoverySaved() ? "已保存" : "待拍照"));
        valueVersion.setText(versionText());
        if (valueLog != null) {
            valueLog.setText("查看 ›");
            valueLog.setTextColor(Ui.ACCENT);
        }
        refreshAnchorWarn();
        if (guardPanel != null) refreshGuardPanel();
        refreshHomeAuth();
        refreshGuard();
        tickUi();
        maybeInitFocus();
    }

    /** 熔断状态的展示：横幅 + 设置页那一行。安全模式下要让家长知道「现在没在防」。 */
    private void refreshGuard() {
        boolean safe = prefs.safeMode();
        boolean needGuard = prefs.gateOn() && prefs.phase() != Prefs.IDLE;
        boolean alive = App.gateAlive;
        boolean authMissing = !Settings.canDrawOverlays(this) || !notifOn();
        String txt;
        int color;
        if (safe) { txt = "已停止（安全模式）"; color = Ui.BAD; }
        else if (!prefs.gateOn()) { txt = "软件已停止 · 不锁屏"; color = Ui.DIM; }
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
        // 总开关由服务在 onStartCommand 里异步置位（ENABLE 才会 setGateOn(true)），
        // onClick 里同步 refresh 会读到旧值，按钮卡在「启动软件」。这里每秒校正一次。
        if (homeBtnGate != null) homeBtnGate.setText(prefs.gateOn() ? "停止软件" : "启动软件");
    }

    /** 当前页面该先给谁焦点。1.2.9 起设置页顶部没有返回按钮了，落在第一项「本次时长」上。 */
    private View focusTarget() {
        if (showingSettings) {
            if (prefs.safeMode()) return banner;
            return settingsFocus != null ? settingsFocus : firstRow;
        }
        return prefs.safeMode() ? homeBanner : homeBtnGate;
    }

    /** 首次把焦点放到该看的东西上（安全模式下是横幅，不是「启动软件」）。 */
    private void maybeInitFocus() {
        if (focusInited) return;
        if (gate != null && gate.getParent() != null) return;
        if (recovery != null || codeOverlay != null || upgrade != null || logPanel != null) return;
        if (guardPanel != null || optionPanel != null || subjectPanel != null || authPanel != null) return;
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
        // 总开关由服务异步置位，onClick 里那次 refresh 可能读到旧的 gateOn；
        // 加时间按钮按秒重判，启动/切模式/锁定状态变了都能立刻出现或收起。
        refreshEarnButton();
        boolean alive = App.gateAlive;
        String phase, big;
        if (!prefs.gateOn()) {
            // 停止软件后 phase 仍是上次的快照（服务只是 stopSelf，没改 phase），
            // 不先判总开关就会显示「守护恢复中…」的红字，跟下面「软件已停止」自相矛盾。
            phase = "未启动 · 待机";
            big = "--:--";
        } else if (p == Prefs.IDLE) {
            phase = "未开始 · 待机";
            big = "--:--";
        } else if (!alive) {
            // 服务不在时 prefs.remainingMs() 是冻结的旧快照，绝不拿来冒充当前剩余时间
            phase = "守护恢复中…";
            big = "--:--";
        } else if (p == Prefs.LOCKED) {
            // 答题积累的锁定是「余额不够门槛」，余额本身还在，直接显示攒了多少比 0:00 有用
            if (prefs.mode() == GateRules.MODE_BANK && prefs.remainingMs() > 0) {
                phase = "余额不足 · 点「答题加时间」";
                big = Core.fmt(prefs.remainingMs());
            } else {
                phase = "已锁定 · 等待答题";
                big = "0:00";
            }
        } else if (p == Prefs.REST) {
            phase = "休息中 · 到点自动恢复";
            big = Core.fmt(prefs.restLeftMs());
        } else if (p == Prefs.OUT) {
            // 看电视时间之外：大字显示「还有多久到看电视时间」，
            // 不再拿冻结的初始余额冒充剩余时间（那种数字永远不动，家长以为坏了）。
            phase = "不在看电视时间 · " + clockText(prefs.windowStart()) + " 开始";
            big = waitText(GateRules.secondsUntilWindow(prefs.secondOfDay(),
                    prefs.windowStart(), prefs.windowEnd()) * 1000L);
        } else if (!prefs.quizOn()) {
            // 答题关：不出题、也不扣观看时长，大字显示「距休息」的倒计时；
            // 选「不休息」时没有上限可倒数，改成向上走的「本次已看」——数字一直在动，
            // 不会像冻结的 remaining 那样让家长以为计时坏了（1.6.1 报过这个问题）。
            // 必须排在下面「在软件里计时暂停」之前：那个分支显示的是不消耗的 remaining。
            phase = prefs.restOn() ? "答题关 · 距休息" : "答题关 · 本次已看";
            big = waitText(prefs.restOn()
                    ? Math.max(0L, prefs.watchLimitMin() * 60_000L - prefs.watchMs())
                    : prefs.watchMs());
        } else if (App.uiVisible && p == Prefs.RUNNING) {
            // 在软件自己的页面里不扣时（服务端同步跳过累加），数字不动要说明原因，
            // 否则家长会以为计时坏了。切回电视这一行立刻变回「计时中」。
            phase = "计时暂停 · 正在使用本软件";
            big = Core.fmt(prefs.remainingMs());
        } else {
            phase = "计时中";
            big = Core.fmt(prefs.remainingMs());
        }
        if (homePhase != null) homePhase.setText(phase);
        if (homeTime != null) homeTime.setText(big);
        // 总开关是服务异步置位的：停止后 refresh() 可能还读到 gateOn=true，
        // 「已攒」就刷不出来。这行跟状态卡一起每秒重算，代价只有一次字符串拼接。
        if (homeModeLabel != null) homeModeLabel.setText(homeModeLine());
        styleHomeReadout(p, alive);
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
        if (!prefs.gateOn() || p == Prefs.IDLE) {
            bar = 0xFF3A3A3A;                 // 灰：待机 / 已停止，不抢注意力
            time = 0xFF6B7075;
            phase = Ui.DIM;
        } else if (Prefs.lockedLike(p)) {
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
        if (top != null && top == bankManager) {
            // 题库管理页自路由：列表 / 添加 / 删除都在 consumeKey 里，BACK 只收起这一页。
            bankManager.consumeKey(e);
            return true;
        }
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
        if (top != null && top == guardPanel) {
            // 守护状态子页：BACK 只收起它。其余键交给焦点树（页内有「显示权限 / 重新检测 / 返回」）
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                hideGuardPanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (top != null && top == optionPanel) {
            // 选值 / 数字键盘：整个面板自路由，按键一个都不许漏给背后的设置页
            if (optionPanel.consumeKey(e)) return true;
            return true;
        }
        if (top != null && top == subjectPanel) {
            if (subjectPanel.consumeKey(e)) return true;
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
                App.uiUnlocked = true;
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
        // 软件没在守护时谈不上「守护被终止」，文案里的「已按原剩余时间继续」也不成立。
        // 未启动就把旧缺口清掉，免得家长下次点启动时弹一条昨天的记录。
        if (!prefs.gateOn()) { prefs.clearInterrupted(); return; }
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
        // 软件自己的页面不扣时：进页面暂停，离开（onPause）恢复，见 GateService.ACTION_UI_STATE
        App.uiVisible = true;
        notifyUiVisible(true);
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
        // 离开软件（回电视/桌面）＝开始计时。埋在 onPause 里发，服务收到就立刻按 phase 上窗，
        // 孩子按 HOME 切走也拦不住这一下。
        App.uiVisible = false;
        // 离开软件页面 = 本次家长授权作废；再次进软件点三颗按钮要重新输口令
        App.uiUnlocked = false;
        notifyUiVisible(false);
    }

    /**
     * 只在软件已启动时才发：服务没跑的时候 startService 会把它拉起来，
     * 那就变成「打开首页→退出」凭空启动守护了。
     */
    private void notifyUiVisible(boolean visible) {
        if (!prefs.gateOn()) return;
        try {
            startService(new Intent(this, GateService.class)
                    .setAction(GateService.ACTION_UI_STATE)
                    .putExtra(GateService.EXTRA_VISIBLE, visible));
        } catch (Throwable ignore) {
            // 服务刚被系统回收时这里可能抛，下一秒的 tick 会按静态标记自愈
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
