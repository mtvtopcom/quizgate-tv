# 证据 D1 · 2026-11-10

设备：MiTV-MFTR0 / Xiaomi / Android 11 (API 30) / OS2.0.5.0.RFTMATV / 1920×1080 @320dpi
桌面：见 `launcher.txt`
被测包：app.quizgate.tv v0.1.3（versionCode 13，debug 签名，`--debug-mode`）

## 文件

| 文件 | 内容 |
| --- | --- |
| `01_pin.png` | 设置页口令键盘，焦点在「1」，琥珀高亮 → 遥控器焦点正常 |
| `02_mini_window.png` | 倒计时小窗 `0:04 剩余` 叠在 PatchWall 上，不抢焦点 |
| `03_mask_bug_mini_leftover.png` | **缺陷现场**：锁屏后读秒窗未移除，压住底部提示 |
| `04_mask_fixed.png` | v0.1.3 修复后：遮罩干净，无残留小窗 |
| `05_after_home.png` | 注入 `KEYCODE_HOME` 之后，遮罩仍全屏、仍持焦点 |
| `getprop.txt` | 完整设备属性 |
| `launcher.txt` | 默认桌面解析结果 |

## 复现命令

```bash
D=<tv-ip>:5555
adb connect $D
adb install -r dist/quizgate-0.1.3.apk
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow

# 触发一次 4 秒后锁屏（测试钩子：--el remainingMs）
adb shell run-as app.quizgate.tv am start-foreground-service --user 0 \
  -n app.quizgate.tv/.GateService -a app.quizgate.tv.START --el remainingMs 4000

adb shell dumpsys window windows | grep -A14 "Window{.*QuizGateMask}"
adb shell dumpsys window | grep mCurrentFocus
adb exec-out screencap -p > mask.png
adb logcat -s QuizGate:I
```

> 说明：服务未导出，shell uid 不能直接 `am start-foreground-service`；必须
> `run-as` 切到应用 uid 并显式 `--user 0`，否则报
> `Permission Denial: ... INTERACT_ACROSS_USERS`。

## 原始输出摘要

```
mOwnerUid=10056 showForAllUsers=false package=app.quizgate.tv appop=SYSTEM_ALERT_WINDOW
mAttrs={(0,0)(fillxfill) gr=TOP START CENTER ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
  fl=DIM_BEHIND KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS HARDWARE_ACCELERATED
mViewVisibility=0x0 mHaveFrame=true mObscured=false
mHasSurface=true isReadyForDisplay()=true
mCurrentFocus=Window{8903aeb u0 QuizGateMask}     # 按 HOME 前后一致
```

## 结论与边界

- C1 悬浮窗层级与焦点：**通过**（n=1）
- C2 HOME 拦截：**部分**——注入 HOME 不逃逸；物理遥控器是否进 `getevent` 未测
- C4 熄屏 / 待机：**通过**（结论反直觉，见下）
- C7 前台服务存活：**部分**——观察到 `START_STICKY` 重启并走 RESTORE；`am kill`/`force-stop` 未测
- C3/C5/C6 与 D2/D3/D4：**未测**，不得外推

### C4 原始数据（2026-11-10 12:44–12:45）

```
12:44:13.145 I QuizGate: screen_off u=79699284 e=79699284
12:45:41.843 I QuizGate: screen_on  du=88697  de=88697
```

- `du == de`（差 0 ms）→ 熄屏 88.7 秒期间 `uptimeMillis` **一秒没停**，该机型**不进深睡**。
- 产品侧：熄屏期间计数增量 ≈0（`177380 → 171736`，差值 5.6s 全部发生在熄屏前），亮屏后恢复递减。
- 结论：`isInteractive()` 门控是**承重墙**，不是保险；ADR-1 原表述「uptime 天然停走」被证伪。
- 设备怪癖：`input keyevent 224`（WAKEUP）**唤不醒**该机型，`dumpsys power` 停在 `Asleep`；必须用 `26`（POWER）。取证脚本已按此改写。

