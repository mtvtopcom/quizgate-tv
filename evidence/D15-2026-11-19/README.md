# D15 · 每日答题时段真机验收（2026-11-19，Android 11）

## 设备与包

| 项 | 值 |
| --- | --- |
| 型号 | `MiTV-MFTR0` |
| 系统 | Android 11（API 30） |
| 固件 | `OS2.0.23.0.RFTMATV` |
| 连接 | `192.168.1.106:5555` |
| 包 | debug `quizgate-1.3.0-debug.apk`，`versionCode 10300` |

> 本功能只在 Android 11（API &lt; 31）验证；Android 12+ 设置项禁用，未验收。
> debug 包通过 `run-as` 临时修改 SharedPreferences 做精确闹钟测试，测完已恢复原配置。

## 验收项

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 时段内保存后立即自动开始 | 通过：`schedule_start ok` → `service_create` → `start_hint` | 日志、`01_home_schedule.png` |
| 首页时段状态行 | 通过：显示「答题时段 08:30–09:00 · 时段内」，同时左上角计时中、右下角读秒 | `01_home_schedule.png` |
| 时段结束硬解锁 | 通过：09:00:08 `schedule_window_end -> end session`，遮罩撤销、`service_destroy phase=未开始` | 日志 |
| 精确闹钟触发自动开始 | 通过：`schedule_arm at=09:14` → 09:14:08 `schedule_start ok` → `service_create` | `02_schedule_log.txt` |
| 时段结束时正在锁定 | 通过：09:15 `lock at 0` / `mask_add ok`；09:17 `schedule_window_end` / `mask_remove` / `service_destroy` | `02_schedule_log.txt` |
| 恢复原配置后重新排闹钟 | 通过：恢复 09:30–10:00 后 `schedule_arm at=09:30` | 日志 |
| 进程/通知监听存活 | 通过：会话结束后 `app.quizgate.tv` 进程仍在，`NotifProbe` 绑定；`GateService` 已销毁 | `dumpsys` |

## 关键日志

09:00 硬结束（第一次测试，当时时段 08:30–09:00）：

```text
schedule_start ok
service_create phase=未开始 remaining=10:00
...
lock at 0 (screen=true)
mask_add ok
...
schedule_window_end -> end session
service_destroy phase=未开始 remaining=1:00
```

09:14 精确闹钟（临时时段 09:14–09:17）：

```text
schedule_arm at=09:14
schedule_arm at=09:14
schedule_start ok
service_create phase=未开始 remaining=1:00
start_hint show 5000ms remaining=1:00
lock at 0 (screen=true)
mask_add ok
schedule_window_end -> end session
mask_remove
service_destroy phase=未开始 remaining=1:00
```

09:30 恢复配置后：

```text
schedule_arm at=09:30
```

完整当前日志见 `02_schedule_log.txt`（电视在两次测试之间重启过一次，缓冲区里保留了重启后的 09:14 与 09:30 日志；08:49 / 09:00 的行引用于上表）。

## 未覆盖

- 屏幕黑着时的 pending / 亮屏补开始：本次测试电视全程亮屏。
- Android 12+：功能禁用，未验收；未引入 `SCHEDULE_EXACT_ALARM` 权限。
- 精确闹钟在待机/Doze 下的准时性：本次电视处于亮屏。
- 09:14–09:17 是用 `run-as` 改 prefs 测的，因为设置页只支持 30 分钟档；生产界面只能选 09:00、09:30 这类时间，但闹钟本身对分钟值不敏感。
