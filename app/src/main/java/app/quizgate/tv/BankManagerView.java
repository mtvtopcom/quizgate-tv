package app.quizgate.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库管理页：默认题库 + 用户题库，单选切换、删除、扫码添加。
 *
 * <p>按键全部自路由（和 {@link OptionPanel} 一致）：D2/Android 14 上浮层里
 * {@code requestFocus()} 是「假成功」，交给焦点树会让遥控器失灵。
 *
 * <p>焦点是个一维列表：0..n-1 是题库项，n 是「添加题库」，n+1 是「删除选中」。
 * 上下移动，左右在底部两个按钮之间切换，OK 执行。
 */
public final class BankManagerView extends FrameLayout {

    public interface Host {
        void closeBankManager();
        void toast(String msg);
    }

    private static final int CARD_ON = 0xFF242424;

    private final Context ctx;
    private final Host host;
    private final BankStore store;
    private final Prefs prefs;

    private final LinearLayout listBox;
    private final ScrollView scroll;
    private final TextView addBtn;
    private final TextView delBtn;
    private final List<View> items = new ArrayList<>();
    private final List<BankStore.Meta> metas = new ArrayList<>();

    private int n = 1;
    private int sel = 0;
    /**
     * 最后一个落在题库项上的下标。焦点移到「添加 / 删除」按钮后 sel 会变成 n / n+1，
     * 删除按钮必须靠这个值才知道要删哪一个（1..n-1），0 是默认题库。
     */
    private int lastItem = 0;
    private boolean confirmDelete = false;
    private long confirmAt = 0L;

    private BankHttpServer server;
    private View qrLayer;
    private ImageView qrImage;
    private TextView qrAddr;
    private String qrUrl = "";
    private final Runnable qrWatch = new Runnable() {
        @Override public void run() {
            if (qrLayer == null || qrLayer.getParent() == null) return;
            // 进程被系统强停后重启，进程级服务会丢失；自动重建并刷新码，避免家长拿着死链接。
            if (App.bankServer == null || !App.bankServer.isRunning()) {
                if (qrLayer != null && qrLayer.getParent() == BankManagerView.this) {
                    removeView(qrLayer);
                }
                qrLayer = null;
                showQr();
                return;
            }
            postDelayed(this, 3000L);
        }
    };

    public BankManagerView(Context c, Host host, BankStore store, Prefs prefs) {
        super(c);
        this.ctx = c;
        this.host = host;
        this.store = store;
        this.prefs = prefs;

        setBackgroundColor(Ui.BG);
        setClickable(true);
        setFocusable(true);

        LinearLayout panel = Ui.col(c);
        // 底部留 36dp 安全区：1080p 电视普遍有 overscan（画面过扫描），原来只留 14dp 时
        // 「添加题库 / 删除选中」贴着屏幕下沿，在真机上会被切掉半行（用户报的遮挡）。
        panel.setPadding(Ui.dp(c, 44), Ui.dp(c, 18), Ui.dp(c, 44), Ui.dp(c, 36));
        addView(panel, new FrameLayout.LayoutParams(-1, -1));

        // 标题行
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.label(c, "‹ 返回", 18, Ui.DIM);
        back.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        back.setClickable(true);
        back.setOnClickListener(v -> host.closeBankManager());
        head.addView(back);
        TextView title = Ui.title(c, "题库管理", 26);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(head);

        TextView sub = Ui.label(c, "方向键选择 · OK 确认 · 左右键到添加/删除 · 返回键退出", 15, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(c, 4);
        slp.bottomMargin = Ui.dp(c, 10);
        panel.addView(sub, slp);

        // 列表
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = Ui.col(c);
        scroll.addView(listBox, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 底部两个按钮
        LinearLayout foot = Ui.row(c);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        addBtn = Ui.label(c, "＋ 添加题库", 19, Ui.TEXT);
        delBtn = Ui.label(c, "删除选中", 19, Ui.TEXT);
        for (TextView b : new TextView[]{addBtn, delBtn}) {
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 14));
            b.setClickable(true);
        }
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1f);
        flp.topMargin = Ui.dp(c, 10);
        flp.rightMargin = Ui.dp(c, 6);
        foot.addView(addBtn, flp);
        LinearLayout.LayoutParams frp = new LinearLayout.LayoutParams(0, -2, 1f);
        frp.topMargin = Ui.dp(c, 10);
        frp.leftMargin = Ui.dp(c, 6);
        foot.addView(delBtn, frp);
        panel.addView(foot);

        addBtn.setOnClickListener(v -> { sel = n; paint(); activate(); });
        delBtn.setOnClickListener(v -> { sel = n + 1; paint(); activate(); });

        refreshList();
    }

    // ---------------- 列表 ----------------

    private void refreshList() {
        metas.clear();
        metas.addAll(store.list());
        n = 1 + metas.size();
        listBox.removeAllViews();
        items.clear();

        items.add(makeItem(0, "默认题库", "随软件自带 · 只读",
                BankStats.builtin(ctx), 0));
        for (int i = 0; i < metas.size(); i++) {
            BankStore.Meta m = metas.get(i);
            // counts 为空 = 1.4.0 之前建的库，先占位「统计中」，后台扫完再刷新，
            // 不能在主线程读几 MB 分片，遥控器会像卡死一样。
            BankStats st = m.counts.isEmpty() ? null : store.stats(m.id);
            items.add(makeItem(i + 1, m.name, "我的题库", st, m.count));
        }
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(ctx, 8);
            listBox.addView(items.get(i), lp);
        }
        sel = Math.max(0, Math.min(sel, n + 1));
        lastItem = Math.max(0, Math.min(lastItem, n - 1));
        paint();
        loadStatsAsync();
    }

    private final java.util.Set<String> statsTried = new java.util.HashSet<>();
    private boolean statsLoading = false;

    /** 老题库的分科题数：后台扫一次并回填 meta.json，只跑一次。 */
    private void loadStatsAsync() {
        if (statsLoading) return;
        final List<BankStore.Meta> todo = new ArrayList<>();
        for (BankStore.Meta m : metas) {
            if (m.counts.isEmpty() && !statsTried.contains(m.id)) todo.add(m);
        }
        if (todo.isEmpty()) return;
        statsLoading = true;
        for (BankStore.Meta m : todo) statsTried.add(m.id);
        new Thread(() -> {
            for (BankStore.Meta m : todo) store.stats(m.id);
            post(() -> {
                statsLoading = false;
                if (isAttachedToWindow() && qrLayer == null) refreshList();
            });
        }, "bank-stats").start();
    }

    private View makeItem(int idx, String name, String sub, BankStats st, int count) {
        LinearLayout row = Ui.col(ctx);
        row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10));

        LinearLayout line = Ui.row(ctx);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.title(ctx, name, 20);
        line.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (bankIdOf(idx).equals(prefs.activeBankId())) {
            line.addView(Ui.label(ctx, "使用中", 14, Ui.OK));
        }
        row.addView(line);
        row.addView(Ui.label(ctx, sub, 14, Ui.DIM));
        TextView nums = Ui.label(ctx, countsLine(st, count), 13, Ui.DIM);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(ctx, 2);
        row.addView(nums, nlp);

        row.setClickable(true);
        row.setOnClickListener(v -> {
            sel = idx;
            if (idx < n) lastItem = idx;
            paint();
            activate();
        });
        return row;
    }

    /** 下标 0 是默认题库，其余按 metas 顺序取 id。 */
    private String bankIdOf(int idx) {
        if (idx == 0) return BankStore.BUILTIN_ID;
        if (idx >= 1 && idx <= metas.size()) return metas.get(idx - 1).id;
        return "";
    }

    private String countsLine(BankStats st, int fallbackTotal) {
        if (st == null) return "分科题数统计中…";
        int total = st.total() > 0 ? st.total() : fallbackTotal;
        return "共 " + fmt(total) + " 题 · 语文 " + fmt(st.subjectTotal("chinese"))
                + " · 数学 " + fmt(st.subjectTotal("math"))
                + " · 英语 " + fmt(st.subjectTotal("english"));
    }

    private static String fmt(int n) {
        return String.format(java.util.Locale.CHINA, "%,d", n);
    }

    private void paint() {
        for (int i = 0; i < items.size(); i++) {
            boolean on = i == sel;
            items.get(i).setBackground(Ui.box(ctx, on ? CARD_ON : Ui.CARD,
                    on ? Ui.ACCENT : 0, on ? Ui.dp(ctx, 2) : 0, 12));
        }
        styleBtn(addBtn, sel == n);
        styleBtn(delBtn, sel == n + 1);
        if (sel < items.size()) {
            final View target = items.get(sel);
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - Ui.dp(ctx, 6))));
        }
    }

    private void styleBtn(TextView b, boolean on) {
        b.setBackground(Ui.box(ctx, on ? CARD_ON : Ui.CARD,
                on ? Ui.ACCENT : 0, on ? Ui.dp(ctx, 2) : 0, 12));
        b.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
    }

    // ---------------- 操作 ----------------

    private void activate() {
        if (sel < n) {
            if (sel == 0) {
                prefs.setActiveBankId(BankStore.BUILTIN_ID);
                host.toast("已切换到默认题库");
            } else {
                BankStore.Meta m = metas.get(sel - 1);
                prefs.setActiveBankId(m.id);
                host.toast("已切换到「" + m.name + "」");
            }
            refreshList();
            return;
        }
        if (sel == n) {
            showQr();
            return;
        }
        deleteSelected();
    }

    private void deleteSelected() {
        int idx = lastItem;
        if (idx == 0) {
            host.toast("默认题库不能删除");
            return;
        }
        if (idx < 1 || idx > n - 1) {
            host.toast("请先用方向键选中要删除的题库");
            return;
        }
        BankStore.Meta m = metas.get(idx - 1);
        long now = SystemClock.elapsedRealtime();
        if (!confirmDelete || now - confirmAt > 4000L) {
            confirmDelete = true;
            confirmAt = now;
            delBtn.setText("再按一次删除");
            host.toast("再按一次确认删除「" + m.name + "」");
            return;
        }
        confirmDelete = false;
        delBtn.setText("删除选中");
        if (store.delete(m.id)) {
            if (m.id.equals(prefs.activeBankId())) prefs.setActiveBankId(BankStore.BUILTIN_ID);
            host.toast("已删除「" + m.name + "」");
            lastItem = Math.max(0, idx - 1);
            sel = lastItem;
            refreshList();
        } else {
            host.toast("删除失败");
        }
    }

    private void move(int d) {
        sel = Math.max(0, Math.min(n + 1, sel + d));
        if (sel < n) lastItem = sel;
        if (confirmDelete) {
            confirmDelete = false;
            delBtn.setText("删除选中");
        }
        paint();
    }

    // ---------------- 二维码 ----------------

    private void showQr() {
        String ip = BankHttpServer.localIp();
        if (ip == null) {
            host.toast("电视还没联网，请先连 WiFi 或网线");
            return;
        }
        // 服务交给 Application 持有：页面关闭 / 重建都不停，旧二维码不失效。
        server = App.bankServer;
        if (server != null && !server.isRunning()) {
            server = null;
            App.bankServer = null;
        }
        if (server == null) {
            try {
                BankHttpServer s = new BankHttpServer(ctx, store, () -> {
                    final View rootView = getRootView();
                    if (rootView != null) rootView.post(this::refreshList);
                });
                s.start();
                server = s;
                App.bankServer = s;
            } catch (Exception e) {
                host.toast("上传服务启动失败，请稍后再试");
                return;
            }
        } else {
            // 进程没死但页面换过：把回调重新指到当前页面，保证上传成功后列表会刷新。
            server.setListener(() -> {
                final View rootView = getRootView();
                if (rootView != null) rootView.post(this::refreshList);
            });
        }

        String url = "http://" + ip + ":" + server.port() + "/?t=" + server.token();
        qrUrl = url;
        int qrSize = Ui.dp(ctx, 190);
        Bitmap bmp = QrCode.make(url, qrSize);

        FrameLayout layer = new FrameLayout(ctx);
        layer.setBackgroundColor(0xE6000000);
        layer.setClickable(true);
        layer.setOnClickListener(v -> closeQr());

        LinearLayout card = Ui.col(ctx);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.box(ctx, Ui.CARD, 0, 0, 18));
        card.setPadding(Ui.dp(ctx, 26), Ui.dp(ctx, 18), Ui.dp(ctx, 26), Ui.dp(ctx, 16));

        card.addView(Ui.title(ctx, "用手机扫码添加题库", 22));
        TextView tip = Ui.label(ctx, "手机和电视要连同一个 WiFi", 14, Ui.DIM);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(ctx, 2);
        tlp.bottomMargin = Ui.dp(ctx, 10);
        card.addView(tip, tlp);

        if (bmp != null) {
            ImageView iv = new ImageView(ctx);
            qrImage = iv;
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout wrap = new FrameLayout(ctx);
            wrap.setBackground(Ui.box(ctx, 0xFFFFFFFF, 0, 0, 10));
            int pad = Ui.dp(ctx, 8);
            wrap.setPadding(pad, pad, pad, pad);
            wrap.addView(iv, new FrameLayout.LayoutParams(qrSize, qrSize));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
            wlp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(wrap, wlp);
        } else {
            card.addView(Ui.label(ctx, "二维码生成失败，请手动访问：\n" + url, 14, Ui.BAD));
        }

        TextView addr = Ui.label(ctx, url, 12, Ui.DIM);
        addr.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        qrAddr = addr;
        card.addView(addr);

        TextView hint = Ui.label(ctx,
                "微信扫到「非官方网页」时选继续访问\n上传成功后列表会自动刷新 · 按返回键关闭", 13, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        card.addView(hint);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 300), -2);
        clp.gravity = Gravity.CENTER;
        layer.addView(card, clp);
        addView(layer, new FrameLayout.LayoutParams(-1, -1));
        qrLayer = layer;
        removeCallbacks(qrWatch);
        postDelayed(qrWatch, 3000L);
    }

    private void closeQr() {
        removeCallbacks(qrWatch);
        qrImage = null;
        qrAddr = null;
        if (qrLayer != null && qrLayer.getParent() == this) removeView(qrLayer);
        qrLayer = null;
        refreshList();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(qrWatch);
        qrLayer = null;
        // 只摘掉回调，不停服务 —— 服务是进程级的，页面关掉后旧二维码仍要能用。
        if (server != null) server.setListener(null);
        server = null;
        super.onDetachedFromWindow();
    }

    // ---------------- 按键 ----------------

    public boolean consumeKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();

        if (qrLayer != null) {
            if (k == KeyEvent.KEYCODE_BACK) closeQr();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            host.closeBankManager();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP) { move(-1); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) { move(1); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (sel < n) {
                // 中间某项要删除时，往下走会经过它后面的题库、把 lastItem 冲掉，
                // 所以从题库项直接左右跳到按钮，保留当前这一项作为删除目标。
                sel = (k == KeyEvent.KEYCODE_DPAD_LEFT) ? n + 1 : n;
                paint();
            } else {
                sel = (sel == n) ? n + 1 : n;
                paint();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            activate();
            return true;
        }
        return true;
    }
}
