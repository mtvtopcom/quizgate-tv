package app.quizgate.tv;

/** 规则内核单测：纯 Java，不依赖 Android。运行：tools/test_gate_rules.sh */
public final class GateRulesTest {
    private static int passed = 0, failed = 0;

    private static void eq(String name, long expect, long actual) {
        if (expect == actual) { passed++; }
        else { failed++; System.out.println("FAIL " + name + ": expect=" + expect + " actual=" + actual); }
    }

    private static void yes(String name, boolean v) { eq(name, 1L, v ? 1L : 0L); }

    public static void main(String[] args) {
        eq("delay payout", GateRules.PAYOUT_IMMEDIATE, GateRules.payout(GateRules.MODE_DELAY));
        eq("bank payout", GateRules.PAYOUT_BANK, GateRules.payout(GateRules.MODE_BANK));
        eq("delay threshold", GateRules.TH_SINGLE, GateRules.threshold(GateRules.MODE_DELAY));
        eq("streak threshold", GateRules.TH_FIXED, GateRules.threshold(GateRules.MODE_STREAK));
        eq("unlock threshold", GateRules.TH_LADDER, GateRules.threshold(GateRules.MODE_UNLOCK));

        eq("single need", 1, GateRules.needed(GateRules.MODE_DELAY, 2, 5, 2, 0));
        eq("fixed need", 2, GateRules.needed(GateRules.MODE_STREAK, 2, 5, 2, 0));
        eq("fixed need clamp low", 1, GateRules.needed(GateRules.MODE_STREAK, 0, 5, 2, 0));
        eq("fixed need clamp high", 10, GateRules.needed(GateRules.MODE_STREAK, 99, 5, 2, 0));
        eq("ladder first", 5, GateRules.needed(GateRules.MODE_UNLOCK, 2, 5, 2, 0));
        eq("ladder second", 2, GateRules.needed(GateRules.MODE_UNLOCK, 2, 5, 2, 1));
        eq("ladder tenth", 2, GateRules.needed(GateRules.MODE_UNLOCK, 2, 5, 2, 9));

        eq("cooldown 0", 0L, GateRules.cooldownMs(0));
        eq("cooldown 1", 5000L, GateRules.cooldownMs(1));
        eq("cooldown 2", 10000L, GateRules.cooldownMs(2));
        eq("cooldown 3", 20000L, GateRules.cooldownMs(3));
        eq("cooldown 4", 30000L, GateRules.cooldownMs(4));
        eq("cooldown 9", 30000L, GateRules.cooldownMs(9));
        yes("no cooldown on delay", !GateRules.cooldownApplies(GateRules.MODE_DELAY));
        yes("cooldown on streak", GateRules.cooldownApplies(GateRules.MODE_STREAK));

        yes("window full day", GateRules.inWindow(0, 0, 1440));
        yes("window start", GateRules.inWindow(1080, 1080, 1260));
        yes("window end excluded", !GateRules.inWindow(1260, 1080, 1260));
        yes("window before", !GateRules.inWindow(600, 1080, 1260));
        yes("window overnight evening", GateRules.inWindow(1380, 1320, 240));
        yes("window overnight morning", GateRules.inWindow(120, 1320, 240));
        yes("window overnight middle", !GateRules.inWindow(720, 1320, 240));

        eq("day cross-day", 0L, "20261125".equals(
                GateRules.logicalDay("20261125", "20261124", 10, false, 0)) ? 0L : 1L);
        eq("day fixed before", 0L, "20261124".equals(
                GateRules.logicalDay("20261125", "20261124", 119, true, 120)) ? 0L : 1L);
        eq("day fixed after", 0L, "20261125".equals(
                GateRules.logicalDay("20261125", "20261124", 120, true, 120)) ? 0L : 1L);

        yes("bank locked below min", !GateRules.unlocked(GateRules.MODE_BANK, 9 * 60000L, 10 * 60000L, 99, 1));
        yes("bank unlocked at min", GateRules.unlocked(GateRules.MODE_BANK, 10 * 60000L, 10 * 60000L, 0, 1));
        yes("streak locked below", !GateRules.unlocked(GateRules.MODE_STREAK, 0, 0, 1, 2));
        yes("streak unlocked", GateRules.unlocked(GateRules.MODE_STREAK, 0, 0, 2, 2));
        yes("delay one correct", GateRules.unlocked(GateRules.MODE_DELAY, 0, 0, 1, 1));

        System.out.println("GateRulesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
