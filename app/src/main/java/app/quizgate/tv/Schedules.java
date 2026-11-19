package app.quizgate.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * 每日答题时段（只在 Android 11 / API &lt; 31 验证）。
 *
 * <p>每天在 {@link Prefs#windowStartMin()} 自动开始一次，{@link Prefs#windowEndMin()} 硬结束。
 * 不跨天保存待开始：当天应用/通知监听/开机/打开应用/亮屏时，才安排当天的闹钟或补开始。
 * 屏幕黑着绝不点亮电视、不挂窗，只记一笔 pending，等亮屏再开始。
 *
 * <p>Android 12+ 使用精确闹钟需要 {@code SCHEDULE_EXACT_ALARM} 用户授权，本功能暂不覆盖；
 * {@link #supported()} 在 API 31+ 返回 false，设置页会提示“仅 Android 11”。
 */
public final class Schedules {
    private static final String TAG = "QuizGate";
    private static final int REQUEST = 0x51C4;

    private Schedules() {}

    public static boolean supported() {
        return Build.VERSION.SDK_INT < 31;
    }

    public static boolean inWindow(Prefs p, long wallMs) {
        int now = minutesOfDay(wallMs);
        int start = p.windowStartMin();
        int end = p.windowEndMin();
        return start < end && now >= start && now < end;
    }

    public static int minutesOfDay(long wallMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallMs);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    public static String hhmm(int min) {
        min = Math.max(0, Math.min(1439, min));
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    public static String windowText(Prefs p) {
        return hhmm(p.windowStartMin()) + "–" + hhmm(p.windowEndMin());
    }

    public static long todayStart(Prefs p, long wallMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallMs);
        c.set(Calendar.HOUR_OF_DAY, p.windowStartMin() / 60);
        c.set(Calendar.MINUTE, p.windowStartMin() % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static boolean beforeWindow(Prefs p, long wallMs) {
        return wallMs < todayStart(p, wallMs);
    }

    public static boolean interactive(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** 开机 / 通知监听连接 / 页面恢复 / 屏幕亮起时调用。 */
    public static void onWake(Context c) {
        onWake(c, interactive(c));
    }

    public static void onWake(Context c, boolean screenOn) {
        Prefs p = new Prefs(c);
        if (!supported() || !p.scheduleEnabled() || p.safeMode() || p.autoStartDoneToday()) return;
        if (p.phase() != Prefs.IDLE) return;   // 已有会话，不重复自动开始
        long now = System.currentTimeMillis();
        if (beforeWindow(p, now)) {
            arm(c, todayStart(p, now));
            return;
        }
        if (inWindow(p, now)) {
            if (screenOn) {
                startGate(c);
            } else {
                p.markAutoStartPending();
                Log.i(TAG, "schedule_pending（屏幕未亮，等亮屏再开始）");
            }
            return;
        }
        if (p.autoStartPendingToday()) p.clearAutoStartPending();
    }

    /** 精确闹钟触发。 */
    public static void onAlarm(Context c) {
        Prefs p = new Prefs(c);
        if (!supported() || !p.scheduleEnabled() || p.safeMode() || p.autoStartDoneToday()) return;
        if (p.phase() != Prefs.IDLE) return;
        long now = System.currentTimeMillis();
        if (beforeWindow(p, now)) {
            // 改系统时间等异常：把今天的闹钟重新排到时段开始
            arm(c, todayStart(p, now));
            return;
        }
        if (inWindow(p, now)) {
            if (interactive(c)) {
                startGate(c);
            } else {
                p.markAutoStartPending();
                Log.i(TAG, "schedule_alarm_pending（屏幕未亮）");
            }
        } else if (p.autoStartPendingToday()) {
            p.clearAutoStartPending();
        }
    }

    /** 屏幕亮起：补当天的闹钟，或补开始。 */
    public static void onScreenOn(Context c) {
        onWake(c, true);
    }

    public static void arm(Context c, long at) {
        if (!supported() || at <= System.currentTimeMillis()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ScheduleReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(TAG, "schedule_arm at=" + hhmm(minutesOfDay(at)));
        } catch (Throwable t) {
            Log.w(TAG, "schedule_arm failed: " + t);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ScheduleReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }

    private static void startGate(Context c) {
        Prefs p = new Prefs(c);
        if (p.autoStartDoneToday() || p.phase() != Prefs.IDLE) return;
        if (!Settings.canDrawOverlays(c)) {
            // 没有悬浮窗权限，自动开始不可能成功；标记已处理，避免每次亮屏反复尝试。
            Log.w(TAG, "schedule_start_no_overlay -> skip");
            p.markAutoStartDone();
            p.clearAutoStartPending();
            return;
        }
        p.markAutoStartDone();
        p.clearAutoStartPending();
        cancel(c);
        try {
            Intent i = new Intent(c, GateService.class).setAction(GateService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            Log.i(TAG, "schedule_start ok");
        } catch (Throwable t) {
            Log.w(TAG, "schedule_start failed: " + t);
        }
    }
}
