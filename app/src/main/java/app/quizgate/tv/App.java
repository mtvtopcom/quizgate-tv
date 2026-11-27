package app.quizgate.tv;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * 进程级崩溃记录，供 SAFE 熔断使用（ADR-8）。
 *
 * <p>为什么不在 GateService 里 try/catch 了事：真正会炸的是 {@code wm.addView}、资源加载、
 * 焦点树这些分散在各处的地方，靠包一层 try 永远漏。只有进程级的
 * {@link Thread.UncaughtExceptionHandler} 能保证「任何未捕获异常都记一笔」。
 *
 * <p>为什么用 {@code commit()} 而不是 {@code apply()}：进程马上就死了，异步落盘会丢。
 * 崩溃路径上多几十毫秒的同步 IO 是值得的。
 *
 * <p>覆盖不到的：native crash、ANR 被杀、LMK/系统强杀、{@code System.exit}。
 * 这几种都不会留下 pendingCrash 标记 —— 对熔断来说这是**正确**的取舍：
 * 小米待机的 killAllThirdApp 属于这一类，不能把它算成「我们崩了」。
 */
public class App extends Application {
    private static final String TAG = "QuizGate";

    /**
     * 守护服务是否在本进程里活着。
     * <p>只有它崩了才是「开机循环」的成因：BootReceiver/NotifProbe 拉起服务 → 立刻崩 → 再拉起。
     * 设置页（Activity）崩溃不会自行循环（需要人手动点），所以不计入熔断，否则家长点开设置页
     * 崩一次就把守护熔断了。
     * <p>这是 volatile 而不是普通字段：崩溃可能发生在任何线程。
     */
    static volatile boolean gateAlive;

    /**
     * 进程内的解锁快速路径。真正的准绳是 {@link Prefs#parentUnlocked()}（落盘）：
     * 电视随时会强停进程，只靠静态变量活不过一次回收。
     *
     * <p>1.3.0 起：在锁屏遮罩上输对口令后不再直接「退出到桌面」，而是结束会话并把应用首页打开；
     * 从这一刻起，只要没开始新的计时，进「设置」就不再重复要口令。
     * 开始计时（{@code doStart()}）会把两处一起清回 false —— 新一局仍然要上锁。
     */
    static volatile boolean parentUnlocked;

    /**
     * 题库上传服务的进程级句柄。
     *
     * <p>不能放在 {@link BankManagerView} 里随页面生死：二维码页一关、或焦点被系统
     * 抢走导致页面重建，服务就没了，家长手上那张二维码随之失效（实测「过一阵连不上」）。
     * 交给 Application 持有后，只要进程还活着，token 和端口就稳定，旧二维码一直可用；
     * 只有进程被系统强停才会重建 —— 那种情况重进页面会显示新二维码。
     */
    static volatile BankHttpServer bankServer;

    /**
     * 家长此刻是不是在「答题续时」自己的界面里（首页/设置/答题方式/题库管理）。
     *
     * <p>为什么要有它：本软件页面不是电视内容，停留在这里不该扣观看时长。
     * {@link GateService} 的计时循环据此暂停，回到电视（Activity onPause）立刻恢复。
     * <p>为什么用静态字段＋服务 action 双通道：静态字段给同一进程内的即时判断，
     * action 让服务在「刚切走」这一刻就 reconcile；进程被系统杀掉后静态字段归 false，
     * 正好等价于「不在软件里」，行为正确。
     */
    static volatile boolean uiVisible;

    @Override public void onCreate() {
        super.onCreate();
        final Context ctx = getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                if (gateAlive) {
                    new Prefs(ctx).noteCrash(t == null ? "?" : t.getName(), e);
                    Log.e(TAG, "crash_recorded thread=" + (t == null ? "?" : t.getName()), e);
                } else {
                    // 界面崩溃：只留日志。不写 pendingCrash，避免把「设置页崩了」当成「守护崩了」
                    Log.e(TAG, "crash_in_ui（不计入熔断）", e);
                }
            } catch (Throwable ignore) {
                // 崩溃路径上再崩就只能放弃记录，绝不能掩盖原始异常
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                // 理论上不会走到：ART 默认 handler 一定存在。兜底是为了不让半死线程挂着
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }
}
