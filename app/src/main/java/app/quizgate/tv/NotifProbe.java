package app.quizgate.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 存活锚点：系统会把已授权的通知监听重新绑定，从而把进程拉回来（实测强停后 ~150ms 就重新绑定）。
 * 只作为「复活信号」，不读通知标题/正文。
 *
 * 关键约束（实测）：待机过程中**启动前台服务会把电视从待机里拽出来**（屏幕自己亮）。
 * 所以复活只"记一笔"，等屏幕真正亮起（SCREEN_ON）才把计时服务接回去。
 */
public class NotifProbe extends NotificationListenerService {
    private static final String TAG = "QuizGate";
    private BroadcastReceiver screenRx;

    /** 媒体暂停需要读当前活动通知；只在本服务被系统绑定期间非空。 */
    private static volatile NotifProbe active;

    public static NotifProbe active() { return active; }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        active = this;
        Prefs p = new Prefs(this);
        int phase = p.phase();
        Log.i(TAG, "notif_connected phase=" + phase + " remaining=" + Core.fmt(p.remainingMs()));
        // 总开关关闭 = 用户主动停了守护，系统重新绑定也不能把它拉回来
        if (!p.gateOn()) {
            Log.i(TAG, "gate_off -> 不复活服务");
            unregisterSelf();
            return;
        }
        // 熔断期间不复活：系统重新绑定通知监听是最高频的拉起路径，这里必须挡住
        if (p.safeMode()) {
            Log.e(TAG, "safe_mode -> 不再复活服务");
            return;
        }
        if (phase == Prefs.IDLE) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive()) {
            startGate("immediate");
        } else {
            // 屏幕是黑的：现在起 FGS 会把电视点亮，必须等 SCREEN_ON
            Log.i(TAG, "resurrect_deferred（屏幕未亮，等亮屏再起服务）");
            if (screenRx == null) {
                screenRx = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                            Log.i(TAG, "resurrect_on_screen_on");
                            startGate("screen_on");
                            unregisterSelf();
                        }
                    }
                };
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(screenRx, f);
                }
            }
        }
    }

    private void startGate(String why) {
        try {
            Intent i = new Intent(this, GateService.class).setAction(GateService.ACTION_RESTORE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Log.i(TAG, "resurrect_restore ok via " + why);
        } catch (Throwable t) {
            Log.w(TAG, "resurrect_restore failed(" + why + "): " + t);
        }
    }

    private void unregisterSelf() {
        if (screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Throwable ignore) { }
            screenRx = null;
        }
    }

    @Override public void onListenerDisconnected() {
        active = null;
        super.onListenerDisconnected();
        Log.i(TAG, "notif_disconnected");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && !getPackageName().equals(sbn.getPackageName())) {
            Log.i(TAG, "notif_posted pkg=" + sbn.getPackageName());
        }
    }
}
