# D4 · 2026-11-10 · 第二台电视「启动没反应」排查与回归

## 设备

| 项 | 值 |
| --- | --- |
| 型号 | Xiaomi `MiTV-MFTR0` |
| Android | 14（API 34） |
| 固件 | `OS3.0.103.0.UFTMATV` |
| ABI | `armeabi-v7a` |
| 分辨率 / 密度 | 1920×1080 / 320 dpi |
| 连接 | 网络 ADB `192.168.1.88:5555` |
| 被测包 | `quizgate-1.2.4.apk`（10204，`release.jks` 签名） |

与第一台（D1）是**同一型号、不同 Android 大版本**：D1 是 Android 11 / `OS2.0.5.0.RFTMATV`。

## 结论：不是启动失败

```
$ adb shell am start -W -n app.quizgate.tv/.MainActivity
Status: ok
LaunchState: WARM
Activity: app.quizgate.tv/.MainActivity
TotalTime: 192
```
MainActivity 正常前台，logcat 无 `AndroidRuntime` / `FATAL` / ANR，界面能截图。
用户说的「没反应」是两层体感：

1. **看得见、按不动**：Android 14 的 `TYPE_APPLICATION_OVERLAY` 悬浮窗里，子 View 的
   `requestFocus()` 是「假成功」—— 焦点标记设上了，方向键 / OK 仍被 WindowManager 判
   `Unhandled key`。所以答题遮罩、遮罩内口令面板像死了一样。
2. **回桌面就再也不出题**：这台电视默认没给通知使用权、也不在电池优化白名单里，
   回桌面后系统直接 `Force stopping app.quizgate.tv`，守护进程消失。

## 设备侧补的三条授权

```bash
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
adb shell dumpsys deviceidle whitelist +app.quizgate.tv
```
补齐后 3 分钟观察进程存活，日志出现 `resurrect_restore ok via immediate`（通知监听断开后自动复活）。

## 截图

| 文件 | 内容 |
| --- | --- |
| `01_before_two_highlights.png` | **修复前**：自绘选中态与 `key_bg.xml` 的 `state_focused` 撞车，同时出现两个黄色高亮（1 和 2） |
| `02_activity_pin_focus.png` | 从桌面启动 → Activity 口令页，按一次右键焦点从 1 移到 2，可见高亮（证明未改坏原有焦点树） |
| `03_mask_single_highlight.png` | 遮罩：方向键移动后**只有一个**选项高亮（πr²） |
| `04_answer_correct.png` | 按 OK 判题「答对了，延长 1 分钟」 |
| `05_overlay_pin_single_highlight.png` | 遮罩内家长口令面板，只有一个键高亮 |
| `06_footer_reachable.png` | 从最后一行再按「下」，高亮落到底部「忘记口令？用恢复码重置」 |
| `07_recovery_pad_highlight.png` | 遮罩内恢复码键盘（8 位），单一高亮 |
| `08_recovery_pad_nav.png` | 恢复码键盘按右键，高亮从 1 移到 2 |

## 关键日志

```
I/QuizGate: update_check self=10204 remote=10203 (1.2.3)   # 本机比服务器新，不提示升级
I/QuizGate: mask_add ok
I/QuizGate: solved +1min -> 1:01
I/QuizGate: pin ok -> exit session
I/QuizGate: mask_remove
I/QuizGate: service_destroy phase=未开始 remaining=1:00 pin=set rc=saved safe=off
I/QuizGate: notif_disconnected
I/QuizGate: resurrect_restore ok via immediate
$ adb shell logcat -d | grep -c 'Unhandled key'
0                                                       # 修复后，遮罩内按键全部被消费
```

## 交付态

验证结束时执行了「返回 → 输原口令结束会话」：会话回到 `未开始`，口令与恢复码未改动，
桌面在前台，只剩常驻的 `NotifProbe`。**这台电视上没有任何测试口令被写入**——口令是用户自己设的。
