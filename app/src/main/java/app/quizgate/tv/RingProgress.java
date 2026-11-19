package app.quizgate.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 环形倒计时：底环 + 按剩余比例收短的进度弧 + 中央等宽数字。
 *
 * <p>为什么自绘而不是用 ProgressBar：系统的 horizontal/环形 ProgressBar 带主题依赖，
 * 悬浮窗里没有 Activity 主题，取不到正确的尺寸与颜色（1.2.7 的面板踩过同一条坑）。
 * 这里一共三个 Paint，画一个圆一条弧一段文字，行为完全可预测。
 */
public class RingProgress extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 0 = 空，1 = 满。倒计时里传「剩余比例」，弧长会随时间收短。 */
    private float progress = 1f;
    private String label = "";

    public RingProgress(Context c) {
        super(c);
        float stroke = Ui.dp(c, 10);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x33FFFFFF);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Ui.ACCENT);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(Ui.dp(c, 40));
    }

    public void set(float p, String label) {
        this.progress = Math.max(0f, Math.min(1f, p));
        this.label = label == null ? "" : label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float stroke = trackPaint.getStrokeWidth();
        float pad = stroke / 2f + Ui.dp(getContext(), 2);
        oval.set(pad, pad, getWidth() - pad, getHeight() - pad);

        cv.drawOval(oval, trackPaint);
        if (progress > 0.001f) {
            // 从 12 点方向顺时针收短，和孩子心里「表盘走完就结束」的直觉一致
            cv.drawArc(oval, -90f, 360f * progress, false, arcPaint);
        }

        if (!label.isEmpty()) {
            float baseline = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            cv.drawText(label, getWidth() / 2f, baseline, textPaint);
        }
    }
}
