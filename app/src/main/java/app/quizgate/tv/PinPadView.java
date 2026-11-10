package app.quizgate.tv;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 家长口令键盘：6 位数字，方向键 + OK 操作，不用系统输入法。 */
public class PinPadView extends FrameLayout {

    public interface Listener {
        void onPinSubmit(String pin);
        void onPinCancel();
    }

    public static final int LEN = 6;

    private final Listener listener;
    private final StringBuilder buf = new StringBuilder();
    private final TextView dots;
    private final TextView msg;
    private boolean busy = false;

    public PinPadView(Context c, String title, Listener l) {
        super(c);
        this.listener = l;
        setBackgroundColor(0xE6000000);
        setClickable(true);          // 吞掉点击，避免穿透到下层选项

        LinearLayout panel = Ui.col(c);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(Ui.dp(c, 40), Ui.dp(c, 32), Ui.dp(c, 40), Ui.dp(c, 32));

        TextView t = Ui.title(c, title, 26);
        panel.addView(t);

        dots = Ui.label(c, mask(), 30, Ui.ACCENT);
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

    private String mask() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LEN; i++) sb.append(i < buf.length() ? "●" : "○");
        return sb.toString();
    }

    public void focusFirst() {
        // 必须等一次布局，否则 requestFocus 会失败（真机上表现为遥控器完全没反应）
        post(() -> {
            if (getChildCount() == 0) return;
            View panel = getChildAt(0);
            if (!(panel instanceof LinearLayout)) return;
            LinearLayout p = (LinearLayout) panel;
            for (int i = 2; i < p.getChildCount(); i++) {
                View rowV = p.getChildAt(i);
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
        if (buf.length() >= LEN) return;
        buf.append(d);
        dots.setText(mask());
        setMessage("");
        if (buf.length() == LEN) submit();
    }

    public void backspace() {
        if (busy) return;
        if (buf.length() > 0) {
            buf.setLength(buf.length() - 1);
            dots.setText(mask());
            setMessage("");
        }
    }

    public void submit() {
        if (busy) return;
        if (buf.length() != LEN) {
            setMessage("请输入 " + LEN + " 位数字");
            return;
        }
        busy = true;
        String pin = buf.toString();
        buf.setLength(0);
        dots.setText(mask());
        listener.onPinSubmit(pin);
    }

    public void fail(String reason) {
        busy = false;
        buf.setLength(0);
        dots.setText(mask());
        setMessage(reason);
    }

    public void reset() {
        busy = false;
        buf.setLength(0);
        dots.setText(mask());
        setMessage("");
    }

    public void setMessage(String s) {
        msg.setText(s);
    }

    public void cancel() {
        listener.onPinCancel();
    }
}
