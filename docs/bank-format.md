# 题库导入格式（K12 题库工厂）

本项目自定义题库的**导入格式与数据结构**采用开源项目
[**K12 题库工厂（mtvtopcom/k12-question-bank）**](https://github.com/mtvtopcom/k12-question-bank)
的统一题目 Schema 与四种导出格式。

| 项 | 说明 |
| --- | --- |
| 上游项目 | <https://github.com/mtvtopcom/k12-question-bank> |
| 版本基准 | v1.3.5（2026-11） |
| 许可 | MIT License，© 2026 mtvtopcom |
| 采用部分 | 题目 Schema（9 个必填字段）、`topic_id` 命名规则、JSON / CSV / Excel-TSV / TXT 四种导出格式 |
| 未采用部分 | 知识点地图（`knowledge-map/`）、出题管道（`src/`）、macOS 桌面台 |

上游项目是「知识点地图 + 大模型出题提示词生成器」：先按 2022 版课标梳理 63 册、
1225 个知识点，再用模板与 LLM 批量出题，按上述四种格式导出。我们把它的**产物格式**
作为本软件的导入标准，这样用上游工具批量生成的题可以直接导进电视。

> 本仓库不包含上游的知识点地图与生成管道，只对齐格式。需要按课标知识点出题时，
> 请直接使用上游项目。

---

## 一、统一题目 Schema

每条题 9 个必填字段，与上游 `docs/question-output.schema.json` 一致：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `topic_id` | string | 来源知识点 ID，如 `primary-g3-s1-math-K018` |
| `topic_name` | string | 来源知识点名称 |
| `stem` | string | 题干，不含选项与题号 |
| `options` | string[4] | 4 个选项，顺序即 A/B/C/D，不含「A.」前缀 |
| `answer` | string | 正确答案字母，`A`/`B`/`C`/`D` |
| `explanation` | string | 解析 |
| `difficulty` | int | 1=容易 2=中等 3=较难 4=难 |
| `bloom` | string | 认知层级：记忆 / 理解 / 应用 / 分析 / 评价 / 创造 |
| `type` | string | 题型，当前固定 `single_choice` |

示例（JSON）：

```json
[
  {
    "topic_id": "primary-g3-s1-math-K018",
    "topic_name": "长方形、正方形的特征与周长",
    "stem": "一个长方形花坛长 8 米、宽 5 米，绕它走一圈是多少米？",
    "options": ["26 米", "13 米", "40 米", "21 米"],
    "answer": "A",
    "explanation": "绕一圈即周长，(8+5)×2=26 米。",
    "difficulty": 2,
    "bloom": "应用",
    "type": "single_choice"
  }
]
```

---

## 二、topic_id 命名规则

```
{学段}-g{年级}-s{册次}-{科目}-K{序号}
```

| 段 | 取值 | 示例 |
| --- | --- | --- |
| 学段 | `kg`（幼儿园）/ `primary`（小学 1–6）/ `junior`（初中 7–9） | `primary` |
| 年级 | `g1`–`g9` | `g3` |
| 册次 | `s1` 上册 / `s2` 下册 | `s1` |
| 科目 | `chinese` / `math` / `english` | `math` |
| 序号 | `K` + 三位数字，知识点在本册内的编号 | `K018` |

---

## 三、年级科目怎么确定

K12 格式本身**没有年级列和科目列**——它们从 `topic_id` 反推。考虑到家长手写表格时
填 `topic_id` 门槛较高，本项目在保持格式兼容的前提下允许补充列。解析优先级：

1. **显式字段**：`grade` / `subject`（中英文均可，如 `3` / `三年级` / `math` / `数学`）
2. **`topic_id` 反推**：从 `primary-g3-s1-math-K018` 得到小学 3 年级、数学
3. **TXT 头行**：旧的 `# 一年级 数学` 写法（兼容格式）
4. 都拿不到 → 该题报错「科目/年级无法确定」，在手机上传页列出

所以两种用法都行：

- 用上游工具生成：只带 `topic_id`，年级科目自动反推，最省事；
- 自己手写表格：只填 `grade`/`subject` + 题干选项答案，`topic_id` 留空也能导入
  （此时该题的 `topic_id` 为空，不影响抽题，只是丢了知识点归属）。

---

## 四、四种导入格式

### 1. JSON

顶层数组，元素为上面的 Schema。也兼容 `{"questions":[...]}` 与 `{"items":[...]}`
包装（本项目旧格式）。

### 2. CSV

RFC 4180，12 列，UTF-8（带不带 BOM 都行，Windows 导出的 GBK 也能自动识别），
列顺序固定：

```
topic_id,topic_name,stem,option_a,option_b,option_c,option_d,answer,explanation,difficulty,bloom,type
```

含逗号、引号、换行时按 RFC 4180 加引号。

### 3. Excel（xlsx / xls）

同一组 12 列的表头即可，列顺序不限（按表头名称识别），可以有额外列
（如 `grade`、`subject`、`term`）。上传页在**手机端**解析 Excel，电视只接收标准
JSON，避免大表格把电视拖死。

### 4. TXT

逐题分段，题间空一行，字段用 `【】` 标记：

```
第 1 题
【题干】一个长方形花坛长 8 米、宽 5 米，绕它走一圈是多少米？
【选项】A. 26 米　B. 13 米　C. 40 米　D. 21 米
【答案】A
【解析】绕一圈即周长，(8+5)×2=26 米。
【难度】中等
【知识点】长方形、正方形的特征与周长（primary-g3-s1-math-K018）
```

- `【解析】【难度】【知识点】` 可省略；`【难度】` 也认中文「容易/中等/较难/难」。
- `【知识点】` 末尾的括号里带 `topic_id` 时会被提取，年级科目随之确定。
- `【选项】` 也可写成多行（每行一个 `A. …`）。

---

## 五、幼儿园题会被跳过

上游题库覆盖幼儿园到初中，本软件只有小学 1 年级到初中 9 年级。因此：

- `topic_id` 以 `kg-` 开头，或 `grade` 写成「幼儿园…」的题，导入时**跳过并计数**；
- 手机上传页会提示「另跳过幼儿园题 N 题」，电视端导入结果里也有同一条说明；
- 不会把它们硬塞进一年级——那会出一批明显低幼的题。

---

## 六、内置题库的 topic_id 是本地编号（重要）

内置的 9731 道题（原创 216 + 自 PoHW 导入 9515）也采用上述 Schema，但
**`topic_id` 是本地编号，不能反查上游的官方知识点地图**。原因：

- 上游知识点是 1225 个课标知识点，PoHW 是 2788 个细粒度知识点，两者粒度差异很大；
- 实测按名称精确/包含匹配，题目级命中率仅 **6.4%**（样例：PoHW「100以内加减法综合
  运算技能」对不上上游任何一条「秒的认识」「两位数加两位数口算」），强行匹配会张冠李戴。

所以我们诚实处理，而不是假装对齐：

| 题库 | topic_id 来源 | difficulty | bloom |
| --- | --- | --- | --- |
| 自 PoHW 导入 9515 题 | 按该库的 `knowledge_point` + 册次排序编号 | 用 PoHW 原始 `difficulty_level`（1–3，真实值） | **推断**：数学纯算式→应用，其余按难度映射 |
| 原创 216 题 | 每份文件 1 个 topic，编号从 `K900` 起（与导入题隔开） | 按题型与年级粗分 | **推断**：按题型判断 |

`difficulty` 与 `bloom` 的具体推断规则见 `tools/k12_schema.py` 的 `infer_bloom()`
与 `tools/original_topics.py` 的注释。它们是工程近似值，**不是课标原始标注**。

自定义题库若直接使用上游工具生成的 `topic_id`，则天然可反查，不受此限制。

---

## 七、与旧格式的兼容

1.4.0 之前的导入格式仍然可用，无需改写历史文件：

| 旧写法 | 现在是否支持 |
| --- | --- |
| 表头「年级、科目、题干、A、B、C、D、答案、解析」 | 支持 |
| `{"questions":[{subject,grade,stem,options,answerIndex,explanation}]}` | 支持 |
| TXT 以 `# 一年级 数学` 开头、`答案 B`、`解析 …` | 支持 |
| 答案写成数字 0–3 | 支持（按选项下标处理） |

新导入一律按 K12 结构落盘（`answer` 存字母），旧数据读入时自动兼容。

---

## 八、文件在哪、怎么改

| 用途 | 路径 |
| --- | --- |
| 格式定义与校验 | `tools/k12_schema.py` |
| 内置题库迁移 | `tools/migrate_builtin_k12.py --db <PoHW quiz.db>` |
| 原创题生成 | `tools/gen_quiz.py`（主题元数据在 `tools/original_topics.py`） |
| PoHW 导入 | `tools/import_pohw.py --db <PoHW quiz.db>` |
| 门禁校验 | `tools/check_quizbank.py`（CI 也跑） |
| 手机上传页解析 | `app/src/main/assets/bank/upload.html` |

改完内置题库后跑一遍门禁：

```bash
python3 tools/check_quizbank.py
```

---

## 九、致谢

上游项目提供了清晰的 Schema 与文档，本项目的导入格式直接沿用其定义：

- K12 题库工厂：<https://github.com/mtvtopcom/k12-question-bank>（MIT）
- 格式文档：<https://github.com/mtvtopcom/k12-question-bank/blob/main/docs/OUTPUT_FORMATS.md>
- JSON Schema：<https://github.com/mtvtopcom/k12-question-bank/blob/main/docs/question-output.schema.json>

按 MIT 协议保留其版权声明。本项目的代码与题库内容的许可见
[LICENSE](../LICENSE) 与 [LICENSE-CONTENT](../LICENSE-CONTENT)。
