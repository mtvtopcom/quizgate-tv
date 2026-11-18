package app.quizgate.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
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

    private static final String TAG = "QuizGate";
    /** 答错后的累计冷却档位：第 1/2/3/4+ 次答错分别冷却 5/10/20/30 秒。 */
    private static final int[] COOLDOWN_SEC = {5, 10, 20, 30};
    private static final String KICKER_SUFFIX = "，本次观看时间到，请答题";
    private static final String HINT_DEFAULT = "按 1–4 或方向键选择，OK 确认　·　菜单键 = 家长";

    public interface Host {
        void onSolved();
        void onPinOk();
    }

    private final Prefs prefs;
    private final Host host;
    private final QuizBank bank;
    private final Random rnd = new Random();
    private final int grade;
    private final int floorGrade;
    /** 配置的科目：0–2 固定科目，3 = 混合（每题独立抽科）。 */
    private final int subject;
    private final int extendMin;
    /** 连对几题才延时，来自设置页「连对题数」。 */
    private final int requiredCorrect;
    /** 设置页「小朋友称呼」，默认「小朋友」；源码里不写真实姓名。 */
    private final String childName;

    private final TextView kickerView;
    private final TextView stemView;
    private final TextView feedback;
    private final TextView hintView;
    private final TextView[] opts = new TextView[4];
    private final LinearLayout box;

    private QuizBank.Q q;
    private PinPadView pin;
    private RecoveryFlow recovery;
    private boolean pinVisible = false;
    private boolean answering = false;
    /** 窗口被移除后，延迟回调不能再发延时或继续出题（家长可能在 700ms 内结束了会话）。 */
    private boolean detached = false;
    /** 当前选中的选项。遮罩在悬浮窗里，不能靠焦点树，只能自己记。 */
    private int sel = 0;

    private final Runnable cooldownTick = new Runnable() {
        @Override public void run() {
            if (!detached && answering) showCooldown();
        }
    };
    private final Runnable advanceQuestion = () -> {
        if (!detached) nextQuestion();
    };
    /** 在构造器里初始化：lambda 捕获构造器参数，避免引用尚未赋值的 blank final 字段。 */
    private final Runnable grantExtension;

    public MaskView(Context c, Prefs prefs, QuizBank bank, Host host) {
        super(c);
        this.prefs = prefs;
        this.bank = bank;
        this.host = host;
        this.grantExtension = () -> {
            if (!detached) host.onSolved();
        };
        this.grade = prefs.grade();
        this.floorGrade = prefs.floorGrade();
        this.subject = prefs.subject();
        this.extendMin = prefs.extendMin();
        this.requiredCorrect = prefs.requiredCorrect();
        this.childName = prefs.childName();

        setBackgroundColor(Ui.BG);
        setFocusable(true);
        setFocusableInTouchMode(true);

        box = Ui.col(c);
        // 1080p 电视只有 540dp 高：右下角加了品牌角标后，竖向留白必须收一收，
        // 否则题干换两行时品牌会被挤出屏幕（模拟器实测）。
        box.setPadding(Ui.dp(c, 72), Ui.dp(c, 28), Ui.dp(c, 72), Ui.dp(c, 28));

        kickerView = Ui.label(c, childName + KICKER_SUFFIX, 20, Ui.DIM);
        box.addView(kickerView);

        stemView = Ui.title(c, "", 30);
        stemView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
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
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Ui.dp(c, 14);
        box.addView(feedback, flp);

        hintView = Ui.label(c, HINT_DEFAULT, 16, Ui.DIM);
        hintView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 16);
        box.addView(hintView, hlp);

        // 右下角品牌角标：家长在出题这一刻看到的还是「答题续时」自己的界面。
        // 放进内容流而不是盖在右下角 —— 1080p 下题干换两行时贴角会压到提示行。
        LinearLayout brand = Ui.row(c);
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

        // 进程随时可能被强停：先从磁盘恢复冷却/连对进度，再决定显示什么。
        if (prefs.cooldownLeftMs() > 0) {
            showCooldown();
        } else if (prefs.correctStreak() >= requiredCorrect) {
            // 上一次进程死在「已连对设定题数、延时还没发」的 700ms 窗口里：补发。
            answering = true;
            feedback.setTextColor(Ui.OK);
            feedback.setText("答对了（" + requiredCorrect + "/" + requiredCorrect
                    + "），延长 " + extendMin + " 分钟");
            Log.i(TAG, "quiz_resume " + requiredCorrect + "/"
                    + requiredCorrect + " -> extend " + extendMin + "min");
            postDelayed(grantExtension, 700);
        } else {
            nextQuestion();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        requestFocus();
        // 选项不再参与焦点树，选中态自己画
        post(this::paintSelection);
    }

    @Override
    protected void onDetachedFromWindow() {
        detached = true;
        removeCallbacks(cooldownTick);
        removeCallbacks(advanceQuestion);
        removeCallbacks(grantExtension);
        super.onDetachedFromWindow();
    }

    public void nextQuestion() {
        removeCallbacks(cooldownTick);
        int subjectIdx = subject;
        if (subjectIdx == Prefs.SUBJECT_MIXED) subjectIdx = pickMixedSubject();
        q = bank.next(Prefs.SUBJECT_KEYS[subjectIdx], grade, floorGrade, rnd);
        answering = false;
        stemView.setText(q.stem);
        for (int i = 0; i < 4; i++) opts[i].setText(q.options[i]);
        String kicker = childName + KICKER_SUFFIX;
        kickerView.setText(subject == Prefs.SUBJECT_MIXED
                ? kicker + "　·　" + Prefs.SUBJECT_NAMES[subjectIdx]
                : kicker);
        int streak = prefs.correctStreak();
        if (streak > 0) {
            feedback.setTextColor(Ui.DIM);
            feedback.setText("已连对 " + streak + "/" + requiredCorrect
                    + " 题 · 再答对 " + Math.max(0, requiredCorrect - streak) + " 题延时");
        } else {
            feedback.setText("");
        }
        hintView.setText(HINT_DEFAULT);
        sel = 0;
        paintSelection();
        Log.i(TAG, "quiz_next subject=" + Prefs.SUBJECT_NAMES[subjectIdx]
                + " streak=" + streak + " wrong=" + prefs.wrongCount());
    }

    /** 混合模式：每题独立按 语文:数学:英语 权重抽科；没有袋子状态，进程被杀也不会重置比例。 */
    private int pickMixedSubject() {
        int[] w = prefs.mixedWeights();
        int total = w[0] + w[1] + w[2];
        int r = rnd.nextInt(total);
        if (r < w[0]) return Prefs.SUBJECT_CHINESE;
        if (r < w[0] + w[1]) return Prefs.SUBJECT_MATH;
        return Prefs.SUBJECT_ENGLISH;
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

    private void answer(int i) {
        if (answering || pinVisible || q == null) return;
        answering = true;
        boolean ok = (i == q.answer);
        if (ok) {
            int streak = prefs.correctStreak() + 1;
            if (streak >= requiredCorrect) {
                prefs.setCorrectStreak(requiredCorrect);
                opts[i].setBackgroundColor(0xFF1F6F45);
                feedback.setTextColor(Ui.OK);
                feedback.setText("答对了（" + requiredCorrect + "/"
                        + requiredCorrect + "），延长 " + extendMin + " 分钟");
                Log.i(TAG, "quiz_correct " + requiredCorrect + "/"
                        + requiredCorrect + " -> extend " + extendMin + "min");
                postDelayed(grantExtension, 700);
            } else {
                prefs.setCorrectStreak(streak);
                opts[i].setBackgroundColor(0xFF1F6F45);
                feedback.setTextColor(Ui.OK);
                feedback.setText("答对了（" + streak + "/" + requiredCorrect
                        + "）· 再答对 " + (requiredCorrect - streak) + " 题才延时");
                Log.i(TAG, "quiz_correct " + streak + "/" + requiredCorrect);
                postDelayed(advanceQuestion, 900);
            }
        } else {
            int next = prefs.wrongCount() + 1;
            int sec = COOLDOWN_SEC[Math.min(next - 1, COOLDOWN_SEC.length - 1)];
            int wrong = prefs.noteWrongAnswer(sec * 1000L);
            opts[i].setBackgroundColor(0x8C6B1D1D);
            opts[q.answer].setBackgroundColor(0xFF1F6F45);
            Log.i(TAG, "quiz_wrong n=" + wrong + " cooldown=" + sec + "s");
            showCooldown();
        }
    }

    /**
     * 答错后的冷却。剩余时间从 {@link Prefs#cooldownLeftMs()} 读，所以进程被强停后
     * 由 NotifProbe 复活的新 MaskView 会接着倒计时，而不是从 5 秒重新开始。
     */
    private void showCooldown() {
        long left = prefs.cooldownLeftMs();
        if (left <= 0L) {
            prefs.clearCooldown();
            nextQuestion();
            return;
        }
        answering = true;
        int sec = (int) ((left + 999L) / 1000L);
        if (q != null) {
            // 刚答错：题目和正确选项还留在屏幕上，冷却期间顺便把解析给家长/孩子看。
            feedback.setTextColor(Ui.BAD);
            feedback.setText("答错了 · " + sec + " 秒后可继续");
            String ex = q.explain == null ? "" : q.explain.trim();
            if (ex.length() > 40) ex = ex.substring(0, 40) + "…";
            hintView.setText("正确答案是「" + q.options[q.answer] + "」"
                    + (ex.isEmpty() ? "" : "　" + ex));
        } else {
            // 进程死在冷却中途：没有题目可显示，只保留倒计时和家长入口。
            feedback.setTextColor(Ui.DIM);
            feedback.setText("冷却中 · " + sec + " 秒后可继续");
            hintView.setText("冷却结束后出下一题　·　菜单键 = 家长");
            stemView.setText("");
            for (TextView o : opts) o.setText("");
        }
        removeCallbacks(cooldownTick);
        postDelayed(cooldownTick, 250L);
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
