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
| 按 HOME | 通过 | 遮罩不消失且保持焦点 |
| 熄屏暂停 | 通过 | 熄屏 88.7s，`du=88697 / de=88697`（uptime 未停），产品扣时 ≈0 |
| 按电源键 | 通过 | `killAllThirdApp forcestop` 后 150ms 由通知监听重绑复活 |
| 强行停止 | 通过 | 同上 |
| 重启接续 | 通过 | 重启后进程被系统拉起，会话续跑至出题 |
| 覆盖安装 | 通过 | 通知监听授权保留 |
| 精确闹钟 | 未测 | 本设计不依赖闹钟 |
| 断电 | 未测 | 未做多次断电测试 |

原始输出见仓库 `evidence/D1-2026-11-10/`。

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

# 日志
adb logcat -v monotonic -s QuizGate:I
```
