package app.quizgate.tv;

/**
 * 答题规则内核：纯 Java，不依赖 Android，可在桌面 javac 下单测。
 *
 * <p>首页的四种模式只是「配置模板」：
 * <pre>
 *   答题延时 = 即时续时 + 单题门槛
 *   连对答题 = 即时续时 + 固定连对 N 题
 *   答题解锁 = 即时续时 + 阶梯（首题数 / 之后增量）
 *   答题积累 = 时间银行 + 单题门槛（攒够最低时长才放行）
 * </pre>
 * 底层只有两个正交维度：时间产出方式 × 门槛策略。
 */
public final class GateRules {
    private GateRules() {}

    public static final int MODE_DELAY = 0, MODE_STREAK = 1, MODE_UNLOCK = 2, MODE_BANK = 3;

    /** 时间产出：答对立即续一段观看时长 / 答对攒进余额。 */
    public static final int PAYOUT_IMMEDIATE = 0, PAYOUT_BANK = 1;

    /** 门槛策略：单题 / 连对固定 N / 阶梯首 M 后 +K。 */
    public static final int TH_SINGLE = 0, TH_FIXED = 1, TH_LADDER = 2;

    public static int payout(int mode) {
        return mode == MODE_BANK ? PAYOUT_BANK : PAYOUT_IMMEDIATE;
    }

    public static int threshold(int mode) {
        switch (mode) {
            case MODE_STREAK: return TH_FIXED;
            case MODE_UNLOCK: return TH_LADDER;
            default: return TH_SINGLE;
        }
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_STREAK: return "连对答题";
            case MODE_UNLOCK: return "答题解锁";
            case MODE_BANK: return "答题积累";
            default: return "答题延时";
        }
    }

    /**
     * 本轮需要连对多少题。阶梯模式按「当天已经成功解锁过几次」取值：
     * 第一次用 firstNeed，之后每次都用 stepAdd（不是累加成 7、9、11）。
     */
    public static int needed(int mode, int fixedNeed, int firstNeed, int stepAdd, int unlockToday) {
        switch (threshold(mode)) {
            case TH_FIXED: return clamp(fixedNeed, 1, 10);
            case TH_LADDER: return unlockToday <= 0 ? clamp(firstNeed, 1, 20) : clamp(stepAdd, 1, 10);
            default: return 1;
        }
    }

    /** 当日第 n 次答错的冷却时长。n &lt;= 0 返回 0。 */
    public static long cooldownMs(int n) {
        if (n <= 0) return 0L;
        if (n == 1) return 5_000L;
        if (n == 2) return 10_000L;
        if (n == 3) return 20_000L;
        return 30_000L;
    }

    /** 答错是否进入冷却：单题门槛模式沿用旧行为（只换题，不罚等）。 */
    public static boolean cooldownApplies(int mode) {
        return threshold(mode) != TH_SINGLE;
    }

    /**
     * 是否处于每日答题时段。start == end 视为全天；start &gt; end 表示跨午夜。
     * minuteOfDay 取 0..1439（本地墙上时钟）。
     */
    public static boolean inWindow(int minuteOfDay, int start, int end) {
        start = clamp(start, 0, 1440);
        end = clamp(end, 0, 1440);
        if (start == end) return true;
        if (start < end) return minuteOfDay >= start && minuteOfDay < end;
        return minuteOfDay >= start || minuteOfDay < end;
    }

    /**
     * 逻辑日 key。resetMode=false 表示「跨天重置」（0 点直接翻页），
     * true 表示「固定时刻重置」：当天 resetAtMin 之前仍算前一天。
     */
    public static String logicalDay(String todayKey, String yesterdayKey, int minuteOfDay,
                                    boolean fixedMoment, int resetAtMin) {
        if (!fixedMoment) return todayKey;
        return minuteOfDay < clamp(resetAtMin, 0, 1439) ? yesterdayKey : todayKey;
    }

    /** 答对后是否放行观看。时间银行看余额门槛，其余看连对门槛。 */
    public static boolean unlocked(int mode, long balanceMs, long minBalanceMs,
                                   int streak, int need) {
        if (payout(mode) == PAYOUT_BANK) return balanceMs >= Math.max(0L, minBalanceMs);
        return streak >= need;
    }

    /** 进度文案，遮罩顶部一行直接显示。 */
    public static String progressText(int mode, int streak, int need,
                                      long balanceMs, long minBalanceMs) {
        if (payout(mode) == PAYOUT_BANK) {
            return "已攒 " + Math.max(0, balanceMs / 60_000L) + " / "
                    + Math.max(0, minBalanceMs / 60_000L) + " 分钟";
        }
        if (threshold(mode) == TH_SINGLE) return "答对 1 题即可观看";
        return "已连对 " + Math.min(streak, need) + " / " + need + " 题";
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
