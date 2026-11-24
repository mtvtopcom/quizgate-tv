package app.quizgate.tv;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 题目科目多选面板（1.5.0）。
 *
 * <p>语文/数学/英语三科可同时勾选，到点锁屏在已选科目里轮流出题；每个科目
 * 显示当前年级的题数，0 题显示「暂无数据」且不能勾。勾完按「保存」才落盘，
 * 中途按 BACK 退回年级选择、不改变原设置。
 *
 * <p>和 {@link OptionPanel} 一样自绘选中态、自路由按键：D2/Android 14 上浮层
 * 里的 {@code requestFocus()} 是假成功，交给焦点树遥控器会失灵。
 */
public final class SubjectPanel extends FrameLayout {

    public interface Listener {
        void onSave(int mask);
        void onBack();
    }

    private final Context ctx;
    private final String[] labels;
    private final String[] subs;
    private final boolean[] enabled;
    /** 只允许「当前年级确有题目」的科目进入 mask：否则会存下数学/英语但一题都抽不到。 */
    private final int allowedMask;
    private final Listener listener;

    private final LinearLayout box;
    private final TextView[] keys = new TextView[3];
    private TextView saveBtn;
    private TextView note;
    private int mask;
    /** 0–2 = 三个科目，3 = 保存。 */
    private int sel = 0;

    public SubjectPanel(Context c, String title, String sub, String[] labels, String[] subs,
                        int mask, Listener l) {
        super(c);
        this.ctx = c;
        this.labels = labels;
        this.subs = subs;
        this.listener = l;
        this.enabled = new boolean[labels.length];
        int allowed = 0;
        for (int i = 0; i < enabled.length; i++) {
            enabled[i] = subs == null || i >= subs.length || !"暂无数据".equals(subs[i]);
            if (enabled[i]) allowed |= (1 << i);
        }
        this.allowedMask = allowed;
        // 老设置可能是「三科全选」，但换到只有语文的题库后数学/英语并没有题：
        // 打开面板时清掉这些无效勾选，避免存下一条永远抽不到题的配置。
        this.mask = (mask & 0x7) & allowed;
        if (this.mask == 0) {
            for (int i = 0; i < enabled.length; i++) {
                if (enabled[i]) { this.mask = 1 << i; break; }
            }
        }
        setBackgroundColor(0xE6000000);
        setClickable(true);
        setFocusable(true);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(c, 44), Ui.dp(c, 24), Ui.dp(c, 44), Ui.dp(c, 24));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(box, lp);

        box.addView(Ui.title(c, title, 28));

        TextView line = Ui.label(c, sub == null ? "" : sub, 17, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(c, 6);
        box.addView(line, slp);

        TextView hint = Ui.label(c, "方向键移动 · OK 勾选 · 选完按「保存」· BACK 取消", 15, Ui.DIM);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Ui.dp(c, 4);
        box.addView(hint, hlp);

        LinearLayout row = Ui.row(c);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 96));
        rlp.topMargin = Ui.dp(c, 18);
        box.addView(row, rlp);
        for (int i = 0; i < labels.length; i++) {
            TextView b = Ui.label(c, "", 22, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setLineSpacing(Ui.dp(c, 3), 1f);
            b.setFocusable(false);
            b.setClickable(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -1, 1f);
            blp.leftMargin = Ui.dp(c, 6);
            blp.rightMargin = Ui.dp(c, 6);
            row.addView(b, blp);
            final int idx = i;
            b.setOnClickListener(v -> { sel = idx; paint(); toggle(idx); });
            keys[i] = b;
        }

        note = Ui.label(c, "", 15, Ui.BAD);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(c, 6);
        box.addView(note, nlp);
        note.setVisibility(GONE);

        saveBtn = Ui.label(c, "保存", 20, Ui.TEXT);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setClickable(true);
        saveBtn.setOnClickListener(v -> { sel = 3; paint(); save(); });
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 54));
        svlp.topMargin = Ui.dp(c, 14);
        box.addView(saveBtn, svlp);

        paint();
    }

    private boolean isOn(int i) {
        return (mask & (1 << i)) != 0;
    }

    private void toggle(int i) {
        if (!enabled[i]) {
            setNote("「" + labels[i] + "」在当前年级暂无题目");
            return;
        }
        mask ^= (1 << i);
        paint();
    }

    private void save() {
        int m = mask & allowedMask;
        if (m == 0) {
            setNote("至少选一科，否则到点没有题可出");
            return;
        }
        listener.onSave(m);
    }

    private void setNote(String s) {
        note.setText(s);
        note.setVisibility(VISIBLE);
    }

    private CharSequence keyText(int i) {
        String first = (isOn(i) ? "✓ " : "") + labels[i];
        if (subs == null || i >= subs.length || subs[i] == null || subs[i].isEmpty()) return first;
        SpannableStringBuilder sb = new SpannableStringBuilder(first);
        sb.append('\n').append(subs[i]);
        sb.setSpan(new AbsoluteSizeSpan(Ui.dp(ctx, 13)), first.length() + 1, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void paint() {
        note.setVisibility(GONE);
        for (int i = 0; i < keys.length; i++) {
            boolean on = i == sel;
            boolean picked = isOn(i);
            keys[i].setText(keyText(i));
            if (on) {
                keys[i].setBackground(Ui.box(ctx, Ui.ACCENT, Ui.ACCENT, Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(enabled[i] ? 0xFF20160A : 0xFF6B7075);
            } else if (picked) {
                keys[i].setBackground(Ui.box(ctx, 0x1AFDC90C, Ui.ACCENT, Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(Ui.ACCENT);
            } else {
                keys[i].setBackground(Ui.box(ctx, 0x1AFFFFFF, 0x33FFFFFF, Ui.dp(ctx, 1), 12));
                keys[i].setTextColor(enabled[i] ? Ui.TEXT : 0xFF5A5F64);
            }
        }
        if (sel == 3) {
            saveBtn.setBackground(Ui.box(ctx, Ui.ACCENT, Ui.ACCENT, Ui.dp(ctx, 2), 12));
            saveBtn.setTextColor(0xFF20160A);
        } else {
            saveBtn.setBackground(Ui.box(ctx, 0x1AFFFFFF, 0x33FFFFFF, Ui.dp(ctx, 1), 12));
            saveBtn.setTextColor(Ui.ACCENT);
        }
    }

    public boolean consumeKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();

        if (k == KeyEvent.KEYCODE_BACK) {
            listener.onBack();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            sel = sel == 3 ? 2 : (sel + keys.length - 1) % keys.length;
            paint();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            sel = sel == 3 ? 0 : (sel + 1) % keys.length;
            paint();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (sel != 3) { sel = 3; paint(); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            if (sel == 3) { sel = 0; paint(); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (sel == 3) save();
            else toggle(sel);
            return true;
        }
        // 遥控器数字键 1/2/3 直接勾选，少按几次方向键
        int d = PinPadView.digitOf(e);
        if (d >= 1 && d <= keys.length) {
            sel = d - 1;
            paint();
            toggle(sel);
            return true;
        }
        return true;
    }
}
