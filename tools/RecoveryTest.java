import app.quizgate.tv.Recovery;

import java.util.Random;

/**
 * 恢复码纯逻辑断言。不依赖 Android，只要 javac/java。
 * 重点不是「能生成」，而是「抄错/按错一定被抓到」——那才是家长真正会遇到的失败。
 */
public class RecoveryTest {
    private static int fails = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        Random rnd = new Random(20261110L);

        // 1. 生成：长度、字符集、自校验
        for (int i = 0; i < 5000; i++) {
            String c = Recovery.generate();
            check(c.length() == Recovery.LEN, "长度不对: " + c);
            check(Recovery.valid(c), "自己生成的码自己校验不过: " + c);
            for (int j = 0; j < c.length(); j++) {
                char ch = c.charAt(j);
                check(ch >= '0' && ch <= '9', "出现非数字: " + c);
            }
        }

        // 2. 任意一位改成任意别的数字，必须全部被抓到（Luhn 对单点错误 100%）
        int single = 0;
        for (int t = 0; t < 2000; t++) {
            String c = Recovery.generate();
            int i = rnd.nextInt(Recovery.LEN);
            char old = c.charAt(i);
            char nu;
            do { nu = (char) ('0' + rnd.nextInt(10)); } while (nu == old);
            char[] a = c.toCharArray();
            a[i] = nu;
            if (!Recovery.valid(new String(a))) single++;
        }
        check(single == 2000, "单点错误漏检 " + (2000 - single) + " 次");

        // 3. 相邻两位对调（数字不同）必须被抓到
        int swapped = 0, tried = 0;
        for (int t = 0; t < 4000 && tried < 2000; t++) {
            String c = Recovery.generate();
            int i = rnd.nextInt(Recovery.LEN - 1);
            if (c.charAt(i) == c.charAt(i + 1)) continue;
            tried++;
            char[] a = c.toCharArray();
            char tmp = a[i];
            a[i] = a[i + 1];
            a[i + 1] = tmp;
            if (!Recovery.valid(new String(a))) swapped++;
        }
        check(swapped == tried, "相邻对调漏检 " + (tried - swapped) + " 次");

        // 4. 格式边界
        check(!Recovery.valid("1234567"), "7 位应被拒");
        check(!Recovery.valid("1234 5678"), "带空格应被拒（调用方先 normalize）");
        check(!Recovery.valid("1234567a"), "含字母应被拒");
        check(!Recovery.valid(null), "null 应被拒");
        check(Recovery.normalize(" 1234 5678 ").equals("12345678"), "normalize");
        check(Recovery.format("12345678").equals("1234 5678"), "format");
        check(Recovery.valid(Recovery.normalize(Recovery.format(Recovery.generate()))), "分组往返");

        System.out.println((fails == 0 ? "PASS" : "FAIL") + "  " + checks + " 项检查，失败 " + fails + " 项");
        if (fails > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("  ✗ " + what);
        }
    }
}
