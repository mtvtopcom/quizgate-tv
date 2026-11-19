# D16 · 桌面图标隐藏 / 自动恢复（2026-11-19，Android 11）

## 设备与包

| 项 | 值 |
| --- | --- |
| 型号 | `MiTV-MFTR0` |
| 系统 | Android 11（API 30） |
| 固件 | `OS2.0.23.0.RFTMATV` |
| 连接 | `192.168.1.106:5555` |
| 包 | debug `quizgate-1.3.0-debug.apk` |

## 测试方式

设置页的隐藏开关需要家长口令，本次用 debug 包的 `run-as` 直接改
`shared_prefs/gate.xml` 验证 alias 状态；测完已恢复原配置（`16:30–22:00`，开启）。

## 结果

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 默认安装 | `cmd package resolve-activity ... LEANBACK_LAUNCHER` → `app.quizgate.tv/.LauncherAlias` | 终端输出 |
| 时段开启 + 隐藏 | App 启动日志 `launcher_alias hidden`；`resolve-activity` → `No activity found` | `alias_log.txt`、终端输出 |
| 时段关闭 + 仍标记隐藏 | App 启动日志 `launcher_alias shown`；`resolve-activity` → `.LauncherAlias` | `alias_log.txt`、终端输出 |
| 恢复原配置 | alias 恢复显示；时段回到 `16:30–22:00`，`scheduleEnabled=true` | 终端输出 |
| adb 临时恢复（2026-11-19） | shell 直接 `pm enable` 被拒（`SecurityException: Shell cannot change component state ...`）；`run-as app.quizgate.tv pm enable ...` → `new state: enabled`，`resolve-activity` 返回 `.LauncherAlias`；进程重启后又记 `launcher_alias hidden`，确认只是临时 | 终端输出、`alias_log.txt` |
| adb 清除隐藏标记（2026-11-19） | `run-as` + `sed` 把 `launcherHidden` 改为 `false`；强制停止后进程 649 重启，日志 `launcher_alias shown`，`resolve-activity` 仍返回 `.LauncherAlias`，prefs 保持 `false` | 终端输出、`alias_log.txt` |

关键日志：

```text
launcher_alias shown
launcher_alias hidden
launcher_alias hidden
launcher_alias shown
```

## 未覆盖

- 设置页「桌面图标」开关（需要口令）和遮罩上的家长入口（时段到点 → 菜单键 → 口令）
  尚未由真人操作验证；本次只验证了 alias 组件状态。
- 小米桌面可能缓存图标；`resolve-activity` 已不返回 alias，但桌面 UI 是否立即刷新需
  重启电视或强制停止 `com.mitv.tvhome` 后确认。
- Android 12+：`Schedules.supported()` 为 false 时隐藏不生效，alias 保持显示；未真机验收。
- 系统设置 → 应用里仍能看到本应用；隐藏不防卸载。
