package app.quizgate.tv;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** 纯逻辑：不依赖 Android，可 JVM 单测（后续抽成独立模块）。 */
public final class Core {
    private Core() {}

    /** 毫秒 -> m:ss */
    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /**
     * 动态口算题：题库抽空后按年级难度无限出题，且不与本轮已出的题重复。
     *
     * @param used 本轮已出的题干集合；为 null 时不查重。生成的题干会 add 进去。
     */
    public static QuizBank.Q arithmetic(int grade, Random r, Set<String> used) {
        int g = Math.max(1, Math.min(9, grade));
        String stem;
        int ans;
        // 最多试 40 次生成一道没出过的题；数字组合足够多，几乎第一次就命中。
        int attempt = 0;
        do {
            if (g <= 2) {
                // 1-2 年级：20 以内加减、连加、比大小、填空
                int type = r.nextInt(4);
                if (type == 0) {
                    int a = 1 + r.nextInt(19), b = 1 + r.nextInt(20 - a);
                    ans = a + b; stem = a + " + " + b + " = ?";
                } else if (type == 1) {
                    int a = 2 + r.nextInt(19), b = 1 + r.nextInt(a - 1);
                    ans = a - b; stem = a + " - " + b + " = ?";
                } else if (type == 2) {
                    int a = 1 + r.nextInt(6), b = 1 + r.nextInt(6), c = 1 + r.nextInt(6);
                    ans = a + b + c; stem = a + " + " + b + " + " + c + " = ?";
                } else {
                    int a = 1 + r.nextInt(9), b = 1 + r.nextInt(9);
                    ans = a + b; stem = "( ) + " + a + " = " + ans + "，括号里填几？";
                }
            } else if (g <= 4) {
                // 3-4 年级：表内乘除、两三位数加减、混合
                int type = r.nextInt(4);
                if (type == 0) {
                    int a = 2 + r.nextInt(8), b = 2 + r.nextInt(8);
                    ans = a * b; stem = a + " × " + b + " = ?";
                } else if (type == 1) {
                    int b = 2 + r.nextInt(8), q = 2 + r.nextInt(8);
                    ans = q; stem = (b * q) + " ÷ " + b + " = ?";
                } else if (type == 2) {
                    int a = 20 + r.nextInt(80), b = 10 + r.nextInt(60);
                    ans = a + b; stem = a + " + " + b + " = ?";
                } else {
                    int a = 30 + r.nextInt(70), b = 10 + r.nextInt(a - 10);
                    ans = a - b; stem = a + " - " + b + " = ?";
                }
            } else if (g <= 6) {
                // 5-6 年级：多位数乘除、四则混合、小数加减速算
                int type = r.nextInt(4);
                if (type == 0) {
                    int a = 11 + r.nextInt(40), b = 2 + r.nextInt(8);
                    ans = a * b; stem = a + " × " + b + " = ?";
                } else if (type == 1) {
                    int b = 3 + r.nextInt(12), q = 4 + r.nextInt(20);
                    ans = q; stem = (b * q) + " ÷ " + b + " = ?";
                } else if (type == 2) {
                    int a = 2 + r.nextInt(9), b = 2 + r.nextInt(9), c = 2 + r.nextInt(9);
                    ans = a * b + c; stem = a + " × " + b + " + " + c + " = ?";
                } else {
                    int a = 5 + r.nextInt(15), b = 5 + r.nextInt(15), c = 2 + r.nextInt(8);
                    ans = (a + b) * c; stem = "(" + a + " + " + b + ") × " + c + " = ?";
                }
            } else {
                // 7-9 年级：有理数、一元一次方程、代数求值、简单几何
                int type = r.nextInt(4);
                if (type == 0) {
                    int x = 2 + r.nextInt(9), b = 1 + r.nextInt(20);
                    ans = x; stem = "x + " + b + " = " + (x + b) + "，x = ?";
                } else if (type == 1) {
                    int k = 2 + r.nextInt(8), x = 2 + r.nextInt(9);
                    ans = k * x; stem = "若 x = " + x + "，则 " + k + "x = ?";
                } else if (type == 2) {
                    int a = 2 + r.nextInt(9), b = 1 + r.nextInt(9);
                    ans = a - b; stem = "计算：" + a + " − " + b + " = ?";
                } else {
                    int a = 2 * (2 + r.nextInt(5)), b = 3 + r.nextInt(9);  // a 必为偶数，面积为整数
                    ans = a * b / 2; stem = "直角三角形两直角边为 " + a + " 和 " + b + "，面积是？";
                }
            }
        } while (used != null && used.contains(stem) && ++attempt < 40);
        if (used != null) used.add(stem);

        // 三个干扰项：围绕正确答案生成，去重后截取
        int[] raw = {ans + 1, ans - 1, ans + 2, ans - 2, ans + 10, ans + 3, ans - 3, ans + 5};
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
        while (filled < 4) {
            int v = ans + filled * 3 + 1;
            boolean dup = false;
            for (int i = 0; i < filled; i++) if (opts[i].equals(String.valueOf(v))) dup = true;
            if (dup) v++;
            opts[filled++] = String.valueOf(v);
        }
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
