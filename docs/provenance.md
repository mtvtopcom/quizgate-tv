# 素材来源台账

原则：每一项第三方素材都要能追溯到来源与许可；能不用就不用。

## 题库

| 项 | 来源 | 许可 | 录入日期 |
| --- | --- | --- | --- |
| `assets/quiz/grade_1..9.json`（72 题） | 本项目原创撰写 | CC BY-SA 4.0 | 2026-11-10 |

- 不含教材原文、教辅扫描、来源不明的网络题目。
- 由 `tools/gen_quiz.py` 生成，脚本内 `DATA` 为唯一真源。
- 口算类题目由 `Core.arithmetic()` 运行时生成，不占题库。

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

**零第三方运行时依赖**（只用 Android Framework 自带 API）。

构建期仅需：JDK 17、Android SDK build-tools 35、platform 35。均不含 GPL 组件。

## 参考项目

设计与思路参考了以下开源项目，**未复制任何代码**：

| 项目 | 许可 | 借鉴了什么 |
| --- | --- | --- |
| [PoHW](https://github.com/jagerzhang/PoHW) | MIT | 策略层与 Android 层分离、悬浮窗焦点恢复、诚实的边界声明 |
| [txhlock](https://github.com/txhtxh11/txhlock) | 无 LICENSE | 仅读思路（悬浮层优先、无障碍真实绑定检测），**不可复用实现** |
| [kidslock](https://github.com/Siumingkm/kidslock) | 无 LICENSE | 仅读产品闭环，**不可复用实现** |
| [GrowQuizLauncher](https://github.com/1zzzzzlll/GrowQuizLauncher) | GPL-3.0 | 仅读思路，**不可复用实现**（GPL 传染） |
| [aerial-tv](https://github.com/pengchujin/aerial-tv) | MIT | 「强停后靠通知监听把进程拉回来」这一条存活思路 |

> 无 LICENSE 的项目默认「保留所有权利」，本项目只借鉴思路，不复制代码。
