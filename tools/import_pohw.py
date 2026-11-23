#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 PoHW 内置题库导入到本项目的 assets/quiz/<subject>_grade_<n>.json。

- 输入：PoHW 仓库的 app/assets/quiz.db（从 https://github.com/jagerzhang/PoHW 下载）
- 输出：1–6 年级 18 份 JSON，保留原有原创题，追加 PoHW 题，按题干去重；7–9 年级不动
- 幂等：重跑会先剔除上次导入的 pohw-* 题再追加，不会重复
- 结构：采用 K12 题库工厂的统一题目 Schema（9 个必填字段），见 docs/bank-format.md

字段映射（PoHW -> K12）：

    knowledge_point      -> topic_name
    unit/term            -> topic_id 里的 s1/s2（该知识点所属上/下册）
    difficulty_level 1-3 -> difficulty 1-3（4 档留空）
    answer_index 0-3     -> answer 字母 A-D
    text/opt_a-d/explain -> stem/options/explanation
    （PoHW 没有 bloom，由 k12_schema.infer_bloom 推断，非课标标注）

topic_id 的编号规则与 tools/migrate_builtin_k12.py 完全一致：把该年级该科目下
实际写入的 (册次, 知识点) 组合排序后从 1 编号，因此两个脚本重跑结果相同。

    python3 tools/import_pohw.py --db /path/to/quiz.db
"""
import argparse
import json
import os
import re
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import k12_schema as K                    # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
QUIZ_DIR = ROOT / "app/src/main/assets/quiz"
SUBJECT_MAP = {"数学": ("math", "数学"), "语文": ("chinese", "语文"), "英语": ("english", "英语")}
PREFIX = "pohw-"


def norm(s):
    return re.sub(r"\s+", "", s or "").strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="PoHW 仓库的 app/assets/quiz.db 路径")
    args = ap.parse_args()

    with sqlite3.connect(str(Path(args.db).resolve())) as con:
        meta = dict(con.execute("SELECT k, v FROM meta").fetchall())
        rows = con.execute(
            "SELECT id, grade, subject, text, opt_a, opt_b, opt_c, opt_d, answer_index,"
            " explain, knowledge_point, difficulty_level, term "
            "FROM questions ORDER BY grade, subject, id"
        ).fetchall()
    print(f"source: {meta.get('version')} questions={len(rows)}")

    by_cell = {}
    for (qid, grade, subject, text, a, b, c, d, ans, explain,
         kp, diff, term_s) in rows:
        mapped = SUBJECT_MAP.get(subject)
        if mapped is None or not 1 <= grade <= 6:
            continue
        key, subject_cn = mapped
        by_cell.setdefault((key, subject_cn, grade), []).append({
            "qid": qid,
            "stem": text,
            "options": [a, b, c, d],
            "answer_index": ans,
            "explanation": explain or "",
            "topic_name": (kp or "").strip() or "综合练习",
            "difficulty": max(K.DIFFICULTY_MIN, min(K.DIFFICULTY_MAX, int(diff or 2))),
            "term": K.normalize_term(term_s),
        })

    grand_total = 0
    for (key, subject_cn, grade), incoming in sorted(by_cell.items()):
        path = QUIZ_DIR / f"{key}_grade_{grade}.json"
        data = json.loads(path.read_text(encoding="utf-8"))

        # 先清掉上次导入的结果，保证脚本可重复运行。
        original = [q for q in data.get("items", [])
                    if not str(q.get("id", "")).startswith(PREFIX)]
        seen = {norm(q.get("stem")) for q in original}

        kept = []
        for q in incoming:
            s = norm(q["stem"])
            if not s or s in seen:
                continue
            seen.add(s)
            kept.append(q)

        # 与迁移脚本一致的编号：按 (册次, 知识点) 排序后从 1 编号。
        topics = sorted({(q["term"], q["topic_name"]) for q in kept})
        num = {t: i + 1 for i, t in enumerate(topics)}

        stage = K.stage_of(grade)
        items = []
        for q in kept:
            items.append(K.item_from_legacy(
                subject_cn, grade,
                {
                    "id": PREFIX + q["qid"],
                    "stem": q["stem"],
                    "options": q["options"],
                    "answerIndex": q["answer_index"],
                    "explanation": q["explanation"],
                },
                topic_id=K.make_topic_id(stage, grade, q["term"], key,
                                         num[(q["term"], q["topic_name"])]),
                topic_name=q["topic_name"],
                difficulty=q["difficulty"],
                bloom=K.infer_bloom(key, q["stem"], q["difficulty"]),
            ))

        # 头部升级到 K12 结构（旧文件是 version 2 且没有 schema 标记）
        data["version"] = 3
        data["schema"] = "k12-question-bank/v1"
        data["items"] = original + items
        path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n",
                        encoding="utf-8")
        print(f"  {path.name}: base={len(original)} added={len(items)} "
              f"topics={len(topics)} total={len(data['items'])}")
        grand_total += len(items)

    print(f"IMPORT_OK added={grand_total} files={len(by_cell)}")


if __name__ == "__main__":
    main()
