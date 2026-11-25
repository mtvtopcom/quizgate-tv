package app.quizgate.tv;

import android.content.Context;
import android.view.KeyEvent;
import android.media.AudioManager;

/**
 * 出题时暂停电视正在播放的节目。
 *
 * <p>优先走通知使用权：通知监听服务能拿到媒体通知里的 MediaSession.Token，
 * 用 MediaController 发 pause —— 这是唯一能精确暂停「当前这个播放器」的办法。
 * 拿不到 token 时降级发媒体键（对准当前有音频焦点的应用）。
 * 所有失败都只记日志、绝不抛到遮罩线程上。
 */
public final class MediaControl {
    private MediaControl() {}

    public static void pause(Context c) {
        try {
            NotifProbe probe = NotifProbe.active();
            if (probe != null) {
                android.service.notification.StatusBarNotification[] list = probe.getActiveNotifications();
                if (list != null) {
                    for (android.service.notification.StatusBarNotification sbn : list) {
                        if (sbn == null || sbn.getNotification() == null) continue;
                        android.os.Bundle ex = sbn.getNotification().extras;
                        if (ex == null) continue;
                        android.media.session.MediaSession.Token token =
                                ex.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION);
                        if (token == null) continue;
                        android.media.session.MediaController mc =
                                new android.media.session.MediaController(c, token);
                        if (mc.getTransportControls() != null) {
                            mc.getTransportControls().pause();
                            android.util.Log.i("QuizGate", "media_pause via session pkg=" + sbn.getPackageName());
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("QuizGate", "media_pause session failed: " + t);
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
                android.util.Log.i("QuizGate", "media_pause via keyevent");
            }
        } catch (Throwable t) {
            android.util.Log.w("QuizGate", "media_pause keyevent failed: " + t);
        }
    }
}
