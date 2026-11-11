package app.quizgate.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.WindowManager;

/**
 * 计时 + 窗口宿主（决策文档 ADR-4：单进程单前台服务）。
 * 计时用 uptimeMillis 增量累加 + isInteractive 门控（ADR-1）；
 * 窗口由 reconcile(phase) 驱动，不由事件直接 add/remove（防重复 addView 崩溃）。
 */
public class GateService extends Service implements MaskView.Host {
    private static final String TAG = "QuizGate";
    public static final String ACTION_START = "app.quizgate.tv.START";
    public static final String ACTION_RESTORE = "app.quizgate.tv.RESTORE";
    public static final String ACTION_EXIT = "app.quizgate.tv.EXIT";

    private static final String CHANNEL = "gate";
    private static final int NOTIF_ID = 1001;
    private static final long TICK_MS = 200L;
    private static final long MINI_THRESHOLD_MS = 30_000L;
    /** 点「开始计时」后右下角亮 5 秒，告诉家长这次设了多久；之后仍按原逻辑只在最后 30 秒出现。 */
    private static final long START_HINT_MS = 5_000L;
    /**
     * 服务活过这么久才算「稳定」，崩溃计数归零。
     * <p>取 60s 的理由：崩溃循环的典型特征是「起来就炸」（毫秒级到几秒级），而正常的一次
     * 短会话至少也有几十秒。**不能启动即清零**，否则崩了又清、永远累计不到 3 次。
     * <p>代价：如果某个崩溃恰好发生在启动后 70 秒，它会被当成稳定而漏检。这种慢速偶发崩溃
     * 本身不构成「电视不能用」，熔断与否都不影响可用性，所以接受。
     */
    private static final long STABLE_MS = 60_000L;

    private Prefs prefs;
    private QuizBank bank;
    private WindowManager wm;
    private PowerManager pm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private MaskView mask;
    private MiniView mini;
    private long remaining;
    private long lastTick;
    private int tickCount;
    private boolean running;
    /** 熔断时置位：本进程已被判死，onStartCommand 不得再干任何事。 */
    private boolean aborted;
    private long bootAt;
    private boolean stableMarked;
    private BroadcastReceiver screenRx;
    private long offU = 0, offE = 0;
    private boolean pendingMask = false;
    /** 遮罩被切走（HOME / 切应用）后重新置顶的节流。 */
    private long lastRegrabAt = 0L;
    private int focusLostCount = 0;
    /**
     * 开局提示的收尾。截止时间存在 Prefs 里而不是本实例字段：小米电视回桌面时会因
     * 「应用还挂着系统悬浮窗」直接 Force stop 进程，守护是在**新进程**里由 NotifProbe
     * RESTORE 复活的，实例字段活不过这一下。
     */
    private final Runnable hintDismiss = () -> {
        prefs.clearStartHint();
        if (running && prefs.phase() == Prefs.RUNNING && remaining > MINI_THRESHOLD_MS) removeMini();
        Log.i(TAG, "start_hint dismissed");
    };
    /** 设备适配期的诊断开关：每 5 秒输出一行 inter/disp，用来观察厂商电源键到底改了什么。 */
    private static final boolean DIAG = true;

    @Override
    public void onCreate() {
        super.onCreate();
        // 第一行就置位：之后本进程内任何崩溃都会被判为「守护崩了」
        App.gateAlive = true;
        prefs = new Prefs(this);
        prefs.sweepStartHint();

        // 必须无条件先 startForeground：前台服务 5 秒内没 startForeground 会被系统判
        // ForegroundServiceDidNotStartInTimeException，而熔断路径同样要进 onCreate，
        // 不能在这条路上省这一步、也不能先做耗时检查。
        startAsForeground();

        // 熔断检查必须早于一切窗口/计时逻辑。光「不挂遮罩」不够 —— 崩溃循环时
        // 系统按 START_STICKY 无限重启，重启本身就在闪屏耗电，必须让它别再拉我们起来。
        if (!admitBoot()) {
            aborted = true;
            stopForeground(true);
            stopSelf();
            return;
        }

        bank = new QuizBank(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        registerScreenReceiver();

        remaining = prefs.remainingMs();
        lastTick = SystemClock.uptimeMillis();

        long hb = prefs.heartbeatAt();
        if (hb > 0) {
            long gap = System.currentTimeMillis() - hb;
            if (gap > 120_000L) {
                prefs.addGap(gap);
                prefs.markInterrupted(gap);
                Log.i(TAG, "service_gap=" + (gap / 1000) + "s（守护被系统终止过，这段时间不扣时）");
            }
        }
        Log.i(TAG, "service_create " + prefs.debugLine());
    }

    /**
     * 熔断闸门。返回 false 表示本次启动不允许挂任何窗口。
     * <p>被系统强杀（小米 killAllThirdApp / LMK）不会留 pendingCrash，所以不会被误判成崩溃。
     */
    private boolean admitBoot() {
        prefs.consumeCrash();
        if (prefs.safeMode()) {
            Log.e(TAG, "safe_mode 连续 " + Prefs.MAX_CRASH + " 次崩溃（最后一次 " + prefs.crashInfo()
                    + "）-> 停止守护，不再重启");
            notify("守护已停止（安全模式）：连续崩溃，请到设置页处理");
            return false;
        }
        bootAt = SystemClock.uptimeMillis();
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);

        // 熔断/已放弃：绝不返回 START_STICKY，否则系统会一直把我们拉起来
        if (aborted || prefs == null || prefs.safeMode()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_EXIT.equals(action)) {
            exitSession();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            // 测试钩子：--ei durationMin 1 / --el remainingMs 4000，仅用于现场调试
            int ovMin = intent.getIntExtra("durationMin", 0);
            long ovMs = intent.getLongExtra("remainingMs", -1L);
            if (ovMin > 0) prefs.setDurationMin(ovMin);
            remaining = ovMs > 0 ? ovMs : prefs.durationMin() * 60_000L;
            prefs.saveSnapshot(Prefs.RUNNING, remaining);
            prefs.markStartHint(START_HINT_MS);
            Log.i(TAG, "start_hint show " + START_HINT_MS + "ms remaining=" + Core.fmt(remaining));
        } else {
            remaining = prefs.remainingMs();
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission -> 守护不可信，会话作废");
            prefs.resetSession();
            notify("缺少悬浮窗权限，守护已停止");
            stopSelf();
            return START_NOT_STICKY;
        }

        lastTick = SystemClock.uptimeMillis();
        running = true;
        scheduleHintDismiss();   // START 或 RESTORE 都要重排：新进程只负责剩下的那几秒
        reconcile();
        h.removeCallbacks(ticker);
        h.post(ticker);
        updateNotification();
        return START_STICKY;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            long delta = now - lastTick;
            lastTick = now;
            if (delta < 0) delta = 0;

            // 稳定存活确认：只有活够了才清零，见 STABLE_MS 的注释
            if (!stableMarked && now - bootAt >= STABLE_MS) {
                stableMarked = true;
                prefs.markStable();
                Log.i(TAG, "stable " + (STABLE_MS / 1000) + "s -> 崩溃计数归零");
            }

            // 熄屏期间被挂起的遮罩：一旦判定「屏幕真的亮了」再挂，避免遮罩把电视唤醒
            if (prefs.phase() == Prefs.LOCKED && pendingMask && screenOn()) {
                pendingMask = false;
                showMask();
            }

            // 锁屏态盯住窗口层级：HOME 键普通应用拦不住（系统在 PhoneWindowManager
            // 就把它吃了，遮罩根本收不到），能做的只有「被切走之后立刻抢回焦点」。
            if (prefs.phase() == Prefs.LOCKED) keepMaskOnTop();

            if (prefs.phase() == Prefs.RUNNING) {
                if (screenOn()) {
                    remaining -= delta;
                    if (remaining <= 0) {
                        remaining = 0;
                        lock();
                        h.postDelayed(this, TICK_MS);   // 锁定后本帧立刻结束，避免 updateMini 把读秒窗又加回来
                        return;
                    }
                }
                if (++tickCount % 5 == 0) {              // 约 1s 一次心跳
                    prefs.tickSnapshot(remaining);
                    updateMini();
                    // 点「开始计时」后应用界面就关了，只有服务在跑。每 60s 刷新一次
                    // 「最后在线」，否则家长看到的是上次打开应用的时间，失联时长会虚高。
                    if (tickCount % 300 == 0) Anchor.touch(GateService.this);
                    // 通知不要每秒刷：通知监听被系统绑着，每次 notify 都会叫醒它，纯属自扰
                    if (tickCount % 150 == 0) updateNotification();
                    if (DIAG) {
                        Log.i(TAG, "diag inter=" + (pm != null && pm.isInteractive())
                                + " disp=" + dispState()
                                + " screenOn=" + screenOn()
                                + " rem=" + remaining
                                + " u=" + now
                                + " e=" + SystemClock.elapsedRealtime());
                    }
                }
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    // ---------- 状态机 ----------

    private void lock() {
        Log.i(TAG, "lock at 0 (screen=" + screenOn() + ")");
        prefs.saveSnapshot(Prefs.LOCKED, 0);
        remaining = 0;
        removeMini();
        if (screenOn()) {
            showMask();
        } else {
            // 屏幕本来是黑的：绝不能在这里挂遮罩，否则遮罩（KEEP_SCREEN_ON）会把电视点亮
            pendingMask = true;
            Log.i(TAG, "mask_deferred（屏幕未亮，等亮屏再挂）");
        }
        updateNotification();
    }

    /**
     * 锁屏态把遮罩抢回最前面。
     *
     * <p>HOME 键在 Android 上不会派发给应用（{@code PhoneWindowManager} 直接处理），
     * {@code TYPE_APPLICATION_OVERLAY} 窗口既收不到也吞不掉，所以「禁止按 HOME」做不到。
     * 能做的是：一旦发现窗口失去焦点（= 被 HOME / 切应用顶掉了），连续 1 秒都没拿回来
     * 就重新挂一次窗口 —— 只有重新 {@code addView} 才会重新请求焦点，对已存在的窗口调
     * {@code requestFocus()} 在 Android 14 上是「假成功」（1.2.7 的教训）。
     *
     * <p>节流：至少 2.5 秒一次，避免和系统弹层（音量条、电源菜单）抢焦点时反复重建。
     * 小米电视按 HOME 时可能把带系统窗口的进程直接强停，那条路由 NotifProbe 复活，
     * 这里只负责「进程还活着」的那一半。
     */
    private void keepMaskOnTop() {
        if (!screenOn()) return;
        long now = SystemClock.uptimeMillis();
        if (mask == null) {
            // 加窗失败（比如权限被回收）时别每 200ms 重试一次，会刷日志也会耗电
            if (now - lastRegrabAt < 2500L) return;
            lastRegrabAt = now;
            showMask();
            return;
        }
        if (mask.hasWindowFocus()) { focusLostCount = 0; return; }
        focusLostCount++;
        // 要求连续约 1 秒都没有焦点才动手：系统音量条、电源菜单这类瞬时弹层
        // 也会让 hasWindowFocus() 短暂变 false，抢太急会把家长正在选的选项刷掉。
        if (focusLostCount < 5 || now - lastRegrabAt < 2500L) return;
        lastRegrabAt = now;
        focusLostCount = 0;
        removeMask();
        showMask();
        Log.i(TAG, "mask_regrab（被切走后重新置顶）");
    }

    /** 屏幕是否真的亮着。两个独立信号都成立才算，任一不可信都倾向于「不亮」。 */
    private boolean screenOn() {
        if (pm != null && !pm.isInteractive()) return false;
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                int s = d.getState();
                if (s == Display.STATE_OFF || s == Display.STATE_DOZE
                        || s == Display.STATE_DOZE_SUSPEND || s == Display.STATE_UNKNOWN) {
                    return false;
                }
            }
        } catch (Throwable ignore) {
            // 拿不到就只信 isInteractive
        }
        return true;
    }

    /** 仅用于诊断日志：Display.STATE_*，拿不到返回 -1。 */
    private int dispState() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d == null ? -1 : d.getState();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override public void onSolved() {
        int ext = prefs.extendMin() * 60_000;
        remaining = Math.max(1, remaining) + ext;
        prefs.saveSnapshot(Prefs.RUNNING, remaining);
        removeMask();
        lastTick = SystemClock.uptimeMillis();
        updateMini();
        updateNotification();
        Log.i(TAG, "solved +" + prefs.extendMin() + "min -> " + Core.fmt(remaining));
    }

    @Override public void onPinOk() {
        Log.i(TAG, "pin ok -> 结束会话并回到应用首页");
        // 1.3.0：口令对了不再只是退出到桌面，而是把应用首页打开，
        // 由家长在首页决定「开始计时 / 结束本次会话」。本次运行标记为已解锁，
        // 之后进设置不再重复要口令（开始计时时会重新上锁）。
        App.parentUnlocked = true;
        prefs.setParentUnlocked(true);   // 进程被系统回收后仍免密，doStart() 会清掉
        // 正在按键分发过程中移除窗口会出问题，抛到下一帧再做
        h.post(() -> {
            exitSession();
            openApp();
        });
    }

    /** 口令通过后把家长带回应用首页。 */
    private void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            Log.i(TAG, "open_app ok");
        } catch (Throwable t) {
            Log.w(TAG, "open_app failed: " + t);
        }
    }

    private void exitSession() {
        running = false;
        h.removeCallbacks(ticker);
        h.removeCallbacks(hintDismiss);
        removeMask();
        removeMini();
        // 家长主动结束 = 这次服务是健康的，不能因为「短会话反复起停」被算成异常启动
        prefs.markStable();
        prefs.resetSession();
        notify("会话已结束");
        stopForeground(true);
        stopSelf();
    }

    private void reconcile() {
        int p = prefs.phase();
        if (p == Prefs.LOCKED) {
            removeMini();
            showMask();
        } else if (p == Prefs.RUNNING) {
            removeMask();
            if (remaining <= MINI_THRESHOLD_MS || prefs.startHintOn()) showMini(); else removeMini();
        } else {
            removeMask();
            removeMini();
        }
    }

    // ---------- 窗口 ----------

    private WindowManager.LayoutParams maskLp() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 刻意不加 FLAG_KEEP_SCREEN_ON：真机实测表明它会在「背光已关但框架仍以为醒着」时
                // 把电视重新点亮，表现为「时间到 → 电视自己开机」。宁可让遮罩跟着屏幕睡。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.dimAmount = 0.92f;
        lp.setTitle("QuizGateMask");
        return lp;
    }

    private void showMask() {
        if (mask != null) return;
        if (!screenOn()) {                 // 双保险：屏幕黑着绝不加窗
            pendingMask = true;
            Log.i(TAG, "mask_skip（屏幕未亮）");
            return;
        }
        pendingMask = false;
        mask = new MaskView(this, prefs, bank, this);
        focusLostCount = 0;     // 新窗口要先给系统一帧时间拿焦点，别立刻判成「被切走」
        try {
            wm.addView(mask, maskLp());
            Log.i(TAG, "mask_add ok");
        } catch (Throwable t) {
            Log.e(TAG, "mask_add failed: " + t);
            mask = null;
        }
    }

    private void removeMask() {
        if (mask == null) return;
        try { wm.removeViewImmediate(mask); } catch (Throwable ignore) { }
        mask = null;
        Log.i(TAG, "mask_remove");
    }

    private void showMini() {
        if (mini != null) return;
        mini = new MiniView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = Ui.dp(this, 40);
        lp.y = Ui.dp(this, 48);
        lp.setTitle("QuizGateMini");
        try {
            wm.addView(mini, lp);
            mini.setRemaining(remaining);
            mini.setCaption(prefs.startHintOn() ? "本次时长" : "剩余");
            Log.i(TAG, "mini_show remaining=" + Core.fmt(remaining)
                    + " caption=" + (prefs.startHintOn() ? "本次时长" : "剩余"));
        } catch (Throwable t) {
            Log.e(TAG, "mini_add failed: " + t);
            mini = null;
        }
    }

    private void removeMini() {
        if (mini == null) return;
        try { wm.removeViewImmediate(mini); } catch (Throwable ignore) { }
        mini = null;
    }

    /** 按 Prefs 里剩下的时间重排收尾；进程被杀后 RESTORE 的新实例也能接着倒数。 */
    private void scheduleHintDismiss() {
        h.removeCallbacks(hintDismiss);
        long left = prefs.startHintLeftMs();
        if (prefs.startHintOn()) {
            h.postDelayed(hintDismiss, left);
            Log.i(TAG, "start_hint reschedule left=" + left + "ms");
        }
    }

    private void updateMini() {
        if (prefs.phase() != Prefs.RUNNING) {   // 双重保险：非计时态绝不允许读秒窗存在
            removeMini();
            return;
        }
        boolean hint = prefs.startHintOn();
        if (hint || remaining <= MINI_THRESHOLD_MS) {
            if (mini == null) showMini();
            if (mini != null) {
                mini.setRemaining(remaining);
                mini.setCaption(hint ? "本次时长" : "剩余");
            }
        } else if (mini != null) {
            removeMini();
        }
    }

    // ---------- 通知与生命周期 ----------

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "计时", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification("准备中", true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text, boolean ongoing) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("答题续时")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_v3)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String s = prefs.phase() == Prefs.LOCKED ? "已锁定，等待答题" : "剩余 " + Core.fmt(remaining);
        try { nm.notify(NOTIF_ID, buildNotification(s, true)); } catch (Throwable ignore) { }
    }

    /** 一次性提示：不能是 ongoing，否则服务退出后这条通知永远擦不掉。 */
    private void notify(String s) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(NOTIF_ID + 1, buildNotification(s, false)); } catch (Throwable ignore) { }
    }

    private void registerScreenReceiver() {
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                long u = SystemClock.uptimeMillis();
                long e = SystemClock.elapsedRealtime();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    offU = u;
                    offE = e;
                    Log.i(TAG, "screen_off u=" + u + " e=" + e);
                    // 屏幕黑了就别再挂着遮罩：避免任何窗口层面的副作用把屏幕点回来
                    if (mask != null) {
                        removeMask();
                        pendingMask = true;
                        Log.i(TAG, "mask_detached（息屏）");
                    }
                } else {
                    // du 远小于 de => 熄屏期间进了深睡，uptime 停走（ADR-1 的免费暂停成立）
                    // du ≈ de        => 没进深睡，必须靠 isInteractive 门控
                    if (offU > 0) {
                        Log.i(TAG, "screen_on du=" + (u - offU) + " de=" + (e - offE));
                    } else {
                        Log.i(TAG, "screen_on du=n/a");
                    }
                }
                lastTick = u;   // 熄屏与亮屏都重置基准，避免补算
                if (Intent.ACTION_SCREEN_ON.equals(a)) reconcile();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenRx, f);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        App.gateAlive = false;
        h.removeCallbacks(ticker);
        removeMask();
        removeMini();
        if (screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Throwable ignore) { }
        }
        Log.i(TAG, "service_destroy " + prefs.debugLine());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
