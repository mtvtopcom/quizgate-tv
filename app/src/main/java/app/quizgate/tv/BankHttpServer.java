package app.quizgate.tv;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只在「添加题库」页存活的极简 HTTP 服务。
 *
 * <p>职责边界刻意压到最小：
 * <ul>
 *   <li>GET  /            返回上传页（把当前题库列表注入页面）</li>
 *   <li>GET  /xlsx.full.min.js  上传页用的表格解析库</li>
 *   <li>POST /upload      收标准 JSON，交给 {@link BankStore#importJson}</li>
 * </ul>
 *
 * <p>不做 multipart：手机网页用 fetch 直接把 JSON 放进 body，省掉一整套边界解析。
 * 所有请求都要求 URL 带一次性 token，避免同网其他人误传。
 * 只被动监听局域网端口，不发起任何外部连接（CI 的出网点断言仍只有 Updater 两处）。
 */
public final class BankHttpServer {
    private static final String TAG = "QuizGate";
    // 8899-8910 是小米电视投屏（MiPlay/MPASink）的常驻端口，实测会被它抢走连接：
    // 客户端连上来读到的是投屏的二进制协议而不是上传页。改到高位段避开。
    private static final int PORT_FROM = 18089;
    private static final int PORT_TO = 18120;
    private static final int SOCKET_TIMEOUT = 30000;

    public interface Listener {
        /** 上传成功、题库有变化时回调（在服务线程里，调用方自己切主线程）。 */
        void onBankChanged();
    }

    private final Context ctx;
    private final BankStore store;
    private volatile Listener listener;
    private final String token;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;

    public BankHttpServer(Context c, BankStore store, Listener l) {
        this.ctx = c.getApplicationContext();
        this.store = store;
        this.listener = l;
        this.token = randomToken();
    }

    public String token() { return token; }

    public int port() { return port; }

    /** 页面重建后把"上传成功刷新列表"的回调指到新页面；服务本身不随页面重建。 */
    public void setListener(Listener l) { this.listener = l; }

    /** 进程级服务是否仍在监听，供页面判断是否需要重建。 */
    public boolean isRunning() { return running && server != null && !server.isClosed(); }

    /** 启动并返回可用端口；失败抛 IOException。 */
    public int start() throws IOException {
        if (running) return port;
        IOException last = null;
        for (int p = PORT_FROM; p <= PORT_TO; p++) {
            try {
                server = new ServerSocket();
                // 不要 setReuseAddress(true)：端口被系统服务占用时会"绑成功"但连接被对方接走。
                // 保持默认 false，被占用就 bind 失败，自动试下一个端口。
                server.bind(new InetSocketAddress(p));
                port = p;
                break;
            } catch (IOException e) {
                last = e;
                closeQuietly(server);
                server = null;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no port");

        running = true;
        pool = Executors.newFixedThreadPool(3);
        Thread t = new Thread(this::acceptLoop, "bank-http");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "bank http on :" + port);
        return port;
    }

    public void stop() {
        running = false;
        closeQuietly(server);
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setSoTimeout(SOCKET_TIMEOUT);
                ExecutorService p = pool;
                if (p != null) p.execute(() -> handle(s));
                else closeQuietly(s);
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept: " + e.getClass().getSimpleName());
            }
        }
    }

    private void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(),
                        line.substring(i + 1).trim());
            }

            int q = target.indexOf('?');
            String path = q >= 0 ? target.substring(0, q) : target;
            Map<String, String> query = parseQuery(q >= 0 ? target.substring(q + 1) : "");

            if (!token.equals(query.get("t"))) {
                send(s, 403, "application/json; charset=utf-8",
                        "{\"ok\":false,\"message\":\"链接已失效，请重新扫码\"}");
                return;
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                send(s, 200, "text/html; charset=utf-8", uploadPage());
                return;
            }
            if ("GET".equals(method) && "/xlsx.full.min.js".equals(path)) {
                send(s, 200, "application/javascript; charset=utf-8",
                        new String(asset("bank/xlsx.full.min.js"), StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equals(method) && "/upload".equals(path)) {
                int len = parseInt(headers.get("content-length"), 0);
                if (len <= 0) {
                    send(s, 400, "application/json; charset=utf-8",
                            "{\"ok\":false,\"message\":\"没有收到内容\"}");
                    return;
                }
                if (len > BankStore.MAX_BODY) {
                    send(s, 413, "application/json; charset=utf-8",
                            "{\"ok\":false,\"message\":\"文件太大，单次上限 40MB\"}");
                    return;
                }
                String body = readBody(in, len);
                BankStore.ImportResult r = store.importJson(
                        query.get("name"), query.get("mode"), query.get("target"), body);
                if (r.ok && listener != null) listener.onBankChanged();
                send(s, 200, "application/json; charset=utf-8", resultJson(r));
                return;
            }
            send(s, 404, "application/json; charset=utf-8",
                    "{\"ok\":false,\"message\":\"not found\"}");
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e.getClass().getSimpleName());
            try {
                send(s, 500, "application/json; charset=utf-8",
                        "{\"ok\":false,\"message\":\"服务器异常\"}");
            } catch (Exception ignored) { }
        } finally {
            closeQuietly(s);
        }
    }

    // ---------------- 响应 ----------------

    private void send(Socket s, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    private String resultJson(BankStore.ImportResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(r.ok)
                .append(",\"message\":\"").append(esc(r.message)).append("\"")
                .append(",\"bankId\":\"").append(esc(r.bankId)).append("\"")
                .append(",\"bankName\":\"").append(esc(r.bankName)).append("\"")
                .append(",\"accepted\":").append(r.accepted)
                .append(",\"skipped\":").append(r.skipped)
                .append(",\"kindergarten\":").append(r.kindergarten)
                .append(",\"errors\":[");
        for (int i = 0; i < r.errors.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(r.errors.get(i))).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------- 页面 ----------------

    private String uploadPage() {
        String html = new String(asset("bank/upload.html"), StandardCharsets.UTF_8);
        StringBuilder banks = new StringBuilder("[");
        List<BankStore.Meta> list = store.list();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) banks.append(',');
            banks.append("{\"id\":\"").append(esc(list.get(i).id))
                    .append("\",\"name\":\"").append(esc(list.get(i).name))
                    .append("\",\"count\":").append(list.get(i).count).append('}');
        }
        banks.append(']');
        return html.replace("__BANKS__", banks.toString())
                .replace("__TOKEN__", token);
    }

    private byte[] asset(String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return ("missing asset: " + path).getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---------------- HTTP 小工具 ----------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 8192) break;
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readBody(InputStream in, int len) throws IOException {
        byte[] buf = new byte[8192];
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(len, 1 << 20));
        int left = len;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) break;
            bos.write(buf, 0, n);
            left -= n;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        if (q == null || q.isEmpty()) return out;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            try {
                if (i > 0) out.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
                else if (!pair.isEmpty()) out.put(URLDecoder.decode(pair, "UTF-8"), "");
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) { }
    }

    private static String randomToken() {
        String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(8);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 取一个局域网 IPv4，优先 192.168 / 10 / 172.16-31。没有网络返回 null。 */
    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip;
                    if (ip.startsWith("172.")) {
                        try {
                            int second = Integer.parseInt(ip.split("\\.")[1]);
                            if (second >= 16 && second <= 31) return ip;
                        } catch (Exception ignored) { }
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIp: " + e.getClass().getSimpleName());
        }
        return fallback;
    }
}
