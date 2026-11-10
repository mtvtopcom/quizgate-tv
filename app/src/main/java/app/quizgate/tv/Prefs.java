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

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    // ---------- 设置 ----------
    public int durationMin() { return sp.getInt("durationMin", 30); }
    public void setDurationMin(int v) { sp.edit().putInt("durationMin", clamp(v, 1, 240)).commit(); }

    public int extendMin() { return sp.getInt("extendMin", 5); }
    public void setExtendMin(int v) { sp.edit().putInt("extendMin", clamp(v, 1, 60)).commit(); }

    public int grade() { return sp.getInt("grade", 3); }
    public void setGrade(int v) { sp.edit().putInt("grade", clamp(v, 1, 9)).commit(); }

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

    public String debugLine() {
        return String.format(Locale.US,
                "phase=%s remaining=%s gap=%ds pin=%s",
                phaseName(phase()), Core.fmt(remainingMs()), serviceGapMs() / 1000, hasPin() ? "set" : "none");
    }

    public static String phaseName(int p) {
        return p == RUNNING ? "计时中" : p == LOCKED ? "已锁定" : "未开始";
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
