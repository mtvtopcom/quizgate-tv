package app.quizgate.tv;

import android.content.Context;
import android.graphics.Bitmap;
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
 * 题库管理页：默认题库 + 用户题库，点题库选「使用 / 删除」，底部只剩「添加题库」。
 *
 * <p>1.5.0 之前是「选中题库 + 底部删除选中，再按一次确认」，中间某一项想删时，
 * 焦点往下走会经过后面的题库、把删除目标冲掉，家长很难删对。现在改成：
 * 点哪一项就针对哪一项弹操作面板，删除在面板里二次确认，目标不会再被别的行顶掉。
 *
 * <p>按键全部自路由（和 {@link OptionPanel} 一致）：D2/Android 14 上浮层里
 * {@code requestFocus()} 是「假成功」，交给焦点树会让遥控器失灵。
 *
 * <p>焦点是一维的：0..n-1 是题库项，n 是「添加题库」。
 */
public final class BankManagerView extends FrameLayout {

    public interface Host {
        void closeBankManager();
        void toast(String msg);
        /** 题库切换成功（含删掉当前题库后回落到默认题库）：宿主负责提示重新设置题目年级。 */
        void onBankSwitched(String name);
    }

    private static final int CARD_ON = 0xFF242424;
    private static final int STEP_ACTION = 0;
    private static final int STEP_DELETE = 1;

    private final Context ctx;
    private final Host host;
    private final BankStore store;
    private final Prefs prefs;

    private final LinearLayout listBox;
    private final ScrollView scroll;
    private final TextView addBtn;
    private final TextView backBtn;
    private final List<View> items = new ArrayList<>();
    private final List<BankStore.Meta> metas = new ArrayList<>();

    /** 题库项 0..n-1，n 是「添加题库」，n+1 是「返回上一级」。 */
    private int n = 1;
    private int sel = 0;
    /** 最后一个落在题库项上的下标：从「添加题库」按上键回来时用它。 */
    private int lastItem = 0;

    // 操作面板（点某一个题库后弹出）
    private FrameLayout actionLayer;
    private final List<TextView> actionKeys = new ArrayList<>();
    private TextView actionTitle;
    private TextView actionSub;
    private TextView actionNote;
    private int actionStep = STEP_ACTION;
    private int actionSel = 0;
    private BankStore.Meta actionMeta;
    private boolean actionActive = false;

    private BankHttpServer server;
    private View qrLayer;
    private ImageView qrImage;
    private TextView qrAddr;
    private TextView qrStatus;
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

        // 标题行。返回入口不放这里：顶部这块在电视上没法用遥控器点到（不在焦点/按键路由里），
        // 家长反馈「点了没反应」，所以统一放到页面最下方的按钮行。
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.title(c, "题库管理", 26);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(head);

        TextView sub = Ui.label(c, "上下选择 · OK 打开「使用 / 删除」· 最下面是返回", 15, Ui.DIM);
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

        // 底部两个按钮：添加题库 + 返回上一级。返回放这里是因为顶部那块在电视上点不到。
        LinearLayout foot = Ui.row(c);
        addBtn = Ui.label(ctx, "＋ 添加题库（手机扫码）", 18, Ui.TEXT);
        backBtn = Ui.label(ctx, "← 返回上一级", 18, Ui.TEXT);
        for (TextView b : new TextView[]{addBtn, backBtn}) {
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 14));
            b.setClickable(true);
        }
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1.5f);
        flp.topMargin = Ui.dp(c, 10);
        flp.rightMargin = Ui.dp(c, 6);
        foot.addView(addBtn, flp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
        blp.topMargin = Ui.dp(c, 10);
        blp.leftMargin = Ui.dp(c, 6);
        foot.addView(backBtn, blp);
        panel.addView(foot);

        addBtn.setOnClickListener(v -> { sel = n; paint(); activate(); });
        backBtn.setOnClickListener(v -> { sel = n + 1; paint(); activate(); });

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
        styleBtn(backBtn, sel == n + 1);
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
        if (sel == n + 1) {
            host.closeBankManager();
            return;
        }
        if (sel == n) {
            showQr();
            return;
        }
        if (sel == 0) {
            if (BankStore.BUILTIN_ID.equals(prefs.activeBankId())) {
                host.toast("当前就是默认题库");
                return;
            }
            prefs.setActiveBankId(BankStore.BUILTIN_ID);
            refreshList();
            host.onBankSwitched("默认题库");
            return;
        }
        openActions(sel);
    }

    /** 点某个用户题库：弹出「使用这个题库 / 删除这个题库 / 取消」。 */
    private void openActions(int idx) {
        actionMeta = metas.get(idx - 1);
        actionActive = actionMeta.id.equals(prefs.activeBankId());
        buildAction(STEP_ACTION);
    }

    private void buildAction(int step) {
        actionStep = step;
        actionSel = 0;
        if (actionLayer != null && actionLayer.getParent() == this) removeView(actionLayer);
        actionLayer = null;
        actionKeys.clear();

        FrameLayout layer = new FrameLayout(ctx);
        layer.setBackgroundColor(0xE6000000);
        layer.setClickable(true);
        layer.setOnClickListener(v -> closeActions());

        LinearLayout card = Ui.col(ctx);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.box(ctx, Ui.CARD, 0x33FFFFFF, Ui.dp(ctx, 1), 18));
        card.setPadding(Ui.dp(ctx, 32), Ui.dp(ctx, 24), Ui.dp(ctx, 32), Ui.dp(ctx, 22));

        actionTitle = Ui.title(ctx, step == STEP_ACTION
                ? "「" + actionMeta.name + "」"
                : "删除「" + actionMeta.name + "」？", 24);
        card.addView(actionTitle);

        // 老题库（1.4.0 之前）还没回填 counts，这里不去扫分片：点一下题库要立刻响应，
        // 几万题的库在主线程解析会像卡死。先显示总数，后台统计完再刷新。
        BankStats st = actionMeta.counts.isEmpty() ? null : store.stats(actionMeta.id);
        int total = st == null ? actionMeta.count : st.total();
        String sub = st == null
                ? "共 " + fmt(total) + " 题 · 分科题数统计中…"
                : countsLine(st, actionMeta.count);
        actionSub = Ui.label(ctx, sub, 14, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(ctx, 6);
        card.addView(actionSub, slp);

        String[] labels = step == STEP_ACTION
                ? new String[]{actionActive ? "当前使用中" : "使用这个题库", "删除这个题库", "取消"}
                : new String[]{"确认删除", "取消"};
        // 确认删除是危险操作：默认落在「取消」上，避免连按两下 OK 直接删库。
        if (step == STEP_DELETE) actionSel = 1;

        LinearLayout row = Ui.row(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 64));
        rlp.topMargin = Ui.dp(ctx, 20);
        card.addView(row, rlp);
        for (int i = 0; i < labels.length; i++) {
            TextView b = Ui.label(ctx, labels[i], 19, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(false);
            b.setClickable(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -1, 1f);
            blp.leftMargin = Ui.dp(ctx, 5);
            blp.rightMargin = Ui.dp(ctx, 5);
            row.addView(b, blp);
            final int idx = i;
            b.setOnClickListener(v -> { actionSel = idx; paintActions(); actionChoose(); });
            actionKeys.add(b);
        }

        actionNote = Ui.label(ctx, step == STEP_DELETE
                ? "题库里的 " + fmt(total) + " 题会一起删掉，不能恢复"
                : "换题库后需要重新设置题目年级", 14,
                step == STEP_DELETE ? Ui.BAD : Ui.DIM);
        actionNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(ctx, 10);
        card.addView(actionNote, nlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.72f), -2);
        clp.gravity = Gravity.CENTER;
        layer.addView(card, clp);
        addView(layer, new FrameLayout.LayoutParams(-1, -1));
        actionLayer = layer;
        paintActions();
    }

    private void paintActions() {
        for (int i = 0; i < actionKeys.size(); i++) {
            TextView b = actionKeys.get(i);
            boolean on = i == actionSel;
            boolean danger = actionStep == STEP_DELETE ? (i == 0) : (i == 1);
            if (on) {
                b.setBackground(Ui.box(ctx, danger ? Ui.BAD : Ui.ACCENT,
                        danger ? Ui.BAD : Ui.ACCENT, Ui.dp(ctx, 2), 12));
                b.setTextColor(danger ? 0xFFFFFFFF : 0xFF20160A);
            } else {
                b.setBackground(Ui.box(ctx, 0x1AFFFFFF, 0x33FFFFFF, Ui.dp(ctx, 1), 12));
                b.setTextColor(danger ? Ui.BAD : Ui.TEXT);
            }
        }
    }

    private void actionChoose() {
        if (actionStep == STEP_DELETE) {
            if (actionSel == 0) doDelete();
            else buildAction(STEP_ACTION);
            return;
        }
        if (actionSel == 0) useBank();
        else if (actionSel == 1) buildAction(STEP_DELETE);
        else closeActions();
    }

    private void useBank() {
        if (actionMeta == null) return;
        // 先把目标抄出来：closeActions() 会把 actionMeta 清空。
        String id = actionMeta.id;
        String name = actionMeta.name;
        boolean wasActive = actionActive;
        closeActions();
        if (wasActive) {
            host.toast("当前已经在用「" + name + "」");
            return;
        }
        prefs.setActiveBankId(id);
        refreshList();
        host.onBankSwitched(name);
    }

    private void doDelete() {
        if (actionMeta == null) return;
        String id = actionMeta.id;
        String name = actionMeta.name;
        boolean wasActive = actionActive;
        closeActions();
        if (!store.delete(id)) {
            host.toast("删除失败");
            return;
        }
        if (wasActive) prefs.setActiveBankId(BankStore.BUILTIN_ID);
        host.toast("已删除「" + name + "」");
        lastItem = 0;
        sel = 0;
        refreshList();
        // 删掉的正是当前题库 → 实际已经切回默认题库，同样要提醒重新设置年级。
        if (wasActive) host.onBankSwitched("默认题库");
    }

    private void closeActions() {
        if (actionLayer != null && actionLayer.getParent() == this) removeView(actionLayer);
        actionLayer = null;
        actionKeys.clear();
        actionMeta = null;
        actionStep = STEP_ACTION;
        actionSel = 0;
    }

    private void move(int d) {
        sel = Math.max(0, Math.min(n + 1, sel + d));
        if (sel < n) lastItem = sel;
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
                BankHttpServer s = new BankHttpServer(ctx, store, r -> postUploaded(r));
                s.start();
                server = s;
                App.bankServer = s;
            } catch (Exception e) {
                host.toast("上传服务启动失败，请稍后再试");
                return;
            }
        } else {
            // 进程没死但页面换过：把回调重新指到当前页面，保证上传成功后列表会刷新。
            server.setListener(r -> postUploaded(r));
        }

        String url = "http://" + ip + ":" + server.port() + "/?t=" + server.token();
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

        // 手机上传成功后电视这边同步亮出结果：新增多少、重复跳过多少。
        qrStatus = Ui.label(ctx, "", 14, Ui.OK);
        qrStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams qslp = new LinearLayout.LayoutParams(-1, -2);
        qslp.topMargin = Ui.dp(ctx, 8);
        card.addView(qrStatus, qslp);
        qrStatus.setVisibility(View.GONE);

        TextView hint = Ui.label(ctx,
                "上传成功后这里会显示结果，列表自动刷新\n按返回键关闭", 13, Ui.DIM);
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

    /** 服务线程回调 → 主线程。页面已经关掉就直接丢掉，不要拿已 detach 的 View 去更新。 */
    private void postUploaded(BankStore.ImportResult r) {
        final View rootView = getRootView();
        if (rootView == null) return;
        rootView.post(() -> onUploaded(r));
    }

    private void onUploaded(BankStore.ImportResult r) {
        if (!isAttachedToWindow() || r == null) return;
        refreshList();
        String detail = "「" + r.bankName + "」新增 " + r.accepted + " 题"
                + (r.skipped > 0 ? " · 重复跳过 " + r.skipped + " 题" : "")
                + " · 现共 " + r.total + " 题";
        if (qrLayer != null && qrStatus != null) {
            qrStatus.setText("✓ 手机上传成功\n" + detail);
            qrStatus.setTextColor(Ui.OK);
            qrStatus.setVisibility(View.VISIBLE);
        } else {
            host.toast("手机上传成功：" + detail);
        }
    }

    private void closeQr() {
        removeCallbacks(qrWatch);
        qrImage = null;
        qrAddr = null;
        qrStatus = null;
        if (qrLayer != null && qrLayer.getParent() == this) removeView(qrLayer);
        qrLayer = null;
        refreshList();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(qrWatch);
        qrLayer = null;
        qrStatus = null;
        // 只摘掉回调，不停服务 —— 服务是进程级的，页面关掉后旧二维码仍要能用。
        if (server != null) server.setListener(null);
        server = null;
        super.onDetachedFromWindow();
    }

    // ---------------- 按键 ----------------

    public boolean consumeKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();

        if (actionLayer != null) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (actionStep == STEP_DELETE) buildAction(STEP_ACTION);
                else closeActions();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                actionSel = (actionSel + actionKeys.size() - 1) % actionKeys.size();
                paintActions();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                actionSel = (actionSel + 1) % actionKeys.size();
                paintActions();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                actionChoose();
                return true;
            }
            return true;
        }
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
        // 底部两个按钮之间左右切换；列表里左右键不再有特殊含义，
        // 免得家长想删中间某一项时被「跳到按钮」的旧逻辑绕晕。
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (sel >= n) { sel = n; paint(); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (sel >= n) { sel = n + 1; paint(); }
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
