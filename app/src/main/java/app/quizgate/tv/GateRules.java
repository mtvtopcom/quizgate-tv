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

    /**
     * 「答题关」不是第五种规则，而是「不出题」这个状态本身（Prefs.quizOn == false）。
     * 只在 UI 层用这个值表示，绝不写进 Prefs.mode（那里保持 0..3），
     * 这样家长切回来时还是上次那种方式，不用另存字段。
     */
    public static final int MODE_OFF = -1;

    /** 时间产出：答对立即续一段观看时长 / 答对攒进余额。 */
    public static final int PAYOUT_IMMEDIATE = 0, PAYOUT_BANK = 1;

    /** 门槛策略：单题 / 连对固定 N / 阶梯首 M 后 +K。 */
    public static final int TH_SINGLE = 0, TH_FIXED = 1, TH_LADDER = 2;

    /**
     * 答题积累的余额上限（分钟）。攒到这里就停止累加：
     * 一是防止一口气答几十题攒出一整天，二是防止 balanceMs 被无限撑大。
     */
    public static final int BANK_MAX_MIN = 120;

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
            case MODE_OFF: return "答题关";
            default: return "答题延时";
        }
    }

    /** 首页按钮只放得下短名（一行三颗按钮，1080p 视口只有 960dp 宽）。 */
    public static String shortName(int mode) {
        switch (mode) {
            case MODE_STREAK: return "连对";
            case MODE_UNLOCK: return "解锁";
            case MODE_BANK: return "积累";
            case MODE_OFF: return "关";
            default: return "延时";
        }
    }

    // ---------- 答题方式页的「按需显示」判定 ----------
    // 放在规则层而不是 MainActivity 里堆 if：以后加方式只要改这里。

    /** 是否用「本次时长」起手（时间银行按余额起手，不用它）。 */
    public static boolean usesDuration(int mode) {
        return mode != MODE_OFF && payout(mode) == PAYOUT_IMMEDIATE;
    }

    /** 答对后是否立即续一段时长（只有即时产出用「答对延长」）。 */
    public static boolean usesExtend(int mode) {
        return usesDuration(mode);
    }

    public static boolean usesFixedNeed(int mode) { return mode == MODE_STREAK; }

    public static boolean usesLadder(int mode) { return mode == MODE_UNLOCK; }

    public static boolean usesBankParams(int mode) { return mode == MODE_BANK; }

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
