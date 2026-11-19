package app.quizgate.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * 全屏遮罩：出题、判题、家长口令入口。按键全部在根 View 上路由，不依赖焦点树。
 *
 * <p>1.3.1 起答题界面按「看得懂进度、看得见反馈」重做：
 * 顶部一行说明 + 进度条，题干放进圆角卡片，选项带 1–4 编号圆标，
 * 答对轻微弹一下、答错整块抖一下，连对 3 题以上给连对提示。
 */
public class MaskView extends FrameLayout {

    public interface Host {
        void onSolved();
        void onPinOk();
        /** 已答满题目、进入强制休息（由宿主记录截止时间并保持锁定）。 */
        void onRestStart();
        /** 强制休息倒计时结束，宿主续时解锁。 */
        void onRestDone();
    }

    /** 答对时的鼓励语，按已答对数轮换，避免同一句反复出现。 */
    private static final String[] PRAISE = {"太棒了！", "真厉害！", "答对啦！", "好聪明！", "完美！"};

    private final Prefs prefs;
    private final Host host;
    private final QuizBank bank;
    private final Random rnd = new Random();
    private final int grade;
    private final int floorGrade;
    private final String subjectKey;
    private final int extendMin;

    /** 本次需要累计答对的题数（见 Prefs.requiredQuizCount）。 */
    private final int required;
    private int solvedCount = 0;
    /** 本轮连续答对数：用于连对提示，答错归零。 */
    private int streak = 0;
    /** 本轮答错次数：为 0 才算「今日满分」。 */
    private int wrongInRound = 0;
    /** 本轮是否已经拿到满分（避免重复记账）。 */
    private boolean perfect = false;
    /**
     * 答对后的提示是否要留到下一题：文案说的是「还差几题」，跨题依然成立，
     * 所以不随换题清空，孩子随时抬头都能看到，而不是闪一下就没了。
     * 答错的提示只说上一题答案，必须随换题清掉，否则会被当成当前题的答案。
     */
    private boolean keepFeedback = false;

    /** 顶部状态行：左侧说明 + 年级科目，右侧「已答对 X / N」。 */
    private final TextView progressView;
    /** 本轮全对才出现的小角标。 */
    private final TextView perfectPill;
    private final TextView countView;
    /** 进度条：fill 的 weight = 已答对，rest 的 weight = 还差几题。 */
    private View progressFill;
    private View progressRest;

    private final TextView stemView;
    private final TextView feedback;
    private final TextView[] opts = new TextView[4];
    private final TextView[] badges = new TextView[4];
    private final LinearLayout[] cells = new LinearLayout[4];
    private final LinearLayout box;
    /** 选项区容器：答错时整块左右抖动。 */
    private LinearLayout optionArea;
    /** 强制休息面板：答满题后替代题目区，显示倒计时。 */
    private final LinearLayout restPanel;
    private final RingProgress restRing;
    private final TextView restCaption;
    private boolean resting = false;

    private QuizBank.Q q;
    private PinPadView pin;
    private RecoveryFlow recovery;
    private boolean pinVisible = false;
    private boolean answering = false;
    /** 当前选中的选项。遮罩在悬浮窗里，不能靠焦点树，只能自己记。 */
    private int sel = 0;

    public MaskView(Context c, Prefs prefs, QuizBank bank, Host host) {
        super(c);
        this.prefs = prefs;
        this.bank = bank;
        this.host = host;
        this.grade = prefs.grade();
        this.floorGrade = prefs.floorGrade();
        this.subjectKey = prefs.subjectKey();
        this.extendMin = prefs.extendMin();
        this.required = prefs.requiredQuizCount();
        bank.resetRound();   // 新一轮答题：清空去重记录，保证本轮内不重复出题

        // 深色渐变打底：纯黑在电视上像「没信号」，加一点层次更像应用界面
        setBackground(nightBg(c));
        setFocusable(true);
        setFocusableInTouchMode(true);

        box = Ui.col(c);
        // 整体垂直居中：电视屏幕高，内容贴顶会显得下面空一大块
        box.setGravity(Gravity.CENTER);
        // 1080p 电视只有 540dp 高：右下角加了品牌角标后，竖向留白必须收一收，
        // 否则题干换两行时品牌会被挤出屏幕（模拟器实测）。
        box.setPadding(Ui.dp(c, 64), Ui.dp(c, 22), Ui.dp(c, 64), Ui.dp(c, 18));

        // ---------- 顶部：进度 ----------
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        progressView = Ui.label(c, "", 16, Ui.DIM);
        head.addView(progressView, new LinearLayout.LayoutParams(0, -2, 1f));

        // 满分角标：默认不占位，本轮一题没错才亮出来（放在计数左边，别顶到屏幕边）
        perfectPill = Ui.label(c, "★ 今日满分", 15, 0xFF20160A);
        perfectPill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        perfectPill.setGravity(Gravity.CENTER);
        perfectPill.setBackground(Ui.box(c, Ui.ACCENT, 0, 0, 99));
        perfectPill.setPadding(Ui.dp(c, 12), Ui.dp(c, 3), Ui.dp(c, 12), Ui.dp(c, 3));
        perfectPill.setVisibility(GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.rightMargin = Ui.dp(c, 10);
        head.addView(perfectPill, plp);

        countView = Ui.label(c, "", 18, Ui.ACCENT);
        countView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(countView, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 进度条：比纯文字「还差 3 题」更直观，孩子一眼看到还剩多少
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(Ui.box(c, 0x1FFFFFFF, 0, 0, 99));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 8));
        trackLp.topMargin = Ui.dp(c, 8);
        box.addView(track, trackLp);

        progressFill = new View(c);
        progressFill.setBackground(Ui.box(c, Ui.ACCENT, 0, 0, 99));
        track.addView(progressFill, new LinearLayout.LayoutParams(0, -1, 0f));

        progressRest = new View(c);
        track.addView(progressRest, new LinearLayout.LayoutParams(0, -1, 1f));

        // ---------- 题干卡片 ----------
        LinearLayout qCard = new LinearLayout(c);
        qCard.setOrientation(LinearLayout.VERTICAL);
        qCard.setGravity(Gravity.CENTER);
        qCard.setBackground(Ui.box(c, 0x14FFFFFF, 0x2EFFFFFF, Ui.dp(c, 1), 18));
        qCard.setPadding(Ui.dp(c, 28), Ui.dp(c, 18), Ui.dp(c, 28), Ui.dp(c, 18));
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(-1, -2);
        qLp.topMargin = Ui.dp(c, 14);
        box.addView(qCard, qLp);

        stemView = Ui.title(c, "", 30);
        stemView.setGravity(Gravity.CENTER);
        stemView.setMaxLines(3);
        qCard.addView(stemView);

        // ---------- 选项：2×2，带 1–4 编号圆标 ----------
        optionArea = new LinearLayout(c);
        optionArea.setOrientation(LinearLayout.VERTICAL);
        box.addView(optionArea, new LinearLayout.LayoutParams(-1, -2));

        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 74));
            rlp.topMargin = Ui.dp(c, row == 0 ? 14 : 10);
            optionArea.addView(r, rlp);
            for (int col = 0; col < 2; col++) {
                final int i = row * 2 + col;

                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setFocusable(false);
                cell.setClickable(true);
                cell.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 16), 0);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
                clp.leftMargin = Ui.dp(c, col == 0 ? 0 : 10);
                clp.rightMargin = Ui.dp(c, col == 0 ? 10 : 0);
                r.addView(cell, clp);
                cell.setOnClickListener(v -> answer(i));

                // 编号圆标：遥控器按 1–4 直接选，数字和键位对得上，孩子不用数格子
                TextView badge = Ui.label(c, String.valueOf(i + 1), 18, 0xFFBFC4CA);
                badge.setGravity(Gravity.CENTER);
                badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                cell.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));

                TextView t = Ui.label(c, "", 23, Ui.TEXT);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(2);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
                tlp.leftMargin = Ui.dp(c, 12);
                cell.addView(t, tlp);

                cells[i] = cell;
                badges[i] = badge;
                opts[i] = t;
            }
        }

        // ---------- 反馈行 ----------
        // 电视离得远，反馈是小屏手机尺度的 20sp 会看不清，这里放大到 34sp 并加粗
        feedback = Ui.label(c, "", 34, Ui.DIM);
        feedback.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        feedback.setGravity(Gravity.CENTER);
        feedback.setMaxLines(2);
        feedback.setMinHeight(Ui.dp(c, 52));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Ui.dp(c, 14);
        box.addView(feedback, flp);

        TextView hint = Ui.label(c, "按 1–4 或方向键选择，OK 确认　·　菜单键 = 家长", 15, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 12);
        box.addView(hint, hlp);

        // 右下角品牌角标：家长在出题这一刻看到的还是「答题续时」自己的界面。
        LinearLayout brand = Ui.row(c);
        brand.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.home_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
        TextView name = Ui.label(c, "答题续时", 16, Ui.DIM);
        name.setPadding(Ui.dp(c, 10), 0, 0, 0);
        brand.addView(name);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(c, 8);
        box.addView(brand, blp);

        addView(box, new FrameLayout.LayoutParams(-1, -1));

        // ---------- 强制休息面板 ----------
        restPanel = Ui.col(c);
        restPanel.setGravity(Gravity.CENTER);
        restPanel.setPadding(Ui.dp(c, 72), Ui.dp(c, 28), Ui.dp(c, 72), Ui.dp(c, 28));
        restPanel.setBackground(nightBg(c));

        TextView restTitle = Ui.title(c, "休息一下", 34);
        restTitle.setGravity(Gravity.CENTER);
        restPanel.addView(restTitle);

        restCaption = Ui.label(c, "", 20, Ui.DIM);
        restCaption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(-1, -2);
        rclp.topMargin = Ui.dp(c, 14);
        restPanel.addView(restCaption, rclp);

        // 倒计时放进和题干一样的卡片里，视觉上「同一个界面换了个状态」
        LinearLayout restCard = new LinearLayout(c);
        restCard.setOrientation(LinearLayout.VERTICAL);
        restCard.setGravity(Gravity.CENTER);
        restCard.setBackground(Ui.box(c, 0x14FFFFFF, 0x2EFFFFFF, Ui.dp(c, 1), 18));
        restCard.setPadding(Ui.dp(c, 56), Ui.dp(c, 16), Ui.dp(c, 56), Ui.dp(c, 16));
        LinearLayout.LayoutParams rclp2 = new LinearLayout.LayoutParams(-2, -2);
        rclp2.topMargin = Ui.dp(c, 16);
        restPanel.addView(restCard, rclp2);

        restRing = new RingProgress(c);
        restCard.addView(restRing, new LinearLayout.LayoutParams(Ui.dp(c, 168), Ui.dp(c, 168)));

        TextView restHint = Ui.label(c, "让眼睛歇一会儿，看看远处　·　菜单键 = 家长", 16, Ui.DIM);
        restHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rhlp = new LinearLayout.LayoutParams(-1, -2);
        rhlp.topMargin = Ui.dp(c, 18);
        restPanel.addView(restHint, rhlp);

        LinearLayout restBrand = Ui.row(c);
        restBrand.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageView restLogo = new ImageView(c);
        restLogo.setImageResource(R.drawable.home_logo);
        restBrand.addView(restLogo, new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
        TextView restName = Ui.label(c, "答题续时", 16, Ui.DIM);
        restName.setPadding(Ui.dp(c, 10), 0, 0, 0);
        restBrand.addView(restName);
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(-1, -2);
        rblp.topMargin = Ui.dp(c, 8);
        restPanel.addView(restBrand, rblp);

        addView(restPanel, new FrameLayout.LayoutParams(-1, -1));
        restPanel.setVisibility(GONE);

        // 进程被待机强杀后由通知监听在新进程 RESTORE：
        // 只要 restUntil 非零（哪怕已到点）就恢复休息界面，由宿主 ticker 判定解锁，
        // 绝不能回退到出题界面（否则会让孩子在休息期看到新题）。
        if (prefs.restUntil() > 0) {
            showRestUi();
        } else {
            nextQuestion();
        }
    }

    /** 每次新建一份渐变：同一个 Drawable 实例挂到两个 View 上会互相抢 bounds。 */
    private static GradientDrawable nightBg(Context c) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF16130A, 0xFF050505});
    }

    /** 「小学 3 年级」/「初二」这类角标文案。 */
    private String stageGradeText() {        int g = prefs.grade();
        if (prefs.stage() == Prefs.STAGE_JUNIOR) {
            return g == 7 ? "初一" : g == 8 ? "初二" : "初三";
        }
        return "小学 " + g + " 年级";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        // 选项不再参与焦点树，选中态自己画
        post(this::paintSelection);
    }

    public void nextQuestion() {
        q = bank.next(subjectKey, grade, floorGrade, rnd);
        answering = false;
        stemView.setText(q.stem);
        for (int i = 0; i < 4; i++) {
            opts[i].setText(q.options[i]);
            cells[i].setScaleX(1f);
            cells[i].setScaleY(1f);
            cells[i].setTranslationX(0f);
        }
        feedback.animate().cancel();
        if (!keepFeedback) feedback.setText("");
        keepFeedback = false;
        feedback.setAlpha(1f);
        feedback.setScaleX(1f);
        feedback.setScaleY(1f);
        sel = 0;
        paintSelection();
        updateProgress();
    }

    /** 顶部：说明、计数、进度条一起更新，三者永远说的是同一件事。 */
    private void updateProgress() {
        progressView.setText("本次观看时间到 · " + stageGradeText() + " · " + prefs.subjectName());
        countView.setText(solvedCount + " / " + required);
        LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) progressFill.getLayoutParams();
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) progressRest.getLayoutParams();
        int done = Math.max(0, Math.min(required, solvedCount));
        fp.weight = done;
        rp.weight = Math.max(0.0001f, required - done);
        progressFill.setLayoutParams(fp);
        progressRest.setLayoutParams(rp);
    }

    /** 切到强制休息界面：答题区隐藏，显示休息倒计时。 */
    public void enterRest() {
        host.onRestStart();               // 宿主记录截止时间、保持锁定
        showRestUi();
    }

    /** 仅切换 UI（供 enterRest 与进程重启恢复共用）。 */
    private void showRestUi() {
        resting = true;
        answering = true;                 // 吞掉答题输入
        box.setVisibility(GONE);
        restPanel.setVisibility(VISIBLE);
        restCaption.setText("答对 " + required + " 题，休息 " + prefs.restMin() + " 分钟后继续"
                + (perfect ? "　·　今日满分！" : ""));
        setRestRemaining(prefs.restUntil() - System.currentTimeMillis());
    }

    /** 由宿主 ticker 每秒刷新；到 0 时由宿主负责续时解锁。 */
    public void setRestRemaining(long ms) {
        if (!resting) return;
        long left = Math.max(0, ms);
        long sec = (left + 999) / 1000;
        long total = Math.max(1, prefs.restMin() * 60_000L);
        restRing.set(left / (float) total,
                String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60));
    }

    // ---------- 选项绘制 ----------

    /** 普通态：半透明底 + 细描边。 */
    private void paintNeutral(int i) {
        cells[i].setBackground(Ui.box(getContext(), 0x14FFFFFF, 0x33FFFFFF,
                Ui.dp(getContext(), 1), 14));
        opts[i].setTextColor(Ui.TEXT);
        badges[i].setBackground(Ui.box(getContext(), 0x1FFFFFFF, 0, 0, 99));
        badges[i].setTextColor(0xFFBFC4CA);
    }

    /** 选中态：黄底黄框、编号反白，电视上一眼能看出光标在哪。 */
    private void paintSelected(int i) {
        cells[i].setBackground(Ui.box(getContext(), 0x33FDC90C, Ui.ACCENT,
                Ui.dp(getContext(), 3), 14));
        opts[i].setTextColor(Ui.ACCENT);
        badges[i].setBackground(Ui.box(getContext(), Ui.ACCENT, 0, 0, 99));
        badges[i].setTextColor(0xFF20160A);
    }

    private void paintCorrect(int i) {
        cells[i].setBackground(Ui.box(getContext(), 0xFF1F6F45, 0xFF4ADE80,
                Ui.dp(getContext(), 3), 14));
        opts[i].setTextColor(0xFFFFFFFF);
        badges[i].setBackground(Ui.box(getContext(), 0xFF4ADE80, 0, 0, 99));
        badges[i].setTextColor(0xFF08301C);
    }

    private void paintWrong(int i) {
        cells[i].setBackground(Ui.box(getContext(), 0x8C6B1D1D, 0xFFFF5252,
                Ui.dp(getContext(), 3), 14));
        opts[i].setTextColor(0xFFFFD7D7);
        badges[i].setBackground(Ui.box(getContext(), 0xFF8C2B2B, 0, 0, 99));
        badges[i].setTextColor(0xFFFFFFFF);
    }

    /** 画出选中态。遮罩里所有按键都由 {@link #dispatchKeyEvent} 路由，不靠系统焦点。 */
    private void paintSelection() {
        for (int i = 0; i < 4; i++) {
            if (i == sel && !answering) paintSelected(i);
            else paintNeutral(i);
        }
    }

    private void select(int i) {
        sel = Math.max(0, Math.min(3, i));
        paintSelection();
        // 轻微放大：遥控器挪光标也有「手感」，不用盯着看
        for (int k = 0; k < 4; k++) {
            float s = (k == sel) ? 1.03f : 1f;
            cells[k].animate().scaleX(s).scaleY(s).setDuration(90).start();
        }
    }

    /** 2×2 布局：左右在行内切换，上下在列内切换（到头就绕回，遥控器上少按键更省事）。 */
    private void moveSel(int dx, int dy) {
        if (answering) return;
        int col = sel % 2, row = sel / 2;
        col = (col + dx + 2) % 2;
        row = (row + dy + 2) % 2;
        select(row * 2 + col);
    }

    private void answer(int i) {
        if (answering || pinVisible || q == null) return;
        answering = true;
        boolean ok = (i == q.answer);
        if (ok) {
            keepFeedback = true;
            solvedCount++;
            streak++;
            paintCorrect(i);
            pulse(cells[i]);
            Sfx.correct(getContext());
            String praise = PRAISE[(solvedCount - 1) % PRAISE.length];
            if (solvedCount >= required) {
                // 本轮一题没答错：记一次「满分」，顶部亮角标
                if (wrongInRound == 0 && !perfect) {
                    perfect = true;
                    prefs.bumpPerfect();
                    showPerfect();
                }
                String tail = perfect ? "全部答对！" : "答对 " + required + " 题，";
                if (prefs.restMin() > 0) {
                    showFeedback(praise + " " + tail + "休息 " + prefs.restMin() + " 分钟后继续", Ui.OK);
                    postDelayed(this::enterRest, 2000);
                } else {
                    showFeedback(praise + " " + tail + "延长 " + extendMin + " 分钟", Ui.OK);
                    postDelayed(host::onSolved, 2000);
                }
            } else {
                String combo = streak >= 3 ? "　连对 " + streak + " 题！" : "";
                showFeedback(praise + " 再答 " + (required - solvedCount) + " 题续时" + combo, Ui.OK);
                // 原来 850ms 一眨眼就换题，孩子根本读不完；拉到 2.2s 能读完再进下一题
                postDelayed(this::nextQuestion, 2200);
            }
            updateProgress();
        } else {
            keepFeedback = false;
            streak = 0;
            wrongInRound++;
            paintWrong(i);
            paintCorrect(q.answer);
            shake(optionArea);
            Sfx.wrong(getContext());
            String ex = q.explain == null ? "" : q.explain.trim();
            SpannableStringBuilder sb = new SpannableStringBuilder();
            // 不用「」包答案：电视上括号显得碎，改成冒号 + 答案提亮放大，一眼就能扫到
            sb.append("正确答案：");
            int ansStart = sb.length();
            sb.append(q.options[q.answer]);
            sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), ansStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.15f), ansStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 题库里有些解析本身就以「答案是 …」开头，再拼一遍会重复说答案
            if (!ex.isEmpty() && !ex.startsWith("答案是")) {
                int start = sb.length();
                sb.append("\n").append(ex);
                sb.setSpan(new RelativeSizeSpan(0.7f), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            showFeedback(sb, Ui.BAD, 28);
            postDelayed(this::nextQuestion, 2600);
        }
    }

    /**
     * 反馈统一入口：放大字号后配一个淡入 + 轻微弹入，
     * 让「答对了」这件事在电视上真的能被看见，而不是闪一下没了。
     */
    private void showFeedback(CharSequence text, int color) {
        showFeedback(text, color, 34);
    }

    /** 答错带解析时文字更长，用略小的字号，保证两行能放下不被截断。 */
    private void showFeedback(CharSequence text, int color, float sizeSp) {
        feedback.setTextSize(sizeSp);
        feedback.setText(text);
        feedback.setTextColor(color);
        feedback.setAlpha(0f);
        feedback.setScaleX(0.92f);
        feedback.setScaleY(0.92f);
        feedback.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /** 满分角标：亮出来并弹一下，比只写一行字更有「拿到奖励」的感觉。 */
    private void showPerfect() {
        perfectPill.setVisibility(VISIBLE);
        perfectPill.setScaleX(0.6f);
        perfectPill.setScaleY(0.6f);
        perfectPill.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    /** 答对时轻微弹一下，比单纯变色更容易被注意到。 */
    private void pulse(View v) {
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    /** 答错时左右抖一下：不刺眼，但明确表达「这个不对」。 */
    private void shake(View v) {
        float d = Ui.dp(getContext(), 10);
        v.animate().translationX(-d).setDuration(60)
                .withEndAction(() -> v.animate().translationX(d).setDuration(60)
                        .withEndAction(() -> v.animate().translationX(-d * 0.6f).setDuration(60)
                                .withEndAction(() -> v.animate().translationX(0f).setDuration(60)
                                        .start())
                                .start())
                        .start())
                .start();
    }

    // ---------- 家长入口 ----------

    private void showPin() {
        if (pinVisible) return;
        pinVisible = true;
        if (pin == null) {
            pin = new PinPadView(getContext(), "家长口令（6 位）", new PinPadView.Listener() {
                @Override public void onPinSubmit(String s) {
                    long r = prefs.verifyPin(s);
                    if (r == 0) {
                        hidePin();
                        host.onPinOk();
                    } else if (r > 0) {
                        pin.fail("尝试次数过多，请 " + (r / 1000) + " 秒后再试");
                    } else {
                        pin.fail("口令不正确");
                    }
                }
                @Override public void onPinCancel() { hidePin(); }
            });
            pin.setFooter("忘记口令？用恢复码重置", this::openRecovery);
        }
        if (pin.getParent() != null) removeView(pin);
        // 悬浮窗里焦点树不可靠：面板自己画选中态，方向键由 dispatchKeyEvent 转进来
        pin.setSelfPaint(true);
        addView(pin, new FrameLayout.LayoutParams(-1, -1));
        // 只让面板吃焦点：实测焦点会从面板最底一行掉到背后的选项卡上
        box.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        pin.reset();
        pin.focusFirst();
    }

    private void hidePin() {
        if (!pinVisible) return;
        pinVisible = false;
        if (pin != null && pin.getParent() != null) removeView(pin);
        sel = 0;
        paintSelection();
    }

    /** 忘口令的家长在遮罩上也能自助：不需要等会话结束，也不需要卸载。 */
    private void openRecovery() {
        if (recovery != null) return;
        if (pin != null && pin.getParent() != null) removeView(pin);
        pinVisible = false;
        box.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        recovery = new RecoveryFlow(getContext(), prefs, ok -> {
            if (recovery != null && recovery.getParent() != null) removeView(recovery);
            recovery = null;
            if (ok) {
                // 口令已重设、恢复码已换新 = 家长接管，按「结束本次会话」处理
                host.onPinOk();
            } else {
                showPin();
            }
        });
        recovery.setSelfPaint(true);   // 遮罩里同样不靠焦点树
        addView(recovery, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (recovery != null) {
            if (recovery.consumeKey(e)) return true;
            // 遮罩里的恢复流程不用焦点树（Android 14 实测 requestFocus 假成功），
            // 剩余按键一律吞掉，别漏给下层正在播放的应用
            return true;
        }
        if (pinVisible) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { hidePin(); return true; }
                // 方向键 / OK 直接转给面板自己路由：悬浮窗上的焦点树不可靠（Android 14 实测
                // requestFocus「成功」但按键仍判 Unhandled）。数字键本来就直连，不受影响。
                if (pin.consumeNav(e)) return true;
                int d = PinPadView.digitOf(e);
                if (d >= 0) {
                    pin.append((char) ('0' + d));
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DEL) { pin.backspace(); return true; }
            }
            return true;   // 面板开着时其余键一律吞掉，别漏给下层应用
        }
        if (resting) {
            // 强制休息期：只放行家长入口与音量，其余一律吞掉，别漏给下层正在播放的应用
            if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_MENU) {
                showPin();
            }
            if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                    || k == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_BACK) return true;                 // 吞掉返回
            if (k == KeyEvent.KEYCODE_MENU) { showPin(); return true; }  // 家长入口
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { moveSel(-1, 0); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { moveSel(1, 0); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP) { moveSel(0, -1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { moveSel(0, 1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                answer(sel);
                return true;
            }
            if (k >= KeyEvent.KEYCODE_1 && k <= KeyEvent.KEYCODE_4) {
                int idx = k - KeyEvent.KEYCODE_1;
                if (!answering) select(idx);
                answer(idx);
                return true;
            }
            if (swallow(k)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 媒体与信源键一律吞掉，音量键放行（家长仍需能调音量）。 */
    private boolean swallow(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_TV_INPUT:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }
}
