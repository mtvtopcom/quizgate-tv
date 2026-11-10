package app.quizgate.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 恢复码重置口令：输码 → 设新口令 → 展示新码。
 *
 * <p>顺序刻意是「先设好新口令、再作废旧恢复码」，两件事在 {@link Prefs#resetPinAndRecovery}
 * 的同一个 commit 里完成：中途退出不会出现「码废了、口令也没设上」的死锁。
 *
 * <p>恢复成功 = 家长接管，调用方必须结束当前会话（不保留剩余时间），
 * 否则孩子一旦猜到码就等于免费续杯。
 */
public class RecoveryFlow extends FrameLayout {

    public interface Done {
        /** @param pinReset true = 新口令已落盘（家长完成了重置）；false = 中途放弃 */
        void onDone(boolean pinReset);
    }

    private static final int STEP_CODE = 0, STEP_PIN = 1, STEP_SHOW = 2;

    private final Prefs prefs;
    private final Done done;
    private int step = STEP_CODE;
    private PinPadView pad;
    private String firstPin;
    /** 悬浮窗（遮罩）里焦点树不可靠：键盘走自绘选中 + 自己路由方向键。Activity 里保持 false。 */
    private boolean selfPaint = false;
    private Runnable showConfirm;

    public RecoveryFlow(Context c, Prefs prefs, Done done) {
        super(c);
        this.prefs = prefs;
        this.done = done;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        stepCode();
    }

    /** 遮罩内必须调用；Activity 里的恢复流程沿用系统焦点树。 */
    public void setSelfPaint(boolean v) {
        selfPaint = v;
        if (pad != null) {
            pad.setSelfPaint(v);
            pad.focusFirst();
        }
    }

    // ---------- 第一步：输入恢复码 ----------

    private void stepCode() {
        step = STEP_CODE;
        firstPin = null;
        pad = new PinPadView(getContext(), "输入恢复码", Recovery.LEN, true, true,
                new PinPadView.Listener() {
                    @Override public void onPinSubmit(String s) {
                        if (!prefs.hasRecoveryCode()) {
                            pad.fail("这台电视还没生成恢复码（v1.1 起才有），只能卸载重装");
                            return;
                        }
                        long r = prefs.verifyRecoveryCode(s);
                        if (r == 0) { stepPin(); return; }
                        if (r == -2) pad.fail("末位对不上，检查是否抄错或按错");
                        else if (r > 0) pad.fail("试错次数过多，请 " + mins(r) + " 分钟后再试");
                        else pad.fail("恢复码不正确");
                    }
                    @Override public void onPinCancel() { finish(false); }
                });
        pad.hint("8 位数字，抄自你拍的那张照片");
        pad.setFooter("没拍过照片？只能卸载重装，设置会丢", null);
        mount(pad);
    }

    // ---------- 第二步：设新口令 ----------

    private void stepPin() {
        step = STEP_PIN;
        firstPin = null;
        pad = new PinPadView(getContext(), "设置新的 6 位口令", PinPadView.LEN, false, false,
                new PinPadView.Listener() {
                    @Override public void onPinSubmit(String s) {
                        if (firstPin == null) {
                            firstPin = s;
                            pad.fail("再输入一次确认");
                            return;
                        }
                        if (!firstPin.equals(s)) {
                            firstPin = null;
                            pad.fail("两次不一致，请重新设置");
                            return;
                        }
                        String fresh = Recovery.generate();
                        if (!prefs.resetPinAndRecovery(s, fresh)) {
                            firstPin = null;
                            pad.fail("写入失败，请重试");
                            return;
                        }
                        stepShow(fresh);
                    }
                    @Override public void onPinCancel() { finish(false); }
                });
        pad.hint("恢复码校验通过，接着换成你记得住的口令");
        mount(pad);
    }

    // ---------- 第三步：展示新恢复码 ----------

    private void stepShow(String code) {
        step = STEP_SHOW;
        showConfirm = () -> { prefs.markRecoverySaved(); finish(true); };
        View p = codePanel(getContext(), code, showConfirm, null);
        removeAllViews();
        addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------- 收尾 ----------

    private void finish(boolean ok) {
        done.onDone(ok);
    }

    private void mount(View v) {
        removeAllViews();
        addView(v, new FrameLayout.LayoutParams(-1, -1));
        if (v instanceof PinPadView) {
            PinPadView p = (PinPadView) v;
            p.setSelfPaint(selfPaint);
            p.focusFirst();
        }
    }

    /**
     * 只处理「字母数字/返回」这类语义键；方向键必须放行，让焦点树自己走，
     * 否则遥控器在键盘上会完全失灵。
     */
    public boolean consumeKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (selfPaint && !down) return true;      // 悬浮窗里抬键也吞掉，别漏给下层应用
        if (!down) return false;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (step == STEP_PIN) { stepCode(); return true; }   // 码还没作废，可以退回去重输
            finish(step == STEP_SHOW);                           // 码已落库，退出等于「稍后再拍」
            return true;
        }
        if (selfPaint) {
            // 悬浮窗里不靠焦点树：方向键 / OK 交给键盘自己路由
            if (step == STEP_SHOW) {
                if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_NUMPAD_ENTER) && showConfirm != null) {
                    showConfirm.run();
                }
                return true;
            }
            if (pad != null) {
                if (pad.consumeNav(e)) return true;
                int d = PinPadView.digitOf(e);
                if (d >= 0) { pad.append((char) ('0' + d)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { pad.backspace(); return true; }
            }
            return true;
        }
        // Activity：方向键必须放行给焦点树，否则遥控器在键盘上会完全失灵
        if (step == STEP_SHOW || pad == null) return false;
        int d = PinPadView.digitOf(e);
        if (d >= 0) { pad.append((char) ('0' + d)); return true; }
        if (k == KeyEvent.KEYCODE_DEL) { pad.backspace(); return true; }
        return false;
    }

    private static long mins(long ms) { return Math.max(1, ms / 60_000L); }

    // ---------- 恢复码展示页（设置页也会直接用） ----------

    /**
     * @param onConfirm 点「我已拍照保存」后调用
     * @param onRegen   传 null 则不显示「换一个」（恢复流程的收尾阶段没有旧码可换）
     */
    public static FrameLayout codePanel(Context c, String code, Runnable onConfirm, Runnable onRegen) {
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(c, 56), Ui.dp(c, 36), Ui.dp(c, 56), Ui.dp(c, 36));

        box.addView(Ui.title(c, "请拍下这串恢复码", 30));

        TextView num = Ui.label(c, Recovery.format(code), 52, Ui.ACCENT);
        num.setTypeface(Typeface.MONOSPACE);
        num.setLetterSpacing(0.14f);
        num.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 8));
        box.addView(num);

        box.addView(line(c, "忘了家长口令时，在「忘记口令？」里输入这 8 位数字，就能重设口令。"));
        box.addView(line(c, "电视里的设置和计时都不会丢。"));
        box.addView(line(c, "它只存在这台电视上，不上传、不联网；用过一次就失效。"));
        TextView warn = line(c, "丢了它 + 忘了口令 = 只能卸载重装。");
        warn.setTextColor(Ui.BAD);
        box.addView(warn);

        TextView ok = Ui.key(c, "我已拍照保存", 24);
        ok.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 66));
        olp.topMargin = Ui.dp(c, 26);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> onConfirm.run());

        if (onRegen != null) {
            TextView re = Ui.key(c, "换一组（旧码立即作废）", 18);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 52));
            rlp.topMargin = Ui.dp(c, 10);
            box.addView(re, rlp);
            re.setOnClickListener(v -> onRegen.run());
        }

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.CENTER;
        root.addView(box, blp);

        ok.post(ok::requestFocus);
        return root;
    }

    private static TextView line(Context c, String s) {
        TextView t = Ui.label(c, s, 18, Ui.DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }
}
