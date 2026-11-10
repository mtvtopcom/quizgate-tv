package app.quizgate.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * 接收 {@link PackageInstaller} 的 commit 结果。
 *
 * <p>不声明这个 receiver 的后果：安装失败（尤其是「签名不一致」这种最常见的一种）会静默无事发生，
 * 家长只看到升级页一直停在「正在安装」。
 *
 * <p><b>最容易漏的一步</b>：{@code STATUS_PENDING_USER_ACTION} 并不是「已经弹出来了」，
 * 而是「系统把确认界面打包成一个 Intent 给你，要你自己 startActivity」。
 * 漏了这一步的症状非常迷惑：日志里 {@code install_commit ok} 一切正常，屏幕上却什么都没发生。
 */
public class InstallResultReceiver extends BroadcastReceiver {
    static final String ACTION = "app.quizgate.tv.INSTALL_RESULT";

    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i("QuizGate", "install_status=" + status + " msg=" + msg);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                    Log.i("QuizGate", "install_confirm_shown");
                } catch (Throwable t) {
                    Log.e("QuizGate", "install_confirm_failed: " + t);
                }
            }
        }
        Updater.deliverInstallResult(c, status, msg);
    }
}
