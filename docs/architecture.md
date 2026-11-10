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

## ADR-7 恢复码：离线、8 位数字、用后作废

**决策**：恢复码 = 7 位随机 + 1 位 Verhoeff 校验的 8 位数字，PBKDF2 存哈希。首次设置口令后
强制展示一次，家长拍照。重置流程：输码 → 设新口令 → 换新码，旧码与新口令在**同一个
`commit()`** 里生效。

**为什么不做手机扫码 / 局域网直连**：要加 `INTERNET` 权限，会摧毁「无网络权限」这条可被
`apkanalyzer manifest permissions` 验证的结构性事实。为一个一年用一次的流程不值当。

**为什么码长只有 8 位（输入成本才是主要约束）**：十字键一次按键只有 5 个选项 ≈ 2.3 bit，
一位十进制要 3.3 bit，所以 D-pad 输入的理论下限就是 ~1.5 次/位；现有 3×4 网格平均 2.14 次/位，
已贴下限。换字母表只会让每个字符更贵（32 键字母网格 3.9 次/位），**能省的只有码长**。
8 位 → 有效空间 1e7，配合「5 次失败锁 30 分钟」，穷举期望 ~114 年，且比 6 位口令强 10 倍。

**校验位用 Verhoeff 而不是 Luhn**：Luhn 对 `0↔9` 的相邻对调是瞎的——自检里 2000 次随机对调
漏检 51 次（2.5%）；Verhoeff 对单点错误和所有相邻对调都是 100%。而家长抄错的方式正是这两类。
[自检 50009 项，tools/test_recovery.sh]

**作废时机**：新口令与新恢复码一次写完。先烧旧码再设口令，中途断电就是「码废了、口令也没设上」
的死锁，比原问题更糟。

**实测（MiTV-MFTR0）**：两个入口（设置页门禁、遮罩菜单键）都走通；旧码重放被拒；把末位改一位
当场提示「末位对不上」；恢复成功后会话按 IDLE 结束（`pin ok -> exit session`），
落盘结果 `pin=set rc=saved`。[实测]

**顺带修掉的两个真机 bug**（都不是恢复码引入的，是它把路走宽后才暴露）：

1. **浮层焦点逃逸**：口令面板盖住了设置页，但焦点能掉到面板背后，再按 OK 会误触「开始计时」
   （日志里真的起了一个会话）。现在每层浮层让下层 `FOCUS_BLOCK_DESCENDANTS`，只留最上面一层吃焦点。
2. **返回语义**：从设置页点「家长口令」再按返回，`onPinCancel` 无条件 `finish()`，把整个设置页关了。
   改成只有「首次必须设口令」的门禁才退出，其余情况回设置页。

---

## ADR-8 崩溃熔断：只有「真的崩过」才计数

**问题**：服务在 `onCreate` 里崩，`START_STICKY` 会把它反复拉起来，结果是「开机 → 闪一下 → 崩 → 再拉」
的无限循环。这比「没守护」严重得多——电视基本没法用。

**决策**：连续 **3 次**「崩溃后重启」就进安全模式，停止挂遮罩，且 `onStartCommand` 返回
`START_NOT_STICKY`。

**为什么判据是 `UncaughtExceptionHandler` 而不是「启动次数」**：小米电视待机会
`killAllThirdApp` 强杀第三方应用（`docs/compatibility.md` 已记录）。如果按启动次数算，
家长按三次电源键、通知监听三次把服务拉回来，就会被判成「崩溃 3 次」而熔断——
**把好好的守护熔掉**，比不熔断更糟。所以只有 `App` 里装的 handler 真触发时才写
`pendingCrash`；被强杀、native crash、ANR、LMK 都不会留这个标记，因此不计入。

**为什么界面崩溃不计入**：`App.gateAlive` 只在 `GateService.onCreate` 里置 true。
设置页崩溃不会自行循环（要人手动点），计入的话家长点开设置页崩一次就把守护熔断了。

**为什么是 60 秒**：崩溃循环的特征是「起来就炸」（毫秒到几秒）。启动即清零等于永远累计不到 3 次；
等太久又会误判正常重启。60 秒能覆盖一次正常的短会话。已知代价：恰好崩在 70 秒的慢速偶发崩溃
会漏检——但那种崩溃不构成「电视不能用」，熔不熔断都不影响可用性。

**出口**：首页红色横幅（比什么都显眼，否则家长以为还在守护）+ 设置页「守护状态」行。
点开显示最后一次崩溃的**根因**（取最内层 cause：真机上服务启动崩溃会被包成
`Unable to start service ... : RuntimeException`，直接取外层等于没说根因）。

**实测（MiTV-MFTR0）**：4 轮触发后 `crashCount` 1→2→3、`safeMode=true`；12 秒内没有新的
`safe_mode` 日志（说明不再重启），`dumpsys` 里只剩 NotifProbe；手动「恢复守护」后
`crashCount=0`，再启动会话 `service_create ... safe=off` 正常。 [实测]

## ADR-9 在线升级：可取消、失败静默、只连一个地址

**决策**：每次进入应用检查一次 `version.json`；有新版本才弹；弹窗可取消；
检查失败一律当「没有更新」。

**为什么不做强制升级**（原计划是强制的，改了）：强制意味着「服务器挂了 / 电视没网 = 应用完全不能用」。
在一个本来就一年用一次的流程上，把可用性押在外部服务器的存活上，不划算。
现在点「以后再说」只是本次不打扰，下次启动照旧提示。

**为什么检查失败要静默**：电视没网是常态。如果检查失败也弹「检查更新失败」，
等于每次开机都给家长一个无意义的弹窗。日志里留 `update_check_fail` 就够排查了。

**为什么 sha256 只是半个防护**：`sha256` 和 `apkUrl` 在同一份 json 里，能改 json 的人能同时改这两个字段。
它能防的是「传输截断/下错文件」，**不防篡改**。真正的防篡改是 APK 签名：系统安装时校验签名
必须与已装版本一致，不一致直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。所以「换了 json 也装不进去」
是系统保证的。升级包必须用同一个 keystore 签。

**交付方式：先落盘，再让用户点「立即安装」**。两个阶段都是必要的：

| 阶段 | 做什么 | 为什么 |
| --- | --- | --- |
| ① 下载落盘 | `MediaStore.Downloads` 写 `/sdcard/Download/quizgate-x.y.z.apk` | 不需要任何存储权限（Android 10+）；包在公共目录里可审计；被待机/换应用打断也不丢 |
| ② 用户点安装 | `ACTION_INSTALL_PACKAGE` + `content://` + `FLAG_GRANT_READ_URI_PERMISSION` | 静默安装做不到，最后一下必须人点；既然要点，就让用户决定什么时候点 |

**为什么不用 `PackageInstaller.Session` 当主路径**：它拿到的
`STATUS_PENDING_USER_ACTION` 只是个 Intent，而且这个确认界面在本机是**缺失**的 ——
只有 `InstallStart`（处理 `ACTION_INSTALL_PACKAGE` + `content/file` scheme + mimeType 的那个）
能起来。所以主路径改成 `ACTION_INSTALL_PACKAGE`，Session 只作为"安装页拉不起来"时的退路保留。
`STATUS_PENDING_USER_ACTION` 那个坑仍然写在代码注释里：它**不会自动弹**，必须自己
`startActivity(EXTRA_INTENT)`。

**「小米电视没有安装器」这个结论是错的，已更正**（2026-11-10）：

| 早期尝试 | 结果 | 真相 |
| --- | --- | --- |
| `resolve-activity -a VIEW -t application/vnd.android.package-archive` | No activity found | 没给 data scheme，匹配不到 `InstallStart` 的 filter，**查不到 ≠ 没有** |
| `resolve-activity -a CONFIRM_INSTALL` | No activity found | 本机确实没有这个确认 Activity，但不能由此推出"没有安装器" |
| `pm list packages` 搜 installer | 只有 `com.android.certinstaller` | 当时漏看了同一行旁边的 `com.android.packageinstaller` |
| `ACTION_INSTALL_PACKAGE` + `content://`（实测） | **拉起 `PackageInstallerActivity`** | 屏幕上就是系统安装页，按「继续更新」后版本真的变了 |

教训写在这里：**探测失败只能推翻"我的探测方法"，不能推翻"设备能力"**。
当年据此写下的"只能落盘、手动装"被当成唯一方案写进了 README 和 ADR，
多花了半天才发现是探针漏了参数。

**几个真踩过的坑**：
- `canRequestPackageInstalls()` 返回 true 不代表能装（它是"有没有被禁用"），返回 false 一定装不了；
  **重装应用会重置这个授权**，所以每次都要先判一次再引导
- 必须 `content://`：`file://` 从 Android 7 起抛 `FileUriExposedException`
- MediaStore 遇到重名会自动改成 `xxx (1).apk`，所以落盘前要删同名旧文件
- 装完系统会删掉 `Download` 里那个包（安装器的 `DeleteStagedFileOnResult`），这是正常的

**为什么下载后要校验包名和 versionCode**：只校验 sha256 挡不住"发布方把 json 指向上一个版本"。
`version.json` 写 10201、包里其实是 10200 的话，装上等于什么都没发生、下次启动又提示更新，
用户会陷进循环。所以用 `getPackageArchiveInfo` 读包里的真实 `versionCode`，与 json 不一致直接拒装。

**`INTERNET` 权限的代价**：README 第 4 条「没有 INTERNET 权限」不再成立，已改写。
可审计的替代承诺：全项目只有 `Updater.java` 两处 `openConnection`，裸 GET、无参数、无 cookie，
UA 固定为 `QuizGate`（默认 UA 会带 `Dalvik/x.x (Linux; U; Android 11; ...)`，把系统版本和机型报出去）。
`build.sh` 支持不配升级源构建，此时一次请求都不发。

**实测（MiTV-MFTR0，2026-11-10）**：两轮完整跑通，含"下载完进程被回收"的场景。

```
update_check self=10203 remote=10204 (1.2.4)
update_downloaded bytes=71088 sha=7b7c9b6d5877
update_inspect ok pkg=app.quizgate.tv vc=10204
update_saved Download/quizgate-1.2.4.apk uri=content://media/external/downloads/14145
（am force-stop 杀掉进程 → 重进 → 页面直接是「已下载，点安装」）
installer_opened → install_open_ok path=Download/quizgate-1.2.4.apk
mCurrentFocus=com.android.packageinstaller/.PackageInstallerActivity
→ dumpsys package: versionName=1.2.4
```

[实测]


---

## 一句话总结

**前台服务 + 剩余时间快照 + 悬浮层 + 通知监听锚点**：不接管桌面，不做离开账本，
不把口令和设置入口留给公开面；靠系统重新绑定通知监听来对抗厂商的强停。
