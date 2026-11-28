package app.quizgate.tv;

import android.content.Context;
import android.graphics.Typeface;
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
    private final String sub;
    private final String[] labels;
    /** 与 labels 等长；第二行小字（题数）。"暂无数据" 表示该选项没有题目、不可选。 */
    private final String[] subs;
    private final int[] values;
    private final int current;
    private final String unit;
    private final int min, max;
    /** true = 「自定义」输入钟点（X 点 X 分）而不是一个分钟数。 */
    private final boolean clock;
    /** 只有「看电视时间 · 结束」允许 24:00（= 1440 分钟，表示到午夜）。 */
    private final boolean allowEnd24;
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
    private TextView note;
    private final StringBuilder buf = new StringBuilder();
    /** 钟点输入：先输小时，够了再输分钟；两位小时 0–23，分钟 0–59。 */
    private final StringBuilder hourBuf = new StringBuilder();
    private final StringBuilder minBuf = new StringBuilder();
    private boolean minuteStage;

    /**
     * @param labels  与 {@code values} 等长，按钮上的字
     * @param current 当前生效值；不在预设里就落到第一项，家长再点「自定义」
     * @param unit    单位（分钟）；传空串表示不需要「自定义」入口与范围提示
     */
    public OptionPanel(Context c, String title, String[] labels, int[] values, int current,
                       String unit, int min, int max, OnPick onPick, Runnable onCancel) {
        this(c, title, "", labels, null, values, current, unit, min, max,
                false, false, onPick, onCancel);
    }

    /**
     * 钟点版：预设值仍用分钟数（labels 显示成 08:00 这样），只有「自定义」改成
     * 按「X 点 X 分」输入。家长不用再心算「19 点 30 分 = 1170 分钟」。
     */
    public OptionPanel(Context c, String title, String[] labels, int[] values, int current,
                       String unit, int min, int max, boolean clock, boolean allowEnd24,
                       OnPick onPick, Runnable onCancel) {
        this(c, title, "", labels, null, values, current, unit, min, max,
                clock, allowEnd24, onPick, onCancel);
    }

    /**
     * 钟点版 + 说明行：「看电视时间 · 开始」用它交代「这段时间之外会锁屏」，
     * 光看标题和时间范围，家长不一定知道这设置的是"允许看"还是"禁止看"。
     */
    public OptionPanel(Context c, String title, String sub, String[] labels, int[] values,
                       int current, String unit, int min, int max, boolean clock,
                       boolean allowEnd24, OnPick onPick, Runnable onCancel) {
        this(c, title, sub, labels, null, values, current, unit, min, max,
                clock, allowEnd24, onPick, onCancel);
    }

    /**
     * 带题数的选值面板（题目年级三级选择用）。
     *
     * @param sub  标题下的说明，如「小学共 9,659 题」
     * @param subs 每个按钮第二行的小字（题数）；传 {@code "暂无数据"} 的按钮不可选
     */
    public OptionPanel(Context c, String title, String sub, String[] labels, String[] subs,
                       int[] values, int current, String unit, int min, int max,
                       OnPick onPick, Runnable onCancel) {
        this(c, title, sub, labels, subs, values, current, unit, min, max,
                false, false, onPick, onCancel);
    }

    private OptionPanel(Context c, String title, String sub, String[] labels, String[] subs,
                        int[] values, int current, String unit, int min, int max,
                        boolean clock, boolean allowEnd24, OnPick onPick, Runnable onCancel) {
        super(c);
        this.ctx = c;
        this.title = title;
        this.sub = sub == null ? "" : sub;
        this.labels = labels;
        this.subs = subs;
        this.values = values;
        this.current = current;
        this.unit = unit == null ? "" : unit;
        this.min = min;
        this.max = max;
        this.clock = clock;
        this.allowEnd24 = allowEnd24;
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

        if (!this.sub.isEmpty()) {
            TextView line = Ui.label(ctx, this.sub, 17, Ui.DIM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(ctx, 6);
            box.addView(line, lp);
        }

        TextView hint = Ui.label(ctx, "方向键选择 · OK 确认 · BACK 取消", 15, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(ctx, 6);
        box.addView(hint, slp);

        boolean custom = !unit.isEmpty();
        int n = values.length + (custom ? 1 : 0);
        LinearLayout row = Ui.row(ctx);
        // 带题数时按钮要高一些：两行文字（名称 22sp + 题数 13sp）在 76dp 里会挤。
        int rowH = subs == null ? 76 : 96;
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, rowH));
        rlp.topMargin = Ui.dp(ctx, 18);
        box.addView(row, rlp);

        keys = new TextView[n];
        keyValues = new int[n];
        for (int i = 0; i < n; i++) {
            boolean isCustom = custom && i == values.length;
            String line2 = (!isCustom && subs != null && i < subs.length) ? subs[i] : null;
            TextView b = Ui.label(ctx, "", isCustom ? 22 : (line2 == null ? 26 : 22), Ui.TEXT);
            b.setText(keyText(isCustom ? "自定义" : labels[i], line2));
            b.setGravity(Gravity.CENTER);
            b.setLineSpacing(Ui.dp(ctx, 3), 1f);
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

        note = Ui.label(ctx, "", 15, Ui.BAD);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(ctx, 6);
        box.addView(note, nlp);
        note.setVisibility(GONE);

        if (custom) {
            String footText = clock
                    ? "「自定义」按「几点几分」输入，例如 19 点 30 分；遥控器数字键可直接输"
                    : "「自定义」可输入 " + min + "–" + max + " " + unit + "，用遥控器数字键或屏幕键盘";
            TextView foot = Ui.label(ctx, footText, 16, Ui.DIM);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.topMargin = Ui.dp(ctx, 16);
            box.addView(foot, flp);
        }
        paint();
    }

    private void choose(int i) {
        if (i >= 0 && i < values.length) {
            if (isDisabled(i)) {
                setNote("「" + labels[i] + "」暂无题目，换一个试试");
                return;
            }
            onPick.onValue(values[i]);
            return;
        }
        if (!unit.isEmpty()) showNumpad();
    }

    /** subs 里写「暂无数据」的按钮不可选（0 题的学段/年级/科目）。 */
    private boolean isDisabled(int i) {
        return subs != null && i >= 0 && i < subs.length && "暂无数据".equals(subs[i]);
    }

    /** 按钮两行文字：名称 + 题数（第二行小一号）；没有题数时保持单行。 */
    private CharSequence keyText(String label, String line2) {
        if (line2 == null || line2.isEmpty()) return label;
        SpannableStringBuilder sb = new SpannableStringBuilder(label);
        sb.append('\n').append(line2);
        sb.setSpan(new AbsoluteSizeSpan(Ui.dp(ctx, 13)), label.length() + 1, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void setNote(String s) {
        if (note == null) return;
        note.setText(s);
        note.setVisibility(VISIBLE);
    }

    // ---------- 第二级：数字键盘 ----------

    private void showNumpad() {
        step = STEP_NUM;
        sel = 0;
        buf.setLength(0);
        hourBuf.setLength(0);
        minBuf.setLength(0);
        minuteStage = false;
        box.removeAllViews();

        box.addView(Ui.title(ctx, "自定义" + title, 24));

        LinearLayout line = Ui.row(ctx);
        line.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(ctx, 10);
        box.addView(line, llp);

        // 数字和单位放进同一个 TextView：分成两个 View 时基线对不齐，
        // 42sp 的数字旁边会浮着一个掉下去的「分钟」（模拟器实测）。
        display = Ui.label(ctx, clock ? "— 点 — 分" : "— " + unit, 36, Ui.ACCENT);
        display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        display.setGravity(Gravity.CENTER);
        line.addView(display, new LinearLayout.LayoutParams(-1, -2));

        msg = Ui.label(ctx, clock ? clockHint() :
                "输入 " + min + "–" + max + " " + unit + "，输完按「确定」", 16, Ui.DIM);
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
        if (clock) { appendClock(d); return; }
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

    /**
     * 小时第一位按 3–9（不可能是两位小时）就直接进分钟，按 0–2 则再等一位：
     * 「9 3 0」= 9 点 30 分，「1 9 3 0」= 19 点 30 分。
     * 结束时刻额外允许 24:00（= 1440 分钟），其他时刻 24 点非法。
     */
    private void appendClock(char d) {
        if (!minuteStage) {
            if (hourBuf.length() >= 2) return;
            hourBuf.append(d);
            int v = parse(hourBuf);
            if (v > 23 && !(allowEnd24 && v == 24)) {
                hourBuf.setLength(hourBuf.length() - 1);
                setMsg(allowEnd24 ? "小时 0–23（24:00 表示到午夜）" : "小时只能是 0–23", true);
                return;
            }
            if (v >= 3 || hourBuf.length() == 2) minuteStage = true;
        } else {
            if (minBuf.length() >= 2) return;
            minBuf.append(d);
            int v = parse(minBuf);
            if (v > 59 || (allowEnd24 && parse(hourBuf) == 24 && v != 0)) {
                minBuf.setLength(minBuf.length() - 1);
                setMsg(allowEnd24 && parse(hourBuf) == 24 ? "24 点只能是 24:00" : "分钟只能是 0–59", true);
                return;
            }
        }
        syncDisplay();
        setMsg(clockHint(), false);
    }

    private String clockHint() {
        return minuteStage ? "输分钟 0–59，输完按「确定」"
                : "先输小时 0–23（如 19 点 30 分按 1 9 3 0）";
    }

    private void backspace() {
        if (clock) {
            if (minuteStage && minBuf.length() > 0) {
                minBuf.setLength(minBuf.length() - 1);
            } else if (minuteStage) {
                minuteStage = false;
                if (hourBuf.length() > 0) hourBuf.setLength(hourBuf.length() - 1);
            } else if (hourBuf.length() > 0) {
                hourBuf.setLength(hourBuf.length() - 1);
            }
            syncDisplay();
            setMsg(clockHint(), false);
            return;
        }
        if (buf.length() == 0) return;
        buf.setLength(buf.length() - 1);
        syncDisplay();
        setMsg("输入 " + min + "–" + max + " " + unit + "，输完按「确定」", false);
    }

    private void syncDisplay() {
        if (display == null) return;
        if (clock) {
            String h = hourBuf.length() == 0 ? "—" : hourBuf.toString();
            String m = minBuf.length() == 0 ? "—" : minBuf.toString();
            display.setText(h + " 点 " + m + " 分");
        } else {
            display.setText(buf.length() == 0 ? ("— " + unit) : (buf + " " + unit));
        }
    }

    private int parse() { return parse(buf); }

    private static int parse(StringBuilder sb) {
        if (sb.length() == 0) return 0;
        try { return Integer.parseInt(sb.toString()); } catch (Throwable t) { return 0; }
    }

    private void submit() {
        if (clock) {
            if (hourBuf.length() == 0 || minBuf.length() == 0) {
                setMsg("请输完整的「几点几分」，再按「确定」", true);
                return;
            }
            int h = parse(hourBuf), m = parse(minBuf);
            if (h > 23 || m > 59 || (h == 24 && (!allowEnd24 || m != 0))) {
                setMsg(allowEnd24 ? "时间无效，结束时刻可设 24:00" : "时间无效，小时 0–23、分钟 0–59", true);
                return;
            }
            onPick.onValue(h * 60 + m);
            return;
        }
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
        if (note != null && step == STEP_CHOOSE) note.setVisibility(GONE);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) continue;
            boolean on = (i == sel);
            boolean cur = (step == STEP_CHOOSE && keyValues[i] == current && keyValues[i] != CUSTOM);
            boolean off = step == STEP_CHOOSE && isDisabled(i);
            if (on) {
                keys[i].setBackground(Ui.box(ctx, Ui.ACCENT, Ui.ACCENT,
                        Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(off ? 0xFF6B7075 : 0xFF20160A);
            } else if (cur) {
                keys[i].setBackground(Ui.box(ctx, 0x1AFDC90C, Ui.ACCENT,
                        Ui.dp(ctx, 2), 12));
                keys[i].setTextColor(off ? 0xFF6B7075 : Ui.ACCENT);
            } else {
                keys[i].setBackground(Ui.box(ctx, 0x1AFFFFFF, 0x33FFFFFF,
                        Ui.dp(ctx, 1), 12));
                keys[i].setTextColor(off ? 0xFF5A5F64 : Ui.TEXT);
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
            // 到头就停，不取模循环：原来从 24:00 再按右键会跳回 自定义→00:00→06:00…
            // 「看电视时间 · 结束」有 7 项，多按几下就落到 18:00 这种错值（真机踩过）。
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (sel > 0) sel--; paint(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (sel < keys.length - 1) sel++;
                paint();
                return true;
            }
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
