#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题库资产门禁：27 份本地 JSON（原创 216 题 + PoHW 导入 9515 题）。

题库改坏后真机只会静默出兜底题，很难定位。这里把文件数、每份题量、
K12 字段完整性（9 个必填字段 / answer 字母 / difficulty 1–4 / bloom 枚举 /
topic_id 格式）和导入题总数一次性钉死，本地与 CI 共用。

    python3 tools/check_quizbank.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import k12_schema as K                    # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
QUIZ_DIR = ROOT / "app/src/main/assets/quiz"
PREFIX = "pohw-"
SCHEMA_TAG = "k12-question-bank/v1"

SUBJECTS = {"chinese": "语文", "math": "数学", "english": "英语"}
# (文件题量, 其中 pohw- 导入题量)。1–6 年级叠加 PoHW，7–9 年级保持原有 8 题。
EXPECTED = {
    ("chinese", 1): (289, 281), ("chinese", 2): (523, 515),
    ("chinese", 3): (724, 716), ("chinese", 4): (335, 327),
    ("chinese", 5): (472, 464), ("chinese", 6): (259, 251),
    ("english", 1): (8, 0), ("english", 2): (8, 0),
    ("english", 3): (1017, 1009), ("english", 4): (381, 373),
    ("english", 5): (479, 471), ("english", 6): (8, 0),
    ("math", 1): (978, 970), ("math", 2): (916, 908),
    ("math", 3): (1053, 1045), ("math", 4): (979, 971),
    ("math", 5): (908, 900), ("math", 6): (322, 314),
}
for _key in SUBJECTS:
    for _grade in (7, 8, 9):
        EXPECTED[(_key, _grade)] = (8, 0)

errors = []
imported_ids = set()


def fail(msg):
    errors.append(msg)


def main():
    total_imported = 0
    total_items = 0
    topic_ids = set()

    for (key, grade), (want, want_imported) in sorted(EXPECTED.items()):
        p = QUIZ_DIR / f"{key}_grade_{grade}.json"
        if not p.exists():
            fail(f"缺少题库文件: {p.relative_to(ROOT)}")
            continue
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:
            fail(f"{p.name} 不是合法 JSON: {e}")
            continue

        # 文件头：version 3 + K12 schema 标记 + 年级科目
        if doc.get("version") != 3 or doc.get("schema") != SCHEMA_TAG \
                or doc.get("subject") != SUBJECTS[key] or doc.get("grade") != grade:
            fail(f"{p.name} 头信息不符（version/schema/subject/grade）")

        items = doc.get("items", [])
        if len(items) != want:
            fail(f"{p.name} 应有 {want} 题，实际 {len(items)}")
        total_items += len(items)

        ids = set()
        got_imported = 0
        for i, q in enumerate(items):
            where = f"{p.name} 第 {i + 1} 题"

            # K12 九个必填字段 + answer 字母 + difficulty + bloom + topic_id 格式
            for e in K.check_item(q, where):
                fail(e)

            qid = str(q.get("id", ""))
            if not qid or qid in ids:
                fail(f"{where} id 缺失或重复: {qid}")
            ids.add(qid)

            if qid.startswith(PREFIX):
                got_imported += 1
                if qid in imported_ids:
                    fail(f"{p.name} 导入题 id 全局重复: {qid}")
                imported_ids.add(qid)

            # topic_id 里的年级科目必须与所在文件一致，防止串片
            parsed = K.parse_topic_id(q.get("topic_id"))
            if parsed:
                if parsed["grade"] != grade or parsed["subject"] != key:
                    fail(f"{where} topic_id 与文件年级科目不符: {q.get('topic_id')}")
                topic_ids.add(q["topic_id"])

        if got_imported != want_imported:
            fail(f"{p.name} 导入题应为 {want_imported}，实际 {got_imported}")
        total_imported += got_imported

    if total_imported != 9515:
        fail(f"PoHW 导入题总数应为 9515，实际 {total_imported}")
    if total_items != 9731:
        fail(f"题库总题量应为 9731，实际 {total_items}")

    # 题量索引：电视端「题库管理 / 题目年级」直接读 counts.json 显示题数，
    # 题库改了却没重跑生成脚本会显示旧数字，必须在这里拦住。
    counts_path = QUIZ_DIR / "counts.json"
    try:
        idx = json.loads(counts_path.read_text(encoding="utf-8"))
    except Exception as e:
        fail(f"counts.json 缺失或损坏（运行 python3 tools/gen_bank_counts.py）: {e}")
        idx = None
    if idx is not None:
        if idx.get("version") != 1:
            fail(f"counts.json version 应为 1，实际 {idx.get('version')}")
        for key in SUBJECTS:
            row = (idx.get("counts") or {}).get(key)
            if not isinstance(row, list) or len(row) != 10:
                fail(f"counts.json 的 {key} 必须是长度 10 的数组")
                continue
            for grade in range(1, 10):
                want = EXPECTED[(key, grade)][0]
                if row[grade] != want:
                    fail(f"counts.json {key} {grade} 年级应为 {want}，实际 {row[grade]}"
                         "（重跑 python3 tools/gen_bank_counts.py）")

    if errors:
        for e in errors:
            print("QUIZBANK_FAIL " + e, file=sys.stderr)
        sys.exit(1)
    print(f"QUIZBANK_OK files={len(EXPECTED)} total={total_items} "
          f"original={total_items - total_imported} imported={total_imported} "
          f"topics={len(topic_ids)} schema={SCHEMA_TAG}")


if __name__ == "__main__":
    main()
