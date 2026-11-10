package app.quizgate.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机/更新后恢复会话：只恢复未结束的会话，IDLE 不启动任何东西。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Prefs p = new Prefs(c);
        if (p.phase() == Prefs.IDLE) return;
        Intent s = new Intent(c, GateService.class).setAction(GateService.ACTION_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Throwable t) {
            // Android 12+ 对 MY_PACKAGE_REPLACED 等后台启动有限制：这里失败只是「没恢复」，不能崩
        }
    }
}
