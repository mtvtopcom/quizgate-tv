package app.quizgate.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户题库的落地存储。
 *
 * <p>目录结构（全部在应用私有目录，覆盖升级保留、卸载即清）：
 * <pre>
 * filesDir/banks/&lt;id&gt;/meta.json              {version,name,createdAt,count}
 * filesDir/banks/&lt;id&gt;/q_&lt;subject&gt;_&lt;grade&gt;.json  {version,items:[...]}
 * </pre>
 * 分片键与内置题库一致（chinese/math/english × 1-9），只加载当前用到的一片。
 *
 * <p>为什么不是一个 SQLite：当前规模（内置 9731 + 用户几千到几万）按年级科目
 * 分片读一次只要几毫秒，内存 1-2MB；等真到 10 万题、要按单元/知识点筛选或全文
 * 搜索时再换，替换点只有 {@link #load} 一个方法。
 *
 * <p>写入一律走「临时文件 + rename」，避免电视被强停时留下半个题库。
 */
public final class BankStore {
    private static final String TAG = "QuizGate";

    /** 内置题库的虚拟 id，不会在 filesDir 下建目录。 */
    public static final String BUILTIN_ID = "builtin";
    /** 单次上传的题目上限，防止电视解析 10 万行 JSON 时 OOM。 */
    public static final int MAX_IMPORT = 30000;
    /** 单个题库的题目上限。 */
    public static final int MAX_BANK = 100000;
    /** 单次请求体上限。 */
    public static final int MAX_BODY = 40 * 1024 * 1024;
    /** K12 规范的认知层级取值，非法值一律回落到「理解」。 */
    static final Set<String> BLOOM_VALUES = new HashSet<>(java.util.Arrays.asList(
            "记忆", "理解", "应用", "分析", "评价", "创造"));

    public static final class Meta {
        public String id = "";
        public String name = "";
        public long createdAt = 0L;
        public int count = 0;
    }

    public static final class ImportResult {
        public boolean ok = false;
        public String message = "";
        public String bankId = "";
        public String bankName = "";
        public int accepted = 0;
        public int skipped = 0;
        /** 因属于幼儿园学段而跳过的题数（本软件只有 1–9 年级）。 */
        public int kindergarten = 0;
        public final List<String> errors = new ArrayList<>();
    }

    private final File root;
    private final Map<String, List<BankQuestion>> cache = new HashMap<>();
    private String cachedId = "";

    public BankStore(Context c) {
        root = new File(c.getApplicationContext().getFilesDir(), "banks");
        if (!root.isDirectory() && !root.mkdirs()) {
            Log.w(TAG, "banks dir unavailable: " + root);
        }
    }

    // ---------------- 列举 / 元数据 ----------------

    public List<Meta> list() {
        List<Meta> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory() || !validId(d.getName())) continue;
            Meta m = readMeta(d);
            if (m != null) out.add(m);
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    public Meta meta(String id) {
        if (!validId(id)) return null;
        File dir = new File(root, id);
        return dir.isDirectory() ? readMeta(dir) : null;
    }

    public int count(String id) {
        Meta m = meta(id);
        return m == null ? 0 : m.count;
    }

    // ---------------- 新建 / 合并 / 删除 ----------------

    public ImportResult create(String name, List<BankQuestion> questions) {
        ImportResult r = new ImportResult();
        if (questions.isEmpty()) return fail(r, "没有可导入的题目");
        if (questions.size() > MAX_IMPORT) return fail(r, "单次最多导入 " + MAX_IMPORT + " 题");
        String safeName = cleanName(name);
        String id = newId();
        File dir = new File(root, id);
        if (!dir.mkdirs()) return fail(r, "无法创建题库目录");
        try {
            writeShards(dir, questions);
            Meta m = new Meta();
            m.id = id;
            m.name = safeName;
            m.createdAt = System.currentTimeMillis();
            m.count = questions.size();
            writeMeta(dir, m);
            r.ok = true;
            r.bankId = id;
            r.bankName = safeName;
            r.accepted = questions.size();
            r.message = "已新建「" + safeName + "」，共 " + questions.size() + " 题";
            return r;
        } catch (Exception e) {
            deleteRec(dir);
            return fail(r, "写入失败：" + e.getClass().getSimpleName());
        }
    }

    public ImportResult merge(String id, List<BankQuestion> questions) {
        ImportResult r = new ImportResult();
        if (BUILTIN_ID.equals(id)) return fail(r, "默认题库不能追加题目");
        if (!validId(id)) return fail(r, "题库不存在");
        File dir = new File(root, id);
        if (!dir.isDirectory()) return fail(r, "题库不存在");
        Meta m = readMeta(dir);
        if (m == null) return fail(r, "题库信息损坏，无法追加");

        List<BankQuestion> all = readAllShards(dir);
        Set<String> seen = new HashSet<>();
        for (BankQuestion q : all) seen.add(q.stem);

        int skipped = 0;
        for (BankQuestion q : questions) {
            if (all.size() >= MAX_BANK) {
                r.skipped = skipped;
                r.errors.add("题库已达上限 " + MAX_BANK + " 题，剩余题目未导入");
                break;
            }
            if (!seen.add(q.stem)) {
                skipped++;
                continue;
            }
            all.add(q);
        }
        try {
            writeShards(dir, all);
            m.count = all.size();
            writeMeta(dir, m);
            invalidate(id);
            r.ok = true;
            r.bankId = id;
            r.bankName = m.name;
            r.accepted = questions.size() - skipped;
            r.skipped = skipped;
            r.message = "已合并到「" + m.name + "」，新增 " + r.accepted + " 题"
                    + (skipped > 0 ? "，跳过重复 " + skipped + " 题" : "");
            return r;
        } catch (Exception e) {
            return fail(r, "写入失败：" + e.getClass().getSimpleName());
        }
    }

    public boolean delete(String id) {
        if (BUILTIN_ID.equals(id) || !validId(id)) return false;
        File dir = new File(root, id);
        if (!dir.isDirectory()) return false;
        boolean ok = deleteRec(dir);
        invalidate(id);
        return ok;
    }

    // ---------------- 读取（运行时抽题用） ----------------

    /**
     * 读某个题库在指定年级科目的题目。返回的列表只读，不要修改。
     *
     * <p>按 {@code q_<subject>_<grade>.json} 分片存储：只解析用到的那一片，
     * 内存占用是「一片几百题」而不是「整库几万题」。这是电视 33MB 堆预算下的
     * 关键约束，也是以后换 SQLite 时唯一需要动的读取点。
     */
    public List<BankQuestion> load(String id, String subjectKey, int grade) {
        if (!validId(id)) return Collections.emptyList();
        if (subjectName(subjectKey).isEmpty()) return Collections.emptyList();
        File dir = new File(root, id);
        if (!dir.isDirectory()) return Collections.emptyList();

        String key = id + "|" + subjectKey + "|" + grade;
        synchronized (cache) {
            if (!id.equals(cachedId)) {
                cache.clear();
                cachedId = id;
            }
            List<BankQuestion> hit = cache.get(key);
            if (hit != null) return hit;
        }

        List<BankQuestion> list = readShard(dir, subjectKey, grade);
        synchronized (cache) {
            if (!id.equals(cachedId)) {
                cache.clear();
                cachedId = id;
            }
            cache.put(key, list);
        }
        return list;
    }

    public void invalidate(String id) {
        synchronized (cache) {
            if (id.equals(cachedId)) {
                cache.clear();
                cachedId = "";
            }
        }
    }

    // ---------------- 导入 JSON ----------------

    /**
     * 统一入口：手机网页把 txt/csv/xlsx/json 都解析成
     * {@code {"questions":[{subject,grade,stem,options,answerIndex,explanation}]}}，
     * 电视端只做校验与入库，不碰原始文件格式。
     */
    public ImportResult importJson(String name, String mode, String targetId, String body) {
        ImportResult r = new ImportResult();
        if (body == null || body.trim().isEmpty()) return fail(r, "上传内容为空");

        JSONArray items;
        try {
            String t = body.trim();
            if (t.startsWith("[")) {
                items = new JSONArray(t);
            } else {
                JSONObject root = new JSONObject(t);
                items = root.optJSONArray("questions");
                if (items == null) items = root.optJSONArray("items");
            }
        } catch (Exception e) {
            return fail(r, "不是合法的题库 JSON");
        }
        if (items == null) return fail(r, "JSON 里找不到 questions 数组");
        if (items.length() == 0) return fail(r, "题目数量为 0");
        if (items.length() > MAX_IMPORT) return fail(r, "单次最多导入 " + MAX_IMPORT + " 题");

        List<BankQuestion> parsed = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                addError(r, i + 1, "不是对象");
                continue;
            }
            BankQuestion q = parseQuestion(o);
            if (q == null) continue;
            // K12 题库覆盖幼儿园到初中，本软件只有 1–9 年级：幼儿园题跳过并计数，
            // 不当作错误（整份文件里混着幼儿园题是正常情况）。
            if (isKindergarten(o)) {
                r.kindergarten++;
                continue;
            }
            if (q.stem.isEmpty()) {
                addError(r, i + 1, "题干为空");
                continue;
            }
            if (q.options[0].isEmpty() || q.options[1].isEmpty()
                    || q.options[2].isEmpty() || q.options[3].isEmpty()) {
                addError(r, i + 1, "选项不足 4 个");
                continue;
            }
            if (q.answerIndex < 0 || q.answerIndex > 3) {
                addError(r, i + 1, "答案必须是 A/B/C/D 或 0-3");
                continue;
            }
            if (q.subject.isEmpty()) {
                addError(r, i + 1, "科目必须是语文/数学/英语");
                continue;
            }
            if (q.grade < 1 || q.grade > 9) {
                addError(r, i + 1, "年级必须是 1-9");
                continue;
            }
            parsed.add(q);
        }

        if (parsed.isEmpty()) {
            r.ok = false;
            r.message = "没有一条题目通过校验";
            if (r.errors.isEmpty()) r.errors.add("请检查文件格式");
            return r;
        }

        ImportResult out = "merge".equals(mode)
                ? merge(targetId, parsed)
                : create(name, parsed);
        out.errors.addAll(0, r.errors);
        out.kindergarten = r.kindergarten;
        if (out.ok && r.kindergarten > 0) {
            out.message += "；跳过幼儿园题 " + r.kindergarten + " 题（本软件只支持 1–9 年级）";
        }
        if (out.ok && !r.errors.isEmpty()) {
            out.message += "；另有 " + r.errors.size() + " 条未通过校验";
        }
        return out;
    }

    private void addError(ImportResult r, int line, String msg) {
        if (r.errors.size() < 20) r.errors.add("第 " + line + " 题：" + msg);
    }

    private static BankQuestion parseQuestion(JSONObject o) {
        BankQuestion q = new BankQuestion();

        // ---- K12 规范字段 ----
        q.topicId = trim(o.optString("topic_id", ""));
        q.topicName = trim(o.optString("topic_name", ""));
        q.difficulty = clampDifficulty(o.optInt("difficulty", 2));
        String bloom = trim(o.optString("bloom", ""));
        q.bloom = BLOOM_VALUES.contains(bloom) ? bloom : "理解";

        q.stem = trim(o.optString("stem", o.optString("题干", "")));
        q.explanation = trim(o.optString("explanation",
                o.optString("解析", o.optString("explain", ""))));

        JSONArray opts = o.optJSONArray("options");
        if (opts == null) opts = o.optJSONArray("选项");
        for (int i = 0; i < 4; i++) {
            String v = "";
            if (opts != null && i < opts.length()) v = trim(opts.optString(i, ""));
            if (v.isEmpty()) {
                String[] letters = {"a", "b", "c", "d"};
                v = trim(o.optString(letters[i], o.optString(letters[i].toUpperCase(), "")));
            }
            q.options[i] = v;
        }

        // ---- 年级科目：显式字段优先，缺失时从 topic_id 反推 ----
        q.subject = normalizeSubject(o.optString("subject", o.optString("科目", "")));
        q.grade = parseGrade(o.opt("grade") != null ? o.opt("grade") : o.opt("年级"));
        if ((q.subject.isEmpty() || q.grade < 1 || q.grade > 9) && !q.topicId.isEmpty()) {
            String[] sg = subjectGradeFromTopic(q.topicId);
            if (sg != null) {
                if (q.subject.isEmpty()) q.subject = sg[0];
                if (q.grade < 1 || q.grade > 9) q.grade = Integer.parseInt(sg[1]);
            }
        }

        // ---- 答案：K12 的 answer 字母优先，其次 answerIndex / 答案 ----
        int byLetter = QuizBank.parseAnswer(o);
        if (byLetter >= 0) {
            q.answerIndex = byLetter;
        } else {
            Object ans = o.opt("answerIndex");
            if (ans == null) ans = o.opt("答案");
            q.answerIndex = parseAnswer(ans);
        }
        return q;
    }

    /**
     * 从 K12 的 topic_id 反推 [科目, 年级]，如 primary-g3-s1-math-K018
     * 得到 ["数学", "3"]。幼儿园前缀（kg-/kindergarten）或格式不合法返回 null。
     */
    private static String[] subjectGradeFromTopic(String topicId) {
        String t = topicId.trim().toLowerCase();
        if (t.isEmpty()) return null;
        String[] p = t.split("-");
        if (p.length < 5) return null;
        if ("kg".equals(p[0]) || "kindergarten".equals(p[0])) return null;
        if (!"primary".equals(p[0]) && !"junior".equals(p[0])) return null;
        if (!p[1].startsWith("g")) return null;
        int grade;
        try {
            grade = Integer.parseInt(p[1].substring(1));
        } catch (Exception e) {
            return null;
        }
        if (grade < 1 || grade > 9) return null;
        String subject = normalizeSubject(p[3]);
        if (subject.isEmpty()) return null;
        return new String[]{subject, String.valueOf(grade)};
    }

    /** 幼儿园题：本软件只有 1–9 年级，导入时跳过并计数。 */
    static boolean isKindergarten(JSONObject o) {
        String t = trim(o.optString("topic_id", "")).toLowerCase();
        String g = trim(o.optString("grade", ""));
        return t.startsWith("kg-") || t.startsWith("kindergarten")
                || (!g.isEmpty() && g.contains("幼儿园"));
    }

    private static int clampDifficulty(int d) {
        return d < 1 ? 1 : (d > 4 ? 4 : d);
    }

    // ---------------- 字段归一化 ----------------

    public static String normalizeSubject(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.equals("语文") || t.equalsIgnoreCase("chinese")) return "语文";
        if (t.equals("数学") || t.equalsIgnoreCase("math")) return "数学";
        if (t.equals("英语") || t.equalsIgnoreCase("english")) return "英语";
        return "";
    }

    public static String subjectName(String subjectKey) {
        if ("chinese".equals(subjectKey)) return "语文";
        if ("math".equals(subjectKey)) return "数学";
        if ("english".equals(subjectKey)) return "英语";
        return normalizeSubject(subjectKey);
    }

    public static int parseGrade(Object v) {
        if (v == null) return -1;
        if (v instanceof Number) {
            int g = ((Number) v).intValue();
            return (g >= 1 && g <= 9) ? g : -1;
        }
        String s = String.valueOf(v).trim();
        if (s.equals("初一")) return 7;
        if (s.equals("初二")) return 8;
        if (s.equals("初三")) return 9;
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
        for (int i = 0; i < cn.length; i++) {
            if (s.equals(cn[i] + "年级") || s.equals(cn[i])) return i + 1;
        }
        try {
            int g = Integer.parseInt(s);
            return (g >= 1 && g <= 9) ? g : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseAnswer(Object ans) {
        if (ans instanceof Number) {
            int v = ((Number) ans).intValue();
            return (v >= 0 && v <= 3) ? v : -1;
        }
        String s = String.valueOf(ans == null ? "" : ans).trim();
        switch (s.toUpperCase()) {
            case "A": return 0;
            case "B": return 1;
            case "C": return 2;
            case "D": return 3;
            default: break;
        }
        try {
            int v = Integer.parseInt(s);
            return (v >= 0 && v <= 3) ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 文件读写（按年级科目分片） ----------------

    private List<BankQuestion> readShard(File dir, String subjectKey, int grade) {
        return parseShard(new File(dir, "q_" + subjectKey + "_" + grade + ".json"));
    }

    private List<BankQuestion> readAllShards(File dir) {
        List<BankQuestion> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.startsWith("q_") && n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) out.addAll(parseShard(f));
        return out;
    }

    private List<BankQuestion> parseShard(File f) {
        List<BankQuestion> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            JSONObject root = new JSONObject(readText(f));
            JSONArray items = root.optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                BankQuestion q = parseQuestion(o);
                if (q.stem.isEmpty() || q.options[0].isEmpty()) continue;
                out.add(q);
            }
        } catch (Exception e) {
            Log.w(TAG, "bank shard broken: " + f.getName() + " (" + e.getClass().getSimpleName() + ")");
        }
        return out;
    }

    private void writeShards(File dir, List<BankQuestion> list) throws Exception {
        File[] old = dir.listFiles((d, n) -> n.startsWith("q_") && n.endsWith(".json"));
        if (old != null) for (File f : old) f.delete();

        Map<String, List<BankQuestion>> groups = new HashMap<>();
        for (BankQuestion q : list) {
            String key = subjectKeyOf(q.subject) + "_" + q.grade;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }
        for (Map.Entry<String, List<BankQuestion>> e : groups.entrySet()) {
            JSONArray arr = new JSONArray();
            for (BankQuestion q : e.getValue()) arr.put(q.toJson());
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("items", arr);
            atomicWrite(new File(dir, "q_" + e.getKey() + ".json"), root.toString());
        }
    }

    private static String subjectKeyOf(String subject) {
        if ("语文".equals(subject)) return "chinese";
        if ("数学".equals(subject)) return "math";
        if ("英语".equals(subject)) return "english";
        return "other";
    }

    private Meta readMeta(File dir) {
        File f = new File(dir, "meta.json");
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readText(f));
            Meta m = new Meta();
            m.id = dir.getName();
            m.name = cleanName(o.optString("name", "未命名题库"));
            m.createdAt = o.optLong("createdAt", 0L);
            m.count = o.optInt("count", 0);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeMeta(File dir, Meta m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("name", m.name);
        o.put("createdAt", m.createdAt);
        o.put("count", m.count);
        atomicWrite(new File(dir, "meta.json"), o.toString());
    }

    private String readText(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void atomicWrite(File f, String data) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f)) {
                throw new Exception("rename failed: " + f.getName());
            }
        }
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        return f.delete();
    }

    // ---------------- 小工具 ----------------

    private static String cleanName(String s) {
        String t = trim(s);
        if (t.isEmpty()) t = "我的题库";
        if (t.length() > 24) t = t.substring(0, 24);
        return t;
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9]{6,32}");
    }

    private static String newId() {
        return "b" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }

    private static ImportResult fail(ImportResult r, String msg) {
        r.ok = false;
        r.message = msg;
        return r;
    }
}
