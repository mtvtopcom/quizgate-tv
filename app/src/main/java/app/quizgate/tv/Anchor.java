package app.quizgate.tv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 卸载留痕：把「这台电视装过答题续时」记在应用数据之外。
 *
 * <p>卸载 APK 会连 /data/data 一起清空（pinHash、恢复码、会话全没），重装后
 * {@code hasPin()==false} 就走「首次安装」分支——孩子自己设个新口令，家长看到的是
 * 一个干净的新应用，完全不知道被卸过。{@code Settings.System} / {@code Settings.Secure}
 * 属于系统设置库，卸载 APK 不会清，遥控器也删不掉（只有 adb / root 能改），
 * 重装时一读就知道。</p>
 *
 * <p><b>隐私</b>：只写首次安装时间、最后心跳、累计卸载次数、最近一次失联时长，
 * 绝不写口令 / 恢复码 / pinHash / 任何凭据派生值——Settings.Secure 全局可读
 * （{@code adb shell settings get secure ...} 不需要 root）。</p>
 *
 * <p><b>只告警、不拦截</b>：重装后仍由用户自己设 6 位口令，不改既有语义。
 * 若反过来要求「原恢复码」，忘口令且丢码的家长就只能卸载重装，会形成死循环。</p>
 *
 * <p>两条装机授权任一生效即可：{@code appops set ... WRITE_SETTINGS allow} 写 System，
 * {@code pm grant ... WRITE_SECURE_SETTINGS} 写 Secure。读取两个命名空间并取心跳较新的一份，
 * 因此只补了其中一条也能工作。</p>
 *
 * <p><b>双机实测（D1 / Android 11、D2 / Android 14）</b>：两套固件都在
 * {@code Settings.System.putString} 自定义键上抛
 * "You cannot keep your settings in the secure settings."，真正生效的是 {@code pm grant}
 * 写进 Secure 的那条；System 分支保留给其它固件兜底，失败一次后本进程不再重试。</p>
 */
public final class Anchor {
    private Anchor() {}

    private static final String TAG = "QuizGate";
    public static final String KEY = "quizgate_install_anchor";

    private static final String K_VER = "v";
    private static final String K_FIRST = "first";
    private static final String K_SEEN = "seen";
    private static final String K_COUNT = "uninstalls";
    private static final String K_GAP = "gap";

    /** Android 14 / 部分厂商固件禁止第三方在 Settings.System 放自定义键，试一次就别再试。 */
    private static volatile boolean systemUnsupported = false;

    /** 本次冷启动的判定结果。warning 只要卸载过就非 null，用于永久红字，不是一次性提示。 */
    public static final class Report {
        public final boolean enabled;          // 至少一个命名空间可写（留痕已启用）
        public final boolean justReinstalled;  // 本次启动才检测到重装
        public final int uninstalls;
        public final long firstInstallAt, lastSeenAt, gapMs;
        public final String warning;

        Report(boolean enabled, boolean justReinstalled, int uninstalls,
               long firstInstallAt, long lastSeenAt, long gapMs, String warning) {
            this.enabled = enabled;
            this.justReinstalled = justReinstalled;
            this.uninstalls = uninstalls;
            this.firstInstallAt = firstInstallAt;
            this.lastSeenAt = lastSeenAt;
            this.gapMs = gapMs;
            this.warning = warning;
        }

        /** 设置页里的完整说明；首页只显示 warning 那一行。 */
        public String detail() {
            if (uninstalls <= 0) return "未发现卸载记录 · 首次安装 " + fmtTime(firstInstallAt);
            return "累计 " + uninstalls + " 次；最近一次约 " + fmtTime(lastSeenAt)
                    + " 之后失联 " + fmtGap(gapMs) + "；首次安装 " + fmtTime(firstInstallAt)
                    + "。此记录存在系统设置里，卸载与重装都擦不掉。";
        }
    }

    /**
     * 每次冷启动调用一次。
     * 首次安装会建锚点；重装（锚点在、本机却没口令、且 prefs 没有首装标记）会累加次数并重算失联；
     * 正常启动只刷新心跳。
     */
    public static Report sync(Context c, Prefs prefs) {
        long now = System.currentTimeMillis();
        JSONObject a = read(c);

        if (a == null) {
            JSONObject fresh = base(now, now, 0, 0L);
            boolean ok = write(c, fresh);
            prefs.markAnchorInit();
            Log.i(TAG, "anchor_init enabled=" + ok + " at=" + fmtTime(now));
            return new Report(ok, false, 0, now, now, 0L, null);
        }

        long first = a.optLong(K_FIRST, now);
        long seen = a.optLong(K_SEEN, first);
        int count = Math.max(0, a.optInt(K_COUNT, 0));
        long gap = a.optLong(K_GAP, 0L);

        // 首次安装后、还没设口令就重进应用：锚点已在但 prefs 里有首装标记，不是卸载
        boolean reinstalled = !prefs.hasPin() && !prefs.anchorInit();
        if (reinstalled) {
            count += 1;
            gap = Math.max(0L, now - seen);
            Log.w(TAG, "anchor_reinstall count=" + count + " last_seen=" + fmtTime(seen)
                    + " gap=" + gap + "ms");
        }
        boolean ok = write(c, base(first, now, count, gap));
        prefs.markAnchorInit();

        String warning = count > 0
                ? "⚠ 检测到曾被卸载 " + count + " 次 · 最近失联 " + fmtGap(gap) + " → 见「设置」"
                : null;
        return new Report(ok, reinstalled, count, first, seen, gap, warning);
    }

    /** 只刷新心跳，不动计数。计时中界面已关闭，靠它让「最后在线」继续往前走。 */
    public static void touch(Context c) {
        JSONObject a = read(c);
        if (a == null) return;
        long first = a.optLong(K_FIRST, System.currentTimeMillis());
        write(c, base(first, System.currentTimeMillis(),
                Math.max(0, a.optInt(K_COUNT, 0)), a.optLong(K_GAP, 0L)));
    }

    /** 自检用：当前进程能否写入任一命名空间。 */
    public static boolean writable(Context c) {
        return Settings.System.canWrite(c)
                || c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                        == PackageManager.PERMISSION_GRANTED;
    }

    public static String adbSystem(String pkg) {
        return "adb shell appops set " + pkg + " WRITE_SETTINGS allow";
    }

    public static String adbSecure(String pkg) {
        return "adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS";
    }

    // ---------- 读写 ----------

    private static JSONObject base(long first, long seen, int count, long gap) {
        try {
            return new JSONObject()
                    .put(K_VER, 1)
                    .put(K_FIRST, first)
                    .put(K_SEEN, seen)
                    .put(K_COUNT, count)
                    .put(K_GAP, gap);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 读两个命名空间，取心跳较新的一份；读系统设置不需要任何权限。 */
    private static JSONObject read(Context c) {
        JSONObject sys = null, sec = null;
        try {
            sys = parse(Settings.System.getString(c.getContentResolver(), KEY));
        } catch (Throwable ignore) {
        }
        try {
            sec = parse(Settings.Secure.getString(c.getContentResolver(), KEY));
        } catch (Throwable ignore) {
        }
        if (sys == null) return sec;
        if (sec == null) return sys;
        return sys.optLong(K_SEEN, 0L) >= sec.optLong(K_SEEN, 0L) ? sys : sec;
    }

    private static JSONObject parse(String s) {
        if (s == null || s.length() < 2) return null;
        try {
            JSONObject o = new JSONObject(s);
            return o.optInt(K_VER, 0) == 1 && o.optLong(K_FIRST, 0L) > 0L ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 两个命名空间都试；写后立刻读回确认，防止固件静默丢弃自定义键。 */
    private static boolean write(Context c, JSONObject o) {
        String s = o.toString();
        boolean ok = false;
        if (!systemUnsupported) {
            try {
                if (Settings.System.canWrite(c)) {
                    Settings.System.putString(c.getContentResolver(), KEY, s);
                    ok = s.equals(Settings.System.getString(c.getContentResolver(), KEY));
                }
            } catch (IllegalArgumentException e) {
                // 小米 / Android 14：「You cannot keep your settings in the secure settings.」
                // 该固件不许第三方往 System 放自定义键。记一次就跳过，别让每分钟的心跳都抛异常。
                systemUnsupported = true;
                Log.i(TAG, "anchor_system_unsupported: " + e.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "anchor_system_write_failed: " + t);
            }
        }
        try {
            if (c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED) {
                Settings.Secure.putString(c.getContentResolver(), KEY, s);
                ok |= s.equals(Settings.Secure.getString(c.getContentResolver(), KEY));
            }
        } catch (Throwable t) {
            Log.w(TAG, "anchor_secure_write_failed: " + t);
        }
        return ok;
    }

    // ---------- 展示 ----------

    private static String fmtTime(long ms) {
        if (ms <= 0L) return "未知";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    /** 粗粒度就好：这是给家长看的「大概失联多久」，不是计费。 */
    public static String fmtGap(long ms) {
        long min = Math.max(0L, ms) / 60_000L;
        if (min < 1) return "不到 1 分钟";
        if (min < 60) return min + " 分钟";
        long hour = min / 60;
        long rest = min % 60;
        if (hour < 48) return hour + " 小时" + (rest > 0 ? " " + rest + " 分" : "");
        return (hour / 24) + " 天" + (hour % 24 > 0 ? " " + (hour % 24) + " 小时" : "");
    }
}
