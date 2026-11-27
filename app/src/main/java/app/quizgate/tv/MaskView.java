package app.quizgate.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** 全屏遮罩：出题、判题、家长口令入口。按键全部在根 View 上路由，不依赖焦点树。 */
public class MaskView extends FrameLayout {

    public interface Host {
        /** 答对：宿主负责累加连对/余额/解锁阈值。返回 true = 已解锁，宿主会在反馈后摘遮罩。 */
        boolean onCorrect();
        /** 答错：宿主负责清连对、计冷却。返回剩余冷却毫秒，0 = 只换题。 */
        long onWrong();
        void onPinOk();
        /**
         * 「答题加时间」模式答对一题：只往余额里加固定分钟，不参与解锁/连对阶梯。
         * 返回累加后的余额毫秒，供遮罩顶部显示。
         */
        long onEarnCorrect();
        /** 退出「答题加时间」：宿主摘掉自己的加时间窗口（不是锁屏遮罩）。 */
        void onEarnExit();
    }

    private final Prefs prefs;
    private final Host host;
    private final QuizBank bank;
    private final Random rnd = new Random();
    private final int grade;
    private final int floorGrade;
    private final int extendMin;
    /**
     * true = 「答题加时间」：答题积累模式下由家长在主界面主动打开，
     * 答对只往余额里加分钟、接着出下一题，返回键随时退出。
     * false = 锁屏答题：答对判定门槛，达标才摘遮罩，返回键吞掉。
     */
    private final boolean earn;

    private final TextView stemView;
    private final TextView kicker;
    private final TextView metaView;
    private final TextView feedback;
    private final TextView hint;
    private final TextView nextBtn;
    private final LinearLayout brandRow;
    private final TextView[] opts = new TextView[4];
    private final LinearLayout box;

    private QuizBank.Q q;
    private PinPadView pin;
    private RecoveryFlow recovery;
    private boolean pinVisible = false;
    private boolean answering = false;
    /** 答错后停在解析页，等用户按「下一题」才继续。true 时除 OK 外的按键一律吞掉。 */
    private boolean waitNext = false;
    /** 答错冷却的截止 uptime；>0 时下一题按钮不可用。落盘真源在 Prefs.cooldownUntil。 */
    private long cooldownUntilWall = 0L;
    /** 当前选中的选项。遮罩在悬浮窗里，不能靠焦点树，只能自己记。 */
    private int sel = 0;

    public MaskView(Context c, Prefs prefs, QuizBank bank, Host host) {
        this(c, prefs, bank, host, false);
    }

    public MaskView(Context c, Prefs prefs, QuizBank bank, Host host, boolean earn) {
        super(c);
        this.prefs = prefs;
        this.bank = bank;
        this.host = host;
        this.earn = earn;
        this.grade = prefs.grade();
        this.floorGrade = prefs.floorGrade();
        this.extendMin = prefs.extendMin();

        setBackgroundColor(Ui.BG);
        setFocusable(true);
        setFocusableInTouchMode(true);

        box = Ui.col(c);
        // 1080p 电视只有 540dp 高：右下角加了品牌角标后，竖向留白必须收一收，
        // 否则题干换两行时品牌会被挤出屏幕（模拟器实测）。
        box.setPadding(Ui.dp(c, 72), Ui.dp(c, 28), Ui.dp(c, 72), Ui.dp(c, 28));

        // 顶部一行：左边是到点说明，右边小字标出这题属于哪一科、考的是哪个知识点。
        // 两者同一行，不额外占高度 —— 540dp 的电视上竖向空间很紧。
        LinearLayout topRow = Ui.row(c);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        kicker = Ui.label(c, "本次观看时间到", 20, Ui.DIM);
        kicker.setMaxLines(1);
        kicker.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(kicker, new LinearLayout.LayoutParams(0, -2, 1f));

        metaView = Ui.label(c, "", 15, Ui.DIM);
        metaView.setGravity(Gravity.END);
        metaView.setMaxLines(1);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        metaView.setMaxWidth(Ui.dp(c, 300));
        metaView.setPadding(Ui.dp(c, 16), 0, 0, 0);
        topRow.addView(metaView, new LinearLayout.LayoutParams(-2, -2));

        box.addView(topRow);

        stemView = Ui.title(c, "", 30);
        stemView.setGravity(Gravity.CENTER);
        // 默认题库题干最长 90 字，固定高度 + 自动缩放，保证 540dp 屏幕上不会被挤出。
        stemView.setMaxLines(3);
        stemView.setAutoSizeTextTypeUniformWithConfiguration(
                16, 30, 1, TypedValue.COMPLEX_UNIT_DIP);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 104));
        slp.topMargin = Ui.dp(c, 16);
        box.addView(stemView, slp);

        for (int row = 0; row < 2; row++) {
            LinearLayout r = Ui.row(c);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 72));
            rlp.topMargin = Ui.dp(c, row == 0 ? 20 : 12);
            box.addView(r, rlp);
            for (int col = 0; col < 2; col++) {
                final int i = row * 2 + col;
                TextView b = Ui.label(c, "", 24, Ui.TEXT);
                b.setGravity(Gravity.CENTER);
                b.setBackgroundResource(R.drawable.opt_bg);
                // 不参与焦点树：悬浮窗上的 requestFocus 在 Android 14 实测「假成功」，
                // 按键照样判 Unhandled。选中态一律由 MaskView 自己维护（见 paintSelection）。
                b.setFocusable(false);
                b.setClickable(true);
                b.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
                // 最长选项 48 字符（英文整句），固定 72dp 高度下自动缩到能放下 3 行。
                b.setMaxLines(3);
                b.setEllipsize(TextUtils.TruncateAt.END);
                b.setAutoSizeTextTypeUniformWithConfiguration(
                        13, 24, 1, TypedValue.COMPLEX_UNIT_DIP);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -1, 1f);
                blp.leftMargin = Ui.dp(c, col == 0 ? 0 : 10);
                blp.rightMargin = Ui.dp(c, col == 0 ? 10 : 0);
                r.addView(b, blp);
                b.setOnClickListener(v -> answer(i));
                opts[i] = b;
            }
        }

        feedback = Ui.label(c, "", 20, Ui.DIM);
        feedback.setGravity(Gravity.CENTER);
        // 错题反馈里带正确答案与解析，最长 200+ 字，限两行自动缩放，别把品牌行顶出屏幕。
        feedback.setMaxLines(2);
        feedback.setEllipsize(TextUtils.TruncateAt.END);
        feedback.setAutoSizeTextTypeUniformWithConfiguration(
                13, 20, 1, TypedValue.COMPLEX_UNIT_DIP);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Ui.dp(c, 14);
        box.addView(feedback, flp);

        hint = Ui.label(c, earn
                        ? "方向键选择 · OK 确认　·　返回键结束加时间"
                        : "按 1–4 或方向键选择，OK 确认　·　菜单键 = 家长",
                16, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 16);
        box.addView(hint, hlp);

        // 答错后的「下一题」按钮：与提示行同一个位置，显示时把提示行收起来。
        // 不再自动跳题 —— 解析要留给用户看完，由他自己按 OK 或点这一行继续。
        nextBtn = Ui.label(c, "下一题  ▶", 22, Ui.ACCENT);
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setBackground(selectedBg());
        nextBtn.setClickable(true);
        nextBtn.setFocusable(false);   // 悬浮窗焦点树不可靠，按键由 dispatchKeyEvent 路由
        nextBtn.setOnClickListener(v -> { if (waitNext && !cooling()) nextQuestion(); });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 46));
        nlp.topMargin = Ui.dp(c, 12);
        box.addView(nextBtn, nlp);
        nextBtn.setVisibility(View.GONE);

        // 右下角品牌角标：家长在出题这一刻看到的还是「答题续时」自己的界面。
        // 放进内容流而不是盖在右下角 —— 1080p 下题干换两行时贴角会压到提示行。
        LinearLayout brand = Ui.row(c);
        brandRow = brand;
        brand.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.home_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        TextView name = Ui.label(c, "答题续时", 18, Ui.DIM);
        name.setPadding(Ui.dp(c, 10), 0, 0, 0);
        brand.addView(name);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(c, 10);
        box.addView(brand, blp);

        addView(box, new FrameLayout.LayoutParams(-1, -1));
        nextQuestion();
        // 进程在冷却期间被强停/重启：Prefs 里还有截止时刻，遮罩重建后接着罚等
        long cd = prefs.cooldownLeftMs();
        if (cd > 0) enterCooldown(cd);
    }

    private void enterCooldown(long ms) {
        waitNext = true;
        cooldownUntilWall = System.currentTimeMillis() + Math.max(0L, ms);
        answering = false;
        hint.setVisibility(View.GONE);
        brandRow.setVisibility(View.GONE);
        nextBtn.setVisibility(View.VISIBLE);
        updateCooldownLabel(ms);
    }

    /** 由 GateService 的 ticker 每 200ms 调一次，刷新冷却读秒。 */
    public void tick() {
        if (cooldownUntilWall <= 0) return;
        long left = cooldownUntilWall - System.currentTimeMillis();
        if (left <= 0) {
            cooldownUntilWall = 0L;
            nextBtn.setText("下一题  ▶");
            return;
        }
        updateCooldownLabel(left);
    }

    private void updateCooldownLabel(long left) {
        nextBtn.setText("冷却中 " + ((left + 999) / 1000) + " 秒");
    }

    private boolean cooling() {
        return cooldownUntilWall > 0 && cooldownUntilWall > System.currentTimeMillis();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        // 选项不再参与焦点树，选中态自己画
        post(this::paintSelection);
    }

    public void nextQuestion() {
        cooldownUntilWall = 0L;
        q = bank.nextAny(grade, floorGrade, rnd);
        answering = false;
        waitNext = false;
        nextBtn.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
        brandRow.setVisibility(View.VISIBLE);
        stemView.setText(q.stem);
        // 右上角：学科 · 知识点。题库没写知识点时只显示学科，不显示多余的分隔符。
        String meta = q.subject == null ? "" : q.subject.trim();
        if (q.topic != null && !q.topic.trim().isEmpty()) {
            meta = meta.isEmpty() ? q.topic.trim() : meta + " · " + q.topic.trim();
        }
        metaView.setText(meta);
        // 加时间模式顶部要写「已攒多少」，右边让给学科信息会挤，直接收起来
        metaView.setVisibility(meta.isEmpty() || earn ? View.GONE : View.VISIBLE);
        // 用户题库缺当前年级题目时只提示一次，不占额外高度：改写顶部这行说明。
        int need = GateRules.needed(prefs.mode(), prefs.fixedNeed(), prefs.firstNeed(),
                prefs.stepAdd(), prefs.unlockToday());
        String prog = GateRules.progressText(prefs.mode(), prefs.streak(), need,
                prefs.balanceMs(), prefs.minBankMin() * 60_000L);
        if (earn) {
            // 加时间模式不关心门槛，只显示「现在攒了多少、答对能加多少」
            kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            String t = "答题加时间 · 已攒 " + Core.fmt(prefs.balanceMs())
                    + " · 答对 +" + Math.max(1, prefs.perAnswerMin()) + " 分钟";
            kicker.setText(q.fallback ? t + " · 已用默认题库" : t);
        } else if (q.fallback) {
            kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            kicker.setText(prog + " · 已用默认题库");
        } else {
            kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            kicker.setText(prog);
        }
        for (int i = 0; i < 4; i++) opts[i].setText(q.options[i]);
        feedback.setText("");
        sel = 0;
        paintSelection();
    }

    /** 画出选中态。遮罩里所有按键都由 {@link #dispatchKeyEvent} 路由，不靠系统焦点。 */
    private void paintSelection() {
        for (int i = 0; i < 4; i++) {
            TextView t = opts[i];
            boolean on = (i == sel) && !answering;
            t.setBackground(on ? selectedBg() : normalBg());
            t.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        }
    }

    private GradientDrawable normalBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x1AFFFFFF);
        d.setStroke(Ui.dp(getContext(), 1), 0x33FFFFFF);
        d.setCornerRadius(Ui.dp(getContext(), 14));
        return d;
    }

    private GradientDrawable selectedBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFC24A);
        d.setStroke(Ui.dp(getContext(), 3), Ui.ACCENT);
        d.setCornerRadius(Ui.dp(getContext(), 14));
        return d;
    }

    private void select(int i) {
        sel = Math.max(0, Math.min(3, i));
        paintSelection();
    }

    /** 2×2 布局：左右在行内切换，上下在列内切换（到头就绕回，遥控器上少按键更省事）。 */
    private void moveSel(int dx, int dy) {
        if (answering) return;
        int col = sel % 2, row = sel / 2;
        col = (col + dx + 2) % 2;
        row = (row + dy + 2) % 2;
        select(row * 2 + col);
    }

    private String unlockText() {
        int mode = prefs.mode();
        if (mode == GateRules.MODE_BANK) {
            return "攒够 " + prefs.minBankMin() + " 分钟，开始观看";
        }
        if (mode == GateRules.MODE_DELAY) {
            return "答对了，延长 " + extendMin + " 分钟";
        }
        return "连对达标，解锁 " + prefs.extendMin() + " 分钟";
    }

    private void answer(int i) {
        if (answering || pinVisible || q == null || cooling()) return;
        answering = true;
        boolean ok = (i == q.answer);
        if (ok) {
            opts[i].setBackgroundColor(0xFF1F6F45);
            if (earn) {
                // 加时间：只加余额，不判门槛、不改连对，答完自动换下一题接着攒
                long bal = host.onEarnCorrect();
                feedback.setTextColor(Ui.OK);
                String t = "＋" + Math.max(1, prefs.perAnswerMin()) + " 分钟，已攒 " + Core.fmt(bal);
                if (bal >= GateRules.BANK_MAX_MIN * 60_000L) {
                    t = "＋" + Math.max(1, prefs.perAnswerMin()) + " 分钟，已攒 "
                            + Core.fmt(bal) + "（已到上限）";
                }
                feedback.setText(t);
                kicker.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                kicker.setText("答题加时间 · 已攒 " + Core.fmt(bal)
                        + " · 答对 +" + Math.max(1, prefs.perAnswerMin()) + " 分钟");
                postDelayed(this::nextQuestion, 700);
                return;
            }
            // 宿主累加连对/余额；返回 true 时宿主会在反馈展示后摘掉遮罩
            boolean unlocked = host.onCorrect();
            feedback.setTextColor(Ui.OK);
            if (unlocked) {
                feedback.setText(unlockText());
            } else {
                int need = GateRules.needed(prefs.mode(), prefs.fixedNeed(), prefs.firstNeed(),
                        prefs.stepAdd(), prefs.unlockToday());
                feedback.setText(GateRules.progressText(prefs.mode(), prefs.streak(), need,
                        prefs.balanceMs(), prefs.minBankMin() * 60_000L));
                postDelayed(this::nextQuestion, 700);
            }
        } else {
            opts[i].setBackgroundColor(0x8C6B1D1D);
            opts[q.answer].setBackgroundColor(0xFF1F6F45);
            feedback.setTextColor(Ui.BAD);
            String ex = q.explain == null || q.explain.isEmpty() ? "" : "　" + q.explain;
            long cd = host.onWrong();
            feedback.setText("正确答案是「" + q.options[q.answer] + "」" + ex
                    + (cd > 0 ? "　冷却 " + (cd / 1000) + " 秒" : ""));
            // 不自动跳题：解析要留给用户看完，亮出「下一题」，他按 OK / 点这一行才继续。
            // 540dp 高的电视上「解析两行 + 按钮 + 品牌角标」会顶出屏幕，等按钮出现时先收起
            // 提示行与品牌行，返回下一题时恢复。
            if (cd > 0) {
                enterCooldown(cd);
            } else {
                waitNext = true;
                hint.setVisibility(View.GONE);
                brandRow.setVisibility(View.GONE);
                nextBtn.setText("下一题  ▶");
                nextBtn.setVisibility(View.VISIBLE);
            }
        }
    }

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
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_BACK) {
                // 加时间不是锁屏，返回键必须能退出；锁屏模式照旧吞掉
                if (earn) { host.onEarnExit(); return true; }
                return true;
            }
            if (k == KeyEvent.KEYCODE_MENU) {
                if (earn) return true;      // 加时间模式下没有「家长口令」入口
                showPin();
                return true;
            }
            if (waitNext) {
                // 答错后停在解析页：只有 OK / 回车进入下一题，其余按键吞掉，
                // 免得用户以为还能改答案，或者按键漏到下层正在播放的应用。
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (!cooling()) nextQuestion();
                    return true;
                }
                return true;
            }
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
                select(k - KeyEvent.KEYCODE_1);
                answer(sel);
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
