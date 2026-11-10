# D6 · 2026-11-10 · v1.2.5 双机取证

## 设备与被测包

| | D1 | D2 |
| --- | --- | --- |
| 型号 | Xiaomi `MiTV-MFTR0` | 同型号 |
| Android | 11（API 30）/ `OS2.0.5.0.RFTMATV` | 14（API 34）/ `OS3.0.103.0.UFTMATV` |
| 连接 | `192.168.1.123:5555` | `192.168.1.88:5555` |
| 被测包 | `quizgate-1.2.5.apk`（10205） | 同左 |

最终发布包 sha256 `bcbbbcbe504df265f3ddc17012b019d0cd77ede569d2f20c95d721c2d5ac6f5e`，
验收时已覆盖安装到两台并重新确认首装态。

## 关键发现：回桌面时进程会被换掉

开局 5 秒提示最初只在服务实例内存里（`startHint` / `startHintUntil` 字段），
真机上 1 秒截图能看到、日志里却没有 `start_hint dismissed`。全量 logcat 给出原因：

```
23:35:22.982 27839 I QuizGate: service_create phase=未开始
23:35:22.982 27839 I QuizGate: onStartCommand action=app.quizgate.tv.START
23:35:23.169    758 I ActivityManager: Force stopping app.quizgate.tv appid=10011 user=0: from pid 758
23:35:23.176    758 I ActivityManager: Killing 27839:app.quizgate.tv/u0a11 (adj 200): stop app.quizgate.tv ...
23:35:23.207    758 I MitvWindowManager: app.quizgate.tv has system window,so kill it.
23:35:23.243    449 I Zygote: Process 27839 exited due to signal 9 (Killed)
23:35:23.377  27928 I QuizGate: notif_connected → resurrect_restore ok via immediate
```

守护本来就能由 `NotifProbe` 在新进程 RESTORE，但提示状态活不过这一下。
修法：把截止时间写进 `Prefs`（墙上时钟），`onStartCommand` 无论 START / RESTORE 都按
`startHintLeftMs()` 重排 `hintDismiss`。

修复后的时间轴（D2，最后一次取证）：

```
23:53:22.652 31126 onStartCommand action=app.quizgate.tv.START
23:53:22.659 31126 start_hint show 5000ms remaining=1:00
23:53:22.674 31126 mini_show remaining=1:00 caption=本次时长
23:53:23.185 31655 onStartCommand action=app.quizgate.tv.RESTORE   ← 新进程
23:53:23.186 31655 start_hint reschedule left=4471ms
23:53:23.284 31655 mini_show remaining=1:00 caption=本次时长
23:53:27.677 31655 start_hint dismissed                            ← 5.018 秒
```

## 截图清单

| 文件 | 内容 |
| --- | --- |
| `01` / `02` / `03` / `07` | 开局 1s「1:00 本次时长」、3.5s「0:57 本次时长」、约 6s 已消失、中途一直无浮窗 |
| `04` | 最后 30 秒「0:29 剩余」读秒窗仍在，标题切回「剩余」 |
| `05` / `06` | 新首页：待机 / 计时中（含授权齐全提示与剩余读秒窗） |
| `08` | 设置页：授权自检四项全 ✓ + `当前版本 1.2.5 (10205)` |
| `09` | 撤销通知使用权与电池白名单后：两项红字 `✗` + 可照抄的 adb 命令 |
| `10` | 首装态：「设置 6 位家长口令」（安装包无默认口令） |
| `11` / `12` | 会话中 `am force-stop` 后重进：红字「授权不完整 · 见设置自检」；倒计时 30:00 → 29:41 继续走 |
| `13` / `14` / `15` | D1（Android 11）：新首页、开局 1s「5:00 本次时长」、约 6s 消失 |
| `16` / `17` | 双机交付态：停在「设置 6 位家长口令」 |

## 自愈验证

```
$ adb shell am force-stop app.quizgate.tv
$ adb shell am start -n app.quizgate.tv/.MainActivity
23:58:10.307 24854 I QuizGate: auto_restore phase=1
23:58:10.528 24854 I QuizGate: service_create phase=计时中 remaining=30:00
23:58:10.528 24854 I QuizGate: onStartCommand action=app.quizgate.tv.RESTORE
23:58:11.438 24854 I QuizGate: diag ... rem=1799092
```

首页不再显示「正常」，也不再停在冻结数字上。D1 同样出现 `auto_restore phase=1`。

## 交付态

两台均 `pm clear` 清掉测试口令与恢复码，并补齐三条授权：

```bash
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set app.quizgate.tv REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
adb shell dumpsys deviceidle whitelist +app.quizgate.tv
```
