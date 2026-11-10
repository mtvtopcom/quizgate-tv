package app.quizgate.tv;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 数字键盘：方向键 + OK 操作，不用系统输入法。
 * 同一个组件承担两件事，靠构造参数区分：
 * <ul>
 *   <li>家长口令：6 位、掩码、失败即清空（日常输入，防肩窥）。</li>
 *   <li>恢复码：8 位、明文、失败不清空（一年用一次，重打 8 位的代价太高；明文才能自己看出错在哪）。
 *       明文不额外泄露风险，因为恢复码用过即作废。</li>
 * </ul>
 */
public class PinPadView extends FrameLayout {

    public interface Listener {
        void onPinSubmit(String pin);
        void onPinCancel();
    }

    public static final int LEN = 6;

    private final Listener listener;
    private final StringBuilder buf = new StringBuilder();
    private final int length;
    private final boolean plain;
    private final boolean keepOnFail;
    private final TextView dots;
    private final TextView msg;
    private final LinearLayout panel;
    private boolean busy = false;

    public PinPadView(Context c, String title, Listener l) {
        this(c, title, LEN, false, false, l);
    }

    public PinPadView(Context c, String title, int length, boolean plain, boolean keepOnFail, Listener l) {
        super(c);
        this.listener = l;
        this.length = length;
        this.plain = plain;
        this.keepOnFail = keepOnFail;
        setBackgroundColor(0xE6000000);
        setClickable(true);          // 吞掉点击，避免穿透到下层选项

        panel = Ui.col(c);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(Ui.dp(c, 40), Ui.dp(c, 32), Ui.dp(c, 40), Ui.dp(c, 32));

        TextView t = Ui.title(c, title, 26);
        panel.addView(t);

        dots = Ui.label(c, display(), 30, Ui.ACCENT);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.dp(c, 18);
        panel.addView(dots, dlp);

        msg = Ui.label(c, "", 18, Ui.DIM);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.dp(c, 10);
        panel.addView(msg, mlp);

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"删除", "0", "确定"}
        };
        for (String[] r : keys) {
            LinearLayout row = Ui.row(c);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.topMargin = Ui.dp(c, 10);
            panel.addView(row, rlp);
            for (String k : r) {
                TextView b = Ui.key(c, k, 24);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(c, 104), Ui.dp(c, 64));
                blp.leftMargin = Ui.dp(c, 6);
                blp.rightMargin = Ui.dp(c, 6);
                row.addView(b, blp);
                b.setOnClickListener(v -> onKey(k));
            }
        }

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.gravity = android.view.Gravity.CENTER;
        addView(panel, plp);
    }

    /** 面板底部追加一行次要入口（如「忘记口令？」）。r 为 null 时只当说明文字，不可聚焦。 */
    public void setFooter(String text, Runnable r) {
        TextView t = Ui.label(getContext(), text, 17, Ui.DIM);
        t.setPadding(Ui.dp(getContext(), 20), Ui.dp(getContext(), 10),
                Ui.dp(getContext(), 20), Ui.dp(getContext(), 10));
        if (r != null) {
            // 必须有可见的焦点态：电视上没有鼠标，看不见焦点就等于这个入口不存在
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackgroundResource(R.drawable.key_bg);
            t.setTextColor(getContext().getColorStateList(R.color.footer_text));
            t.setOnClickListener(v -> r.run());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(getContext(), 14);
        panel.addView(t, lp);
    }

    /**
     * 遥控器数字键 / USB 小键盘 → 0-9，其余返回 -1。
     * 之前只认 KEYCODE_0..9，漏了 KEYCODE_NUMPAD_0..9，导致插数字小键盘按不出来。
     */
    public static int digitOf(KeyEvent e) {
        int k = e.getKeyCode();
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private String display() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (length == 8 && i == 4) sb.append(' ');        // 4-4 分组，肉眼分组不会串位
            if (i < buf.length()) sb.append(plain ? buf.charAt(i) : '●');
            else sb.append('○');
        }
        return sb.toString();
    }

    public void focusFirst() {
        // 必须等一次布局，否则 requestFocus 会失败（真机上表现为遥控器完全没反应）
        post(() -> {
            if (panel.getChildCount() == 0) return;
            for (int i = 2; i < panel.getChildCount(); i++) {
                View rowV = panel.getChildAt(i);
                if (rowV instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) rowV;
                    if (row.getChildCount() > 0) {
                        row.getChildAt(0).requestFocus();
                        return;
                    }
                }
            }
        });
    }

    private void onKey(String k) {
        if (busy) return;
        if ("删除".equals(k)) { backspace(); return; }
        if ("确定".equals(k)) { submit(); return; }
        append(k.charAt(0));
    }

    public void append(char d) {
        if (busy) return;
        if (buf.length() >= length) return;
        buf.append(d);
        dots.setText(display());
        setMessage("");
        if (buf.length() == length) submit();
    }

    public void backspace() {
        if (busy) return;
        if (buf.length() > 0) {
            buf.setLength(buf.length() - 1);
            dots.setText(display());
            setMessage("");
        }
    }

    public void submit() {
        if (busy) return;
        if (buf.length() != length) {
            setMessage("请输入 " + length + " 位数字");
            return;
        }
        busy = true;
        String v = buf.toString();
        if (!keepOnFail) {
            buf.setLength(0);
            dots.setText(display());
        }
        listener.onPinSubmit(v);
    }

    public void fail(String reason) {
        busy = false;
        if (!keepOnFail) {
            buf.setLength(0);
            dots.setText(display());
        }
        setMessage(reason);
    }

    public void reset() {
        busy = false;
        buf.setLength(0);
        dots.setText(display());
        setMessage("");
    }

    /** 错误/提示：非空即标红。 */
    public void setMessage(String s) {
        msg.setText(s);
        msg.setTextColor(s == null || s.isEmpty() ? Ui.DIM : Ui.BAD);
    }

    /** 中性说明（不是错误，别标红）。 */
    public void hint(String s) {
        msg.setText(s);
        msg.setTextColor(Ui.DIM);
    }

    public void cancel() {
        listener.onPinCancel();
    }
}
