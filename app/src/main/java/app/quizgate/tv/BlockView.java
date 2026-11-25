package app.quizgate.tv;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全局锁定变体（每日时段外 / 强制休息）用的全屏遮罩。
 * 比答题遮罩简单：只显示原因和倒计时；家长入口仍是菜单键口令。
 */
public class BlockView extends FrameLayout {
    public interface Host {
        void onPinOk();
        Prefs prefs();
    }

    private final Host host;
    private final TextView title;
    private final TextView detail;
    private final TextView count;
    private PinPadView pin;
    private RecoveryFlow recovery;
    private boolean pinVisible = false;

    public BlockView(Context c, Host host) {
        super(c);
        this.host = host;
        setBackgroundColor(Ui.BG);
        setFocusable(true);
        setFocusableInTouchMode(true);

        LinearLayout box = Ui.col(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(c, 72), Ui.dp(c, 40), Ui.dp(c, 72), Ui.dp(c, 40));

        title = Ui.title(c, "", 32);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        detail = Ui.label(c, "", 20, Ui.DIM);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(3);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = Ui.dp(c, 20);
        box.addView(detail, dlp);

        count = Ui.label(c, "", 26, Ui.ACCENT);
        count.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(c, 24);
        box.addView(count, clp);

        TextView hint = Ui.label(c, "菜单键 = 家长", 16, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 28);
        box.addView(hint, hlp);

        addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    public void setInfo(String t, String d, long leftMs) {
        title.setText(t == null ? "" : t);
        detail.setText(d == null ? "" : d);
        if (leftMs > 0) {
            long sec = (leftMs + 999) / 1000;
            count.setText(sec / 60 + " 分 " + (sec % 60) + " 秒后继续");
        } else {
            count.setText("");
        }
    }

    private void showPin() {
        if (pinVisible) return;
        pinVisible = true;
        if (pin == null) {
            pin = new PinPadView(getContext(), "家长口令（6 位）", new PinPadView.Listener() {
                @Override public void onPinSubmit(String s) {
                    long r = host.prefs().verifyPin(s);
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
        pin.setSelfPaint(true);
        addView(pin, new FrameLayout.LayoutParams(-1, -1));
        pin.reset();
        pin.focusFirst();
    }

    private void hidePin() {
        if (!pinVisible) return;
        pinVisible = false;
        if (pin != null && pin.getParent() != null) removeView(pin);
    }

    private void openRecovery() {
        if (recovery != null) return;
        if (pin != null && pin.getParent() != null) removeView(pin);
        pinVisible = false;
        recovery = new RecoveryFlow(getContext(), host.prefs(), ok -> {
            if (recovery != null && recovery.getParent() != null) removeView(recovery);
            recovery = null;
            if (ok) host.onPinOk();
            else showPin();
        });
        recovery.setSelfPaint(true);
        addView(recovery, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (recovery != null) {
            recovery.consumeKey(e);
            return true;
        }
        if (pinVisible) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { hidePin(); return true; }
                if (pin.consumeNav(e)) return true;
                int d = PinPadView.digitOf(e);
                if (d >= 0) { pin.append((char) ('0' + d)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { pin.backspace(); return true; }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_MENU) { showPin(); return true; }
            if (k == KeyEvent.KEYCODE_BACK) return true;
            return true;   // 时段外/休息期一律不把按键漏给下层播放器
        }
        return super.dispatchKeyEvent(e);
    }
}
