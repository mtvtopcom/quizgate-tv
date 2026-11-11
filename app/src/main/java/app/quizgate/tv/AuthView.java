package app.quizgate.tv;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 开始计时前的授权检查面板（v1.2.9）。
 *
 * <p>为什么要有这一页：未授权时点「开始计时」，界面一关，系统就把守护进程强停
 * （Android 14 固件回桌面直接 {@code Force stopping}），家长看到的现象是
 * <b>「计时暂停 / 时间不走」</b>。与其让它在后台悄悄停表，不如开始前把缺的项摆出来，
 * 由家长决定现在开还是先凑合。
 *
 * <p>分级：
 * <ul>
 *   <li><b>悬浮窗</b>：硬性。缺了到点出不来题，面板上不给「仍然开始」。</li>
 *   <li><b>通知使用权</b>：强烈建议。缺了仍可开始，但会明确写清后果。</li>
 * </ul>
 * 卸载留痕是可选功能（只记录卸载次数），<b>不在这里提示</b>——按用户要求，
 * 不影响计时的授权不该在开始计时的路上拦人。
 *
 * <p>按键与 {@link OptionPanel} 一样自绘 + 自路由：Activity 里的浮层在 Android 14
 * 上不能靠焦点树。
 */
public class AuthView extends FrameLayout {

    /** 一项授权的当前状态。 */
    public static final class Item {
        public final String id, name, why;
        public final boolean ok;

        public Item(String id, String name, boolean ok, String why) {
            this.id = id;
            this.name = name;
            this.ok = ok;
            this.why = why == null ? "" : why;
        }
    }

    public interface Host {
        /** 每次刷新都重新查一遍真实状态。 */
        List<Item> items();

        /** 跳到某一项的系统设置页（或给出 adb 命令）。 */
        void fix(String id);

        /** 家长选择「仍然开始」。调用方必须自己再校验硬性项。 */
        void startAnyway();

        void cancel();
    }

    private static final int ZONE_ROWS = 0, ZONE_BTNS = 1;
    private static final String ID_OVERLAY = "overlay";

    private final Context ctx;
    private final Host host;
    private final LinearLayout panel;

    private List<Item> items = new ArrayList<>();
    private final List<LinearLayout> rowViews = new ArrayList<>();
    private TextView detail;
    private LinearLayout btnRow;
    private final List<TextView> btns = new ArrayList<>();

    private int zone = ZONE_ROWS;
    private int rowSel = 0;
    private int btnSel = 0;

    public AuthView(Context c, Host h) {
        super(c);
        ctx = c;
        host = h;
        setBackgroundColor(0xE6000000);
        setClickable(true);
        setFocusable(true);

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(Ui.dp(c, 44), Ui.dp(c, 26), Ui.dp(c, 44), Ui.dp(c, 26));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) (c.getResources().getDisplayMetrics().widthPixels * 0.82f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(panel, lp);

        rebuild();
    }

    /** 从系统设置页回来、或者状态变了：重新查一遍并重绘。选中项自动落到第一个还没开的。 */
    public void refresh() {
        rebuild();
    }

    private void rebuild() {
        items = host.items() == null ? new ArrayList<>() : host.items();
        boolean hardMissing = false;
        for (Item it : items) if (!it.ok && ID_OVERLAY.equals(it.id)) hardMissing = true;

        panel.removeAllViews();
        rowViews.clear();
        btns.clear();

        panel.addView(Ui.title(ctx, "开始计时前，先补齐授权", 26));

        String sub = hardMissing
                ? "悬浮窗是硬性条件：缺了它到点出不来题，所以不能跳过。"
                : "下面缺的项建议都开。少了它们，退出应用后系统可能把计时停掉——"
                  + "表现就是「时间不走了」，而不是继续倒计时。";
        TextView subV = Ui.label(ctx, sub, 16, hardMissing ? Ui.BAD : Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(ctx, 8);
        panel.addView(subV, slp);

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), 0);

            TextView mark = Ui.label(ctx, it.ok ? "✓" : "✗", 22, it.ok ? Ui.OK : Ui.BAD);
            row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(ctx, 30), -2));

            TextView name = Ui.label(ctx, it.name, 22, it.ok ? Ui.TEXT : Ui.TEXT);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView st = Ui.label(ctx, it.ok ? "已开启" : "未开启", 17, it.ok ? Ui.DIM : Ui.BAD);
            row.addView(st);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 46));
            rlp.topMargin = Ui.dp(ctx, 6);
            panel.addView(row, rlp);
            rowViews.add(row);

            final String id = it.id;
            row.setOnClickListener(v -> { if (!it.ok) host.fix(id); });
        }

        detail = Ui.label(ctx, "", 15, Ui.DIM);
        detail.setLineSpacing(Ui.dp(ctx, 2), 1f);
        detail.setMaxLines(4);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 88));
        dlp.topMargin = Ui.dp(ctx, 6);
        panel.addView(detail, dlp);

        btnRow = Ui.row(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(ctx, 10);
        panel.addView(btnRow, blp);

        boolean allOk = true;
        for (Item it : items) if (!it.ok) allOk = false;

        // D1（Android 11 / MiTV）实测：系统里根本没有「通知使用权」这一页，
        // `ACTION_NOTIFICATION_LISTENER_SETTINGS` 和 `ACTION_SETTINGS` 都启动失败，
        // 家长按「去开启」只会看到一闪而过的提示。所以把兜底写死在面板上，
        // 不指望 toast 能把话说完。
        if (!allOk) {
            TextView tip = Ui.label(ctx,
                    "电视里找不到这一页时，用电脑执行上面那条 adb 命令即可。", 14, Ui.DIM);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = Ui.dp(ctx, 10);
            panel.addView(tip, tlp);
        }

        if (allOk) {
            addBtn("开始计时", true, () -> host.startAnyway());
            addBtn("取消", true, () -> host.cancel());
        } else {
            addBtn("去开启", true, () -> {
                Item it = selected();
                if (it != null) host.fix(it.id);
            });
            addBtn("仍然开始", !hardMissing, () -> host.startAnyway());
            addBtn("取消", true, () -> host.cancel());
        }

        zone = ZONE_ROWS;
        if (rowSel >= items.size()) rowSel = 0;
        // 默认停在第一项「还没开」的授权上：直接按 OK 就能去开，不用先挪方向键
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).ok) { rowSel = i; break; }
        }
        btnSel = 0;
        paint();
    }

    private void addBtn(String text, boolean enabled, Runnable act) {
        TextView b = Ui.label(ctx, text, 21, enabled ? Ui.TEXT : Ui.DIM);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(false);
        b.setClickable(true);
        b.setAlpha(enabled ? 1f : 0.45f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(ctx, 54));
        lp.leftMargin = Ui.dp(ctx, 8);
        lp.rightMargin = Ui.dp(ctx, 8);
        b.setMinWidth(Ui.dp(ctx, 170));
        b.setPadding(Ui.dp(ctx, 22), 0, Ui.dp(ctx, 22), 0);
        b.setOnClickListener(v -> { if (enabled) act.run(); });
        btnRow.addView(b, lp);
        btns.add(b);
    }

    private Item selected() {
        if (rowSel < 0 || rowSel >= items.size()) return null;
        return items.get(rowSel);
    }

    // ---------- 绘制 ----------

    private void paint() {
        for (int i = 0; i < rowViews.size(); i++) {
            boolean on = (zone == ZONE_ROWS && i == rowSel);
            rowViews.get(i).setBackground(Ui.box(ctx,
                    on ? 0x33FDC90C : 0x0FFFFFFF,
                    on ? Ui.ACCENT : 0x1FFFFFFF,
                    Ui.dp(ctx, on ? 2 : 1), 10));
        }
        for (int i = 0; i < btns.size(); i++) {
            boolean on = (zone == ZONE_BTNS && i == btnSel);
            btns.get(i).setBackground(Ui.box(ctx,
                    on ? 0x33FDC90C : 0x1AFFFFFF,
                    on ? Ui.ACCENT : 0x33FFFFFF,
                    Ui.dp(ctx, on ? 3 : 1), 12));
        }
        Item it = selected();
        if (detail != null) {
            if (it == null) {
                detail.setText("");
            } else if (it.ok) {
                detail.setText(it.name + "已开启，无需处理。");
                detail.setTextColor(Ui.DIM);
            } else {
                detail.setText(it.why);
                detail.setTextColor(Ui.DIM);
            }
        }
    }

    // ---------- 按键 ----------

    public boolean consumeKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();

        if (k == KeyEvent.KEYCODE_BACK) { host.cancel(); return true; }

        if (zone == ZONE_ROWS) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (rowSel >= items.size() - 1 && !btns.isEmpty()) { zone = ZONE_BTNS; btnSel = 0; }
                else rowSel = Math.min(items.size() - 1, rowSel + 1);
                paint();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                rowSel = Math.max(0, rowSel - 1);
                paint();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                Item it = selected();
                if (it != null && !it.ok) host.fix(it.id);
                return true;
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            zone = ZONE_ROWS;
            rowSel = Math.max(0, items.size() - 1);
            paint();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) { btnSel = Math.max(0, btnSel - 1); paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { btnSel = Math.min(btns.size() - 1, btnSel + 1); paint(); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (btnSel >= 0 && btnSel < btns.size()) btns.get(btnSel).performClick();
            return true;
        }
        return true;
    }
}
