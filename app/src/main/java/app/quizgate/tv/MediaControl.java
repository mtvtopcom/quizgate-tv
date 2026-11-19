package app.quizgate.tv;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.KeyEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 答题锁定期间暂停后台视频，解锁后恢复。
 *
 * <p>优先用 {@link MediaSessionManager} 找到正在播放的会话并 {@code pause()}，
 * 只动「我们暂停过」的那几个包；解锁时按落盘的包名逐个 {@code play()}。
 *
 * <p>已经授予通知使用权，所以 {@code getActiveSessions(NotifProbe)} 可用，
 * 不需要额外权限，也不碰 CI 的权限白名单。
 *
 * <p>少数电视视频 App 不注册标准 MediaSession：这时若 {@link AudioManager#isMusicActive()}
 * 为真，用媒体键 PAUSE 兜底；只有确实检测到音频在动才记录，解锁时再发 PLAY，
 * 避免「本来就没在播，解锁后被我们误启动」。
 */
public final class MediaControl {
    private static final String TAG = "QuizGate";

    private MediaControl() {}

    /** 锁定瞬间调用：暂停正在播放的媒体，并把「我们暂停过谁」落盘。 */
    public static void pauseIfPlaying(Context c) {
        Prefs prefs = new Prefs(c);
        Set<String> paused = new HashSet<>();

        MediaSessionManager msm =
                (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm != null) {
            try {
                List<MediaController> sessions =
                        msm.getActiveSessions(new ComponentName(c, NotifProbe.class));
                if (sessions != null) {
                    for (MediaController mc : sessions) {
                        if (mc == null || c.getPackageName().equals(mc.getPackageName())) continue;
                        PlaybackState st = mc.getPlaybackState();
                        if (st == null) continue;
                        int s = st.getState();
                        if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_BUFFERING) {
                            continue;
                        }
                        try {
                            mc.getTransportControls().pause();
                            paused.add(mc.getPackageName());
                            Log.i(TAG, "media_pause pkg=" + mc.getPackageName());
                        } catch (Throwable t) {
                            Log.w(TAG, "media_pause one failed: " + t);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "media_pause sessions failed: " + t);
            }
        }

        if (!paused.isEmpty()) {
            prefs.setPausedPkgs(paused);
            prefs.setPausedByKey(false);
            return;
        }

        // 不注册标准会话的播放器：只有音频确实在动才兜底，避免误伤。
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) {
            dispatchKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
            prefs.setPausedPkgs(new HashSet<>());
            prefs.setPausedByKey(true);
            Log.i(TAG, "media_pause via keyevent (music active)");
        }
    }

    /** 解锁 / 正常结束会话时调用：只恢复「我们暂停过」的会话。 */
    public static void resumePaused(Context c) {
        Prefs prefs = new Prefs(c);
        Set<String> pkgs = prefs.pausedPkgs();
        boolean byKey = prefs.pausedByKey();
        if (pkgs.isEmpty() && !byKey) return;

        boolean resumed = false;
        MediaSessionManager msm =
                (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (!pkgs.isEmpty() && msm != null) {
            try {
                List<MediaController> sessions =
                        msm.getActiveSessions(new ComponentName(c, NotifProbe.class));
                if (sessions != null) {
                    for (MediaController mc : sessions) {
                        if (mc == null || !pkgs.contains(mc.getPackageName())) continue;
                        PlaybackState st = mc.getPlaybackState();
                        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) continue;
                        try {
                            mc.getTransportControls().play();
                            resumed = true;
                            Log.i(TAG, "media_resume pkg=" + mc.getPackageName());
                        } catch (Throwable t) {
                            Log.w(TAG, "media_resume one failed: " + t);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "media_resume sessions failed: " + t);
            }
        }

        if (!resumed && byKey) {
            dispatchKey(c, KeyEvent.KEYCODE_MEDIA_PLAY);
            Log.i(TAG, "media_resume via keyevent");
        }
        prefs.clearPausedMedia();
    }

    private static void dispatchKey(Context c, int code) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Throwable t) {
            Log.w(TAG, "dispatchKey " + code + " failed: " + t);
        }
    }
}
