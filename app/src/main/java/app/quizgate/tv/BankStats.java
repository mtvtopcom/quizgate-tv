package app.quizgate.tv;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 题量统计：科目 × 年级。
 *
 * <p>「题库管理」要显示默认题库/用户题库各有多少题，「题目年级」三级选择要在
 * 每个选项上显示题数、0 题显示「暂无数据」。电视内存只有 33MB 堆，不能每次
 * 打开面板都把 5.5MB 题库解析一遍：
 * <ul>
 *   <li>内置题库读构建期生成的 {@code assets/quiz/counts.json}（1KB，见
 *       {@code tools/gen_bank_counts.py}）；索引缺失才退化为逐份扫描，保证不会因为
 *       漏打资源就显示 0 题。</li>
 *   <li>用户题库读 {@code meta.json} 里的 counts（导入时写入）；1.4.0 之前建的
 *       题库没有这份数据，由 {@link BankStore#stats} 扫一次分片并回填。</li>
 * </ul>
 */
public final class BankStats {
    private static final String TAG = "QuizGate";

    /** 下标 = 年级 1–9；0 号位留空，读写都按年级直接取。 */
    private final Map<String, int[]> cells = new HashMap<>();
    private int total = 0;

    private static volatile BankStats builtinCache;

    private BankStats() {}

    public static BankStats empty() {
        return new BankStats();
    }

    public int at(String subjectKey, int grade) {
        int[] row = cells.get(subjectKey);
        if (row == null || grade < 1 || grade > 9) return 0;
        return row[grade];
    }

    public int subjectTotal(String subjectKey) {
        int[] row = cells.get(subjectKey);
        if (row == null) return 0;
        int n = 0;
        for (int g = 1; g <= 9; g++) n += row[g];
        return n;
    }

    public int gradeTotal(int grade) {
        int n = 0;
        for (String k : Prefs.SUBJECT_KEYS) n += at(k, grade);
        return n;
    }

    public int stageTotal(int stage) {
        boolean junior = stage == Prefs.STAGE_JUNIOR;
        int from = junior ? 7 : 1;
        int to = junior ? 9 : 6;
        int n = 0;
        for (int g = from; g <= to; g++) n += gradeTotal(g);
        return n;
    }

    public int total() {
        return total;
    }

    void put(String subjectKey, int grade, int count) {
        if (grade < 1 || grade > 9) return;
        int[] row = cells.get(subjectKey);
        if (row == null) {
            row = new int[10];
            cells.put(subjectKey, row);
        }
        total -= row[grade];
        row[grade] = Math.max(0, count);
        total += row[grade];
    }

    /** 供 meta.json 持久化；只写有数据的科目。 */
    Map<String, int[]> cells() {
        return cells;
    }

    // ---------------- 内置题库 ----------------

    /**
     * 默认题库的题量。索引在构建期生成，读一次 1KB；整个进程只算一次。
     */
    public static BankStats builtin(Context c) {
        BankStats hit = builtinCache;
        if (hit != null) return hit;
        synchronized (BankStats.class) {
            if (builtinCache != null) return builtinCache;
            BankStats s = fromIndex(c);
            if (s == null) {
                // 索引漏打进包 / 被改坏：退化为逐份扫描，宁愿慢一点也不能显示 0 题
                Log.w(TAG, "quiz counts.json unavailable, scanning assets");
                s = new BankStats();
                for (String key : Prefs.SUBJECT_KEYS) {
                    for (int g = 1; g <= 9; g++) {
                        s.put(key, g, countStemKeys(
                                readAsset(c, "quiz/" + key + "_grade_" + g + ".json")));
                    }
                }
            }
            builtinCache = s;
            return s;
        }
    }

    private static BankStats fromIndex(Context c) {
        String text = readAsset(c, "quiz/counts.json");
        if (text.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("version", 0) != 1) return null;
            JSONObject counts = root.optJSONObject("counts");
            if (counts == null) return null;
            BankStats s = new BankStats();
            for (String key : Prefs.SUBJECT_KEYS) {
                JSONArray row = counts.optJSONArray(key);
                if (row == null || row.length() != 10) return null;
                for (int g = 1; g <= 9; g++) s.put(key, g, row.optInt(g, 0));
            }
            return s.total() > 0 ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String readAsset(Context c, String path) {
        try {
            AssetManager am = c.getAssets();
            InputStream in = am.open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 数一段题库 JSON 里有多少道理题：只找 {@code "stem"} 键，不解析整棵树。
     *
     * <p>我们自己的写入器（内置管线 / JSONObject.toString）都是合法 JSON，题干里
     * 若出现 {@code "stem":} 这样的文本一定被转义成 {@code \"stem\":}，所以额外检查
     * 前一个字符不是反斜杠就不会多数。比 {@code new JSONArray(...)} 省一个数量级
     * 的内存 —— 用户题库上限 10 万题，整棵解析会直接把电视堆打爆。
     */
    static int countStemKeys(String json) {
        if (json == null || json.isEmpty()) return 0;
        int n = 0;
        int from = 0;
        while (true) {
            int i = json.indexOf("\"stem\"", from);
            if (i < 0) break;
            boolean escaped = i > 0 && json.charAt(i - 1) == '\\';
            int j = i + 6;
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
            if (!escaped && j < json.length() && json.charAt(j) == ':') n++;
            from = j > i ? j : i + 6;
        }
        return n;
    }
}
