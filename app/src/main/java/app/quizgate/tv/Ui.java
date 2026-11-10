package app.quizgate.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 极简 UI 工厂：全部代码布局，不依赖 XML 主题继承（悬浮窗里没有 Activity 主题）。
 *  主题色取自新 logo：黑 #000000 / 白 #FFFFFF / 黄 #FDC90C。 */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF000000;
    public static final int CARD = 0xFF141414;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int DIM = 0xFF9AA0A6;
    public static final int ACCENT = 0xFFFDC90C;
    public static final int OK = 0xFFFDC90C;
    public static final int BAD = 0xFFFF5252;
    public static final int BLACK = 0xFF000000;

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

    /** 圆角色块：首页卡片和按钮都用它，避免再堆一层 XML。 */
    public static GradientDrawable box(Context c, int fill, int stroke, int strokePx, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    /**
     * 电视首页按钮：焦点态必须一眼看得出来（遥控器没有鼠标 hover）。
     * 主按钮黄底黑字，次按钮黑底白字；聚焦时统一加白色描边。
     */
    public static TextView homeKey(Context c, String s, boolean primary) {
        TextView t = label(c, s, 24, primary ? BLACK : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setFocusable(true);
        t.setClickable(true);
        t.setMinWidth(dp(c, 200));
        t.setMinHeight(dp(c, 68));
        t.setPadding(dp(c, 26), dp(c, 14), dp(c, 26), dp(c, 14));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                box(c, primary ? ACCENT : 0xFF242424, TEXT, dp(c, 3), 12));
        sld.addState(new int[]{},
                box(c, primary ? ACCENT : CARD, primary ? ACCENT : 0xFF3A3A3A, dp(c, 1), 12));
        t.setBackground(sld);
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
