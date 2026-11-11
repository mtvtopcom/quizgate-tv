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
    public static final int IDLE = 0, RUNNING = 1, LOCKED = 2;

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
    public int durationMin() { return sp.getInt("durationMin", 30); }
    public void setDurationMin(int v) { sp.edit().putInt("durationMin", clamp(v, 1, 240)).commit(); }

    public int extendMin() { return sp.getInt("extendMin", 5); }
    public void setExtendMin(int v) { sp.edit().putInt("extendMin", clamp(v, 1, 60)).commit(); }

    /** 学段：0 = 小学（1-6 年级），1 = 初中（初一/初二/初三 = 7/8/9 年级）。 */
    public static final int STAGE_PRIMARY = 0, STAGE_JUNIOR = 1;

    /** 科目：0 = 语文，1 = 数学，2 = 英语。题库文件名为 <subject>_grade_<n>.json。 */
    public static final int SUBJECT_CHINESE = 0, SUBJECT_MATH = 1, SUBJECT_ENGLISH = 2;
    public static final String[] SUBJECT_KEYS = {"chinese", "math", "english"};
    public static final String[] SUBJECT_NAMES = {"语文", "数学", "英语"};

    public int subject() { return clamp(sp.getInt("subject", SUBJECT_MATH), 0, 2); }

    public void setSubject(int v) { sp.edit().putInt("subject", clamp(v, 0, 2)).commit(); }

    public String subjectKey() { return SUBJECT_KEYS[subject()]; }

    public String subjectName() { return SUBJECT_NAMES[subject()]; }

    /**
     * 1.3.0：家长在锁屏遮罩上输对过一次口令后，到「开始新一局计时」为止进设置不再要口令。
     *
     * <p><b>必须落盘</b>：小米电视回桌面 / 待机会强停第三方进程，只放进程内静态变量
     * 活不过这一下，家长刚解锁完、按 HOME 再回来又被要一次口令。卸载重装会随
     * gate prefs 一起清掉，不会把解锁状态带到新安装。
     */
    public boolean parentUnlocked() { return sp.getBoolean("parentUnlocked", false); }

    public void setParentUnlocked(boolean v) {
        sp.edit().putBoolean("parentUnlocked", v).commit();
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

    public long remainingMs() { return sp.getLong("remainingMs", durationMin() * 60_000L); }

    /** 状态迁移：必须落盘，否则「崩溃/升级 = 免费续杯」。 */
    public void saveSnapshot(int phase, long remainingMs) {
        sp.edit().putInt("phase", phase).putLong("remainingMs", Math.max(0, remainingMs)).commit();
    }

    /** tick 心跳：允许丢，避免每秒同步写盘。 */
    public void tickSnapshot(long remainingMs) {
        sp.edit().putLong("remainingMs", Math.max(0, remainingMs)).putLong("heartbeatAt", System.currentTimeMillis()).apply();
    }

    public long heartbeatAt() { return sp.getLong("heartbeatAt", 0L); }

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
        return p == RUNNING ? "计时中" : p == LOCKED ? "已锁定" : "未开始";
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
