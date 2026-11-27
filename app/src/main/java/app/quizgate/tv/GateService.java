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
    /** 流程重构：总开关决定服务在不在，答题开关决定出不出题。 */
    public static final String ACTION_ENABLE = "app.quizgate.tv.ENABLE";
    public static final String ACTION_DISABLE = "app.quizgate.tv.DISABLE";
    public static final String ACTION_QUIZ_ON = "app.quizgate.tv.QUIZ_ON";
    public static final String ACTION_QUIZ_OFF = "app.quizgate.tv.QUIZ_OFF";
    /**
     * 1.6.0：在首页「答题方式」页切换方式后调用。只 setMode 不够——时间银行用余额起手、
     * 其他方式用「本次时长」起手，两者含义不同，不重算就会把余额当成剩余时长继续扣。
     */
    public static final String ACTION_APPLY_MODE = "app.quizgate.tv.APPLY_MODE";

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
    private BlockView block;
    private MiniView mini;
    /** 本轮遮罩已经暂停过播放；解锁后复位，下一轮才会再暂停。 */
    private boolean pausedForQuiz = false;
    private long remaining;
    /** 连续观看累计（只在亮屏且 RUNNING 时增长），与余额分开记账。 */
    private long watchMs;
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
        watchMs = prefs.watchMs();
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
        // 总开关判断已移到 onStartCommand（那里拿得到 action，能正确放行 ENABLE）。
        // 这里只做熔断检查：若还在这里按 gateOn 判死，点「启动软件」时 gateOn 尚未置位，
        // ENABLE 会被误杀（D1 实测）。
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

        if (ACTION_DISABLE.equals(action)) {
            // 总开关：彻底停下守护，通知监听也不会再把它拉起来
            prefs.setGateOn(false);
            running = false;
            h.removeCallbacks(ticker);
            h.removeCallbacks(hintDismiss);
            removeMask();
            removeBlock();
            removeMini();
            prefs.setParentUnlocked(false);
            App.parentUnlocked = false;
            notify("守护已停止");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!prefs.gateOn() && !ACTION_ENABLE.equals(action)) {
            Log.i(TAG, "gate_off -> 忽略 action=" + action);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_QUIZ_ON.equals(action) || ACTION_QUIZ_OFF.equals(action)) {
            boolean on = ACTION_QUIZ_ON.equals(action);
            prefs.setQuizOn(on);
            Log.i(TAG, "quiz_switch=" + on);
            if (!on && prefs.phase() == Prefs.LOCKED) {
                if (remaining <= 0) remaining = Math.max(1, prefs.durationMin()) * 60_000L;
                prefs.saveSnapshot(Prefs.RUNNING, remaining);
            }
            reconcile();
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_APPLY_MODE.equals(action)) {
            prefs.setQuizOn(true);
            int mode = prefs.mode();
            int p = prefs.phase();
            // 会话已开始就按新方式重算时长基准：时间银行是「余额」，其余是「本次时长」。
            // OUT / REST 只换基准不动阶段（时段外、休息中不该被切方式打断）。
            if (p != Prefs.IDLE) {
                boolean bank = GateRules.payout(mode) == GateRules.PAYOUT_BANK;
                remaining = bank ? prefs.balanceMs() : Math.max(1, prefs.durationMin()) * 60_000L;
                int target = p;
                if (p == Prefs.RUNNING || p == Prefs.LOCKED) {
                    target = (bank && remaining < prefs.minBankMin() * 60_000L)
                            ? Prefs.LOCKED : Prefs.RUNNING;
                }
                prefs.saveSnapshot(target, remaining);
            }
            Log.i(TAG, "apply_mode=" + GateRules.modeName(mode) + " rem=" + remaining);
            reconcile();
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_ENABLE.equals(action)) {
            prefs.setGateOn(true);
            prefs.setParentUnlocked(false);
            App.parentUnlocked = false;
            int p = prefs.phase();
            if (p == Prefs.IDLE) {
                long base = prefs.mode() == GateRules.MODE_BANK
                        ? prefs.balanceMs() : prefs.durationMin() * 60_000L;
                remaining = base;
                p = Prefs.RUNNING;
                if (prefs.quizOn() && prefs.mode() == GateRules.MODE_BANK
                        && remaining < prefs.minBankMin() * 60_000L) p = Prefs.LOCKED;
                prefs.saveSnapshot(p, remaining);
            } else {
                remaining = prefs.remainingMs();
            }
            if (!prefs.quizOn() && prefs.phase() == Prefs.LOCKED) {
                if (remaining <= 0) remaining = Math.max(1, prefs.durationMin()) * 60_000L;
                prefs.saveSnapshot(Prefs.RUNNING, remaining);
            }
            if (!prefs.inWindow()) prefs.saveSnapshot(Prefs.OUT, remaining);
            if (prefs.ruleDayKey().isEmpty()) {
                prefs.saveRuleState(0, 0, 0, 0L, prefs.balanceMs(), 0L, 0L, prefs.logicalDayKey());
            }
            notify("守护已启动");
        } else if (ACTION_START.equals(action)) {
            // 测试钩子：--ei durationMin 1 / --el remainingMs 4000，仅用于现场调试
            int ovMin = intent.getIntExtra("durationMin", 0);
            long ovMs = intent.getLongExtra("remainingMs", -1L);
            if (ovMin > 0) prefs.setDurationMin(ovMin);
            long base = prefs.mode() == GateRules.MODE_BANK
                    ? prefs.balanceMs() : prefs.durationMin() * 60_000L;
            remaining = ovMs > 0 ? ovMs : base;
            int startPhase = Prefs.RUNNING;
            // 时间银行：余额不够最低门槛就不能开始看，先进入答题遮罩攒时间
            if (prefs.mode() == GateRules.MODE_BANK
                    && remaining < prefs.minBankMin() * 60_000L) startPhase = Prefs.LOCKED;
            if (!prefs.inWindow()) startPhase = Prefs.OUT;
            prefs.saveSnapshot(startPhase, remaining);
            if (prefs.ruleDayKey().isEmpty()) {
                prefs.saveRuleState(0, 0, 0, 0L, prefs.balanceMs(), 0L, 0L, prefs.logicalDayKey());
            }
            prefs.markStartHint(START_HINT_MS);
            Log.i(TAG, "start_hint show " + START_HINT_MS + "ms remaining=" + Core.fmt(remaining)
                    + " phase=" + Prefs.phaseName(startPhase));
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

            // 规则引擎先于计时：跨天重置 / 强制休息到期 / 每日时段切换都要在本帧生效
            enforceRules();
            if (mask != null) mask.tick();
            if (block != null) updateBlockInfo();

            // 熄屏期间被挂起的遮罩：一旦判定「屏幕真的亮了」再挂，避免遮罩把电视唤醒
            if (Prefs.lockedLike(prefs.phase()) && pendingMask && screenOn()) {
                pendingMask = false;
                reconcile();
            }

            // 锁屏态盯住窗口层级：HOME 键普通应用拦不住（系统在 PhoneWindowManager
            // 就把它吃了，遮罩根本收不到），能做的只有「被切走之后立刻抢回焦点」。
            if (prefs.phase() == Prefs.LOCKED) keepMaskOnTop();

            if (prefs.phase() == Prefs.RUNNING) {
                if (screenOn()) {
                    // 答题关掉后不扣时长：用户选的是「不答题直接看」，但 watchMs 照常累加，
                    // 每日时段与强制休息仍然生效（全局规则是天花板）。
                    if (prefs.quizOn()) remaining -= delta;
                    watchMs += delta;
                    // 连续观看达标：强制休息，本帧不再扣余额
                    if (watchMs >= prefs.watchLimitMin() * 60_000L) {
                        prefs.tickRuleState(prefs.mode() == GateRules.MODE_BANK
                                ? remaining : prefs.balanceMs(), watchMs);
                        enterRest();
                        h.postDelayed(this, TICK_MS);
                        return;
                    }
                    if (remaining <= 0 && prefs.quizOn()) {
                        remaining = 0;
                        lock();
                        h.postDelayed(this, TICK_MS);   // 锁定后本帧立刻结束，避免 updateMini 把读秒窗又加回来
                        return;
                    }
                }
                if (++tickCount % 5 == 0) {              // 约 1s 一次心跳
                    prefs.tickSnapshot(remaining);
                    prefs.tickRuleState(prefs.mode() == GateRules.MODE_BANK
                            ? remaining : prefs.balanceMs(), watchMs);
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

    // ---------- 规则引擎：跨天 / 时段 / 强制休息 ----------

    /** 每秒检查一次即可（ticker 里每帧调用，内部自己按秒节流不必要：Calendar 取得起）。 */
    private void enforceRules() {
        String day = prefs.logicalDayKey();
        if (!day.equals(prefs.ruleDayKey())) {
            boolean clearedBalance = prefs.resetDailyRules(day);
            watchMs = prefs.watchMs();
            if (clearedBalance) remaining = 0;
            Log.i(TAG, "daily_reset day=" + day + " clearBalance=" + clearedBalance);
        }

        int p = prefs.phase();
        // 答题关掉后不该继续停留在答题遮罩：直接放行观看，但全局规则照管
        if (!prefs.quizOn() && p == Prefs.LOCKED) {
            if (remaining <= 0) remaining = Math.max(1, prefs.durationMin()) * 60_000L;
            prefs.saveSnapshot(Prefs.RUNNING, remaining);
            resumeAfterBlock("quiz_off");
            return;
        }
        if (p == Prefs.REST && prefs.restLeftMs() <= 0) {
            prefs.saveRuleState(prefs.streak(), prefs.wrongToday(), prefs.unlockToday(),
                    prefs.cooldownUntil(), prefs.balanceMs(), watchMs, 0L, prefs.ruleDayKey());
            resumeAfterBlock("rest_done");
            return;
        }

        boolean inWindow = prefs.inWindow();
        // 家长口令应急解锁：时段外也放行，但 watchMs 继续累计，休息照常触发
        if (!inWindow && !prefs.parentUnlocked()
                && (p == Prefs.RUNNING || p == Prefs.LOCKED)) {
            enterOut();
        } else if (inWindow && p == Prefs.OUT) {
            resumeAfterBlock("window_open");
        }
    }

    private void enterOut() {
        prefs.saveSnapshot(Prefs.OUT, remaining);
        removeMask();
        removeMini();
        showBlock();
        updateNotification();
        Log.i(TAG, "enter_out window=" + prefs.windowStart() + "-" + prefs.windowEnd());
    }

    private void enterRest() {
        long until = System.currentTimeMillis() + Math.max(1, prefs.restMin()) * 60_000L;
        long bal = prefs.mode() == GateRules.MODE_BANK ? remaining : prefs.balanceMs();
        prefs.saveRuleState(prefs.streak(), prefs.wrongToday(), prefs.unlockToday(),
                prefs.cooldownUntil(), bal, 0L, until, prefs.ruleDayKey());
        // 连续观看计时清零，否则休息结束的下一帧又会立刻触发休息
        watchMs = 0L;
        prefs.saveSnapshot(Prefs.REST, remaining);
        removeMask();
        removeMini();
        pausedForQuiz = false;   // 休息结束后的第一轮答题仍要暂停播放
        showBlock();
        updateNotification();
        Log.i(TAG, "enter_rest " + prefs.restMin() + "min until=" + until);
    }

    private void resumeAfterBlock(String why) {
        removeBlock();
        int target = (remaining > 0 || !prefs.quizOn()) ? Prefs.RUNNING : Prefs.LOCKED;
        prefs.saveSnapshot(target, remaining);
        lastTick = SystemClock.uptimeMillis();
        reconcile();
        updateNotification();
        Log.i(TAG, "resume_after_block " + why + " -> " + Prefs.phaseName(target));
    }

    private void showBlock() {
        if (block != null) return;
        if (!screenOn()) { pendingMask = true; return; }
        block = new BlockView(this, new BlockView.Host() {
            @Override public Prefs prefs() { return prefs; }
            @Override public void onPinOk() { GateService.this.onPinOk(); }
        });
        try {
            wm.addView(block, maskLp());
            Log.i(TAG, "block_add ok phase=" + Prefs.phaseName(prefs.phase()));
            updateBlockInfo();
        } catch (Throwable t) {
            Log.e(TAG, "block_add failed: " + t);
            block = null;
        }
    }

    private void removeBlock() {
        if (block == null) return;
        try { wm.removeViewImmediate(block); } catch (Throwable ignore) { }
        block = null;
        Log.i(TAG, "block_remove");
    }

    private void updateBlockInfo() {
        if (block == null) return;
        if (prefs.phase() == Prefs.REST) {
            block.setInfo("强制休息",
                    "连续观看 " + prefs.watchLimitMin() + " 分钟，休息 " + prefs.restMin() + " 分钟后再看",
                    prefs.restLeftMs());
        } else {
            block.setInfo("不在答题时段",
                    "每日 " + fmtMin(prefs.windowStart()) + " – " + fmtMin(prefs.windowEnd())
                            + " 可以答题解锁", 0L);
        }
    }

    private static String fmtMin(int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    // ---------- 状态机 ----------

    private void lock() {
        if (!prefs.quizOn()) return;   // 答题关掉后不进答题遮罩
        Log.i(TAG, "lock at 0 (screen=" + screenOn() + ")");
        if (!prefs.inWindow() && !prefs.parentUnlocked()) {
            enterOut();
            return;
        }
        prefs.saveSnapshot(Prefs.LOCKED, 0);
        if (prefs.mode() == GateRules.MODE_BANK) prefs.tickRuleState(0L, watchMs);
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

    /** 答对：累加连对/余额，按当前模式判断是否放行。返回 true 时宿主会在 700ms 后摘遮罩。 */
    @Override public boolean onCorrect() {
        int mode = prefs.mode();
        int streak = prefs.streak() + 1;
        int unlockToday = prefs.unlockToday();
        long balance = prefs.balanceMs()
                + (mode == GateRules.MODE_BANK ? prefs.perAnswerMin() * 60_000L : 0L);
        int need = GateRules.needed(mode, prefs.fixedNeed(), prefs.firstNeed(),
                prefs.stepAdd(), unlockToday);
        boolean unlocked = GateRules.unlocked(mode, balance,
                prefs.minBankMin() * 60_000L, streak, need);

        if (unlocked) {
            if (mode == GateRules.MODE_UNLOCK) unlockToday++;
            streak = 0;   // 下一轮重新计数：固定 N 仍是 N 题，阶梯用 stepAdd
            if (mode == GateRules.MODE_BANK) {
                remaining = balance;
            } else {
                remaining = Math.max(1, remaining) + prefs.extendMin() * 60_000L;
            }
        }
        prefs.saveRuleState(streak, prefs.wrongToday(), unlockToday, prefs.cooldownUntil(),
                balance, watchMs, prefs.restUntil(), prefs.ruleDayKey());

        if (unlocked) {
            prefs.saveSnapshot(Prefs.RUNNING, remaining);
            lastTick = SystemClock.uptimeMillis();
            pausedForQuiz = false;
            // 与旧版一致：先让绿色反馈显示 700ms，再摘遮罩、把时间交给观看
            h.postDelayed(() -> {
                removeMask();
                updateMini();
                updateNotification();
            }, 700);
        }
        Log.i(TAG, "correct mode=" + mode + " streak=" + streak + "/" + need
                + " balance=" + Core.fmt(balance) + " unlocked=" + unlocked);
        return unlocked;
    }

    /** 答错：连对归零；冷却按当日累计次数 5/10/20/30 秒，绝对时间落盘。 */
    @Override public long onWrong() {
        int wrong = prefs.wrongToday() + 1;
        long cd = GateRules.cooldownApplies(prefs.mode()) ? GateRules.cooldownMs(wrong) : 0L;
        long until = cd > 0 ? System.currentTimeMillis() + cd : 0L;
        prefs.saveRuleState(0, wrong, prefs.unlockToday(), until,
                prefs.balanceMs(), watchMs, prefs.restUntil(), prefs.ruleDayKey());
        Log.i(TAG, "wrong #" + wrong + " cooldown=" + cd + "ms");
        return cd;
    }

    @Override public void onPinOk() {
        // 家长在遮罩上输对口令 = 「临时放行一段时间去改设置」，不是「停止软件」。
        // 新流程没有会话概念：软件总开关仍开着，家长豁免标记暂时跳过时段限制；
        // 输对口令后给一段宽限继续观看，但不会停止守护。
        long grace = Math.max(1, prefs.extendMin()) * 60_000L;
        long bal = prefs.balanceMs();
        if (prefs.mode() == GateRules.MODE_BANK) {
            bal = Math.max(bal, grace);
            remaining = bal;
        } else {
            remaining = Math.max(remaining, grace);
        }
        // 家长临时放行：清掉连续观看计时与休息截止，这是有意保留的家长豁免；
        // 软件总开关不受影响，全局规则在下一轮照常生效。
        watchMs = 0L;
        prefs.saveRuleState(prefs.streak(), prefs.wrongToday(), prefs.unlockToday(),
                prefs.cooldownUntil(), bal, 0L, 0L, prefs.ruleDayKey());
        prefs.saveSnapshot(Prefs.RUNNING, remaining);
        pausedForQuiz = false;
        App.parentUnlocked = true;
        prefs.setParentUnlocked(true);   // 进程被系统回收后仍免密，doStart() 会清掉
        Log.i(TAG, "pin ok -> 会话继续 remaining=" + Core.fmt(remaining));
        // 正在按键分发过程中移除窗口会出问题，抛到下一帧再做
        h.post(() -> {
            reconcile();
            updateMini();
            updateNotification();
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
        removeBlock();
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
            removeBlock();
            showMask();
        } else if (p == Prefs.REST || p == Prefs.OUT) {
            removeMini();
            removeMask();
            showBlock();
        } else if (p == Prefs.RUNNING) {
            removeMask();
            removeBlock();
            pausedForQuiz = false;
            if (remaining <= MINI_THRESHOLD_MS || prefs.startHintOn()) showMini(); else removeMini();
        } else {
            removeMask();
            removeBlock();
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
            if (prefs.pauseOnQuiz() && !pausedForQuiz) {
                pausedForQuiz = true;
                MediaControl.pause(this);
            }
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
        String s = Prefs.lockedLike(prefs.phase())
                ? Prefs.phaseName(prefs.phase()) : "剩余 " + Core.fmt(remaining);
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
                    if (mask != null || block != null) {
                        removeMask();
                        removeBlock();
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
        removeBlock();
        removeMini();
        if (screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Throwable ignore) { }
        }
        Log.i(TAG, "service_destroy " + prefs.debugLine());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
