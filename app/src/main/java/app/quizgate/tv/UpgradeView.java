package app.quizgate.tv;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 升级提示页（ADR-9）。**可取消**：家长不想现在升级就点「以后再说」，
 * 本次运行不再打扰，下次冷启动照旧提示。
 *
 * <p>刻意不做「强制」：强制升级意味着「服务器挂了 / 电视没网 = 应用完全不能用」，
 * 这个代价远大于「晚几天升级」。所以这里只有软提醒。
 *
 * <p>交互是两段式，和很多应用「点一下就开始装」不一样，这里是有意的：
 * <ol>
 *   <li><b>下载</b>：只把包存进「下载」目录，不动系统、不弹任何窗</li>
 *   <li><b>安装</b>：下载完把保存路径亮出来，家长确认后才拉起系统安装页</li>
 * </ol>
 * 好处是每一步都看得见、可回退：包在硬盘上，没装也不会白下。
 */
public class UpgradeView extends FrameLayout {

    public interface Listener {
        /** 用户选择跳过（或装完了）。 */
        void onDone();
    }

    private enum State { READY_TO_DOWNLOAD, DOWNLOADING, DOWNLOADED, INSTALLING, FAILED }

    private final Context ctx;
    private final Updater.Result info;
    private final Listener listener;

    private TextView state;
    private ProgressBar bar;
    private TextView primary;
    private TextView secondary;
    private State st = State.READY_TO_DOWNLOAD;

    public UpgradeView(Context c, Updater.Result r, Listener l) {
        super(c);
        ctx = c;
        info = r;
        listener = l;
        setBackgroundColor(Ui.BG);

        LinearLayout wrap = Ui.col(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackgroundResource(R.drawable.panel_bg);
        wrap.setPadding(Ui.dp(c, 56), Ui.dp(c, 40), Ui.dp(c, 56), Ui.dp(c, 40));

        wrap.addView(Ui.title(c, "发现新版本 " + info.versionName, 32));

        TextView ver = Ui.label(c,
                "当前 " + Updater.selfVersionName(c) + "　→　" + info.versionName, 20, Ui.DIM);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = Ui.dp(c, 10);
        wrap.addView(ver, vlp);

        if (info.notes != null && !info.notes.trim().isEmpty()) {
            TextView notes = Ui.label(c, info.notes.trim(), 19, Ui.TEXT);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
            nlp.topMargin = Ui.dp(c, 20);
            wrap.addView(notes, nlp);
        }

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 10));
        blp.topMargin = Ui.dp(c, 26);
        wrap.addView(bar, blp);

        state = Ui.label(c, "", 18, Ui.DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(c, 14);
        wrap.addView(state, slp);

        LinearLayout btns = Ui.row(c);
        LinearLayout.LayoutParams btnlp = new LinearLayout.LayoutParams(-1, -2);
        btnlp.topMargin = Ui.dp(c, 28);
        wrap.addView(btns, btnlp);

        primary = Ui.key(c, "立即更新", 22);
        primary.setBackgroundResource(R.drawable.opt_bg);
        primary.setMinWidth(Ui.dp(c, 220));
        primary.setMinHeight(Ui.dp(c, 62));
        primary.setOnClickListener(v -> onPrimary());
        btns.addView(primary);

        secondary = Ui.key(c, "以后再说", 22);
        secondary.setBackgroundResource(R.drawable.opt_bg);
        secondary.setMinWidth(Ui.dp(c, 200));
        secondary.setMinHeight(Ui.dp(c, 62));
        secondary.setOnClickListener(v -> { if (st != State.DOWNLOADING) listener.onDone(); });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.leftMargin = Ui.dp(c, 24);
        btns.addView(secondary, klp);

        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                (int) (c.getResources().getDisplayMetrics().widthPixels * 0.72f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER;
        addView(wrap, wlp);

        // 上一次可能已经下载好了（甚至进程被系统回收过）：直接进「待安装」态，
        // 别让家长白下一遍。
        Updater.Saved done = Updater.staged();
        if (done == null) done = Updater.findDownloaded(c, info.versionName);
        if (done != null) {
            Updater.adopt(done);
            showDownloaded(done);
        } else {
            showReadyToDownload();
        }

        primary.post(primary::requestFocus);
    }

    // ---------- 四个状态 ----------

    private void showReadyToDownload() {
        st = State.READY_TO_DOWNLOAD;
        bar.setVisibility(INVISIBLE);
        state.setTextColor(Ui.DIM);
        state.setText("下载后安装包会存到电视的「下载」目录，\n"
                + "装不装、什么时候装，都由你决定。");
        primary.setText("立即更新");
        secondary.setText("以后再说");
        setButtonsEnabled(true);
    }

    private void showDownloaded(Updater.Saved saved) {
        st = State.DOWNLOADED;
        bar.setVisibility(VISIBLE);
        bar.setProgress(1000);
        state.setTextColor(Ui.OK);
        state.setText("安装包已下载到电视：\n" + saved.shown + "\n\n"
                + "点「立即安装」会打开电视的安装页，在那一页按「安装」即可。");
        primary.setText("立即安装");
        secondary.setText("稍后再说");
        setButtonsEnabled(true);
    }

    private void showFailed(String why) {
        st = State.FAILED;
        bar.setVisibility(INVISIBLE);
        state.setTextColor(Ui.BAD);
        state.setText("更新失败：" + why + "\n\n可以先点「以后再说」继续用当前版本。");
        primary.setText("重试");
        secondary.setText("以后再说");
        setButtonsEnabled(true);
    }

    // ---------- 动作 ----------

    private void onPrimary() {
        switch (st) {
            case DOWNLOADED: startInstall(); break;
            case DOWNLOADING: break;
            case INSTALLING: listener.onDone(); break;   // 已经交给系统安装页，按钮此时是「知道了」
            default: startDownload(); break;
        }
    }

    private void startDownload() {
        if (st == State.DOWNLOADING) return;
        st = State.DOWNLOADING;
        setButtonsEnabled(false);
        bar.setVisibility(VISIBLE);
        bar.setProgress(0);
        state.setTextColor(Ui.DIM);
        state.setText("正在下载安装包…");

        Updater.stage(ctx, info,
                (done, total) -> {
                    if (total > 0) {
                        bar.setProgress((int) (done * 1000 / total));
                        state.setText("正在下载 " + (done * 100 / total) + "%");
                    } else {
                        state.setText("正在下载 " + (done / 1024) + " KB");
                    }
                },
                (ok, msg, saved) -> {
                    if (ok && saved != null) {
                        showDownloaded(saved);
                    } else {
                        showFailed(msg);
                    }
                });
    }

    /**
     * 点「立即安装」才动系统安装器。没给「安装未知应用」权限时先引导 ——
     * 电视上这个开关有的叫「安装未知应用」，有的被安全中心接管，所以打不开设置页
     * 不是致命问题，提示已经写在文字里。
     */
    private void startInstall() {
        if (!Updater.canInstall(ctx)) {
            state.setTextColor(Ui.BAD);
            state.setText("需要先允许本应用「安装未知应用」。\n"
                    + "如果设置页没弹出来：设置 → 应用 → 安全 → 安装未知应用 → 答对再看 → 允许\n"
                    + "（安装包已经下载好了，授权后回来点「立即安装」就行）");
            try {
                ctx.startActivity(Updater.installSettingsIntent(ctx));
            } catch (Throwable ignore) {
                // 很多电视固件没这个页面，提示已经写在上面
            }
            return;
        }

        st = State.INSTALLING;
        setButtonsEnabled(false);
        state.setTextColor(Ui.DIM);
        state.setText("正在打开电视的安装页…");

        Updater.install(ctx, (ok, msg, installerOpened) -> {
            if (ok && installerOpened) {
                st = State.INSTALLING;   // 已经交给系统安装页，本页只做说明
                state.setTextColor(Ui.OK);
                state.setText("已打开电视的安装页，请在那一页按「安装」。\n\n"
                        + "安装包位置：" + msg + "\n"
                        + "装完后应用会变成新版本，下次打开就不会再提示了。");
                primary.setText("知道了");
                setButtonsEnabled(true);
            } else if (ok) {
                st = State.INSTALLING;
                state.setTextColor(Ui.DIM);
                state.setText(msg);
                primary.setText("知道了");
                setButtonsEnabled(true);
            } else {
                showFailed(msg);
            }
        });
    }

    private void setButtonsEnabled(boolean on) {
        primary.setEnabled(on);
        primary.setAlpha(on ? 1f : 0.4f);
        secondary.setEnabled(on);
        secondary.setAlpha(on ? 1f : 0.4f);
        if (on) primary.requestFocus();
    }
}
