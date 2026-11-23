package app.quizgate.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 用户题库里的一道题，字段采用 K12 题库工厂的统一题目 Schema。
 *
 * <p>九个规范字段：{@code topic_id} / {@code topic_name} / {@code stem} /
 * {@code options} / {@code answer}（字母 A–D）/ {@code explanation} /
 * {@code difficulty}（1–4）/ {@code bloom} / {@code type}。
 * 格式说明见 docs/bank-format.md 与 tools/k12_schema.py。
 *
 * <p>内部仍以 {@code answerIndex}（0–3）参与判题与打乱选项，写盘时才转成字母；
 * 另外在落盘对象里额外带上 {@code subject}/{@code grade}，用于分片定位与旧数据
 * 兼容，它们不属于 K12 规范，但不影响规范字段的完整性。
 */
public final class BankQuestion {
    public String topicId = "";
    public String topicName = "";
    public String subject = "";
    public int grade = 1;
    public String stem = "";
    public String[] options = new String[]{"", "", "", ""};
    public int answerIndex = 0;
    public String explanation = "";
    public int difficulty = 2;
    public String bloom = "理解";

    /** 写入 q_&lt;subject&gt;_&lt;grade&gt;.json 时的结构，规范字段与 K12 一致。 */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("topic_id", topicId);
        o.put("topic_name", topicName);
        o.put("stem", stem);
        JSONArray arr = new JSONArray();
        for (String s : options) arr.put(s);
        o.put("options", arr);
        int idx = answerIndex >= 0 && answerIndex <= 3 ? answerIndex : 0;
        o.put("answer", String.valueOf("ABCD".charAt(idx)));
        o.put("explanation", explanation);
        o.put("difficulty", difficulty);
        o.put("bloom", bloom);
        o.put("type", "single_choice");
        // 以下两个为扩展字段，仅用于分片定位与旧数据兼容
        o.put("subject", subject);
        o.put("grade", grade);
        return o;
    }
}
