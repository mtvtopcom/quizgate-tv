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
