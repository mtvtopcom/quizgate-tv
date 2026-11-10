package app.quizgate.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：遥控器可完成全部操作，不依赖输入法。 */
public class MainActivity extends Activity {

    private interface IntPick { void on(int v); }

    private Prefs prefs;
    private FrameLayout root;
    private LinearLayout content;

    private TextView valueDuration, valueExtend, valueGrade, valuePin, status, diag, btnPerm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private PinPadView gate;
    private boolean settingPin = false;
    private String firstPin = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        buildContent();
        setContentView(root);
        gate();
    }

    // ---------- UI ----------

    private void buildContent() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 88), Ui.dp(this, 56), Ui.dp(this, 88), Ui.dp(this, 56));
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        content.addView(Ui.title(this, "答对再看", 40));
        TextView sub = Ui.label(this, "到点一题，答对续时。答完继续看，家长口令结束本次会话。", 18, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(this, 8);
        content.addView(sub, slp);

        status = Ui.label(this, "", 22, Ui.ACCENT);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, -2);
        stlp.topMargin = Ui.dp(this, 26);
        content.addView(status, stlp);

        valueDuration = addRow("本次时长", v -> prefs.setDurationMin(v), this::refresh);
        valueExtend = addRow("答对延长", v -> prefs.setExtendMin(v), this::refresh);
        valueGrade = addRow("题目年级", v -> prefs.setGrade(v), this::refresh);
        valuePin = addRow("家长口令", v -> promptSetPin(), this::refresh);

        LinearLayout btns = Ui.row(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 34);
        content.addView(btns, blp);

        TextView start = Ui.key(this, "开始计时", 24);
        start.setBackgroundResource(R.drawable.opt_bg);
        start.setMinWidth(Ui.dp(this, 220));
        start.setMinHeight(Ui.dp(this, 66));
        start.setOnClickListener(v -> startSession());
        btns.addView(start);
        start.post(start::requestFocus);

        TextView exit = Ui.key(this, "结束本次会话", 24);
        exit.setBackgroundResource(R.drawable.opt_bg);
        exit.setMinWidth(Ui.dp(this, 240));
        exit.setMinHeight(Ui.dp(this, 66));
        exit.setOnClickListener(v -> exitSession());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = Ui.dp(this, 20);
        btns.addView(exit, elp);

        TextView perm = Ui.key(this, "授权：显示在其他应用上层", 18);
        btnPerm = perm;
        perm.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = Ui.dp(this, 22);
        content.addView(perm, plp);
        perm.setOnClickListener(v -> requestOverlay());

        diag = Ui.label(this, "", 14, Ui.DIM);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.dp(this, 28);
        content.addView(diag, dlp);
    }

    private TextView addRow(String label, IntPick onPick, Runnable after) {
        LinearLayout row = Ui.row(this);
        row.setBackgroundResource(R.drawable.opt_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16));

        TextView l = Ui.label(this, label, 24, Ui.TEXT);
        TextView v = Ui.label(this, "", 24, Ui.ACCENT);
        v.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 14);
        content.addView(row, rlp);

        row.setOnClickListener(x -> {
            if ("本次时长".equals(label)) {
                choose("本次时长（分钟）", new int[]{1, 5, 10, 15, 20, 30, 45, 60, 90, 120}, prefs.durationMin(), onPick, after);
            } else if ("答对延长".equals(label)) {
                choose("答对延长（分钟）", new int[]{1, 2, 3, 5, 10, 15, 20, 30}, prefs.extendMin(), onPick, after);
            } else if ("题目年级".equals(label)) {
                choose("题目年级", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, prefs.grade(), onPick, after);
            } else {
                onPick.on(0);
            }
        });
        return v;
    }

    private void choose(String title, int[] vals, int cur, IntPick onPick, Runnable after) {
        Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 40), Ui.dp(this, 28), Ui.dp(this, 40), Ui.dp(this, 28));
        box.addView(Ui.title(this, title, 24));

        TextView first = null;
        for (int v : vals) {
            String txt = (v == cur ? "● " : "　 ") + v + (title.contains("年级") ? " 年级" : "");
            TextView k = Ui.key(this, txt, 22);
            k.setBackgroundResource(R.drawable.key_bg);
            k.setMinWidth(Ui.dp(this, 260));
            k.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            k.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
            klp.topMargin = Ui.dp(this, 8);
            box.addView(k, klp);
            final int vv = v;
            k.setOnClickListener(x -> { onPick.on(vv); after.run(); d.dismiss(); });
            if (first == null) first = k;
        }
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout(Ui.dp(this, 420), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        if (first != null) first.requestFocus();
    }

    // ---------- 会话 ----------

    private void startSession() {
        if (!Settings.canDrawOverlays(this)) {
            toast("遮罩需要「显示在其他应用上层」权限，否则到点出不来题");
            requestOverlay();
            return;
        }
        if (!prefs.hasPin()) {
            toast("先设置 6 位家长口令");
            promptSetPin();
            return;
        }
        int p = prefs.phase();
        if (p != Prefs.IDLE) {
            toast("已有会话在进行，请先结束");
            return;
        }
        Intent i = new Intent(this, GateService.class).setAction(GateService.ACTION_START);
        startForegroundService(i);
        toast("已开始：" + prefs.durationMin() + " 分钟后出题");
        refresh();
    }

    private void exitSession() {
        if (prefs.phase() == Prefs.IDLE) {
            toast("当前没有进行中的会话");
            return;
        }
        startService(new Intent(this, GateService.class).setAction(GateService.ACTION_EXIT));
        prefs.resetSession();
        refresh();
    }

    /**
     * 电视固件经常把「显示在其他应用上层」藏起来、甚至整个入口都没有。
     * 所以不能只发一个 Intent 就完事：按可靠性依次尝试 4 个入口，全失败就给一张可照抄的说明页。
     */
    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("已授权，可以开始计时");
            refresh();
            return;
        }
        Intent[] tries = new Intent[]{
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (Throwable ignore) {
                // 该入口在这台机器上不存在，继续试下一个
            }
        }
        showGrantGuide();
    }

    /** 应用内授权说明页：能照抄、能量、不依赖任何系统菜单。 */
    private void showGrantGuide() {
        Dialog d = new Dialog(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        box.addView(Ui.title(this, "怎么开启「显示在其他应用上层」", 28));

        box.addView(section("① 用 ADB（最可靠，不需要 root）"));
        box.addView(mono("adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"));
        box.addView(section("验证是否生效："));
        box.addView(mono("adb shell appops get " + getPackageName() + " SYSTEM_ALERT_WINDOW"));
        box.addView(section("看到 SYSTEM_ALERT_WINDOW: allow 即成功，回到本页会自动变「已授予」。"));

        box.addView(section("② 在电视设置里找下面任意一个词"));
        box.addView(section("显示在其他应用上层 · 悬浮窗 · 显示悬浮窗 · 在其他应用上层显示"));
        box.addView(section("常见位置：设置 → 应用 → 应用管理 → 答对再看 → 权限；或 电视管家 → 权限管理。"));

        box.addView(section("③ 两条都找不到"));
        box.addView(section("说明这台固件彻底隐藏了该权限，请把日志发我，我改成「Activity 兜底锁屏」模式。"));

        TextView retry = Ui.key(this, "已开启，重新检测", 22);
        retry.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 62));
        rlp.topMargin = Ui.dp(this, 26);
        box.addView(retry, rlp);
        retry.setOnClickListener(v -> {
            d.dismiss();
            refresh();
            if (Settings.canDrawOverlays(this)) toast("检测到已授权");
            else toast("仍未检测到授权");
        });

        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(sv);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            // 用屏幕比例算，不要写死 dp：1080p 电视的可用高度只有 540dp
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) (dm.widthPixels * 0.86f), (int) (dm.heightPixels * 0.82f));
        }
        d.show();
        retry.requestFocus();
    }

    private TextView section(String s) {
        TextView t = Ui.label(this, s, 19, Ui.TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView mono(String s) {
        TextView t = Ui.label(this, s, 19, Ui.ACCENT);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        t.setBackgroundColor(0xFF10151A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------- 口令门禁 ----------

    private void gate() {
        if (prefs.hasPin()) showUnlockPin();
        else promptSetPin();
    }

    private void promptSetPin() {
        settingPin = true;
        firstPin = null;
        showPinOverlay("设置 6 位家长口令", "请牢记，忘记只能用恢复路径或卸载");
    }

    private void showUnlockPin() {
        settingPin = false;
        showPinOverlay("家长口令", "输入 6 位数字进入设置");
    }

    private void showPinOverlay(String title, String sub) {
        if (gate != null && gate.getParent() != null) root.removeView(gate);
        gate = new PinPadView(this, title, new PinPadView.Listener() {
            @Override public void onPinSubmit(String s) {
                if (settingPin) {
                    if (firstPin == null) {
                        firstPin = s;
                        gate.fail("再输入一次确认");
                    } else if (firstPin.equals(s)) {
                        if (prefs.setPin(s)) {
                            settingPin = false;
                            hidePinOverlay();
                            toast("口令已设置");
                            refresh();
                        } else {
                            firstPin = null;
                            gate.fail("设置失败，请重试");
                        }
                    } else {
                        firstPin = null;
                        gate.fail("两次不一致，请重新设置");
                    }
                } else {
                    long r = prefs.verifyPin(s);
                    if (r == 0) {
                        hidePinOverlay();
                    } else if (r > 0) {
                        gate.fail("尝试次数过多，请 " + (r / 1000) + " 秒后再试");
                    } else {
                        gate.fail("口令不正确");
                    }
                }
            }
            @Override public void onPinCancel() {
                if (!prefs.hasPin()) toast("必须先设置家长口令");
                else finish();
            }
        });
        gate.setBackgroundColor(Ui.BG);
        root.addView(gate, new FrameLayout.LayoutParams(-1, -1));
        gate.focusFirst();
    }

    private void hidePinOverlay() {
        if (gate != null && gate.getParent() != null) root.removeView(gate);
        refresh();
    }

    // ---------- 刷新 ----------

    private void refresh() {
        if (valueDuration == null) return;
        valueDuration.setText(prefs.durationMin() + " 分钟");
        valueExtend.setText("+ " + prefs.extendMin() + " 分钟");
        valueGrade.setText(prefs.grade() + " 年级");
        valuePin.setText(prefs.hasPin() ? "已设置" : "未设置");
        tickUi();
    }

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            tickUi();
            h.postDelayed(this, 1000);
        }
    };

    private void tickUi() {
        int p = prefs.phase();
        if (p == Prefs.IDLE) {
            status.setText("未开始 · 待机");
        } else if (p == Prefs.LOCKED) {
            status.setText("已锁定 · 等待答题");
        } else {
            status.setText("计时中 · 剩余 " + Core.fmt(prefs.remainingMs()));
        }
        boolean ov = Settings.canDrawOverlays(this);
        if (btnPerm != null) {
            btnPerm.setText(ov ? "显示权限：已授予（点击重新检测）" : "显示权限：未授予（点击查看开启方法）");
            btnPerm.setTextColor(ov ? Ui.OK : Ui.BAD);
        }
        diag.setText((ov ? "悬浮窗权限：已授予" : "悬浮窗权限：未授予 —— 遮罩出不来，计时等于没开")
                + "　|　" + prefs.debugLine()
                + "\n日志：adb logcat -s QuizGate");
    }

    /** 遥控器数字键直接输入口令（很多电视遥控器带数字键，别浪费）。 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (gate != null && gate.getParent() != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k >= android.view.KeyEvent.KEYCODE_0 && k <= android.view.KeyEvent.KEYCODE_9) {
                gate.append((char) ('0' + (k - android.view.KeyEvent.KEYCODE_0)));
                return true;
            }
            if (k == android.view.KeyEvent.KEYCODE_DEL) {
                gate.backspace();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 首页一次性提示：上次守护被系统终止过（小米待机时会强停第三方应用）。 */
    private void maybeShowInterruptNotice() {
        long gap = prefs.interruptedGapMs();
        if (gap <= 0) return;
        if (gate != null && gate.getParent() != null) return;   // 口令键盘还盖着，等它关掉再说
        prefs.clearInterrupted();

        Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 36));

        box.addView(Ui.title(this, "上次守护被系统终止了", 26));
        TextView body = Ui.label(this,
                "检测到约 " + Math.max(1, gap / 60_000) + " 分钟的计时缺口。\n\n"
                        + "这台小米电视在按电源键待机时，会强制停止所有第三方应用"
                        + "（系统日志：killAllThirdApp）。任何应用都无法在这种强制停止后自行恢复，"
                        + "所以那段时间等于没有守护。\n\n"
                        + "本次已按原剩余时间继续。如果孩子会自己按电源键，请改成只用主页键退回桌面，"
                        + "或在电视设置里把「短按电源键」改成息屏而不是待机。",
                18, Ui.TEXT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = Ui.dp(this, 16);
        box.addView(body, blp);

        TextView ok = Ui.key(this, "知道了", 22);
        ok.setBackgroundResource(R.drawable.opt_bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        olp.topMargin = Ui.dp(this, 24);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> d.dismiss());

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            d.getWindow().setLayout((int) (dm.widthPixels * 0.78f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        ok.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        h.removeCallbacks(uiTick);
        h.post(uiTick);
        h.postDelayed(this::maybeShowInterruptNotice, 600);
    }    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(uiTick);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
