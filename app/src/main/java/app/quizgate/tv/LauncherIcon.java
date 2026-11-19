package app.quizgate.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 桌面入口 alias 的显示/隐藏。
 *
 * <p>只在「答题时段」开启时才允许隐藏：家长入口是时段到点后的答题遮罩（菜单键 + 6 位口令）。
 * 关闭答题时段时 {@link Prefs#setScheduleEnabled(boolean)} 会清掉 hidden 标记，
 * {@link #apply(Context, Prefs)} 随即恢复 alias，避免把家长锁在应用外面。
 *
 * <p>系统设置 → 应用里仍然能看到本包；隐藏只影响桌面，不防卸载。
 */
public final class LauncherIcon {
    private static final String TAG = "QuizGate";
    public static final String ALIAS = "app.quizgate.tv.LauncherAlias";

    private LauncherIcon() {}

    public static void apply(Context c, Prefs p) {
        setHidden(c, p.launcherHidden() && p.scheduleEnabled() && Schedules.supported());
    }

    public static void setHidden(Context c, boolean hidden) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, ALIAS);
            pm.setComponentEnabledSetting(cn,
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Log.i(TAG, "launcher_alias " + (hidden ? "hidden" : "shown"));
        } catch (Throwable t) {
            Log.w(TAG, "launcher_alias failed: " + t);
        }
    }
}
