package app.quizgate.tv;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 右下角读秒：最后 30 秒出现，绝不抢焦点。 */
public class MiniView extends FrameLayout {
    private final TextView num;
    private final TextView cap;

    public MiniView(Context c) {
        super(c);
        LinearLayout card = Ui.col(c);
        card.setBackgroundResource(R.drawable.panel_bg);
        card.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 18), Ui.dp(c, 10));

        num = Ui.title(c, "0:30", 24);
        num.setTextColor(Ui.ACCENT);
        num.setGravity(Gravity.CENTER);
        card.addView(num);

        TextView t = Ui.label(c, "剩余", 12, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        cap = t;
        card.addView(t);

        addView(card, new FrameLayout.LayoutParams(-2, -2));
        setAlpha(0.92f);
    }

    public void setRemaining(long ms) {
        num.setText(Core.fmt(ms));
    }

    /** 开局 5 秒提示复用同一个小窗，换个标题区分「本次时长」和「最后 30 秒读秒」。 */
    public void setCaption(String s) {
        cap.setText(s);
    }
}
