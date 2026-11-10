package app.quizgate.tv;

import java.util.Locale;
import java.util.Random;

/** 纯逻辑：不依赖 Android，可 JVM 单测（后续抽成独立模块）。 */
public final class Core {
    private Core() {}

    /** 毫秒 -> m:ss */
    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** 口算兜底：任何年级、任何题库缺失时都保证有题。 */
    public static QuizBank.Q arithmetic(int grade, Random r) {
        int a, b, ans;
        String stem;
        if (grade <= 2) {
            a = 1 + r.nextInt(9);
            b = 1 + r.nextInt(9);
            if (r.nextBoolean()) { ans = a + b; stem = a + " + " + b + " = ?"; }
            else { if (a < b) { int t = a; a = b; b = t; } ans = a - b; stem = a + " - " + b + " = ?"; }
        } else if (grade <= 4) {
            a = 2 + r.nextInt(9);
            b = 2 + r.nextInt(9);
            ans = a * b;
            stem = a + " × " + b + " = ?";
        } else if (grade <= 6) {
            b = 2 + r.nextInt(9);
            ans = 2 + r.nextInt(9);
            a = b * ans;
            stem = a + " ÷ " + b + " = ?";
        } else {
            a = 2 + r.nextInt(9);
            b = 1 + r.nextInt(20);
            ans = a + b;
            stem = "x - " + b + " = " + a + "，x = ?";
        }
        // 三个干扰项：围绕正确答案生成，去重后截取
        int[] raw = {ans + 1, ans - 1, ans + 2, ans - 2, ans + 10, ans + 3};
        String[] opts = new String[4];
        opts[0] = String.valueOf(ans);
        int filled = 1;
        for (int v : raw) {
            if (filled >= 4) break;
            if (v == ans || v < 0) continue;
            boolean dup = false;
            for (int i = 1; i < filled; i++) if (opts[i].equals(String.valueOf(v))) dup = true;
            if (!dup) opts[filled++] = String.valueOf(v);
        }
        while (filled < 4) opts[filled++] = String.valueOf(ans + filled * 3);
        // 答案此时在下标 0，按下标打乱并取回新位置（不靠文本匹配，避免重复项歧义）
        int idx = QuizBank.shuffle(opts, 0, r);
        QuizBank.Q q = new QuizBank.Q();
        q.stem = stem;
        q.options = opts;
        q.answer = idx;
        q.explain = "正确答案：" + ans;
        return q;
    }
}
