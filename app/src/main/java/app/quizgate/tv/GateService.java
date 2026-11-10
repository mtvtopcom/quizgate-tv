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
    private BroadcastReceiver screenRx;
    private long offU = 0, offE = 0;
    private boolean pendingMask = false;
    /** 设备适配期的诊断开关：每 5 秒输出一行 inter/disp，用来观察厂商电源键到底改了什么。 */
    private static final boolean DIAG = true;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        bank = new QuizBank(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        startAsForeground();
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);

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

            // 熄屏期间被挂起的遮罩：一旦判定「屏幕真的亮了」再挂，避免遮罩把电视唤醒
            if (prefs.phase() == Prefs.LOCKED && pendingMask && screenOn()) {
                pendingMask = false;
                showMask();
            }

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
        Log.i(TAG, "pin ok -> exit session");
        // 正在按键分发过程中移除窗口会出问题，抛到下一帧再做
        h.post(this::exitSession);
    }

    private void exitSession() {
        running = false;
        h.removeCallbacks(ticker);
        removeMask();
        removeMini();
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
            if (remaining <= MINI_THRESHOLD_MS) showMini(); else removeMini();
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

    private void updateMini() {
        if (prefs.phase() != Prefs.RUNNING) {   // 双重保险：非计时态绝不允许读秒窗存在
            removeMini();
            return;
        }
        if (remaining <= MINI_THRESHOLD_MS) {
            if (mini == null) showMini();
            if (mini != null) mini.setRemaining(remaining);
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
        return b.setContentTitle("答对再看")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
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
