# 素材来源台账

原则：每一项第三方素材都要能追溯到来源与许可；能不用就不用。

## 题库

题库是同一套 27 份 JSON，但题来自两个来源，许可不同，不要混为一谈：

| 项 | 范围 | 来源 | 许可 | 录入日期 |
| --- | --- | --- | --- | --- |
| id 以 `pohw-` 开头的 9515 题 | 小学 1–6 年级，追加在原有题之后 | [PoHW](https://github.com/jagerzhang/PoHW) 随 APK 发布的题库，源版本 `pohw-2026.11.10-bank-v9` | **不随 MIT / Apache / CC BY-SA 授权**，权利归各自权利人 | 2026-11-20 |
| 其余 216 题（每科每年 8 题） | 1–9 年级，7–9 年级只有这部分 | 本项目原创撰写 | CC BY-SA 4.0 | 2026-11-10 |

- 导入题部分整理自互联网公开渠道，仅供个人非商业家庭学习；如涉及版权或其他权益问题，
  请提 issue，核实后会删除或替换。[LICENSE-CONTENT](../LICENSE-CONTENT) 保留了同一声明。
- 转换脚本 `tools/import_pohw.py` 把 PoHW 的 `quiz.db` 转成本项目的 K12 结构
  （详见下节），按题干去重、可重复运行；`tools/gen_quiz.py` 重跑时会保留 `pohw-` 题，
  不会把它们打回 8 道。
- 导入题带教材年级语义，PoHW 英语为三年级起点；本项目原有 1/2/6 年级英语题仍在，
  所以设置页仍可选英语，不做年级禁用。
- 口算题由 `Core.arithmetic()` 运行时生成，不占题库。
- 全部 27 份 JSON 的完整性由 `tools/check_quizbank.py` 校验
  （题量、K12 九字段、answer 字母、difficulty、bloom、topic_id 格式）。

### 题库格式（K12 题库工厂）

- **来源**：[K12 题库工厂](https://github.com/mtvtopcom/k12-question-bank)
  （`mtvtopcom/k12-question-bank`），版本基准 v1.3.5，录入日期 2026-11-23。
- **许可**：MIT License，© 2026 mtvtopcom。按 MIT 协议保留版权声明。
- **采用的**：统一题目 Schema（9 个必填字段）、`topic_id` 命名规则、
  JSON / CSV / Excel-TSV / TXT 四种导出格式定义。完整说明见
  [docs/bank-format.md](bank-format.md)。
- **未采用的**：上游的知识点地图 `knowledge-map/`（63 册 / 1225 个知识点）、
  出题管道 `src/`、macOS 桌面台。本仓库不含其任何数据文件或代码。
- **内置题库的 topic_id 是本地编号**：实测 PoHW 的 2788 个细粒度知识点与上游 1225 个
  课标知识点的名称匹配率仅 6.4%，强行匹配会张冠李戴，因此内置题的 `topic_id` 按本地
  编号生成（格式与规范一致，但不能反查上游官方知识点地图）。`difficulty` 对 PoHW 题
  用其原始 `difficulty_level`，`bloom` 为推断值。详见 [docs/bank-format.md](bank-format.md)
  第六节。

## 字体

**未打包任何字体**，全部使用系统字体。

理由：题干以汉字为主，拼音声调只在极少数题目里出现；为一个边缘场景引入字体文件 +
许可文件 + 子集化流程，属于第一版的过度设计。若将来需要，计划使用
Noto Sans CJK（SIL OFL 1.1）或 DejaVu 子集（Bitstream Vera 许可），届时在此登记。

## 图标

| 项 | 来源 | 许可 |
| --- | --- | --- |
| `res/drawable/ic_launcher.xml` | 本项目自绘 VectorDrawable | Apache-2.0 |
| `res/drawable/banner.xml` | 本项目自绘 | Apache-2.0 |
| 其余背景/边框（`opt_bg`、`key_bg`、`panel_bg`） | 本项目自绘 shape | Apache-2.0 |

未使用任何第三方图标库。

## 音效

无。v1.0.0 不使用任何音频素材。

## 截图

`docs/img/*.png` 为本项目在自有设备上拍摄的实机截图，内容仅为本应用界面。
未包含影视画面、频道内容或第三方应用界面。

## 依赖

**第三方运行时依赖**（全部 Apache-2.0，不含 GPL）：

| 组件 | 版本 | 用途 | 位置 |
| --- | --- | --- | --- |
| [ZXing core](https://github.com/zxing/zxing) | 3.5.3 | 电视端生成二维码 | `libs/core-3.5.3.jar` |
| [SheetJS Community Edition](https://sheetjs.com/) | 0.20.3 | 手机上传页解析 xlsx / xls / csv | `app/src/main/assets/bank/xlsx.full.min.js` |

两者以未修改的发行文件形式引入，行为与上游一致。

构建期仅需：JDK 17、Android SDK build-tools 35、platform 35。均不含 GPL 组件。

## 参考项目

设计与思路参考了以下开源项目，**未复制任何代码**：

| 项目 | 许可 | 借鉴了什么 |
| --- | --- | --- |
| [PoHW](https://github.com/jagerzhang/PoHW) | 代码 MIT；题库内容不随 MIT 授权 | 策略层与 Android 层分离、悬浮窗焦点恢复、诚实的边界声明；自 2026-11-20 起还引入其随 APK 发布的题库数据（见上表） |
| [txhlock](https://github.com/txhtxh11/txhlock) | 无 LICENSE | 仅读思路（悬浮层优先、无障碍真实绑定检测），**不可复用实现** |
| [kidslock](https://github.com/Siumingkm/kidslock) | 无 LICENSE | 仅读产品闭环，**不可复用实现** |
| [GrowQuizLauncher](https://github.com/1zzzzzlll/GrowQuizLauncher) | GPL-3.0 | 仅读思路，**不可复用实现**（GPL 传染） |
| [aerial-tv](https://github.com/pengchujin/aerial-tv) | MIT | 「强停后靠通知监听把进程拉回来」这一条存活思路 |
| [知题 Zhiti](https://github.com/xxInovxx/Zhiti) | Apache-2.0 | 用户题库表格的表头设计：按名称识别、列顺序不限；未复制代码 |
| [K12 题库工厂](https://github.com/mtvtopcom/k12-question-bank) | MIT | 自定义题库的导入格式与题目 Schema（9 字段 / topic_id 规则 / JSON·CSV·TSV·TXT 四格式），见上方「题库格式」 |

> 无 LICENSE 的项目默认「保留所有权利」，本项目只借鉴思路，不复制代码。
