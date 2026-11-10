package app.quizgate.tv;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** 全屏遮罩：出题、判题、家长口令入口。按键全部在根 View 上路由，不依赖焦点树。 */
public class MaskView extends FrameLayout {

    public interface Host {
        void onSolved();
        void onPinOk();
    }

    private final Prefs prefs;
    private final Host host;
    private final QuizBank bank;
    private final Random rnd = new Random();
    private final int grade;
    private final int extendMin;

    private final TextView stemView;
    private final TextView feedback;
    private final TextView[] opts = new TextView[4];
    private final LinearLayout box;

    private QuizBank.Q q;
    private PinPadView pin;
    private RecoveryFlow recovery;
    private boolean pinVisible = false;
    private boolean answering = false;

    public MaskView(Context c, Prefs prefs, QuizBank bank, Host host) {
        super(c);
        this.prefs = prefs;
        this.bank = bank;
        this.host = host;
        this.grade = prefs.grade();
        this.extendMin = prefs.extendMin();

        setBackgroundColor(Ui.BG);
        setFocusable(true);
        setFocusableInTouchMode(true);

        box = Ui.col(c);
        box.setPadding(Ui.dp(c, 72), Ui.dp(c, 40), Ui.dp(c, 72), Ui.dp(c, 40));

        TextView kicker = Ui.label(c, "本次观看时间到", 20, Ui.DIM);
        box.addView(kicker);

        stemView = Ui.title(c, "", 30);
        stemView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(c, 22);
        box.addView(stemView, slp);

        for (int row = 0; row < 2; row++) {
            LinearLayout r = Ui.row(c);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 78));
            rlp.topMargin = Ui.dp(c, row == 0 ? 28 : 16);
            box.addView(r, rlp);
            for (int col = 0; col < 2; col++) {
                final int i = row * 2 + col;
                TextView b = Ui.label(c, "", 24, Ui.TEXT);
                b.setGravity(Gravity.CENTER);
                b.setBackgroundResource(R.drawable.opt_bg);
                b.setFocusable(true);
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
        flp.topMargin = Ui.dp(c, 18);
        box.addView(feedback, flp);

        TextView hint = Ui.label(c, "按 1–4 或方向键选择，OK 确认　·　菜单键 = 家长", 16, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 24);
        box.addView(hint, hlp);

        addView(box, new FrameLayout.LayoutParams(-1, -1));
        nextQuestion();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 悬浮窗的焦点不会自动落到子 View 上，必须自己抢
        requestFocus();
        post(() -> opts[0].requestFocus());
    }

    public void nextQuestion() {
        q = bank.next(grade, rnd);
        answering = false;
        restoreBackgrounds();
        stemView.setText(q.stem);
        for (int i = 0; i < 4; i++) opts[i].setText(q.options[i]);
        feedback.setText("");
        post(() -> opts[0].requestFocus());
    }

    private void restoreBackgrounds() {
        for (TextView t : opts) {
            t.setBackgroundResource(R.drawable.opt_bg);
            t.setTextColor(Ui.TEXT);
        }
    }

    private void answer(int i) {
        if (answering || pinVisible || q == null) return;
        answering = true;
        boolean ok = (i == q.answer);
        if (ok) {
            opts[i].setBackgroundColor(0xFF1F6F45);
            feedback.setTextColor(Ui.OK);
            feedback.setText("答对了，延长 " + extendMin + " 分钟");
            postDelayed(host::onSolved, 700);
        } else {
            opts[i].setBackgroundColor(0x8C6B1D1D);
            opts[q.answer].setBackgroundColor(0xFF1F6F45);
            feedback.setTextColor(Ui.BAD);
            String ex = q.explain == null || q.explain.isEmpty() ? "" : "　" + q.explain;
            feedback.setText("正确答案是「" + q.options[q.answer] + "」" + ex);
            postDelayed(this::nextQuestion, 1800);
        }
    }

    private void showPin() {
        if (pinVisible) return;
        pinVisible = true;
        box.setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        for (TextView t : opts) t.setFocusable(false);
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
        for (TextView t : opts) t.setFocusable(true);
        opts[0].requestFocus();
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
        addView(recovery, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (recovery != null) {
            if (recovery.consumeKey(e)) return true;
            return super.dispatchKeyEvent(e);   // 方向键交给焦点树，否则遥控器在键盘上会失灵
        }
        if (pinVisible) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { hidePin(); return true; }
                int d = PinPadView.digitOf(e);
                if (d >= 0) {
                    pin.append((char) ('0' + d));
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DEL) { pin.backspace(); return true; }
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_BACK) return true;                 // 吞掉返回
            if (k == KeyEvent.KEYCODE_MENU) { showPin(); return true; }  // 家长入口
            if (k >= KeyEvent.KEYCODE_1 && k <= KeyEvent.KEYCODE_4) {
                answer(k - KeyEvent.KEYCODE_1);
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
