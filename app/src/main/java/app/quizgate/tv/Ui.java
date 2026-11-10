package app.quizgate.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 极简 UI 工厂：全部代码布局，不依赖 XML 主题继承（悬浮窗里没有 Activity 主题）。 */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0B0F14;
    public static final int CARD = 0xFF161B21;
    public static final int TEXT = 0xFFEDEFF2;
    public static final int DIM = 0xFF9AA4B2;
    public static final int ACCENT = 0xFFFFC24A;
    public static final int OK = 0xFF4ADE80;
    public static final int BAD = 0xFFFF6B6B;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView label(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, String s, float sp) {
        TextView t = label(c, s, sp, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView key(Context c, String s, float sp) {
        TextView t = label(c, s, sp, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.key_bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setMinWidth(dp(c, 96));
        t.setMinHeight(dp(c, 64));
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        return t;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }
}
