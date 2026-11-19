package app.quizgate.tv;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 题库：assets/quiz/&lt;subject&gt;_grade_&lt;1..9&gt;.json，按科目 + 年级分片。
 * 规则（见决策文档 ADR-6）：
 *  - 抽题 grade<=当前年级，向低年级回退，任何回退都不突破年级下限（初中不回落小学）
 *  - 判题用下标，选项打乱后重算下标
 *  - 空池兜底到口算生成器（仅数学；语文/英语没有算术兜底，改为回退最低年级）
 */
public final class QuizBank {
    private static final String TAG = "QuizGate";

    public static final class Q {
        public String stem = "";
        public String[] options = new String[]{"", "", "", ""};
        public int answer = 0;
        public String explain = "";
    }

    private final Context ctx;
    private final Map<String, List<Q>> cache = new HashMap<>();
    private final Map<String, String> lastStem = new HashMap<>();
    /** 本轮（一次遮罩）已出过的题干，保证一轮内不重复。MaskView 创建时 reset。 */
    private final Set<String> usedThisRound = new HashSet<>();

    public QuizBank(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** 开始新一轮答题：清空去重记录。由 MaskView 构造时调用。 */
    public void resetRound() { usedThisRound.clear(); }

    /** 打乱选项，返回打乱后正确答案的下标。 */
    public static int shuffle(String[] opts, int answerIdx, Random r) {
        int[] idx = {0, 1, 2, 3};
        for (int i = 3; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
        }
        String[] copy = opts.clone();
        for (int i = 0; i < 4; i++) opts[i] = copy[idx[i]];
        for (int i = 0; i < 4; i++) if (idx[i] == answerIdx) return i;
        return 0;
    }

    /**
     * @param subjectKey 题库前缀：chinese / math / english
     * @param floor      最低可回落的年级。初中（7-9）要传 7，否则题库缺片时会退到小学题。
     */
    public Q next(String subjectKey, int grade, int floor, Random r) {
        int g = Math.max(1, Math.min(9, grade));
        int lo = Math.max(1, Math.min(g, floor));
        // 一轮内去重：先在 [当前年级 → 下限] 里找没出过的题。
        for (int cur = g; cur >= lo; cur--) {
            Q out = pickFresh(subjectKey, cur, r);
            if (out != null) return mark(out);
        }
        // 本年级全部出过：从下限往上再找一轮没出过的（跨年级补题）
        for (int cur = lo; cur <= g; cur++) {
            Q out = pickFresh(subjectKey, cur, r);
            if (out != null) return mark(out);
        }
        // 数学：题库全部出过后，用动态口算无限补足，按本轮已出题干查重
        if ("math".equals(subjectKey)) return Core.arithmetic(g, r, usedThisRound);
        // 语文/英语没有动态生成：所有题都出过了就清空记录重新循环（延后重复到极限）
        if (!load(subjectKey, g).isEmpty()) {
            usedThisRound.clear();
            for (int cur = g; cur >= lo; cur--) {
                Q out = pickFresh(subjectKey, cur, r);
                if (out != null) return mark(out);
            }
        }
        // 终极兜底：通用常识题，保证永远有题
        Q q = new Q();
        q.stem = "下面哪一项是正确的中文标点用法？";
        q.options = new String[]{"句子结束用句号。", "句子结束用逗号，", "句子结束用顿号、", "句子结束用分号；"};
        q.answer = 0;
        q.explain = "陈述句末尾用句号。";
        q.answer = shuffle(q.options, q.answer, r);
        return mark(q);
    }

    /** 从某年级题库里抽一道「本轮没出过」的题；全出过返回 null。 */
    private Q pickFresh(String subjectKey, int grade, Random r) {
        List<Q> list = load(subjectKey, grade);
        if (list.isEmpty()) return null;
        List<Q> fresh = new ArrayList<>();
        for (Q c : list) if (!usedThisRound.contains(c.stem)) fresh.add(c);
        if (fresh.isEmpty()) return null;
        // 在新鲜题里优先避开上一道（体验更好）
        Q src = fresh.get(r.nextInt(fresh.size()));
        String last = lastStem.get(subjectKey + grade);
        if (fresh.size() > 1 && last != null && last.equals(src.stem)) {
            src = fresh.get(r.nextInt(fresh.size()));
            if (last.equals(src.stem)) {
                for (Q c : fresh) if (!last.equals(c.stem)) { src = c; break; }
            }
        }
        lastStem.put(subjectKey + grade, src.stem);
        Q out = new Q();
        out.stem = src.stem;
        out.options = src.options.clone();
        out.answer = src.answer;
        out.explain = src.explain;
        out.answer = shuffle(out.options, out.answer, r);
        return out;
    }

    /** 把抽出的题登记到本轮集合。 */
    private Q mark(Q q) {
        usedThisRound.add(q.stem);
        return q;
    }

    private List<Q> load(String subjectKey, int grade) {
        String key = subjectKey + grade;
        List<Q> cached = cache.get(key);
        if (cached != null) return cached;
        List<Q> list = new ArrayList<>();
        String path = "quiz/" + subjectKey + "_grade_" + grade + ".json";
        try {
            AssetManager am = ctx.getAssets();
            InputStream in = am.open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    JSONArray opts = o.getJSONArray("options");
                    if (opts.length() != 4) continue;
                    Q q = new Q();
                    q.stem = o.getString("stem");
                    q.options = new String[]{opts.getString(0), opts.getString(1), opts.getString(2), opts.getString(3)};
                    q.answer = o.getInt("answerIndex");
                    if (q.answer < 0 || q.answer > 3) continue;
                    q.explain = o.optString("explanation", "");
                    list.add(q);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "quiz asset missing/broken: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
        cache.put(key, list);
        return list;
    }
}
