package app.quizgate.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/**
 * 答题反馈音效：答对「叮」、答错「咚」。
 *
 * <p>用 SoundPool 而不是 MediaPlayer：短音效要的是「按下即响」，MediaPlayer 每次
 * prepare 会有几十毫秒延迟，叠在动画上会显得迟钝。资源是 22.05kHz 单声道 wav，
 * 两个加起来不到 30KB，放在 res/raw 里不占安装体积。
 *
 * <p>静音策略：池子建不起来（老旧固件没有 SoundPool.Builder）就静默降级，
 * 绝不能因为放不出声音把答题流程搞崩——遮罩起不来等于没守护。
 */
public final class Sfx {

    private static final String TAG = "QuizGate";

    private static SoundPool pool;
    private static int dingId, dongId;
    /** 已加载完成的样本数；为 0 时 play 直接跳过，避免首题「哑火」刷日志。 */
    private static volatile int loaded = 0;

    private Sfx() {}

    private static void ensure(Context c) {
        if (pool != null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool p = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
            p.setOnLoadCompleteListener((sp, sampleId, status) -> {
                if (status == 0) loaded++;
            });
            dingId = p.load(c, R.raw.ding, 1);
            dongId = p.load(c, R.raw.dong, 1);
            pool = p;
        } catch (Throwable t) {
            Log.w(TAG, "sfx init failed: " + t);
            pool = null;
        }
    }

    public static void correct(Context c) { play(c, true); }

    public static void wrong(Context c) { play(c, false); }

    private static void play(Context c, boolean ok) {
        ensure(c);
        if (pool == null || loaded <= 0) return;
        try {
            int id = ok ? dingId : dongId;
            if (id <= 0) return;
            // 音量压到 0.6/0.5：电视音量普遍开得偏大，满幅会和视频声音打架
            float v = ok ? 0.6f : 0.5f;
            pool.play(id, v, v, 1, 0, 1f);
        } catch (Throwable ignored) {
            // 播放失败不影响判题
        }
    }
}
