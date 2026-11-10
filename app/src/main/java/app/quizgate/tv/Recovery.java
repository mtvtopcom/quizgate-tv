package app.quizgate.tv;

import java.security.SecureRandom;

/**
 * 恢复码：8 位十进制 = 7 位随机 + 1 位 Luhn 校验，显示为 4-4 分组。
 * 纯逻辑，不依赖 Android，可用 JVM 断言（tools/test_recovery.sh）。
 *
 * <p>为什么是 8 位数字，而不是更长的 Base32：
 * 电视只有十字键，一次按键只能表达 5 个选项（上下左右 OK）≈ 2.3 bit，而一位十进制是
 * 3.3 bit，所以 D-pad 输入的理论下限就是 ~1.5 次/位。现有 3×4 数字网格平均 2.14 次/位，
 * 已经贴着下限；换成 32 键字母网格则要 3.9 次/位。**键盘没有优化空间，只能在码长上做减法**。
 *
 * <p>熵够不够：有效空间 1e7（校验位不是熵），配合「5 次失败锁 30 分钟」，穷举期望
 * 1e7/5×30min ≈ 114 年；同时比现有 6 位口令（1e6）强 10 倍，满足「不与口令更弱」。
 *
 * <p>校验位只用于抓「抄错一位/按错一位」（公开算法，不承担安全性）。
 * 用的是 Verhoeff（D5 二面体群）而不是 Luhn：Luhn 对 0↔9 的相邻对调是瞎的
 * （实测 2000 次对调漏检 51 次，2.5%），而 Verhoeff 对单点错误和所有相邻对调都是 100%。
 * 家长抄错的方式恰恰以「相邻两位写反」和「0/9 抄混」为主。
 */
public final class Recovery {
    /** 随机位数。 */
    public static final int PAYLOAD = 7;
    /** 含校验位的总长度。 */
    public static final int LEN = 8;

    /** Verhoeff 乘法表 D5。 */
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };
    /** Verhoeff 位置置换表（周期 8）。 */
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };
    private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    private Recovery() {}

    public static String generate() {
        return generate(new SecureRandom());
    }

    public static String generate(SecureRandom r) {
        StringBuilder sb = new StringBuilder(LEN);
        for (int i = 0; i < PAYLOAD; i++) sb.append((char) ('0' + r.nextInt(10)));
        sb.append(checkDigit(sb));
        return sb.toString();
    }

    /**
     * 校验位：把 payload 当成「校验位在后」的完整码，只有校验位（位置 0）待定。
     * 位置 0 的置换是恒等，所以 c 初值就是待求数字本身；要让最终 c 归零，
     * 待求值 = INV[payload 的累积值]。
     */
    static char checkDigit(CharSequence payload) {
        int c = 0;
        int n = payload.length();
        for (int k = 1; k <= n; k++) {
            c = D[c][P[k % 8][payload.charAt(n - k) - '0']];
        }
        return (char) ('0' + INV[c]);
    }

    /** 长度、字符集、校验位三重检查。全过才可能是一串真码。 */
    public static boolean valid(String code) {
        if (code == null || code.length() != LEN) return false;
        for (int i = 0; i < LEN; i++) {
            char ch = code.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        int c = 0;
        for (int k = 0; k < LEN; k++) {
            c = D[c][P[k % 8][code.charAt(LEN - 1 - k) - '0']];
        }
        return c == 0;
    }

    /** 只保留数字：家长可能连分组空格一起抄下来。 */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(LEN);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    /** 4-4 分组，仅用于展示。 */
    public static String format(String code) {
        if (code == null) return "";
        if (code.length() != LEN) return code;
        return code.substring(0, 4) + " " + code.substring(4);
    }
}
