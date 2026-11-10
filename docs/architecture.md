# 架构决策记录

每条决策都标了证据等级：`[实测]` 在本项目真机复现过；`[AOSP 源码]` 可在指定符号上读到；
`[推断]` 由源码或同类实现推导，未经本机验证。

## ADR-1 计时基准：`uptimeMillis` 增量累加 + 屏幕门控

**决策**：倒计时用 `SystemClock.uptimeMillis()` 的增量逐帧累加，且只在
`phase == RUNNING && screenOn()` 时累加。快照存 `(remainingMs, snapshotElapsed, bootCount)`。

**为什么不是墙钟死线**：关机三小时再开机就会立刻被锁，与「孩子没在看就不该走」冲突；
而且改系统时间即可逃逸。用 uptime 后，改时间/时区/NTP 全部无效——**逃逸路径在设计上被消解**。

**关键实测（反直觉）**：原本以为 `uptimeMillis` 不计深睡，所以「熄屏暂停」是白送的。
实测 MiTV-MFTR0 熄屏 88.7 秒，`du=88697 / de=88697`，**uptime 一秒没停**——该机型熄屏不进深睡。
所以**暂停完全由 `isInteractive()` + `Display.getState()` 门控承担**，门控是承重墙不是保险。
[实测]

**被否决**：墙钟死线（体验错）、`elapsedRealtime` 差值（含深睡，待机也扣时）、
`AlarmManager` 驱动（精确闹钟默认被拒，Doze 下延迟分钟级）、WorkManager（周期下限 15 分钟）。

## ADR-2 锁屏载体：服务自持全屏悬浮窗

**决策**：`TYPE_APPLICATION_OVERLAY` 全屏窗口，由前台服务直接 `addView`。不接管桌面、不用
`startActivity`、不用 Device Owner。

**为什么**：接管桌面在小米上不可持久（每次开机 HOME 角色会被夺回）；`startActivity` 受
Android 10+ 后台启动限制且会先闪一下桌面。

**实测**：按 HOME 后遮罩不消失且**保持焦点**（`mCurrentFocus` 仍指向遮罩），比预期更好。
[实测]

**被否决**：替换桌面（`CATEGORY_HOME`）、Activity 拉起、Device Owner + LockTask、纯无障碍覆盖。

## ADR-3 窗口类型与标志

**决策**：遮罩 `LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS | DIM_BEHIND`，可聚焦，自绘不透明背景；
读秒小窗 `NOT_FOCUSABLE | NOT_TOUCHABLE`。

**刻意不加 `FLAG_KEEP_SCREEN_ON`**：实测发现加窗会把处于待机的电视点亮，表现为「电视自己开机」。
[实测]

**层序事实**：application overlay 高于所有 activity，但低于状态栏、输入法、系统对话框。
所以系统对话框能短暂盖住遮罩，这是必然，只能靠焦点恢复压缩窗口期。[AOSP 源码]

## ADR-4 服务生命周期与存活锚点

**决策**：单进程 + 单个 `specialUse` 前台服务；恢复入口四个
（`BOOT_COMPLETED`、`QUICKBOOT_POWERON`、`MY_PACKAGE_REPLACED`、用户手动）；
**外加一个 `NotificationListenerService` 作为存活锚点**。

**为什么需要锚点**：小米固件在待机/关机时执行 `killAllThirdApp forcestop`，把第三方应用置为
`stopped=true`。被强停的包不接收任何广播，`START_STICKY` 也不生效——`BOOT_COMPLETED` 这条路是死的。
[实测]

但系统会重新绑定已授权的通知监听，**顺带把进程拉回来**（实测强停后约 150ms）。[实测]
复活后若 `phase != IDLE`：屏幕亮着立即起服务；屏幕黑着只记一笔，等 `SCREEN_ON` 再起
（否则会把电视点亮）。[实测]

**被否决**：双进程守护（MIUI 上更易被杀，且是恶意软件特征）、无障碍服务当锚点
（该机型强停后授权会被清空）、`BOOT_COMPLETED` 单独依赖（实测不投递）。

## ADR-5 口令门禁

**决策**：6 位数字，`PBKDF2WithHmacSHA256` 60,000 次迭代，16 字节随机盐，常量时间比较。
三个入口共用：进设置、结束会话、从计时中重新开始。

**为什么 6 位不是 4 位**：10^6 空间，配合失败锁定（5 次 / 30 秒）足以挡住肩窥试错。
**为什么不做一次性令牌**：本项目不导出任何组件，关闭动作只能由同进程内经口令的界面发起，
攻击面不存在（PoHW 的令牌是为跨进程场景设计的）。

## ADR-6 题库格式

**决策**：`assets/quiz/grade_<1..9>.json`，每年级一片，运行时只加载当前年级。

**决定性理由不是性能，是「可 review」**：JSON 能 diff、能 PR、能在 GitHub 网页上逐题看；
二进制 SQLite 会让「社区贡献题目」这条路直接死掉。约 2000 题不需要索引。

判题用 `answerIndex`（不用选项文本比较），出题时打乱选项并回写正确下标。

**被否决**：SQLite（不可 diff）、纯文本竖线格式（解析静默出错）、运行时在线拉题（引入网络与隐私面）。

---

## 一句话总结

**前台服务 + 剩余时间快照 + 悬浮层 + 通知监听锚点**：不接管桌面，不做离开账本，
不把口令和设置入口留给公开面；靠系统重新绑定通知监听来对抗厂商的强停。
