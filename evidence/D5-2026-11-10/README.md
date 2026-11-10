# D5 · 2026-11-10 · 第二台电视「到点不锁屏 / 倒计时卡死」

设备：`192.168.1.88:5555`，MiTV-MFTR0 / Android 14（API 34）。
用户报「1.4 版本」，实测包是 `1.2.4 / versionCode 10204`，刚卸载重装过。

## 根因

`SYSTEM_ALERT_WINDOW` 与 `REQUEST_INSTALL_PACKAGES` 两个 appop 是 allow，但
`enabled_notification_listeners` 为空、`dumpsys deviceidle whitelist` 里没有
`app.quizgate.tv` —— **卸载重装把通知使用权和电池白名单清掉了**。
系统随后强停服务，日志还原为：

```
lock at 0 → mask_add ok → 12ms 后 mask_remove → service_destroy
```

遮罩刚挂上就被 `onDestroy()` 摘掉；ticker 停止后 `prefs.remainingMs()` 快照冻结在 `0:28`，
设置页显示「计时中 · 剩余 0:28」但数字不再变化，而 `refreshGuard()` 当时只看 `safeMode`，
服务死了仍显示「守护状态：正常」。

## 修复（设备侧）

```bash
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set app.quizgate.tv REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
adb shell dumpsys deviceidle whitelist +app.quizgate.tv
```

补齐后：`notif_connected` → `resurrect_restore ok via immediate` → 到点 `mask_add ok` →
`QuizGateMask` 成焦点窗口 → 方向键 + OK 判题 `solved +5min -> 5:01` → `Unhandled key` 计数 0；
重进应用 diag 逐秒递减，倒计时不再冻结。

## 截图

| 文件 | 内容 |
| --- | --- |
| `01_故障_设置页倒计时冻结0-28.png` | 冻结的 0:28 与误报「守护状态：正常」 |
| `02_修复后_到点遮罩正常.png` | 补齐授权后遮罩正常弹出 |
| `03_方向键选中20.png` | 遥控器选中选项 20 |
| `04_重进应用_口令页正常.png` | 重进应用口令页可操作 |

## 后续产品化

这次暴露的三件事在 v1.2.5 落地：设置页授权自检（缺项红字 + adb 指引）、
会话非 IDLE 且服务不在时自动 RESTORE、不再显示「正常」和冻结数字。
双机取证见 `evidence/D6-2026-11-10/`。
