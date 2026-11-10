package app.quizgate.tv;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 在线升级（ADR-9）。整个应用**唯一**的联网处，只有两个动作：
 * <ol>
 *   <li>GET {base}version.json —— 裸 GET，不带参数、不带 query、不带设备信息</li>
 *   <li>GET 那个 APK —— 同样裸 GET</li>
 * </ol>
 * 不上报、不打点、不发 cookie。想审计就 {@code grep -rn "openConnection" app/}，
 * 应该只在本文件里出现两处。
 *
 * <p><b>关于 sha256</b>：它只防「传输被截断/损坏」，<b>不防篡改</b>——因为 sha256 和 apkUrl 在
 * 同一份 json 里，能改 json 的人就能同时改这两个字段。真正的防篡改是 APK 签名：
 * 系统安装时校验签名必须与已安装版本一致，不一致直接拒绝。也就是说
 * 「换了 json 也装不进去」是系统保证的，不是这段代码保证的。
 *
 * <p><b>关于「静默安装」</b>：做不到。普通应用只能把 APK 交给系统安装器，再由人在弹窗上按一次「安装」。
 *
 * <p><b>交付方式是「先落盘、再提示安装」，不是「直接弹安装」</b>：
 * <ol>
 *   <li>下载 → 校验 → 存进系统「下载」目录（{@code /sdcard/Download/quizgate-x.y.z.apk}）</li>
 *   <li>页面上告诉家长「已下载到这里」，并给一个「立即安装」按钮</li>
 *   <li>点了才去拉起系统安装器 —— 不自动跳，避免家长正在设口令时被弹窗打断</li>
 * </ol>
 * 落盘在前还有一个实际好处：拉起安装器失败（某些固件改过、或权限没给）时，
 * 安装包已经在硬盘上，用户仍能自己打开装，不会白下载一遍。
 *
 * <p><b>关于小米电视（实测 MiTV-MFTR0 / Android 11）</b>：一开始的判断是「系统里没有 APK
 * 安装器」（{@code resolve-activity -a VIEW -t application/vnd.android.package-archive} 返回
 * "No activity found"），但那是<b>探测方法错了</b>：不带 data scheme 去 resolve 必然查不到。
 * 实际 {@code com.android.packageinstaller} 存在且启用，用 MediaStore 的 {@code content://}
 * URI 加上 {@code FLAG_GRANT_READ_URI_PERMISSION} 能正常拉起 {@code PackageInstallerActivity}，
 * 屏幕上就是那个熟悉的安装页。所以安装器不是「没有」，只是「要喂对 URI」。
 */
public final class Updater {
    private static final String TAG = "QuizGate";

    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 2500;
    private static final int MAX_JSON = 64 * 1024;
    private static final int DL_CONNECT_MS = 8000;
    private static final int DL_READ_MS = 15000;
    /** 上限不是防攻击，是防「配置写错指向一个视频文件」把孩子电视的存储塞满。 */
    private static final long MAX_APK = 100L * 1024 * 1024;

    private Updater() {}

    // ---------- 结果 ----------

    public static final class Result {
        public final String error;
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final String notes;

        Result(String error, int versionCode, String versionName,
               String apkUrl, String sha256, String notes) {
            this.error = error;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.notes = notes;
        }

        static Result fail(String why) { return new Result(why, 0, "", "", "", ""); }

        public boolean ok() { return error == null; }
    }

    public interface Cb { void onResult(Result r); }

    public interface Progress { void on(long done, long total); }

    /** installerOpened = 已经替用户拉起系统安装界面（仅落盘路径会用到）。 */
    public interface Done { void on(boolean ok, String msg, boolean installerOpened); }

    // ---------- 环境能力 ----------

    /** 是否配置了升级源。空配置 = 纯离线，一次网络请求都不发。 */
    public static boolean enabled() {
        return BuildCfg.UPGRADE_BASE != null && BuildCfg.UPGRADE_BASE.startsWith("http");
    }

    /**
     * 这台设备是否真的能把 APK 交给人去装。
     * <p>不能用 {@code PackageManager.canRequestPackageInstalls()} 判断：小米电视这个值可以返回 true。
     * 也不能只用 {@code ACTION_VIEW + mimeType} 判断 —— 会被 "*&#47;*" 的普通过滤器命中（假阳性）。
     * 可靠一点的信号是：有没有 Activity 处理 PackageInstaller 的专属 action {@code CONFIRM_INSTALL}，
     * 通配过滤器匹配不到它。
     * <p>注意这只是「要不要给用户一个安装按钮」的提示，<b>不是</b>硬门槛：
     * 真正拉安装器用的是 {@link #install}，它失败时会退回落盘提示，代价可控。
     */
    public static boolean hasInstaller(Context c) {
        try {
            // 判据是「系统里有没有那个安装确认界面」，不是「谁声称能打开 APK」。
            // ACTION_VIEW + mimeType 会被 "*/*" 的普通过滤器命中（实测小米电视
            // queryIntentActivities 返回非空，但 adb resolve-activity 说没有，
            // 且真去 commit 时确认 Intent 指向不存在的 com.android.packageinstaller）。
            // CONFIRM_INSTALL 是 PackageInstaller 的专属 action，通配过滤器匹配不到它。
            Intent confirm = new Intent(ConfirmAction)
                    .addCategory(Intent.CATEGORY_DEFAULT);
            return !c.getPackageManager()
                    .queryIntentActivities(confirm, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统安装确认界面的 action（PackageInstaller 内部使用，adb 日志里可见）。 */
    private static final String ConfirmAction = "android.content.pm.action.CONFIRM_INSTALL";

    /** 是否已允许「安装未知应用」。只在 {@link #hasInstaller} 为真时才有意义。 */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return c.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static Intent installSettingsIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
    }

    public static int selfVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String selfVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- 检查 ----------

    /**
     * 后台检查一次。失败也回调（error 非空），由调用方决定「静默放弃」——
     * 这里的取舍是：检查失败绝不能挡住家长用应用，所以调用方一律当成「没有更新」。
     */
    public static void check(final Cb cb) {
        if (!enabled()) {
            cb.onResult(Result.fail("未配置升级源"));
            return;
        }
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = fetch(BuildCfg.UPGRADE_BASE);
            } catch (Throwable e) {
                r = Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            final Result fr = r;
            post(() -> cb.onResult(fr));
        }, "quizgate-update-check");
        t.setDaemon(true);
        t.start();
    }

    static Result fetch(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "version.json").openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("GET");
            // 默认 UA 是 "Dalvik/2.1.0 (Linux; U; Android 11; ...)"，会把系统版本和设备型号报出去。
            // 换个固定串：服务器只知道「有个 quizgate 来查过版本」。
            c.setRequestProperty("User-Agent", "QuizGate");
            c.setRequestProperty("Accept", "application/json");
            c.setUseCaches(false);

            int code = c.getResponseCode();
            if (code != 200) return Result.fail("HTTP " + code);
            return parse(readCapped(c.getInputStream(), MAX_JSON), base);
        } catch (Throwable t) {
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Result parse(String body, String base) {
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Throwable t) {
            return Result.fail("version.json 不是合法 JSON");
        }
        int vc = o.optInt("versionCode", 0);
        String vn = o.optString("versionName", "");
        String apk = o.optString("apk", "");
        String sha = o.optString("sha256", "").toLowerCase(Locale.US);
        String notes = o.optString("notes", "");
        if (vc <= 0) return Result.fail("versionCode 非法");
        if (apk.isEmpty()) return Result.fail("缺少 apk 字段");
        if (!sha.matches("[0-9a-f]{64}")) return Result.fail("sha256 必须是 64 位十六进制");
        String url = apk.startsWith("http://") || apk.startsWith("https://") ? apk : base + apk;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return Result.fail("apk 地址非法");
        return new Result(null, vc, vn, url, sha, notes);
    }

    private static String readCapped(InputStream in, int max) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = i.read(b)) > 0) {
                if (bo.size() + n > max) throw new IOException("响应超过 " + max + " 字节");
                bo.write(b, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        }
    }

    // ---------- 下载 + 交付 ----------

    /** 安装结果回调由 receiver 转进来（进程内静态，够用：安装失败时进程还活着）。 */
    private static Done pendingDone;

    /** 已下载待安装的包。进程内静态：升级页被回收重建后仍能显示「已下载完成」。 */
    private static Saved staged;

    /** 当前是否已经有下载好的包等着装。 */
    static Saved staged() { return staged; }

    static void adopt(Saved s) { staged = s; }

    static void forgetStaged() { staged = null; }

    static void deliverInstallResult(Context c, int status, String msg) {
        Done d = pendingDone;
        pendingDone = null;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            clearCache(c);
            staged = null;
        }
        if (d == null) return;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            d.on(true, "安装成功", false);
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            d.on(true, "等待你在电视上的安装页按「安装」", true);
        } else {
            d.on(false, describe(status, msg), false);
        }
    }

    private static String describe(int status, String msg) {
        String base;
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) base = "安装被取消";
        else if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) base = "被系统拦截（可能是电视的安全中心）";
        else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) base = "与已安装版本冲突（签名不一致）";
        else if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) base = "与这台电视不兼容";
        else if (status == PackageInstaller.STATUS_FAILURE_INVALID) base = "安装包无效";
        else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) base = "存储空间不足";
        else base = "安装失败(" + status + ")";
        return msg == null || msg.isEmpty() ? base : base + "：" + msg;
    }

    /** 第一步（下载落盘）的回调。saved 非空表示安装包已经在硬盘上了。 */
    public interface StageCb { void on(boolean ok, String msg, Saved saved); }

    /**
     * 第一步：<b>下载 → 校验 → 存进「下载」目录</b>，全程不弹任何界面。
     *
     * <p>顺序是刻意的：先把包稳稳落到公共「下载」目录，再谈安装。反过来的话，
     * 一旦安装器拉不起来，用户就白下载一次、还得再来一遍。
     *
     * <p>校验有三道，各自挡不同的错：
     * <ol>
     *   <li>sha256 —— 传输被截断/损坏（<b>不防篡改</b>，防篡改靠 APK 签名）</li>
     *   <li>包名 —— 升级源配错成别人的 APK</li>
     *   <li>versionCode —— json 写着 10201、包其实是 10200 这种发布事故</li>
     * </ol>
     */
    public static void stage(final Context ctx, final Result r, final Progress p, final StageCb cb) {
        Thread t = new Thread(() -> {
            File apk;
            try {
                apk = download(ctx, r, p);
                inspect(ctx, apk, r);
            } catch (Throwable e) {
                Log.w(TAG, "update_stage_failed: " + e);
                post(() -> cb.on(false, msg(e), null));
                return;
            }
            try {
                Saved saved = saveToDownloads(ctx, apk, "quizgate-" + r.versionName + ".apk");
                staged = saved;
                post(() -> cb.on(true, saved.shown, saved));
            } catch (Throwable e) {
                Log.w(TAG, "update_save_failed: " + e);
                post(() -> cb.on(false, "保存到「下载」目录失败：" + msg(e), null));
            }
        }, "quizgate-update-stage");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 第二步：用户点了「立即安装」才走这里 —— 用 content URI 拉起系统安装页。
     * 拉不起来就退到 {@link PackageInstaller} session；再不行就只提示路径。
     */
    public static void install(final Context ctx, final Done d) {
        final Saved s = staged;
        if (s == null) {
            d.on(false, "还没有下载好的安装包", false);
            return;
        }
        if (s.uri != null && tryOpenInstaller(ctx, s.uri)) {
            Log.i(TAG, "install_open_ok path=" + s.shown);
            d.on(true, s.shown, true);
            return;
        }
        File cache = new File(ctx.getCacheDir(), "update.apk");
        if (cache.exists() && hasInstaller(ctx)) {
            commitSession(ctx, cache, d);
            return;
        }
        d.on(false, "这台电视没法自己弹出安装页。安装包已经在这里：" + s.shown
                + "\n用电视自带的「文件管理 / 媒体中心」打开它即可。", false);
    }

    /** 校验下载下来的包「是不是我们的、是不是真的更新」。 */
    private static void inspect(Context c, File apk, Result r) throws IOException {
        PackageInfo pi;
        try {
            pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Throwable t) {
            pi = null;
        }
        if (pi == null) {
            apk.delete();
            throw new IOException("安装包读不出来，可能下载不完整");
        }
        if (!c.getPackageName().equals(pi.packageName)) {
            apk.delete();
            throw new IOException("下载到的不是本应用（" + pi.packageName + "），已丢弃");
        }
        int vc = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        int self = selfVersionCode(c);
        if (vc <= self) {
            apk.delete();
            throw new IOException("安装包版本 " + vc + " 不比当前 " + self + " 新，已丢弃"
                    + "（可能是升级源上的文件没换）");
        }
        if (r.versionCode > 0 && vc != r.versionCode) {
            apk.delete();
            throw new IOException("版本对不上：version.json 写 " + r.versionCode
                    + "，安装包实际是 " + vc + "。请让发布方改正后再试");
        }
        // 签名不一致的后果比"装不上"严重得多：系统会让你先卸载旧版，
        // 而卸载 = 口令、恢复码、计时设置全丢，守护也没了。所以在这里直接拦死。
        if (!sameSignature(c, apk)) {
            apk.delete();
            throw new IOException("更新包的签名和电视上已装的版本不一致，装不上。\n"
                    + "强行安装只能先卸载旧版，那样口令和设置都会丢，所以这里不继续。");
        }
        Log.i(TAG, "update_inspect ok pkg=" + pi.packageName + " vc=" + vc);
    }

    /** 已经下载的包，签名是否和当前安装的一致（不一致就绝对不要递出去）。 */
    private static boolean sameSignature(Context c, File apk) {
        try {
            byte[] mine = certDigest(c, c.getPackageName(), null);
            byte[] theirs = certDigest(c, c.getPackageName(), apk.getAbsolutePath());
            if (mine == null || theirs == null) return false;
            return java.util.Arrays.equals(mine, theirs);
        } catch (Throwable t) {
            Log.w(TAG, "update_sign_check_failed: " + t);
            return false;
        }
    }

    /** 取第一个签名证书的 SHA-256。apkPath 为 null 表示查"已安装的那个应用"。 */
    private static byte[] certDigest(Context c, String pkg, String apkPath) throws Exception {
        android.content.pm.PackageManager pm = c.getPackageManager();
        PackageInfo pi;
        if (apkPath == null) {
            pi = Build.VERSION.SDK_INT >= 28
                    ? pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    : pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES);
        } else {
            pi = Build.VERSION.SDK_INT >= 28
                    ? pm.getPackageArchiveInfo(apkPath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    : pm.getPackageArchiveInfo(apkPath, android.content.pm.PackageManager.GET_SIGNATURES);
        }
        if (pi == null) return null;
        android.content.pm.Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            sigs = pi.signingInfo.getApkContentsSigners();
        } else {
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length == 0) return null;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(sigs[0].toByteArray());
    }

    private static void clearCache(Context c) {
        try {
            File f = new File(c.getCacheDir(), "update.apk");
            if (f.exists()) f.delete();
        } catch (Throwable ignore) { }
    }


    private static File download(Context ctx, Result r, Progress p) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        if (out.exists() && !out.delete()) throw new IOException("无法清理旧的安装包");

        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        try {
            c.setConnectTimeout(DL_CONNECT_MS);
            c.setReadTimeout(DL_READ_MS);
            c.setRequestProperty("User-Agent", "QuizGate");
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);

            long total = c.getContentLengthLong();
            if (total > MAX_APK) throw new IOException("安装包异常大：" + total + " 字节");

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = c.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done > MAX_APK) throw new IOException("安装包超过上限");
                    final long fd = done, ft = total;
                    post(() -> p.on(fd, ft));
                }
                os.flush();
            }

            String got = hex(md.digest());
            if (!got.equals(r.sha256)) {
                out.delete();
                // 这一条最常见的原因是「服务器上的 apk 和 version.json 不是同一版」，
                // 所以提示里要写清是哪边的问题，否则用户只会以为是自己网不好。
                throw new IOException("安装包和版本信息对不上（sha256 不一致）。\n"
                        + "多半是服务器上的 apk 还没换成新的，请让发布方重新上传后再试");
            }
            Log.i(TAG, "update_downloaded bytes=" + done + " sha=" + got.substring(0, 12));
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** 交给系统安装器。用 Session 而不是文件路径：不需要存储权限、不需要 FileProvider。 */
    private static void commitSession(Context ctx, File apk, Done d) {
        pendingDone = d;
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());
            int id = pi.createSession(sp);
            session = pi.openSession(id);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
                session.fsync(os);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent cb = PendingIntent.getBroadcast(ctx, id,
                    new Intent(ctx, InstallResultReceiver.class).setAction(InstallResultReceiver.ACTION),
                    flags);
            session.commit(cb.getIntentSender());
            session = null;   // commit 之后不能再碰 session
            Log.i(TAG, "install_commit ok");
        } catch (Throwable e) {
            Log.w(TAG, "install_commit_failed: " + e);
            if (session != null) {
                try { session.abandon(); } catch (Throwable ignore) { }
            }
            final String why = msg(e);
            post(() -> {
                pendingDone = null;
                d.on(false, why, false);
            });
        }
    }

    /** 落盘结果：给用户看的路径 + 可用于拉起安装器的 content URI。 */
    static final class Saved {
        final String shown;
        final Uri uri;
        final String name;
        Saved(String shown, Uri uri, String name) {
            this.shown = shown;
            this.uri = uri;
            this.name = name;
        }
    }

    /**
     * 用 content URI 把系统安装页拉起来（用户点「立即安装」时才调用）。
     *
     * <p>为什么必须用 content URI：`file://` 从 Android 7 起就过不了 StrictMode 的
     * FileUriExposedException；而 MediaStore 给的 `content://` 加上
     * `FLAG_GRANT_READ_URI_PERMISSION`，安装器才读得到这个文件。
     *
     * <p>这台小米电视实测能拉起 {@code PackageInstallerActivity}（就是那个「继续更新」的安装页）。
     * 拉起失败也不会丢东西 —— 包已经在「下载」目录里了。
     */
    private static boolean tryOpenInstaller(Context c, Uri uri) {
        if (uri == null) return false;
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            Log.i(TAG, "installer_opened");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "installer_open_failed: " + t);
            return false;
        }
    }

    /**
     * 存进系统「下载」目录。Android 10+ 用 MediaStore，不需要任何存储权限；
     * 9 及以下退回应用外部目录（公共 Downloads 要 WRITE_EXTERNAL_STORAGE，不值得为一个
     * 一年一次的升级多要一个敏感权限）。
     *
     * <p>同名旧文件先删掉：MediaStore 遇到重名会自动加「(1)」，留着会越升级越乱。
     * <p>cache 里那份<b>不删</b>：它是 {@link #install} 拉不起安装页时的退路。
     */
    static Saved saveToDownloads(Context c, File apk, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            deleteExisting(c, name);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("无法在「下载」目录创建文件");

            try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(apk)) {
                if (os == null) throw new IOException("无法写入「下载」目录");
                copy(in, os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(uri, done, null, null);
            String shown = Environment.DIRECTORY_DOWNLOADS + "/" + name;
            Log.i(TAG, "update_saved " + shown + " uri=" + uri);
            return new Saved(shown, uri, name);
        }

        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = c.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录");
        File dst = new File(dir, name);
        try (InputStream in = new FileInputStream(apk);
             OutputStream os = new FileOutputStream(dst)) {
            copy(in, os);
        }
        // API 26-28 没有 MediaStore 的 downloads 集合，拿不到可授权的 URI，只能提示路径
        return new Saved(dst.getAbsolutePath(), null, name);
    }

    /** 删掉同名旧包，避免「下载」目录里堆一串 quizgate-x.apk(1)(2)(3)。 */
    private static void deleteExisting(Context c, String name) {
        try {
            c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{name});
        } catch (Throwable ignore) {
            // 删不掉（比如是别的应用建的）就算了，MediaStore 会自己加序号
        }
    }

    /**
     * 找回「上次已经下好的包」。
     *
     * <p>为什么需要：电视上进程随时会被系统回收（待机、内存、换应用），一回收
     * {@link #staged} 就没了。如果只靠内存标记，家长会看到「明明下过了，又让我下一遍」。
     * 这里直接查 MediaStore 里有没有同名文件 —— 以磁盘为真相，不额外存状态，
     * 也就不会出现「记录说下好了、文件其实被删了」这种不一致。
     */
    static Saved findDownloaded(Context c, String versionName) {
        String name = "quizgate-" + versionName + ".apk";
        if (Build.VERSION.SDK_INT >= 29) {
            try (android.database.Cursor cu = c.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{name}, null)) {
                if (cu != null && cu.moveToFirst()) {
                    Uri u = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cu.getLong(0));
                    return new Saved(Environment.DIRECTORY_DOWNLOADS + "/" + name, u, name);
                }
            } catch (Throwable t) {
                Log.w(TAG, "update_find_failed: " + t);
            }
        }
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) {
            File f = new File(dir, name);
            if (f.exists()) return new Saved(f.getAbsolutePath(), null, name);
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String msg(Throwable e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String hex(byte[] v) {
        StringBuilder sb = new StringBuilder(v.length * 2);
        for (byte b : v) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
