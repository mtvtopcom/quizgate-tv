package app.quizgate.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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

    /** 12 个按键，顺序 = 4 行 × 3 列，供「自己路由方向键」时用（悬浮窗里的焦点树不可靠）。 */
    private final List<TextView> btns = new ArrayList<>();
    /** sel == 12 表示选中面板底部的次要入口（如「忘记口令？」）。 */
    private static final int SEL_FOOTER = 12;
    private TextView footerBtn;
    private int sel = 0;
    /**
     * 是否自己画选中态。
     * <p>只在悬浮窗（遮罩内）开启：那里焦点树失效，必须自绘；在 Activity 里必须保持关闭，
     * 否则系统焦点态和自绘选中态会同时出现两个高亮。
     */
    private boolean selfPaint = false;

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
                btns.add(b);
            }
        }

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.gravity = android.view.Gravity.CENTER;
        addView(panel, plp);
    }

    /**
     * 标题下方的常驻提示（如「卸载留痕」告警）。单独一行，不用 msg ——
     * msg 会被 fail() 的「口令不正确 / 两次不一致」覆盖，而这类告警不能被冲掉。
     */
    public void setNotice(String text) {
        if (text == null || text.length() == 0) return;
        TextView n = Ui.label(getContext(), text, 18, Ui.BAD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.dp(getContext(), 10);
        panel.addView(n, 1);   // 紧跟标题，在圆点之前
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
            footerBtn = t;
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
        if (selfPaint) { paintSel(); return; }   // 悬浮窗里不碰焦点树
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

    /**
     * 悬浮窗里的按键路由。
     *
     * <p>为什么不能靠焦点树：Android 14 的 {@code TYPE_APPLICATION_OVERLAY} 窗口上，
     * 子 View 的 {@code requestFocus()} 会「成功但等于没成功」—— 焦点标记设上了，
     * 按键却仍被 WindowManager 判为 {@code Unhandled key}。实测（MiTV-MFTR0 / Android 14）
     * 遮罩上按方向键只会在 logcat 里刷 Unhandled key，屏幕毫无反应。
     * 所以遮罩内的口令面板由 {@link MaskView} 把方向键/OK 直接转进来，自己维护选中态。
     *
     * @return true 表示已消费，调用方不必再往下传
     */
    public boolean consumeNav(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) { move(-1, 0); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { move(1, 0); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_UP) { move(0, -1); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) { move(0, 1); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (sel == SEL_FOOTER) {
                if (footerBtn != null) footerBtn.performClick();
            } else {
                onKey(btns.get(sel).getText().toString());
            }
            return true;
        }
        return false;
    }

    /** 悬浮窗内使用：自己画选中态，配合 {@link #consumeNav(KeyEvent)}。 */
    public void setSelfPaint(boolean v) {
        selfPaint = v;
        if (v) {
            // key_bg.xml 的 state_focused 是黄色的：只要系统焦点还留在某个键上，
            // 就会和自绘选中态同时亮两个黄块（实测按一次方向键必现）。
            // 悬浮窗里索性彻底不用焦点树：后代一律不可聚焦，焦点清掉。
            panel.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            for (TextView t : btns) t.setFocusable(false);
            if (footerBtn != null) footerBtn.setFocusable(false);
            clearFocus();
        }
        paintSel();
    }

    private void move(int dx, int dy) {
        if (sel == SEL_FOOTER) {
            if (dy < 0) sel = 10;               // 从底部入口往上回到「0」
            paintSel();
            return;
        }
        int row = sel / 3, col = sel % 3;
        if (dy > 0 && row == 3 && footerBtn != null) {
            sel = SEL_FOOTER;                   // 最后一行再往下 = 底部入口
            paintSel();
            return;
        }
        col = (col + dx + 3) % 3;
        row = (row + dy + 4) % 4;
        sel = row * 3 + col;
        paintSel();
    }

    /**
     * 选中态一律自己画，且所有键都换成固定色 drawable。
     * 不能用 key_bg.xml（selector）：它的 state_focused 是黄色的，系统焦点一旦残留，
     * 就会和这里的高亮同时出现两个黄块。
     */
    private void paintSel() {
        if (!selfPaint) return;
        for (int i = 0; i < btns.size(); i++) {
            TextView t = btns.get(i);
            if (i == sel) {
                t.setBackground(accentBg());
                t.setTextColor(0xFF20160A);
            } else {
                t.setBackground(plainBg());
                t.setTextColor(Ui.TEXT);
            }
        }
        if (footerBtn != null) {
            boolean on = (sel == SEL_FOOTER);
            footerBtn.setBackground(on ? accentBg() : plainBg());
            footerBtn.setTextColor(on ? 0xFF20160A : Ui.ACCENT);
        }
    }

    private GradientDrawable accentBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Ui.ACCENT);
        d.setCornerRadius(Ui.dp(getContext(), 10));
        return d;
    }

    private GradientDrawable plainBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x22FFFFFF);
        d.setCornerRadius(Ui.dp(getContext(), 10));
        return d;
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
        sel = 0;
        dots.setText(display());
        setMessage("");
        paintSel();
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
