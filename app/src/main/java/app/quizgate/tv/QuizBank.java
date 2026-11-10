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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 题库：assets/quiz/grade_<1..9>.json，按年级分片。
 * 规则（见决策文档 ADR-6）：
 *  - 抽题 grade<=当前年级，向低年级回退，任何回退都不突破年级上限
 *  - 判题用下标，选项打乱后重算下标
 *  - 空池兜底到口算生成器，保证永远有题
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
    private final Map<Integer, List<Q>> cache = new HashMap<>();
    private final Map<Integer, String> lastStem = new HashMap<>();

    public QuizBank(Context ctx) { this.ctx = ctx.getApplicationContext(); }

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

    public Q next(int grade, Random r) {
        int g = Math.max(1, Math.min(9, grade));
        for (int cur = g; cur >= 1; cur--) {
            List<Q> list = load(cur);
            if (list.isEmpty()) continue;
            Q src = pick(list, cur, r);
            if (src == null) continue;
            Q out = new Q();
            out.stem = src.stem;
            out.options = src.options.clone();
            out.answer = src.answer;
            out.explain = src.explain;
            out.answer = shuffle(out.options, out.answer, r);
            return out;
        }
        Q q = Core.arithmetic(g, r);
        return q;
    }

    private Q pick(List<Q> list, int grade, Random r) {
        String last = lastStem.get(grade);
        for (int attempt = 0; attempt < 6; attempt++) {
            Q q = list.get(r.nextInt(list.size()));
            if (last == null || !last.equals(q.stem)) {
                lastStem.put(grade, q.stem);
                return q;
            }
        }
        Q q = list.get(r.nextInt(list.size()));
        lastStem.put(grade, q.stem);
        return q;
    }

    private List<Q> load(int grade) {
        List<Q> cached = cache.get(grade);
        if (cached != null) return cached;
        List<Q> list = new ArrayList<>();
        String path = "quiz/grade_" + grade + ".json";
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
        cache.put(grade, list);
        return list;
    }
}
