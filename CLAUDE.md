# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

「答题续时」（包名 `app.quizgate.tv`）是给电视用的到点答题闸门：计时结束后弹全屏遮罩出题，答对增加时长，家长用 6 位口令结束会话。纯 Java Android 应用，**零 Gradle**，无第三方依赖，目标设备是小米电视（Android 11 / 14 实测）。

文档分工：`README.md` 是面向用户的产品说明；`docs/architecture.md` 是带证据等级的架构决策记录（先读这里再改核心逻辑）；`docs/upgrade.md` 是发布/OTA 流程；`docs/compatibility.md` 只记录真机结论；`evidence/D<n>-<日期>/` 是每次真机验证的原始取证。

## 构建与测试

需要 JDK 17、Android SDK（build-tools 35.0.0 + platform android-35）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh            # 调试包，debug 签名，输出 build/quizgate-<ver>-debug.apk
./build.sh release    # 发布包，需要 keystore/release.jks 与 keystore/release.pass
```

构建链固定为 `aapt2 → javac → d8 → zipalign → apksigner`，产物在 `build/`。

可覆盖的环境变量（都只影响本次构建，不落仓库）：

```bash
QUIZGATE_VERSION_NAME=1.3.1 QUIZGATE_VERSION_CODE=10301 ./build.sh release
QUIZGATE_UPGRADE_BASE=http://127.0.0.1:8765/ ./build.sh debug   # 真机 OTA 自测
QUIZGATE_UPGRADE_BASE= ./build.sh release                       # 完全离线，一次请求都不发
```

发布包只接受 `https` 升级源；调试包允许 `http` 并自动打开明文流量。

自动化测试只有一项（恢复码纯逻辑，不需要 Android SDK）：

```bash
./tools/test_recovery.sh
```

题库由脚本生成，**不要手改 JSON**：

```bash
python3 tools/gen_quiz.py
```

它按 `MATH` / `CHINESE` / `ENGLISH` 三份字典重写 `app/src/main/assets/quiz/<subject>_grade_<1..9>.json`（3 科 × 9 年级 × 8 题）。

发布一条新版：

```bash
./tools/publish.sh 1.3.1 "更新说明"
```

产物在 `dist/`（`quizgate-<ver>.apk` + `version.json`）。上传时**先传 APK、最后传 `version.json`**。正式发版还要同步 `README.md`、`CHANGELOG.md` 和 `docs/`（`tools/publish.sh` 结尾会提醒核对项）。

真机辅助脚本（默认电视 `192.168.1.100:5555`，可用 `TV_IP=` 覆盖）：

```bash
./tools/adb_lan_install.sh [IP]                # 局域网 adb 安装
./tools/tv_remote.sh shot|live|key ok|tap X Y|text ...|ui|find 文字|log
```

脚本里 `ADB_MDNS=0` 是必需的：主机名含中文会让 adb 的 mDNS 组件崩溃、server 自杀。注意 `tools/adb_lan_install.sh` 里的 APK 路径是硬编码的旧版本文件名，装当前包前先核对 `APK=` 那一行。

## 版本号与构建注入（改之前必须知道）

- `build.sh` 是版本的**唯一可信源**。它把 `VERSION_NAME` / `VERSION_CODE` 注入临时清单 `build/AndroidManifest.xml`；直接改 `app/src/main/AndroidManifest.xml` 的 `versionCode` 不会生效于构建参数（`aapt2 --version-code` 只在清单没有该属性时起作用）。
- `BuildCfg.UPGRADE_BASE` 也在构建时被 `build.sh` 临时改写，编译结束通过 `trap` 从备份还原。不要依赖手工修改该文件去改升级源。
- `versionCode = MAJOR*10000 + MINOR*100 + PATCH`（1.3.0 = 10300），必须单调递增。
- 正式包与调试包是两把不同的钥匙；签名不一致会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，OTA 依赖签名一致。

## 架构

### 运行主体

`MainActivity`（首页 + 设置页，遥控器全键操作）只负责配置和发起会话；真正计时和挂窗口的是 `GateService`（单进程、单个 `specialUse` 前台服务）。

状态机只有三态，持久化在 `SharedPreferences("gate")` 的 `Prefs` 里：

- `IDLE`：没有会话
- `RUNNING`：计时中，`GateService` 每 200ms tick 一次
- `LOCKED`：到点，等待答题或家长口令

关键不变量：**状态迁移用 `commit()`，tick 心跳用 `apply()`**（`Prefs.saveSnapshot` vs `tickSnapshot`）。任何「崩溃/升级后变成免费续时」的改动都不接受。

### 计时（ADR-1）

倒计时用 `SystemClock.uptimeMillis()` 增量累加，并且**只在 `phase == RUNNING && screenOn()` 时扣时**。`screenOn()` 同时检查 `PowerManager.isInteractive()` 和 `Display.getState()`。不要改回墙钟死线——改系统时间/时区/NTP 会直接绕过它。剩余时间快照落盘，进程被回收后按快照 RESTORE。

### 每日答题时段（仅 Android 11，ADR-12）

`Prefs.scheduleEnabled() / windowStartMin() / windowEndMin()` 保存一个每日时段（30 分钟精度，默认 16:30–22:00）。`Schedules` 负责当天尽力自动开始、22:00 硬结束；`ScheduleReceiver` 接当天精确闹钟；`NotifProbe` 在亮屏时补开始；`GateService.onStartCommand/ticker` 拒绝时段外启动并硬结束。不做跨天持久化：只有当天应用/通知监听/开机/亮屏时才会安排；屏幕黑着不点亮、不挂窗。`Schedules.supported()` 在 API 31+ 返回 false，设置页显示“仅 Android 11”，未新增权限。已在 MiTV-MFTR0 / Android 11 实测（D15）；屏幕熄灭后的 pending 路径和 Android 12+ 未验收。

桌面入口是 `LauncherAlias`（`activity-alias`）；`LauncherIcon.apply()` 只在 `launcherHidden && scheduleEnabled && Schedules.supported()` 时禁用 alias，`App.onCreate` 每次进程启动重新落地。关闭答题时段会清 `launcherHidden` 并恢复 alias；家长入口是遮罩菜单键 + 口令。系统设置 → 应用仍可见，不防卸载。alias 隐藏/恢复已用 debug prefs 验证（D16），UI 开关与遮罩入口待真人操作验收。

### 遮罩与按键

`GateService` 用 `WindowManager` 直接添加 `TYPE_APPLICATION_OVERLAY` 窗口：

- `MaskView`：全屏、可聚焦，自己路由方向键/OK/菜单键（Android 14 的焦点树会「假成功」，不能依赖子 View 焦点）。顶部称呼来自 `Prefs.childName()`（默认「小朋友」，设置页「小朋友称呼」可改），不要硬编码真实姓名。
- `MiniView`：右下角读秒，`NOT_FOCUSABLE | NOT_TOUCHABLE`
- 刻意不加 `FLAG_KEEP_SCREEN_ON`：实测会把待机中的电视点亮

`GateService.reconcile(phase)` 是窗口增删的唯一入口，不要用事件直接 `addView`/`removeView`。锁定态由 `keepMaskOnTop()` 在被 HOME/切应用顶掉后重新挂窗口抢焦点（HOME 键在系统层被处理，普通应用无法拦截或吞掉）。

### 服务存活

小米电视待机会 `killAllThirdApp` 强停第三方应用，`BOOT_COMPLETED` / `START_STICKY` 都可能失效。存活依赖：

- `NotifProbe`（`NotificationListenerService`）作为被系统重新绑定的锚点，约 150ms 把进程拉回来；它**不读通知内容**，只用包名判断存活
- `BootReceiver` 接 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`
- `MainActivity.autoRestore()` 在 `onResume` / `onCreate` 按快照恢复
- 屏幕黑着时只记一笔，等 `SCREEN_ON` 再起前台服务，避免把电视点亮

装机必需授权只有两条：悬浮窗 + 通知使用权。`WRITE_SECURE_SETTINGS` 是卸载留痕的可选授权。1.3.0 起电池优化白名单已取消（双机实测非必需）。

### 崩溃熔断（ADR-8）

`App` 注册进程级 `UncaughtExceptionHandler`，只有在 `App.gateAlive == true`（即守护服务在本进程里活着）时才写 `pendingCrash`。连续 3 次启动崩溃 → `safeMode`，服务 `onStartCommand` 返回 `START_NOT_STICKY` 并停止挂遮罩。**被系统强杀不计入崩溃**——否则家长按三次电源键就会把正常守护熔断。服务稳定运行 60 秒后 `markStable()` 清零计数。设置页「守护状态」提供手动恢复。

### 口令与恢复码

- 6 位家长口令：`PBKDF2WithHmacSHA256`，60,000 次迭代，16 字节随机盐，常量时间比较；5 次失败锁 30 秒
- 恢复码：7 位随机 + 1 位 Verhoeff 校验的 8 位数字，PBKDF2 存哈希；失败计数与口令独立（5 次锁 30 分钟）
- 重置时新口令与新恢复码在**同一个 `commit()`** 里生效（`Prefs.resetPinAndRecovery`），避免「旧码烧了、新口令没设上」的死锁
- 应用里没有默认口令；`allowBackup="false"`

### 题库

`QuizBank.next(subjectKey, grade, floor, r)` 按科目加载 `assets/quiz/<subject>_grade_<n>.json`，抽不到题时向低年级回退，但不突破 `floor`（初中 7–9 年级不会退到小学题）。数学最终兜底 `Core.arithmetic()`；语文/英语兜底到该科目最低年级，再不行给一道通用常识题。判题用 `answerIndex`，选项打乱后回写下标，不比较选项文本。

答对一题不会立刻延时：`MaskView` 要求连对 `Prefs.requiredCorrect()` 题（默认 2，设置页「连对题数」可调 1–10）。答错清零连对进度，按本次锁定内累计错题数冷却 5/10/20/30 秒；冷却和连对进度落在 `Prefs`，按电源键强停后仍继续。规则与取舍见 `docs/architecture.md` ADR-10。

混合模式：`Prefs.SUBJECT_MIXED`；`MaskView` 每题用 `Prefs.mixedWeights()` 独立抽科（默认语文:数学:英语 = 2:2:1），跨科连对有效。权重存 `Prefs`，暂无设置 UI；规则见 ADR-11。

### 卸载留痕

`Anchor` 把 JSON 写进 `Settings.System` / `Settings.Secure` 的 `quizgate_install_anchor` 键（应用数据之外，卸载擦不掉），只存首次安装时间、最后心跳、卸载次数、失联时长，不含任何凭据派生值。读时取心跳较新的一份；小米固件拒绝 `Settings.System` 自定义键，实际生效的是 `pm grant WRITE_SECURE_SETTINGS` 写 `Settings.Secure`。只告警、不拦截。

### 在线升级

`Updater.java` 是**唯一**的出网点，全项目只有两处 `.openConnection()`（GET `version.json`、GET APK）。请求是裸 GET，无参数、无 cookie，UA 固定为 `QuizGate`。流程：比较 `versionCode` → 下载到 cache → 校验 sha256 + 包内真实包名/versionCode + 与已装应用签名一致 → 存入 `Download/` → 用户点「立即安装」走 `ACTION_INSTALL_PACKAGE` + `content://`。检查失败一律静默当「没有更新」。1.3.0 起只连 `https://7789.cn/y/quizgate-tv/` 一个地址，没有 fallback。

## CI 必须保持的断言

`.github/workflows/build.yml` 在每次 push/PR 上做三件事，改动相关代码时必须同步：

1. **权限白名单**：APK 清单里的权限必须逐条列在 workflow 中（基础 5 + 升级 2 + 卸载留痕 2）。新增权限要连同理由一起加进白名单，否则 CI 直接红。
2. **出网点恰好两处**：`grep -rn "\.openConnection()" app/src/main/java/` 必须等于 2。数量变化会同时让 `docs/upgrade.md` 的隐私审计说明失效。
3. **DCO**：PR 的每个 commit 都必须有 `Signed-off-by:`（`git commit -s`）。

## 工作约定

- 文档、注释、提交信息都用中文。提交信息格式：`类型: 摘要`（`fix:` / `feat:` / `docs:` / `build:`）。
- 核心行为改动前先读 `docs/architecture.md` 对应 ADR，并在 PR 描述里回答三件事：**不丢时间、不静默重置、不锁死**。
- 真机结论必须有证据。`docs/compatibility.md` 的规矩是「没有取证的格子写未验证，不许推断」；同一型号不同 Android 大版本的结论不能互相套用。验证记录放 `evidence/D<n>-<日期>/`。
- 改动行为或修复故障后，同步更新 `README.md`（用户说明/故障表）、`CHANGELOG.md`，以及受影响的 `docs/`。
- 题库只改 `tools/gen_quiz.py` 后重跑生成；要求原创、四选项互不相同、`answerIndex` 正确、带 `explanation`。
- 调试日志统一 tag `QuizGate`，`adb logcat -v monotonic -s QuizGate:I` 可看全量行为日志。
