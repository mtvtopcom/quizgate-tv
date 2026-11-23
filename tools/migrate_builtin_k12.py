#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把内置 27 份题库从旧结构（answerIndex）迁移到 K12 结构（answer 字母）。

用法：

    # 预览，不写文件
    python3 tools/migrate_builtin_k12.py --db /path/to/pohw-quiz.db --dry-run

    # 正式迁移
    python3 tools/migrate_builtin_k12.py --db /path/to/pohw-quiz.db

数据来源：

- **原创 216 题**（id 形如 m3-001）：主题元数据来自 tools/original_topics.py，
  每份文件共用 1 个 topic，编号从 900 起。
- **PoHW 导入 9515 题**（id 以 pohw- 开头）：从 PoHW 的 quiz.db 按
  「题干 + 年级 + 科目」取回 knowledge_point / difficulty_level / term，
  topic 按知识点去重编号（1..899），difficulty 用原始 difficulty_level（1–3）。
  bloom 由 k12_schema.infer_bloom 推断（PoHW 没有该字段）。

幂等：已含 topic_id 的文件整个跳过，可反复运行。

注意：PoHW 的题库不随本仓库分发，--db 指向本地下载的 quiz.db；缺失时脚本
会拒绝运行（否则 PoHW 题的 topic 会全部退化成「综合练习」，丢掉知识点信息）。
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import k12_schema as K                      # noqa: E402
from original_topics import ORIGINAL_TOPIC_INDEX, topic_for   # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUIZ = os.path.join(ROOT, "app", "src", "main", "assets", "quiz")

SUBJECTS = [("chinese", "语文"), ("math", "数学"), ("english", "英语")]

SCHEMA_TAG = "k12-question-bank/v1"
FILE_VERSION = 3


def load_pohw_meta(db_path):
    """(grade, subject_cn, stem) -> (knowledge_point, difficulty_level, term)。"""
    con = sqlite3.connect(db_path)
    idx = {}
    dups = 0
    for grade, subject, text, kp, diff, term in con.execute(
            "SELECT grade, subject, text, knowledge_point, difficulty_level, term"
            " FROM questions"):
        key = (int(grade), subject, text)
        if key in idx:
            if idx[key][0] != kp:
                dups += 1
            continue
        idx[key] = (kp or "", int(diff or 2), term or "上")
    con.close()
    return idx, dups


def migrate_file(path, subject_en, subject_cn, grade, meta, dry_run, stats):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    items = doc.get("items", [])
    if not items:
        return doc

    if all("topic_id" in q for q in items):
        stats["skipped_files"] += 1
        return None

    stage = K.stage_of(grade)
    if stage is None:
        raise SystemExit("年级 %d 超出 1–9 范围：%s" % (grade, path))

    # 1) 第一遍：为 PoHW 题收集知识点并分配编号
    topics = {}
    missing = 0
    for q in items:
        if not str(q.get("id", "")).startswith("pohw-"):
            continue
        m = meta.get((grade, subject_cn, q.get("stem", "")))
        if not m:
            missing += 1
            continue
        kp, diff, term_s = m
        key = (K.normalize_term(term_s), kp)
        topics.setdefault(key, None)

    num = {key: i + 1 for i, key in enumerate(sorted(topics))}

    # 2) 第二遍：生成 K12 item
    out = []
    for q in items:
        is_pohw = str(q.get("id", "")).startswith("pohw-")
        if is_pohw:
            m = meta.get((grade, subject_cn, q.get("stem", "")))
            if m:
                kp, diff, term_s = m
                term = K.normalize_term(term_s)
                tid = K.make_topic_id(stage, grade, term, subject_en, num[(term, kp)])
                tname = kp or "综合练习"
                difficulty = max(K.DIFFICULTY_MIN, min(K.DIFFICULTY_MAX, int(diff)))
            else:
                tid = K.make_topic_id(stage, grade, 1, subject_en, 899)
                tname = "综合练习"
                difficulty = 2
            bloom = K.infer_bloom(subject_en, q.get("stem", ""), difficulty)
            stats["pohw"] += 1
            if missing:
                stats["pohw_missing_meta"] = missing
        else:
            tname, difficulty, bloom = topic_for(subject_en, grade)
            tid = K.make_topic_id(stage, grade, 1, subject_en, ORIGINAL_TOPIC_INDEX)
            stats["original"] += 1

        out.append(K.item_from_legacy(
            subject_cn, grade, q, tid, tname, difficulty, bloom))

    new_doc = {
        "version": FILE_VERSION,
        "schema": SCHEMA_TAG,
        "subject": subject_cn,
        "grade": grade,
        "items": out,
    }

    # 3) 自检：逐题过一遍 K12 校验，有问题就中止，避免写出坏数据
    errs = []
    for i, it in enumerate(out):
        errs.extend(K.check_item(it, "%s 第 %d 题" % (os.path.basename(path), i + 1)))
    if errs:
        for e in errs[:10]:
            print("  ERR", e)
        raise SystemExit("迁移中止：%s 有 %d 处不合规" % (os.path.basename(path), len(errs)))

    stats["files"] += 1
    stats["items"] += len(out)
    stats["topics"] += len(topics) + 1

    if not dry_run:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(new_doc, f, ensure_ascii=False, indent=1)
            f.write("\n")
        os.replace(tmp, path)
    return new_doc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="PoHW quiz.db 路径（用于取知识点元数据）")
    ap.add_argument("--dry-run", action="store_true", help="只预览，不写文件")
    args = ap.parse_args()

    if not os.path.isfile(args.db):
        raise SystemExit(
            "找不到 PoHW 题库：%s\n"
            "该库不随本仓库分发，请先取得 quiz.db 再用 --db 指定。\n"
            "缺少它会丢失知识点信息，脚本拒绝继续。" % args.db)

    meta, dups = load_pohw_meta(args.db)
    print("PoHW 元数据索引 %d 条（同题干跨知识点的冲突 %d 条，取首条）" % (len(meta), dups))

    stats = {"files": 0, "items": 0, "topics": 0, "original": 0, "pohw": 0,
             "pohw_missing_meta": 0, "skipped_files": 0}

    for subject_en, subject_cn in SUBJECTS:
        for grade in range(1, 10):
            path = os.path.join(QUIZ, "%s_grade_%d.json" % (subject_en, grade))
            if not os.path.isfile(path):
                print("  跳过不存在的文件", os.path.basename(path))
                continue
            migrate_file(path, subject_en, subject_cn, grade, meta,
                         args.dry_run, stats)
            print("  完成 %s_grade_%d.json" % (subject_en, grade))

    print("\n%s：文件 %d，题 %d（原创 %d + PoHW %d），知识点 %d，缺元数据 %d"
          % ("预览" if args.dry_run else "已写入",
             stats["files"], stats["items"], stats["original"], stats["pohw"],
             stats["topics"], stats["pohw_missing_meta"]))
    if stats["skipped_files"]:
        print("已迁移过而跳过的文件：%d" % stats["skipped_files"])


if __name__ == "__main__":
    main()
