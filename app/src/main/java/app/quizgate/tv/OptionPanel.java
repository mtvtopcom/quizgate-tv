package app.quizgate.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 选项面板：一排预设值 + 末尾的「自定义」数字键盘。
 *
 * <p>为什么不用 {@code Dialog} + 焦点树：D2（Android 14）上 Activity 里的浮层反复出现
 * 「{@code requestFocus()} 成功但按钮永远 {@code focused=false}」，方向键和 OK 全判
 * {@code Unhandled key}（1.2.7 的恢复码面板踩过同一条坑）。所以这里和
 * {@link RecoveryFlow.CodePanel} 一样：选中框自绘、按键自己路由，完全不碰焦点树。
 *
 * <p>两级结构：
 * <ol>
 *   <li>预设值（1 / 5 / 10 / 15 / 30）—— 一步到位，家长最常用；</li>
 *   <li>自定义 —— 3×4 数字键盘，遥控器数字键也能直接按，输完按「确定」。</li>
 * </ol>
 */
public class OptionPanel extends FrameLayout {

    public interface OnPick { void onValue(int v); }

    private static final int STEP_CHOOSE = 0, STEP_NUM = 1;
    /** 「自定义」在 keys 里的占位值；预设值都是正数，不会撞上。 */
    private static final int CUSTOM = Integer.MIN_VALUE;

    private final Context ctx;
    private final String title;
    private final String[] labels;
    private final int[] values;
    private final int current;
    private final String unit;
    private final int min, max;
    private final OnPick onPick;
    private final Runnable onCancel;

    private final LinearLayout box;
    private int step = STEP_CHOOSE;
    private int sel = 0;
    private TextView[] keys = new TextView[0];
    /** choose 步骤里每个键对应的值（CUSTOM = 打开数字键盘）；数字键盘步骤里是 0-9/删除/确定。 */
    private int[] keyValues = new int[0];
    private TextView display;
    private TextView msg;
    private final StringBuilder buf = new StringBuilder();

    /**
     * @param labels  与 {@code values} 等长，按钮上的字
     * @param current 当前生效值；不在预设里就落到第一项，家长再点「自定义」
     * @param unit    单位（分钟）；传空串表示不需要「自定义」入口与范围提示
     */
    public OptionPanel(Context c, String title, String[] labels, int[] values, int current,
                       String unit, int min, int max, OnPick onPick, Runnable onCancel) {
        super(c);
        this.ctx = c;
        this.title = title;
        this.labels = labels;
        this.values = values;
        this.current = current;
        this.unit = unit == null ? "" : unit;
        this.min = min;
        this.max = max;
        this.onPick = onPick;
        this.onCancel = onCancel;

        setBackgroundColor(0xE6000000);
        setClickable(true);        // 吞掉点击，避免穿透到背后的设置页
        setFocusable(true);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(c, 44), Ui.dp(c, 26), Ui.dp(c, 44), Ui.dp(c, 26));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(box, lp);

        buildChoose();
    }

    // ---------- 第一级：预设 ----------

    private void buildChoose() {
        step = STEP_CHOOSE;
        sel = 0;
        keys = new TextView[0];
        keyValues = new int[0];
        box.removeAllViews();

        box.addView(Ui.title(ctx, title, 28));

        TextView sub = Ui.label(ctx, "方向键选择 · OK 确认 · BACK 取消", 16, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(ctx, 8);
        box.addView(sub, slp);

        boolean custom = !unit.isEmpty();
        int n = values.length + (custom ? 1 : 0);
        LinearLayout row = Ui.row(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 76));
        rlp.topMargin = Ui.dp(ctx, 20);
        box.addView(row, rlp);

        keys = new TextView[n];
        keyValues = new int[n];
        for (int i = 0; i < n; i++) {
            boolean isCustom = custom && i == values.length;
            TextView b = Ui.label(ctx, isCustom ? "自定义" : labels[i], isCustom ? 22 : 26, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            // 自绘选中：系统的 focused 态和这里的高亮会同时亮两个黄块（实测）
            b.setFocusable(false);
            b.setClickable(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -1, 1f);
            blp.leftMargin = Ui.dp(ctx, 6);
            blp.rightMargin = Ui.dp(ctx, 6);
            row.addView(b, blp);
            final int idx = i;
            b.setOnClickListener(v -> { sel = idx; paint(); choose(idx); });
            keys[i] = b;
            keyValues[i] = isCustom ? CUSTOM : values[i];
            if (!isCustom && values[i] == current) sel = i;
        }

        if (custom) {
            TextView foot = Ui.label(ctx,
                    "「自定义」可输入 " + min + "–" + max + " " + unit + "，用遥控器数字键或屏幕键盘",
                    16, Ui.DIM);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.topMargin = Ui.dp(ctx, 16);
            box.addView(foot, flp);
        }
        paint();
    }

    private void choose(int i) {
        if (i >= 0 && i < values.length) {
            onPick.onValue(values[i]);
            return;
        }
        if (!unit.isEmpty()) showNumpad();
    }

    // ---------- 第二级：数字键盘 ----------

    private void showNumpad() {
        step = STEP_NUM;
        sel = 0;
        buf.setLength(0);
        box.removeAllViews();

        box.addView(Ui.title(ctx, "自定义" + title, 24));

        LinearLayout line = Ui.row(ctx);
        line.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(ctx, 10);
        box.addView(line, llp);

        // 数字和单位放进同一个 TextView：分成两个 View 时基线对不齐，
        // 42sp 的数字旁边会浮着一个掉下去的「分钟」（模拟器实测）。
        display = Ui.label(ctx, "— " + unit, 36, Ui.ACCENT);
        display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        display.setGravity(Gravity.CENTER);
        line.addView(display, new LinearLayout.LayoutParams(-1, -2));

        msg = Ui.label(ctx, "输入 " + min + "–" + max + " " + unit + "，输完按「确定」", 16, Ui.DIM);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(ctx, 4);
        box.addView(msg, mlp);

        String[][] pad = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"删除", "0", "确定"}
        };
        keys = new TextView[12];
        keyValues = new int[12];
        int k = 0;
        for (String[] r : pad) {
            LinearLayout row = Ui.row(ctx);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(ctx, 6);
            box.addView(row, rlp);
            for (String s : r) {
                TextView b = Ui.label(ctx, s, 22, Ui.TEXT);
                b.setGravity(Gravity.CENTER);
                b.setFocusable(false);
                b.setClickable(true);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 54), 1f);
                blp.leftMargin = Ui.dp(ctx, 5);
                blp.rightMargin = Ui.dp(ctx, 5);
                row.addView(b, blp);
                b.setOnClickListener(v -> press(s));
                keys[k] = b;
                keyValues[k] = CUSTOM;
                k++;
            }
        }
        paint();
    }

    private void press(String s) {
        if ("删除".equals(s)) { backspace(); return; }
        if ("确定".equals(s)) { submit(); return; }
        if (s.length() == 1 && Character.isDigit(s.charAt(0))) append(s.charAt(0));
    }

    private void append(char d) {
        if (buf.length() >= 3) return;
        if (buf.length() == 0 && d == '0') return;   // 不出现前导 0
        buf.append(d);
        syncDisplay();
        int v = parse();
        if (v > max) {
            // 超过上限就不收这一位，避免家长按出一串无效数字再被拒
            buf.setLength(buf.length() - 1);
            syncDisplay();
            setMsg("不能超过 " + max + " " + unit, true);
        } else {
            setMsg("输入 " + min + "–" + max + " " + unit + "，输完按「确定」", false);
        }
    }

    private void backspace() {
        if (buf.length() == 0) return;
        buf.setLength(buf.length() - 1);
        syncDisplay();
        setMsg("输入 " + min + "–" + max + " " + unit + "，输完按「确定」", false);
    }

    private void syncDisplay() {
        if (display == null) return;
        display.setText(buf.length() == 0 ? ("— " + unit) : (buf + " " + unit));
    }

    private int parse() {
        if (buf.length() == 0) return 0;
        try { return Integer.parseInt(buf.toString()); } catch (Throwable t) { return 0; }
    }

    private void submit() {
        int v = parse();
        if (v < min || v > max) {
            setMsg("请输入 " + min + "–" + max + " 之间的" + unit, true);
            return;
        }
        onPick.onValue(v);
    }

    private void setMsg(String s, boolean bad) {
        if (msg == null) return;
        msg.setText(s);
        msg.setTextColor(bad ? Ui.BAD : Ui.DIM);
    }

    // ---------- 绘制与按键 ----------

    /** 选中态一律自绘：黄底黑字；当前生效值用黄框标出来。 */
    private void paint() {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) continue;
            boolean on = (i == sel);
            boolean cur = (step == STEP_CHOOSE && keyValues[i] == current && keyValues[i] != CUSTOM);
            if (on) {
                keys[i].setBackground(Ui.box(ctx, Ui.ACCENT, Ui.ACCENT,
                        Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(0xFF20160A);
            } else if (cur) {
                keys[i].setBackground(Ui.box(ctx, 0x1AFDC90C, Ui.ACCENT,
                        Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(Ui.ACCENT);
            } else {
                keys[i].setBackground(Ui.box(ctx, 0x1AFFFFFF, 0x33FFFFFF,
                        Ui.dp(ctx, 1), 12));
                keys[i].setTextColor(Ui.TEXT);
            }
        }
    }

    /**
     * @return true 表示已消费；false 永远不会返回 —— BACK 由本面板自己处理
     *         （数字键盘里退一级，选值列表里关闭面板），不交给 Activity 猜。
     */
    public boolean consumeKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   // 抬键也吞，别漏给背后的页面
        int k = e.getKeyCode();

        if (k == KeyEvent.KEYCODE_BACK) {
            if (step == STEP_NUM) buildChoose();
            else onCancel.run();
            return true;
        }

        if (step == STEP_CHOOSE) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { sel = (sel + keys.length - 1) % keys.length; paint(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { sel = (sel + 1) % keys.length; paint(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                choose(sel);
                return true;
            }
            return true;
        }

        // 数字键盘：遥控器上的数字键直接当输入，方向键移动选中框
        int d = PinPadView.digitOf(e);
        if (d >= 0) { append((char) ('0' + d)); return true; }
        if (k == KeyEvent.KEYCODE_DEL) { backspace(); return true; }

        if (k == KeyEvent.KEYCODE_DPAD_LEFT) { sel = (sel % 3 == 0) ? sel + 2 : sel - 1; paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { sel = (sel % 3 == 2) ? sel - 2 : sel + 1; paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_UP) { sel = (sel < 3) ? sel + 9 : sel - 3; paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) { sel = (sel > 8) ? sel - 9 : sel + 3; paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            press(((TextView) keys[sel]).getText().toString());
            return true;
        }
        return true;
    }
}
