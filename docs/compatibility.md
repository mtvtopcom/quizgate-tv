# 兼容性矩阵

> 规矩：**没有取证的格子写「未验证」，不许推断。**
> 本页只记录实测结果，每条附命令与原始输出摘要。

## 已验证

### D1 · 小米电视 MiTV-MFTR0

| 项 | 值 |
| --- | --- |
| 型号 | `MiTV-MFTR0` |
| 厂商 | Xiaomi |
| Android | 11（API 30） |
| 固件 | `OS2.0.5.0.RFTMATV` |
| 分辨率 / 密度 | 1920×1080 / 320 dpi |
| 桌面 | `com.mitv.tvhome`（UID 1000） |
| 连接方式 | 网络 ADB `:5555` |
| 取证日期 | 2026-11-10 |

| 能力 | 结果 | 证据 |
| --- | --- | --- |
| 悬浮窗层级 | 通过 | `ty=APPLICATION_OVERLAY`、`mHasSurface=true`、`mObscured=false` |
| 悬浮窗焦点 | 通过 | `mCurrentFocus=Window{… QuizGateMask}` |
| 按 HOME | 通过 | 遮罩不消失且保持焦点（2026-11-10 v1.2.4；2026-11-11 v1.2.9 复测：按后 5 秒仍是同一窗口 id） |
| 通知使用权设置页 | **不存在** | `cmd package resolve-activity -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` → `No activity found`；只能 adb 开启，v1.2.9 起授权面板底部常驻 adb 兜底提示 |
| 熄屏暂停 | 通过 | 熄屏 88.7s，`du=88697 / de=88697`（uptime 未停），产品扣时 ≈0 |
| 按电源键 | 通过 | `killAllThirdApp forcestop` 后 150ms 由通知监听重绑复活 |
| 强行停止 | 通过 | 同上 |
| 重启接续 | 通过 | 重启后进程被系统拉起，会话续跑至出题 |
| 覆盖安装 | 通过 | 通知监听授权保留 |
| 精确闹钟 | 未测 | 本设计不依赖闹钟 |
| 断电 | 未测 | 未做多次断电测试 |

原始输出见仓库 `evidence/D1-2026-11-10/`。

> 2026-11-11 用 v1.2.9 复测：设置页顶部删除、时长自定义（7 分钟）、年级「初中·初二」、
> 授权面板、首页最下一行、到点出题、口令结束会话、**锁定态按 HOME 遮罩保持**
> 均通过（`evidence/D10-2026-11-11/`）。按 HOME 的前两次复测被现场实体遥控器按 OK
> 打断（题目被答对、遮罩收起），第三次确认窗口区间内只有虚拟 HOME 键，结论成立。

### D2 · 小米电视 MiTV-MFTR0（Android 14 固件）

| 项 | 值 |
| --- | --- |
| 型号 | `MiTV-MFTR0`（与 D1 同型号、不同系统版本） |
| 厂商 | Xiaomi |
| Android | 14（API 34） |
| 固件 | `OS3.0.103.0.UFTMATV` |
| 分辨率 / 密度 | 1920×1080 / 320 dpi |
| ABI | `armeabi-v7a` |
| 桌面 | `com.mitv.tvhome` |
| 连接方式 | 网络 ADB `:5555` |
| 取证日期 | 2026-11-10 |

| 能力 | 结果 | 说明 |
| --- | --- | --- |
| 应用启动 / 界面显示 | 通过 | `am start -W` → `Status: ok`、`LaunchState: WARM`，MainActivity 正常前台，无崩溃 / ANR |
| 悬浮窗内子 View 焦点 | **失效（系统行为）** | `requestFocus()` 假成功，方向键 / OK 被 WindowManager 判 `Unhandled key`；v1.2.4 改为根 View 自行路由 |
| 回桌面后守护存活 | 需三条授权 | 缺电池白名单 / 通知使用权时 `Force stopping app.quizgate.tv`；补齐后 3 分钟观察存活，并出现 `resurrect_restore ok via immediate` |
| 遮罩出题与判题 | 通过（v1.2.4） | 方向键移动选中（高亮唯一）、OK 判题 `solved +1min -> 1:01` |
| 遮罩内家长口令面板 | 通过（v1.2.4） | 高亮唯一、底部「忘记口令」可达、数字键输码 `pin ok -> exit session` |
| 遮罩内恢复码键盘 | 通过（v1.2.4） | 8 位输入、方向键可用、`Unhandled key` 计数 0 |
| 通知使用权设置页 | 存在 | `com.android.tv.settings/.privacy.NotificationAccessActivity`；v1.2.9 授权面板「去开启」直达，遥控器可开开关 |
| 锁屏按 HOME | 通过（v1.2.9） | 只剩遮罩时 HOME 不杀进程、窗口保持焦点；后台有 Activity 时会被杀，`NotifProbe` 0.1s 内 RESTORE、遮罩 <1s 回来 |

**这次踩坑的重点：同一型号、不同 Android 大版本，悬浮窗焦点与后台策略都变了**，不能把 D1 的
结论套到 D2 上（D1 的「悬浮窗焦点：通过」在 D2 上不成立）。新增装机的三条 adb 授权见
[README](../README.md) 第 2 步。

截图与日志见 `evidence/D4-2026-11-10/`。

#### 1.2.4 在 D1 上的回归（同日）

焦点路由改动影响所有设备，不能只凭「Android 11 原本就正常」推断。同日在 D1 覆盖安装
`quizgate-1.2.4.apk`（10204）后重跑：冷启动口令页焦点可见 → 设 1 分钟 → 回桌面 64 秒
遮罩弹出 → 方向键移动选中（高亮唯一）→ OK 判题 `solved +5min -> 5:01` →
`Unhandled key` 计数 0。验证后 `pm clear` 清空测试口令与恢复码、补回两条 appops，
设备回到「设置 6 位家长口令」的交付态。

#### 1.2.5 双机回归（同日）

1.2.5 改了首页布局、按键路由入口、开局提示与守护自愈，**两台都重新取证**，没有互相套结论：

| 项 | D1 / Android 11 | D2 / Android 14 |
| --- | --- | --- |
| 新首页方向键 / OK | 通过（RIGHT→结束会话、LEFT→开始计时） | 通过 |
| 开局「本次时长」5 秒提示 | 1s「5:00」→ 6s 消失，`start_hint dismissed` 5.013s | 1s「1:00」→ 3.5s「0:57」→ 6s 消失，`start_hint dismissed` 5.018s |
| 最后 30 秒「剩余」读秒 | 沿用原逻辑 | 通过（0:29 截图） |
| 进程被换后提示是否续上 | 通过：`RESTORE` 后按剩余时间重排 | 通过：`RESTORE` 后按剩余时间重排 |
| 会话中 `am force-stop` 后重进 | 通过：`auto_restore phase=1` | 通过：`auto_restore phase=1`，倒计时 30:00 → 29:41 |
| 设置页授权自检 / 当前版本 | 同版本代码 | 全绿 `✓`、`1.2.5 (10205)`；撤销两项后红字 + adb 命令 |

**新增系统行为记录（D2）**：应用带着系统悬浮窗回桌面时，小米固件会
`MitvWindowManager: app.quizgate.tv has system window,so kill it.` → `Force stopping app.quizgate.tv`，
进程 pid 在 0.4 秒内换掉；守护靠 `NotifProbe` RESTORE 复活。任何只存在服务实例内存里的
短时 UI 状态都必须落盘，否则活不过这一下。截图与日志见 `evidence/D6-2026-11-10/`。

#### 1.2.6 双机回归：系统设置锚点（2026-11-11）

1.2.6 新增卸载留痕，两套固件**分别取证**，没有互套结论：

| 项 | D1 / Android 11 | D2 / Android 14 |
| --- | --- | --- |
| `Settings.System.putString` 写自定义键 | **被拒**：`You cannot keep your settings in the secure settings.` | 同左（两套固件行为一致） |
| `pm grant WRITE_SECURE_SETTINGS` 后写 `Settings.Secure` | 通过，写后读回一致 | 通过 |
| 锚点跨 `pm uninstall` 保留 | 通过 | 通过 |
| 第一次卸载重装 | `anchor_reinstall count=1`，口令面板红字 | `count=1`，口令面板红字 |
| 第二次卸载重装 | — | `count=2`（累加不重置），红字变 2 次 |
| 覆盖升级 | 保持 `1`，不误报、不重复计数 | 保持 `2`，不误报、不重复计数 |
| 无授权时降级 | 同版本代码 | 自检「✗ 卸载留痕：缺失」+ adb 命令，原有计时不受影响 |

**结论**：真正生效的授权是
`adb shell pm grant app.quizgate.tv android.permission.WRITE_SECURE_SETTINGS`；
`appops set … WRITE_SETTINGS allow` 只是给其它固件的兜底，自检里的命令顺序按此调整。
`Anchor.write()` 捕获 System 分支的 `IllegalArgumentException` 后本进程不再重试，
避免每 60 秒一次的心跳反复抛异常。

判据「锚点存在 + `!hasPin()` + `!anchorInit()`」里的 `anchorInit` 存在 `/data/data`，
卸载即消失，所以「首次安装后还没设口令就重进应用」不会被当成卸载。
证据与完整日志时间轴见 `evidence/D7-2026-11-11/`。

### 弹层焦点与桌面图标（1.2.7 起，2026-11-11）

**D2（Android 14）实测**：`blockFocusBelow()` 给 overlay 根容器设 `focusable=true` 后，
`uiautomator dump` 显示 `focused=true` 落在根 `FrameLayout`，恢复码面板两个按钮
`focusable=true / focused=false`；`ok.post(ok::requestFocus)` 抢不回来，
方向键和 OK 全判 `Unhandled key`。**Activity 内弹层也不能只靠 `requestFocus()`**，
和悬浮窗里那个坑同源。1.2.7 起所有弹层自绘选中框 + 自路由按键；
`MainActivity.topOverlay()` 按 `addView` 顺序取最上层，别写死优先级
（恢复码面板会盖在升级提示上）。

**升级说明过长会吃掉按钮**：1.2.6 的 notes 八行，1080p 上
`立即更新` / `以后再说` 被顶出屏幕（D1 截图 `D8/03`）。1.2.7 限制四行 + 省略号。

**桌面图标缓存**：1.2.4 及之前是 `ic_launcher.xml`（矢量简笔电视），
1.2.5 换成同名 `ic_launcher.png`，`aapt` 按去扩展名的文件名分配资源名 →
键没变，`com.mitv.tvhome` 一直显示旧图，覆盖升级 / 卸载重装都刷不掉。
1.2.7 改名 `ic_launcher_v2` / `banner_v2`。**D2 真机实测**：卸载重装修复包后
桌面直接刷出新 logo，再覆盖升级到 `10207` 也不回退，`pm clear` 本次完全没用上；
个别固件仍缓存旧图时才按下面顺序兜底：

```bash
adb shell am force-stop com.mitv.tvhome      # 无损，先试
adb shell pm clear com.mitv.tvhome           # 会重置桌面布局，需用户确认
```

**双机回归（2026-11-11 补齐）**：先前只在 D1 验过，按「改焦点 / 按键路由两台都要
取证，别只测出问题的那台」补做 D2 报障机。两台都用 `versionCode=10205` 的验证包
复现「设置页 + 升级提示 + 最上层恢复码面板」完整叠层，逐键回归全部通过：

| 机器 | 系统 | 恢复码面板 | 升级提示 | `Unhandled key` |
| --- | --- | --- | --- | --- |
| D1 `192.168.1.123` | Android 11 | 自绘选中框正常 | 四行说明、两按钮可见 | 0 |
| D2 `192.168.1.88` | Android 14（报障机） | 自绘选中框正常 | 四行说明、两按钮可见 | 0 |

D2 另验 `10205 → 10207` 覆盖升级：保留口令与设置、无「曾被卸载」红字、
不再弹升级提示（已是最新）、锚点仍为 `null`（覆盖升级不自动获得可选授权）。
桌面「应用 → 我的应用 → 已安装应用」滚到底找到「答对再看」，显示**新 logo**；
装 `10205` 验证包与再升到 `10207` 后两次截图 sha256 完全相同
（`fa5ca707fe4fb2213398e53f…`），缓存刷新稳定、不随覆盖升级回退。
**图标刷新只在 D2 取证，D1 未单独核对，不套结论。**
截图见 `evidence/D8-2026-11-11/` 的 `08`–`15`。

**取证工具坑**：`uiautomator dump` 在两台小米电视上偶发
`ERROR: could not get idle state`，换 `screencap` 逐帧截图，别判成设备问题。
`adb shell input keyevent` 一次传多个 keycode 会丢键，要逐条发 + `sleep 0.15`；
`PinPadView` 输满 6 位自动提交，多发一个 OK 会落在焦点按钮「1」上把口令搅乱。

### 界面改版（1.2.8，**只有模拟器证据**）

1.2.8 的首页状态读数、设置页拆分、结束会话口令、答题页品牌角标，全部在
**Android 14 TV 模拟器**（`avd tvprobe`，1920×1080 / 320dpi，有效视口 960×540dp）
上走通，截图见 `evidence/D9-2026-11-11/`。

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 待机状态读数 | 通过 | 灰条 + 灰字，无黄框；计时中黄条、锁定红条 |
| 设置页日志子页 | 通过 | 开 / 关、顶部与底部返回、BACK 逐级返回 |
| 结束会话口令 | 通过 | 错口令只提示、会话继续；对口令即结束 |
| 答题页右下角角标 | 通过 | 题干两行不重叠；答对态仍在 |
| 口令面板 540dp 适配 | 通过 | 键高 58dp 后「忘记口令？」完整可见 |

> **真机回归待做**：取证当天 D1 / D2 都不在线（ping 100% 丢包、`adb connect` 超时），
> 只有 ARP 表项。遥控器按键路由、`TYPE_APPLICATION_OVERLAY` 层级、小米固件行为
> **都不能用模拟器结论替代**，上电后必须按 `docs/upgrade.md`「双机验收」两台分别跑一遍。

## 未验证

以下机型/系统组合**没有任何实测数据**，不构成支持声明：

- 小米电视 4A / ES / 6 系列
- 小米盒子 4 / 4S
- 原生 Android TV（Sony / Philips / TCL 海外版）
- 创维 / 酷开 / 海信 / TCL 国内版

如果你的机型可用或不可用，欢迎按 [issue 模板](../../.github/ISSUE_TEMPLATE/bug_report.yml) 提交，附 `dumpsys` 日志，我们会把它补进这张表。

## 复现命令

```bash
D=<电视IP>:5555
adb connect $D

# 悬浮窗与焦点
adb shell dumpsys window windows | grep -A14 "Window{.*QuizGateMask}"
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"

# 灭屏行为
adb shell input keyevent 223      # 只息屏，不强停
adb shell input keyevent 26       # 电源键，会触发 killAllThirdApp

# 是否被强停
adb shell dumpsys package app.quizgate.tv | grep -o "stopped=[a-z]*"
adb shell logcat -d | grep killAllThirdApp

# 卸载留痕锚点（v1.2.6 起；读不需要任何权限）
adb shell settings get secure quizgate_install_anchor
adb shell settings get system quizgate_install_anchor

# 日志
adb logcat -v monotonic -s QuizGate:I
```
