package app.quizgate.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 设置 + 会话快照 + 家长口令。
 * 快照规则（决策文档 ADR-1）：tick 用 apply（可丢），状态迁移用 commit（不可丢）。
 */
public final class Prefs {
    /** IDLE/RUNNING/LOCKED 是 1.5.0 就有的三态；REST/OUT 是规则引擎新增的「锁定变体」。 */
    public static final int IDLE = 0, RUNNING = 1, LOCKED = 2, REST = 3, OUT = 4;

    /** 锁定变体：这些 phase 都必须挂遮罩、不扣观看时长。 */
    public static boolean lockedLike(int p) {
        return p == LOCKED || p == REST || p == OUT;
    }

    private static final String F = "gate";
    private static final int PBKDF2_ITER = 60000;
    private static final int MAX_FAIL = 5;
    private static final long LOCKOUT_MS = 30_000L;
    /** 恢复码是比口令更强的凭据，试错代价也更高：5 次 → 锁 30 分钟。 */
    private static final int MAX_RC_FAIL = 5;
    private static final long RC_LOCKOUT_MS = 30 * 60_000L;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    // ---------- 设置 ----------
    /**
     * 软件总开关。老用户（已设口令或已有会话）升级后默认继续运行；
     * 全新安装默认关闭，等家长在首页主动点「启动软件」，避免装上就突然锁屏。
     */
    public boolean gateOn() {
        if (sp.contains("gateOn")) return sp.getBoolean("gateOn", false);
        return hasPin() || phase() != IDLE;
    }
    public void setGateOn(boolean v) { sp.edit().putBoolean("gateOn", v).commit(); }

    /**
     * 答题开关。关掉后不弹题、不扣观看时长，但每日时段与强制休息仍然生效。
     * 连对/余额/冷却状态保留，重新开启时接着原来的状态。
     */
    public boolean quizOn() { return sp.getBoolean("quizOn", true); }
    public void setQuizOn(boolean v) { sp.edit().putBoolean("quizOn", v).commit(); }

    public int durationMin() { return sp.getInt("durationMin", 30); }
    public void setDurationMin(int v) { sp.edit().putInt("durationMin", clamp(v, 1, 240)).commit(); }

    public int extendMin() { return sp.getInt("extendMin", 5); }
    public void setExtendMin(int v) { sp.edit().putInt("extendMin", clamp(v, 1, 60)).commit(); }

    // ---------- 规则引擎设置（1.6.0） ----------
    /** 首页四种模式其实是「产出方式 × 门槛策略」的模板，见 GateRules。 */
    public int mode() { return clamp(sp.getInt("mode", GateRules.MODE_DELAY), 0, 3); }
    public void setMode(int v) { sp.edit().putInt("mode", clamp(v, 0, 3)).commit(); }

    /** 连对答题：需要连对几题（1..10）。 */
    public int fixedNeed() { return sp.getInt("fixedNeed", 2); }
    public void setFixedNeed(int v) { sp.edit().putInt("fixedNeed", clamp(v, 1, 10)).commit(); }

    /** 答题解锁：首次需要几题（1..20）。 */
    public int firstNeed() { return sp.getInt("firstNeed", 5); }
    public void setFirstNeed(int v) { sp.edit().putInt("firstNeed", clamp(v, 1, 20)).commit(); }

    /** 答题解锁：首次之后每次新增几题（1..10）。 */
    public int stepAdd() { return sp.getInt("stepAdd", 2); }
    public void setStepAdd(int v) { sp.edit().putInt("stepAdd", clamp(v, 1, 10)).commit(); }

    /** 答题积累：每答对一题攒多少分钟（1..60）。 */
    public int perAnswerMin() { return sp.getInt("perAnswerMin", 1); }
    public void setPerAnswerMin(int v) { sp.edit().putInt("perAnswerMin", clamp(v, 1, 60)).commit(); }

    /** 答题积累：余额至少多少分钟才允许开始观看（1..240）。 */
    public int minBankMin() { return sp.getInt("minBankMin", 10); }
    public void setMinBankMin(int v) { sp.edit().putInt("minBankMin", clamp(v, 1, 240)).commit(); }

    /** 每日答题时段（本地分钟数）。默认 0..1440 = 全天，避免老用户升级后突然被锁死。 */
    public int windowStart() { return clamp(sp.getInt("windowStart", 0), 0, 1440); }
    public int windowEnd() { return clamp(sp.getInt("windowEnd", 1440), 0, 1440); }
    public void setWindow(int start, int end) {
        sp.edit().putInt("windowStart", clamp(start, 0, 1440))
                .putInt("windowEnd", clamp(end, 0, 1440)).commit();
    }
    public boolean inWindow() {
        return GateRules.inWindow(minuteOfDay(), windowStart(), windowEnd());
    }

    /** 连续观看上限 / 强制休息时长（分钟）。 */
    public int watchLimitMin() { return sp.getInt("watchLimitMin", 30); }
    public void setWatchLimitMin(int v) { sp.edit().putInt("watchLimitMin", clamp(v, 1, 240)).commit(); }
    public int restMin() { return sp.getInt("restMin", 10); }
    public void setRestMin(int v) { sp.edit().putInt("restMin", clamp(v, 1, 120)).commit(); }

    /** 每日计数重置方式：false = 跨天 0 点重置；true = 固定时刻重置。 */
    public boolean fixedResetMoment() { return sp.getBoolean("fixedResetMoment", false); }
    public int resetAtMin() { return clamp(sp.getInt("resetAtMin", 0), 0, 1439); }
    public void setResetMode(boolean fixedMoment, int atMin) {
        sp.edit().putBoolean("fixedResetMoment", fixedMoment)
                .putInt("resetAtMin", clamp(atMin, 0, 1439)).commit();
    }

    /** 出题时暂停正在播放的节目，默认开。 */
    public boolean pauseOnQuiz() { return sp.getBoolean("pauseOnQuiz", true); }
    public void setPauseOnQuiz(boolean v) { sp.edit().putBoolean("pauseOnQuiz", v).commit(); }

    public int minuteOfDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /** [今天 key, 昨天 key]，格式 yyyyMMdd。给 GateRules.logicalDay 用。 */
    public String[] dayKeys() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        String today = f.format(c.getTime());
        c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        return new String[]{today, f.format(c.getTime())};
    }

    public String todayKey() { return dayKeys()[0]; }

    /** 当前规则下的逻辑日；跨天检测每秒做一次，成本只有一个 Calendar。 */
    public String logicalDayKey() {
        String[] keys = dayKeys();
        return GateRules.logicalDay(keys[0], keys[1], minuteOfDay(),
                fixedResetMoment(), resetAtMin());
    }

    /** 学段：0 = 小学（1-6 年级），1 = 初中（初一/初二/初三 = 7/8/9 年级）。 */
    public static final int STAGE_PRIMARY = 0, STAGE_JUNIOR = 1;

    /** 科目：0 = 语文，1 = 数学，2 = 英语。题库文件名为 <subject>_grade_<n>.json。 */
    public static final int SUBJECT_CHINESE = 0, SUBJECT_MATH = 1, SUBJECT_ENGLISH = 2;
    public static final String[] SUBJECT_KEYS = {"chinese", "math", "english"};
    public static final String[] SUBJECT_NAMES = {"语文", "数学", "英语"};

    public int subject() { return clamp(sp.getInt("subject", SUBJECT_MATH), 0, 2); }

    /** 1.5.0：单选改多选；兼容只有 subject 的老数据（取这一科）。 */
    public int subjectMask() {
        int m = sp.getInt("subjectMask", 0) & 0x7;
        if (m == 0) m = 1 << subject();
        return m;
    }

    public void setSubjectMask(int mask) {
        int m = mask & 0x7;
        if (m == 0) m = 1 << SUBJECT_MATH;   // 至少留一科，不能出现「什么题都不出」
        int primary = 0;
        while (primary < 3 && (m & (1 << primary)) == 0) primary++;
        if (primary > 2) primary = SUBJECT_MATH;
        // subject 仍写成主科目（最低位），老代码/旧快照读它不会拿到空值
        sp.edit().putInt("subjectMask", m).putInt("subject", primary).commit();
    }

    public void setSubject(int v) { setSubjectMask(1 << clamp(v, 0, 2)); }

    public String subjectKey() { return SUBJECT_KEYS[subject()]; }

    public String subjectName() { return SUBJECT_NAMES[subject()]; }

    /** 已选科目（语文/数学/英语 顺序，用「+」连接），设置页那一行显示用。 */
    public String subjectText() {
        StringBuilder sb = new StringBuilder();
        int m = subjectMask();
        for (int i = 0; i < 3; i++) {
            if ((m & (1 << i)) == 0) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(SUBJECT_NAMES[i]);
        }
        return sb.length() == 0 ? SUBJECT_NAMES[SUBJECT_MATH] : sb.toString();
    }

    /** 抽题用的科目 key 列表，至少一个。 */
    public String[] subjectKeys() {
        int m = subjectMask();
        int n = Integer.bitCount(m);
        if (n == 0) return new String[]{SUBJECT_KEYS[SUBJECT_MATH]};
        String[] out = new String[n];
        int k = 0;
        for (int i = 0; i < 3; i++) {
            if ((m & (1 << i)) != 0) out[k++] = SUBJECT_KEYS[i];
        }
        return out;
    }

    /**
     * 当前使用的题库：{@code builtin} 或 filesDir/banks 下的题库 id。
     * 用户题库被删除时由 {@link BankManagerView} 自动写回 builtin，这里不做校验
     * （读的时候校验会让每次抽题都碰一次文件系统）。
     */
    public String activeBankId() {
        String v = sp.getString("activeBankId", "builtin");
        return v == null || v.isEmpty() ? "builtin" : v;
    }

    public void setActiveBankId(String id) {
        sp.edit().putString("activeBankId", id == null || id.isEmpty() ? "builtin" : id).commit();
    }

    /** 用户题库缺当前年级题目、回退默认题库时，同一题库只提示一次。 */
    public boolean fallbackNoticed(String bankId) {
        return sp.getBoolean("fb_" + bankId, false);
    }

    public void setFallbackNoticed(String bankId) {
        sp.edit().putBoolean("fb_" + bankId, true).commit();
    }

    /**
     * 家长豁免态有效期。1.6.0 新流程取消了「开始新一局」（原本在那里清除家长态），
     * 若不设时限，输一次口令后时段/休息豁免会永久有效——全局规则形同虚设。
     * 5 分钟够家长改完设置，到点自动失效，不需要额外操作。
     */
    private static final long PARENT_UNLOCK_MS = 5 * 60_000L;

    /**
     * 1.3.0：家长在锁屏遮罩上输对过一次口令后，进设置不再要口令。
     *
     * <p><b>必须落盘</b>：小米电视回桌面 / 待机会强停第三方进程，只放进程内静态变量
     * 活不过这一下，家长刚解锁完、按 HOME 再回来又被要一次口令。卸载重装会随
     * gate prefs 一起清掉，不会把解锁状态带到新安装。
     *
     * <p>1.6.0 起带 5 分钟时限：墙上时钟（强停后仍按真实时间算）。老数据没有时间戳，
     * 视作已过期，避免升级后沿用一段来历不明的豁免。
     */
    public boolean parentUnlocked() {
        if (!sp.getBoolean("parentUnlocked", false)) return false;
        long at = sp.getLong("parentUnlockedAt", 0L);
        return at > 0 && System.currentTimeMillis() - at < PARENT_UNLOCK_MS;
    }

    public void setParentUnlocked(boolean v) {
        sp.edit().putBoolean("parentUnlocked", v)
                .putLong("parentUnlockedAt", v ? System.currentTimeMillis() : 0L)
                .commit();
    }

    /**
     * 1.2.9 之前只有「年级 1-9」，没有学段。老用户升上来时按已有年级推断，
     * 别让一个设了 8 年级的电视突然显示「小学」。
     */
    public int stage() {
        if (sp.contains("stage")) return clamp(sp.getInt("stage", STAGE_PRIMARY), 0, 1);
        return sp.getInt("grade", 3) >= 7 ? STAGE_JUNIOR : STAGE_PRIMARY;
    }

    public void setStage(int v) { sp.edit().putInt("stage", clamp(v, 0, 1)).commit(); }

    public int grade() { return sp.getInt("grade", 3); }
    public void setGrade(int v) { sp.edit().putInt("grade", clamp(v, 1, 9)).commit(); }

    /**
     * 抽题下限。初中不能回落到小学题：{@link QuizBank} 抽不到题会逐级往下退，
     * 不设下限的话初二会抽到「3 + 4 = ?」。
     */
    public int floorGrade() { return stage() == STAGE_JUNIOR ? 7 : 1; }

    // ---------- 会话快照 ----------
    public int phase() { return sp.getInt("phase", IDLE); }

    /**
     * 恢复时的可信时长。答题积累模式下「剩余时间」就是余额池，
     * 不能拿旧的 durationMin 冒充，否则强停复活后余额会凭空回到初始时长。
     */
    public long remainingMs() {
        if (mode() == GateRules.MODE_BANK) return balanceMs();
        return sp.getLong("remainingMs", durationMin() * 60_000L);
    }

    /** 状态迁移：必须落盘，否则「崩溃/升级 = 免费续杯」。 */
    public void saveSnapshot(int phase, long remainingMs) {
        sp.edit().putInt("phase", phase).putLong("remainingMs", Math.max(0, remainingMs)).commit();
    }

    /** tick 心跳：允许丢，避免每秒同步写盘。 */
    public void tickSnapshot(long remainingMs) {
        sp.edit().putLong("remainingMs", Math.max(0, remainingMs)).putLong("heartbeatAt", System.currentTimeMillis()).apply();
    }

    public long heartbeatAt() { return sp.getLong("heartbeatAt", 0L); }

    // ---------- 规则引擎会话状态（1.6.0） ----------
    public int streak() { return sp.getInt("streak", 0); }
    public int wrongToday() { return sp.getInt("wrongToday", 0); }
    public int unlockToday() { return sp.getInt("unlockToday", 0); }
    /** 墙上时钟毫秒，0 = 不在冷却。重启后仍按绝对时间续算。 */
    public long cooldownUntil() { return sp.getLong("cooldownUntil", 0L); }
    public long cooldownLeftMs() { return Math.max(0L, cooldownUntil() - System.currentTimeMillis()); }

    /** 答题攒下的可观看时长；只增不因「结束会话」清零（跨天是否清零由 carryOver 决定）。 */
    public long balanceMs() { return sp.getLong("balanceMs", 0L); }
    public boolean balanceCarryOver() { return sp.getBoolean("balanceCarryOver", true); }
    public void setBalanceCarryOver(boolean v) { sp.edit().putBoolean("balanceCarryOver", v).commit(); }

    /** 连续观看时长：锁屏、休息、答题期间都不累加。 */
    public long watchMs() { return sp.getLong("watchMs", 0L); }
    /** 强制休息截止时刻（墙上时钟），0 = 不在休息。 */
    public long restUntil() { return sp.getLong("restUntil", 0L); }
    public long restLeftMs() { return Math.max(0L, restUntil() - System.currentTimeMillis()); }
    public String ruleDayKey() { return sp.getString("ruleDayKey", ""); }

    /**
     * 规则状态一次性落盘。答对/答错/跨天重置都走这里，避免「连对加了、冷却没写」这种
     * 断电后的半截状态。
     */
    public void saveRuleState(int streak, int wrongToday, int unlockToday, long cooldownUntil,
                              long balanceMs, long watchMs, long restUntil, String dayKey) {
        sp.edit()
                .putInt("streak", Math.max(0, streak))
                .putInt("wrongToday", Math.max(0, wrongToday))
                .putInt("unlockToday", Math.max(0, unlockToday))
                .putLong("cooldownUntil", Math.max(0L, cooldownUntil))
                .putLong("balanceMs", Math.max(0L, balanceMs))
                .putLong("watchMs", Math.max(0L, watchMs))
                .putLong("restUntil", Math.max(0L, restUntil))
                .putString("ruleDayKey", dayKey == null ? "" : dayKey)
                .commit();
    }

    /** 心跳允许丢：余额/连续观看每秒写一次就够，用 apply 不阻塞主线程。 */
    public void tickRuleState(long balanceMs, long watchMs) {
        sp.edit().putLong("balanceMs", Math.max(0L, balanceMs))
                .putLong("watchMs", Math.max(0L, watchMs))
                .putLong("heartbeatAt", System.currentTimeMillis()).apply();
    }

    /** 当日计数归零；余额默认保留，只有家长明确开了「跨天清余额」才清。 */
    public boolean resetDailyRules(String newDayKey) {
        long bal = balanceCarryOver() ? balanceMs() : 0L;
        saveRuleState(0, 0, 0, 0L, bal, 0L, 0L, newDayKey);
        return !balanceCarryOver();
    }

    public void addGap(long ms) {
        long v = sp.getLong("serviceGapMs", 0L) + ms;
        sp.edit().putLong("serviceGapMs", v).commit();
    }
    public long serviceGapMs() { return sp.getLong("serviceGapMs", 0L); }

    /** 记录「上次守护被系统终止」这件事，供首页提示；0 表示没有未读中断。 */
    public void markInterrupted(long gapMs) {
        sp.edit().putLong("interruptedGapMs", Math.max(1, gapMs)).commit();
    }
    public long interruptedGapMs() { return sp.getLong("interruptedGapMs", 0L); }
    public void clearInterrupted() { sp.edit().putLong("interruptedGapMs", 0L).commit(); }

    public void resetSession() {
        saveSnapshot(IDLE, durationMin() * 60_000L);
        // 答题积累模式下余额是「劳动所得」，结束会话不能清；只清当日过程态。
        String day = ruleDayKey().isEmpty() ? logicalDayKey() : ruleDayKey();
        saveRuleState(0, wrongToday(), unlockToday(), 0L, balanceMs(), 0L, 0L, day);
        clearStartHint();
    }

    // ---------- 开局 5 秒提示（必须跨进程存活） ----------
    /**
     * 小米电视在「应用带着系统悬浮窗回桌面」时会 Force stop 整个进程
     * （系统日志：MitvWindowManager: app.xxx has system window, so kill it），
     * 随后由通知监听在新进程里 RESTORE。所以提示截止时间不能只放服务实例字段，
     * 必须和会话快照一样落盘，否则「点开始 → 界面关闭」这一下就把提示弄丢了。
     * <p>用墙上时钟而不是 elapsedRealtime：后者重启后归零，会把开机前留下的
     * 截止时间误判成「还没到」，导致重启后一直挂着提示。
     */
    public void markStartHint(long ms) {
        sp.edit().putLong("startHintUntil", System.currentTimeMillis() + ms).commit();
    }

    public long startHintLeftMs() {
        return Math.max(0L, sp.getLong("startHintUntil", 0L) - System.currentTimeMillis());
    }

    public boolean startHintOn() {
        return phase() == RUNNING && startHintLeftMs() > 0L;
    }

    public void clearStartHint() {
        sp.edit().remove("startHintUntil").commit();
    }

    /** 服务启动时清掉过期值；进程被杀来不及清也没关系，startHintOn() 自己会过期。 */
    public void sweepStartHint() {
        if (!startHintOn()) clearStartHint();
    }

    // ---------- 卸载留痕 ----------
    /**
     * 区分「首次装完还没设口令就重进应用」和「卸载后重装」：
     * 两种情况下 hasPin() 都是 false、系统设置锚点都存在。这个标记随 /data/data 一起被卸载清掉，
     * 所以重装后必然是 false —— 正是我们要的判据。
     */
    public boolean anchorInit() { return sp.getBoolean("anchorInit", false); }

    public void markAnchorInit() {
        if (!anchorInit()) sp.edit().putBoolean("anchorInit", true).commit();
    }

    // ---------- 家长口令 ----------
    public boolean hasPin() { return sp.getString("pinHash", null) != null; }

    public boolean setPin(String pin) {
        if (pin == null || pin.length() < 4) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt);
        if (hash == null) return false;
        sp.edit()
                .putString("pinSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pinHash", Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt("failCount", 0)
                .putLong("lockUntil", 0L)
                .commit();
        return true;
    }

    /** 返回 0 = 通过；>0 = 还需等待的毫秒；-1 = 口令错误 */
    public long verifyPin(String pin) {
        long lockUntil = sp.getLong("lockUntil", 0L);
        long now = System.currentTimeMillis();
        if (lockUntil > now) return lockUntil - now;

        String saltB64 = sp.getString("pinSalt", null);
        String hashB64 = sp.getString("pinHash", null);
        if (saltB64 == null || hashB64 == null) return -1;
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        byte[] expect = Base64.decode(hashB64, Base64.NO_WRAP);
        byte[] actual = pbkdf2(pin, salt);
        if (actual != null && MessageDigest.isEqual(expect, actual)) {
            sp.edit().putInt("failCount", 0).putLong("lockUntil", 0L).commit();
            return 0;
        }
        int fail = sp.getInt("failCount", 0) + 1;
        if (fail >= MAX_FAIL) {
            sp.edit().putInt("failCount", 0).putLong("lockUntil", now + LOCKOUT_MS).commit();
            return LOCKOUT_MS;
        }
        sp.edit().putInt("failCount", fail).commit();
        return -1;
    }

    private static byte[] pbkdf2(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITER, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 恢复码 ----------

    public boolean hasRecoveryCode() { return sp.getString("rcHash", null) != null; }

    /** 家长是否在展示页点过「我已拍照保存」。false = 码在库里，但家长手上未必有。 */
    public boolean recoverySaved() { return sp.getBoolean("rcSaved", false); }

    public void markRecoverySaved() { sp.edit().putBoolean("rcSaved", true).commit(); }

    public long recoverySetAt() { return sp.getLong("rcSetAt", 0L); }

    /** 生成本机专属恢复码并落库；旧码在同一时刻失效。返回明文，由调用方展示给家长拍照。 */
    public String newRecoveryCode() {
        String code = Recovery.generate();
        setRecoveryCode(code);
        return code;
    }

    public boolean setRecoveryCode(String code) {
        String c = Recovery.normalize(code);
        if (!Recovery.valid(c)) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(c, salt);
        if (hash == null) return false;
        sp.edit()
                .putString("rcSalt", b64(salt))
                .putString("rcHash", b64(hash))
                .putLong("rcSetAt", System.currentTimeMillis())
                .putBoolean("rcSaved", false)
                .putInt("rcFail", 0)
                .putLong("rcLockUntil", 0L)
                .commit();
        return true;
    }

    /**
     * 返回 0 = 通过；>0 = 还需等待的毫秒；-1 = 码错；-2 = 格式错。
     * -2 不计入失败次数：校验位都过不了的串只可能是抄错/按错，不是猜。
     * 失败计数与口令完全独立，否则孩子乱输恢复码会把家长的日常口令一起锁死。
     */
    public long verifyRecoveryCode(String code) {
        String c = Recovery.normalize(code);
        if (!Recovery.valid(c)) return -2;

        long lockUntil = sp.getLong("rcLockUntil", 0L);
        long now = System.currentTimeMillis();
        if (lockUntil > now) return lockUntil - now;

        String saltB64 = sp.getString("rcSalt", null);
        String hashB64 = sp.getString("rcHash", null);
        if (saltB64 == null || hashB64 == null) return -1;
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        byte[] expect = Base64.decode(hashB64, Base64.NO_WRAP);
        byte[] actual = pbkdf2(c, salt);
        if (actual != null && MessageDigest.isEqual(expect, actual)) {
            sp.edit().putInt("rcFail", 0).putLong("rcLockUntil", 0L).commit();
            return 0;
        }
        int fail = sp.getInt("rcFail", 0) + 1;
        if (fail >= MAX_RC_FAIL) {
            sp.edit().putInt("rcFail", 0).putLong("rcLockUntil", now + RC_LOCKOUT_MS).commit();
            return RC_LOCKOUT_MS;
        }
        sp.edit().putInt("rcFail", fail).commit();
        return -1;
    }

    /**
     * 恢复流程的收尾：新口令 + 新恢复码在**同一个 commit** 里落盘。
     * 顺序不能反——先设口令、后烧旧码。否则中途断电/退出就是「码废了、口令也没设上」的死锁，
     * 那比原来「忘口令只能卸载」更糟。
     */
    public boolean resetPinAndRecovery(String newPin, String newRecoveryCode) {
        if (newPin == null || newPin.length() < 4) return false;
        String rc = Recovery.normalize(newRecoveryCode);
        if (!Recovery.valid(rc)) return false;

        byte[] pinSalt = new byte[16], rcSalt = new byte[16];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(pinSalt);
        rnd.nextBytes(rcSalt);
        byte[] pinHash = pbkdf2(newPin, pinSalt);
        byte[] rcHash = pbkdf2(rc, rcSalt);
        if (pinHash == null || rcHash == null) return false;

        return sp.edit()
                .putString("pinSalt", b64(pinSalt))
                .putString("pinHash", b64(pinHash))
                .putString("rcSalt", b64(rcSalt))
                .putString("rcHash", b64(rcHash))
                .putLong("rcSetAt", System.currentTimeMillis())
                .putBoolean("rcSaved", false)
                .putInt("failCount", 0)
                .putLong("lockUntil", 0L)
                .putInt("rcFail", 0)
                .putLong("rcLockUntil", 0L)
                .commit();
    }

    private static String b64(byte[] v) { return Base64.encodeToString(v, Base64.NO_WRAP); }

    // ---------- 崩溃熔断（SAFE 模式） ----------

    /**
     * 连续崩几次就熔断。
     * <p>判据是「真的崩过」而不是「启动过几次」：小米电视待机会强杀第三方应用
     * （系统日志 killAllThirdApp），按启动次数算的话家长按三次电源键就把守护熔断了。
     * 所以只有 {@code UncaughtExceptionHandler} 真触发时才记一笔。
     */
    public static final int MAX_CRASH = 3;

    /** 由 handler 同步写入；下次启动时消费。被系统强杀不会留下这个标记。 */
    public void noteCrash(String thread, Throwable e) {
        // 取最内层 cause：真机实测「服务启动崩溃」会被包成
        // "Unable to start service ... : java.lang.RuntimeException"，直接取外层等于没说根因
        Throwable root = e;
        while (root != null && root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = thread + ": "
                + (root == null ? "?" : root.getClass().getSimpleName() + ": " + root.getMessage());
        if (msg.length() > 200) msg = msg.substring(0, 200);
        sp.edit()
                .putBoolean("pendingCrash", true)
                .putString("crashInfo", msg)
                .putLong("crashAt", System.currentTimeMillis())
                .commit();
    }

    /** 启动时调用：消费上一次的崩溃标记，累计到 {@link #MAX_CRASH} 即熔断。 */
    public boolean consumeCrash() {
        if (!sp.getBoolean("pendingCrash", false)) return false;
        int n = sp.getInt("crashCount", 0) + 1;
        SharedPreferences.Editor ed = sp.edit().putBoolean("pendingCrash", false).putInt("crashCount", n);
        if (n >= MAX_CRASH) {
            ed.putBoolean("safeMode", true).putLong("safeAt", System.currentTimeMillis());
        }
        ed.commit();
        return true;
    }

    public boolean safeMode() { return sp.getBoolean("safeMode", false); }
    public int crashCount() { return sp.getInt("crashCount", 0); }
    public long safeAt() { return sp.getLong("safeAt", 0L); }
    public String crashInfo() { return sp.getString("crashInfo", ""); }

    /**
     * 稳定运行确认：清零崩溃计数。
     * <p>必须等服务真的活过一段时间再调（见 GateService.STABLE_MS）。启动即清零等于永远抓不到循环崩溃。
     */
    public void markStable() {
        if (sp.getInt("crashCount", 0) == 0 && !sp.getBoolean("pendingCrash", false)) return;
        sp.edit().putInt("crashCount", 0).putBoolean("pendingCrash", false).commit();
    }

    /** 家长手动解除熔断（设置页「守护状态」→ 口令）。 */
    public void clearSafeMode() {
        sp.edit().putBoolean("safeMode", false).putInt("crashCount", 0)
                .putBoolean("pendingCrash", false).putLong("safeAt", 0L).commit();
    }

    public String debugLine() {
        return String.format(Locale.US,
                "phase=%s remaining=%s gap=%ds pin=%s rc=%s safe=%s",
                phaseName(phase()), Core.fmt(remainingMs()), serviceGapMs() / 1000,
                hasPin() ? "set" : "none",
                hasRecoveryCode() ? (recoverySaved() ? "saved" : "unsaved") : "none",
                safeMode() ? ("on/" + crashCount()) : "off");
    }

    public static String phaseName(int p) {
        if (p == RUNNING) return "计时中";
        if (p == LOCKED) return "已锁定";
        if (p == REST) return "强制休息";
        if (p == OUT) return "时段外";
        return "未开始";
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
