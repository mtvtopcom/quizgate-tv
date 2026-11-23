#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K12 题库格式：字段规范、topic_id 规则、映射与校验。

格式与数据结构采用开源项目 **K12 题库工厂**：
    https://github.com/mtvtopcom/k12-question-bank  （MIT License, © 2026 mtvtopcom）

该项目的统一题目 Schema 为 9 个必填字段：

    topic_id    来源知识点 ID，形如 primary-g3-s1-math-K018
    topic_name  来源知识点名称
    stem        题干，不含选项与题号
    options     4 个选项，顺序即 A/B/C/D，不含「A.」前缀
    answer      正确答案字母，A/B/C/D
    explanation 解析
    difficulty  1=容易 2=中等 3=较难 4=难
    bloom       认知层级：记忆/理解/应用/分析/评价/创造
    type        题型，当前固定 single_choice

导出格式（JSON 数组 / CSV 12 列 / Excel-TSV / TXT【】分段）与该项目的
docs/OUTPUT_FORMATS.md 保持一致，详见本项目 docs/bank-format.md。

本模块只做「格式层」的事：常量、topic_id 编解码、字段归一化、单题校验。
不依赖第三方库，供 tools/ 下的脚本与 CI 校验共用。

**关于 topic_id 的诚实声明**：内置题库（原创 216 题 + 自 PoHW 导入的 9515 题）
的 topic_id 是本地按知识点名称编号生成的，格式与 K12 规范一致，但不保证能
反查 K12 题库工厂的官方 knowledge-map —— 实测两者知识点粒度差异过大（PoHW
2788 个细粒度知识点 vs 官方 1225 个课标知识点，名称精确/包含匹配命中率仅
6.4%），强行匹配会张冠李戴。自定义题库若直接使用官方 topic_id，则天然可反查。
"""
from __future__ import annotations

import re

# ---------------- 枚举与映射 ----------------

BLOOM_LEVELS = ["记忆", "理解", "应用", "分析", "评价", "创造"]

# 允许的题型。K12 规范当前固定 single_choice；多选暂不入库。
SUPPORTED_TYPES = {"single_choice"}

DIFFICULTY_MIN, DIFFICULTY_MAX = 1, 4

SUBJECT_EN = {"语文": "chinese", "数学": "math", "英语": "english"}
SUBJECT_CN = {"chinese": "语文", "math": "数学", "english": "英语"}

# 学段前缀（与 K12 knowledge-map/volumes 的目录一致）
STAGE_KG = "kg"
STAGE_PRIMARY = "primary"
STAGE_JUNIOR = "junior"

REQUIRED_FIELDS = [
    "topic_id", "topic_name", "stem", "options",
    "answer", "explanation", "difficulty", "bloom", "type",
]

# 年级 -> 学段。本软件只有 1–9 年级，幼儿园（kg）不在支持范围。
def stage_of(grade: int):
    if 1 <= grade <= 6:
        return STAGE_PRIMARY
    if 7 <= grade <= 9:
        return STAGE_JUNIOR
    return None


# ---------------- 归一化 ----------------

_GRADE_CN = "一二三四五六七八九"


def normalize_subject(value) -> str:
    """任意写法 -> chinese / math / english；无法识别返回空串。"""
    if value is None:
        return ""
    s = str(value).strip().lower()
    if not s:
        return ""
    if s in SUBJECT_CN:
        return s
    if s in SUBJECT_EN:
        return SUBJECT_EN[s]
    # 允许 subject_group 之类的复合值，取包含的科目名
    for cn, en in SUBJECT_EN.items():
        if cn in s:
            return en
    for en in SUBJECT_CN:
        if en in s:
            return en
    return ""


def normalize_grade(value):
    """任意写法 -> 1..9 的整数；无法识别或幼儿园返回 None。

    支持：3 / "3" / "三年级" / "三" / "g3" / "grade3" / "三年级上"
    幼儿园（"幼儿园小班" / "kg-..." / 0）一律返回 None —— 本软件只有 1–9 年级，
    调用方应把它计入「跳过」而不是硬塞进一年级。
    """
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        g = int(value)
        return g if 1 <= g <= 9 else None
    s = str(value).strip()
    if not s:
        return None
    low = s.lower()
    if "幼儿园" in s or low.startswith("kg") or low.startswith("kindergarten"):
        return None
    m = re.search(r"(?:grade|g)\s*([1-9])", low)
    if m:
        return int(m.group(1))
    m = re.search(r"([1-9])\s*年级", s)
    if m:
        return int(m.group(1))
    if len(s) == 1 and s in _GRADE_CN:
        return _GRADE_CN.index(s) + 1
    m = re.fullmatch(r"[1-9]", s)
    if m:
        return int(s)
    return None


def normalize_term(value) -> int:
    """任意写法 -> 1（上册）/ 2（下册）；无法识别默认 1。

    支持："上" / "上册" / "第一学期" / "s1" / 1 / "下" / "s2" / 2
    """
    if value is None:
        return 1
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return 2 if int(value) == 2 else 1
    s = str(value).strip().lower()
    if not s:
        return 1
    if s in {"2", "s2", "下", "下册", "第二学期", "下学期"}:
        return 2
    if "下" in s:
        return 2
    return 1


def normalize_answer(value):
    """任意写法 -> "A".."D"；无法识别返回空串。

    接受：A / b / "A." / 1 / 0 / "答案A"
    """
    if value is None:
        return ""
    if isinstance(value, bool):
        return ""
    if isinstance(value, (int, float)):
        i = int(value)
        # 0..3 视为下标，1..4 视为选项序号
        if 0 <= i <= 3:
            return "ABCD"[i]
        if 1 <= i <= 4:
            return "ABCD"[i - 1]
        return ""
    s = str(value).strip().upper()
    if not s:
        return ""
    m = re.search(r"[ABCD]", s)
    if m:
        return m.group(0)
    if s.isdigit():
        return normalize_answer(int(s))
    return ""


def answer_index(letter) -> int:
    """A-D -> 0-3；非法返回 -1。"""
    a = normalize_answer(letter)
    return "ABCD".index(a) if a else -1


def answer_letter(index) -> str:
    try:
        i = int(index)
    except Exception:
        return ""
    return "ABCD"[i] if 0 <= i <= 3 else ""


# ---------------- topic_id 编解码 ----------------

_TOPIC_RE = re.compile(
    r"^(kg|primary|junior)-g([1-9])-s([12])-(chinese|math|english)-K(\d{1,4})$",
    re.IGNORECASE,
)


def make_topic_id(stage: str, grade: int, term: int, subject_en: str, index: int) -> str:
    """拼出规范 topic_id，如 primary-g3-s1-math-K018。"""
    return "%s-g%d-s%d-%s-K%03d" % (stage, int(grade), normalize_term(term),
                                    subject_en, int(index))


def parse_topic_id(topic_id):
    """解析 topic_id。返回 dict(stage,grade,term,subject,index)，失败返回 None。

    幼儿园前缀 kg- 也返回 stage='kg'、grade=None，由调用方决定跳过。
    """
    if not topic_id:
        return None
    s = str(topic_id).strip()
    low = s.lower()
    if low.startswith("kg-") or low.startswith("kindergarten"):
        return {"stage": STAGE_KG, "grade": None, "term": 1, "subject": "", "index": 0}
    m = _TOPIC_RE.match(s)
    if not m:
        return None
    return {
        "stage": m.group(1).lower(),
        "grade": int(m.group(2)),
        "term": int(m.group(3)),
        "subject": m.group(4).lower(),
        "index": int(m.group(5)),
    }


def resolve_grade_subject(item):
    """从一条题里确定 (grade, subject, term)。

    优先级（与 README 的导入说明一致）：
      1. 显式的 grade / subject 字段（用户补充列）
      2. topic_id 反推

    返回 (grade:int|None, subject:str, term:int, kindergarten:bool)。
    grade 为 None 且 kindergarten=True 表示该题属于幼儿园，应跳过。
    """
    grade = normalize_grade(item.get("grade") if isinstance(item, dict) else None)
    subject = normalize_subject(item.get("subject") if isinstance(item, dict) else None)
    term = normalize_term(item.get("term") if isinstance(item, dict) else None)

    parsed = parse_topic_id(item.get("topic_id") if isinstance(item, dict) else None)
    if parsed:
        if parsed["stage"] == STAGE_KG:
            return None, subject or "", term, True
        if grade is None:
            grade = parsed["grade"]
        if not subject:
            subject = parsed["subject"]
        if isinstance(item, dict) and not item.get("term"):
            term = parsed["term"]
    return grade, subject, term, False


# ---------------- bloom 推断 ----------------

_ARITH_RE = re.compile(r"\d\s*[+\-×÷*/]\s*\d")


def infer_bloom(subject_en: str, stem: str, difficulty: int) -> str:
    """给没有 bloom 标注的题推断一个认知层级。

    **这是推断值，不是课标原始标注**，docs/bank-format.md 里已如实说明。
    规则：数学纯算式题记为「应用」（运用计算规则）；其余按难度粗映射
    1→记忆 / 2→理解 / 3→应用 / 4→分析。
    """
    if subject_en == "math" and _ARITH_RE.search(str(stem or "")):
        return "应用"
    return {1: "记忆", 2: "理解", 3: "应用", 4: "分析"}.get(int(difficulty or 2), "理解")


# ---------------- 校验 ----------------

def check_item(item, where: str = ""):
    """校验一条题是否符合 K12 规范，返回错误信息列表（空列表 = 通过）。"""
    errs = []
    pre = (where + "：") if where else ""
    if not isinstance(item, dict):
        return [pre + "题目不是对象"]

    for f in REQUIRED_FIELDS:
        if f not in item or item[f] in (None, ""):
            errs.append(pre + "缺少字段 " + f)

    tid = item.get("topic_id")
    if tid:
        parsed = parse_topic_id(tid)
        if parsed is None:
            errs.append(pre + "topic_id 格式不合法：" + str(tid))
        elif parsed["stage"] == STAGE_KG:
            errs.append(pre + "幼儿园题不应入库：" + str(tid))

    stem = str(item.get("stem") or "").strip()
    if stem and len(stem) < 4:
        errs.append(pre + "题干过短")

    opts = item.get("options")
    if not isinstance(opts, list) or len(opts) != 4:
        errs.append(pre + "options 必须是 4 个元素的数组")
    else:
        for i, o in enumerate(opts):
            if not str(o).strip():
                errs.append(pre + "第 %s 个选项为空" % "ABCD"[i])

    ans = item.get("answer")
    if ans and not normalize_answer(ans):
        errs.append(pre + "answer 必须是 A/B/C/D：" + str(ans))

    d = item.get("difficulty")
    try:
        di = int(d)
        if not (DIFFICULTY_MIN <= di <= DIFFICULTY_MAX):
            errs.append(pre + "difficulty 必须在 1–4：%s" % d)
    except Exception:
        errs.append(pre + "difficulty 必须是整数 1–4：%s" % d)

    if item.get("bloom") and item.get("bloom") not in BLOOM_LEVELS:
        errs.append(pre + "bloom 不在允许值内：" + str(item.get("bloom")))

    if item.get("type") and item.get("type") not in SUPPORTED_TYPES:
        errs.append(pre + "不支持的题型：" + str(item.get("type")))

    return errs


# ---------------- 与旧结构的互转 ----------------

def item_from_legacy(subject_cn: str, grade: int, raw: dict,
                     topic_id: str, topic_name: str,
                     difficulty: int, bloom: str, keep_id: bool = True) -> dict:
    """把旧结构（answerIndex）转成 K12 结构（answer 字母）。"""
    subject_en = SUBJECT_EN.get(subject_cn) or normalize_subject(subject_cn)
    item = {
        "topic_id": topic_id,
        "topic_name": topic_name,
        "stem": str(raw.get("stem") or "").strip(),
        "options": [str(x).strip() for x in (raw.get("options") or [])][:4],
        "answer": answer_letter(raw.get("answerIndex", 0)),
        "explanation": str(raw.get("explanation") or "").strip(),
        "difficulty": int(difficulty),
        "bloom": bloom,
        "type": "single_choice",
    }
    if keep_id and raw.get("id"):
        # 可选扩展字段，不属于 K12 规范，用于去重与迁移追溯
        item["id"] = str(raw["id"])
    return item


def item_to_legacy(item: dict, subject_cn: str, grade: int) -> dict:
    """K12 结构 -> 旧结构，供只认 answerIndex 的旧代码/旧数据读取。"""
    out = {
        "subject": subject_cn,
        "grade": int(grade),
        "stem": item.get("stem", ""),
        "options": list(item.get("options") or [])[:4],
        "answerIndex": max(0, answer_index(item.get("answer"))),
        "explanation": item.get("explanation", ""),
    }
    if item.get("id"):
        out["id"] = item["id"]
    return out
