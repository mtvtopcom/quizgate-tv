#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成内置题库的题量索引 app/src/main/assets/quiz/counts.json。

电视端要在「题库管理」「题目年级」里显示语数英各有多少题。运行时把 27 份
题库（5.5MB）全解析一遍太慢，构建期生成一份 1KB 的索引，客户端只读它。

题库内容变了就重新跑一次；tools/check_quizbank.py 会核对索引与真实文件一致，
忘了重跑会让 CI 红，不会静默显示旧题数。

    python3 tools/gen_bank_counts.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
QUIZ_DIR = ROOT / "app/src/main/assets/quiz"
OUT = QUIZ_DIR / "counts.json"
SUBJECTS = ("chinese", "math", "english")


def main():
    counts = {}
    for key in SUBJECTS:
        row = [0] * 10          # 下标 = 年级 1–9，0 号位留空
        for grade in range(1, 10):
            p = QUIZ_DIR / f"{key}_grade_{grade}.json"
            if not p.exists():
                raise SystemExit(f"缺少题库文件: {p}")
            row[grade] = len(json.loads(p.read_text(encoding="utf-8"))["items"])
        counts[key] = row

    doc = {
        "version": 1,
        "generatedBy": "tools/gen_bank_counts.py",
        "counts": counts,
    }
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    total = sum(sum(v) for v in counts.values())
    print(f"写入 {OUT.relative_to(ROOT)}：总计 {total} 题")
    for key in SUBJECTS:
        print(f"  {key}: {sum(counts[key])} 题")


if __name__ == "__main__":
    main()
