# 贡献指南

单人维护的项目，规则尽量少，但下面几条是硬的。

## 提 issue 之前

1. 先看 [README 的「常见故障」](../README.md#常见故障) 和 [兼容性矩阵](compatibility.md)
2. 确认你的机型在矩阵里是**已验证**还是**未验证**——未验证机型上的问题同样欢迎，但请标注
3. 用 issue 表单提交（不是空白 issue）。表单会强制你填机型、系统版本、桌面、复现步骤

必带的日志：

```bash
adb logcat -v monotonic -s QuizGate:I > gate.txt
adb logcat -d -s AndroidRuntime:E > crash.txt
adb shell dumpsys window windows > win.txt
```

## 提 PR

- 一个 PR 只做一件事
- 提交信息用 `类型: 摘要`（`fix:` / `feat:` / `docs:` / `build:`）
- 每个 commit 必须带 DCO 签名：

```bash
git commit -s -m "fix: 遮罩失焦后按键无响应"
```

CI 会检查 `Signed-off-by:`。**本项目不使用 CLA**——你保留版权，只是声明你有权提交这段代码。

- 改动涉及以上三项时，请在 PR 描述里说明：
  1. 不丢时间（快照不会被静默重置）
  2. 不静默重置（升级/崩溃不能变成免费续时）
  3. 不锁死（任何状态都要有出口）

## 构建

不需要 Gradle，也不需要联网：

```bash
export ANDROID_HOME=/path/to/android-sdk   # build-tools 35 + platform 35
./build.sh            # 调试包
./build.sh release    # 发布包（需要 keystore/release.jks）
```

## 提交题库

题库在 `app/src/main/assets/quiz/grade_<1..9>.json`，由 `tools/gen_quiz.py` 生成。
**不要直接改 JSON**，改脚本里的 `DATA` 后重跑：

```bash
python3 tools/gen_quiz.py
```

要求：

- 原创，不摘抄教材/教辅
- 四个选项互不相同，`answerIndex` 正确
- 题干低年级 ≤40 字、高年级 ≤60 字
- 有 `explanation`

## 响应预期

**best-effort，通常 7 天内回复，不承诺 SLA。**
不接受功能排期承诺；不接受商业支持请求。

## 分支与合并

- `main` 受保护：必须走 PR、必须 CI 通过、禁止 force push
- 单人维护，所以**不要求 approval**（自己无法批准自己），但 CI 必须绿
- 紧急修复允许 admin bypass，但必须在 CHANGELOG 记一行
