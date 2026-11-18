# D14 · 连对两题 / 累计冷却真机验收（2026-11-18，历史）

> **后续变更**：当前源码默认 **2 连对**，并可在设置页调整 1–10。本目录的 1/2、2/2 验证
> 仍对应当前默认；调到其他题数时的进度与延时发放需另行验收。

## 设备

| 项 | 值 |
| --- | --- |
| 型号 | `MiTV-MFTR0` |
| 系统 | Android 11（API 30） |
| 固件 | `OS2.0.23.0.RFTMATV` |
| 分辨率 | 1920×1080 |
| 连接 | `192.168.1.106:5555`（网络 ADB） |

> 与 `docs/compatibility.md` 里的 D1 同型号、同 Android 大版本，但固件小版本不同
> （D1 是 `OS2.0.5.0.RFTMATV`）。本文只对这台机器、这个固件负责。

## 被测包

- `build/quizgate-1.3.0-debug.apk`，`versionCode 10300`，debug 签名。
- 安装前电视上是正式签名的 v1.3.0；征得同意后按 `docs/upgrade.md` 的顺序处理：
  先删除 `Settings.Secure` / `Settings.System` 里的 `quizgate_install_anchor`，再卸载正式包，
  安装 debug 包，补两条必需授权（悬浮窗 + 通知使用权）。
- 正式包已备份：`E:\soft\J2EE\android-dev\backup\quizgate-1.3.0-official.apk`，
  SHA-256 `ba8a51bf29b175673151051871f9a83d8ce62beffca2c5521e31c6f7fa623ce2`（与 GitHub Release 一致）。

## 验收结果

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 到点出题，初始进度 0/2 | 通过：`quiz_next streak=0 wrong=0` | `01_mask_lock.png` |
| 第 1 次答错 → 5 秒冷却 | 通过：`quiz_wrong n=1 cooldown=5s`，界面显示“4 秒后可继续” | `02_wrong_5s.png` |
| 第 2 次答错 → 10 秒 | 通过：`quiz_wrong n=2 cooldown=10s`，界面显示 9 秒 | `03_wrong_10s.png` |
| 第 3 次答错 → 20 秒 | 通过：`quiz_wrong n=3 cooldown=20s`，界面显示 19 秒 | `04_wrong_20s.png` |
| 第 4 次答错 → 30 秒封顶 | 通过：`quiz_wrong n=4 cooldown=30s` | 日志 |
| 冷却期间菜单键家长入口 | 通过：弹出“家长口令（6 位）”及恢复码入口 | `05_menu_during_cooldown.png` |
| 冷却期间按电源键强停 / 复活 | 通过：进程 `19261 → 20152`，`NotifProbe` 亮屏后 RESTORE；30 秒冷却复活时只剩 2 秒，`wrong=4` 保留，没有从 5 秒重来 | `06_power_resume_cooldown.png` |
| 答对第 1 题只记 1/2 | 通过：`quiz_correct 1/2`，下一题显示“已连对 1/2 题 · 再答对一题延时” | `07_correct_1_of_2.png` |
| 连对第 2 题发放延时 | 通过：`quiz_correct 2/2 -> extend 1min` → `solved +1min -> 1:01` | `08_after_solved_1min.png` |
| 答对不降低累计错题数 | 通过：`quiz_next streak=1 wrong=4`，说明累计制生效 | 日志 |

关键日志（完整见 `gate_log.txt`）：

```
lock at 0 (screen=true)
quiz_next streak=0 wrong=0
quiz_wrong n=1 cooldown=5s
quiz_next streak=0 wrong=1
quiz_wrong n=2 cooldown=10s
quiz_next streak=0 wrong=2
quiz_wrong n=3 cooldown=20s
quiz_next streak=0 wrong=3
quiz_wrong n=4 cooldown=30s
resurrect_restore ok via screen_on
quiz_next streak=0 wrong=4
quiz_correct 1/2
quiz_next streak=1 wrong=4
quiz_correct 2/2 -> extend 1min
solved +1min -> 1:01
```

> 日志前段还有用户手动操作的 `quiz_correct 1/2` / `quiz_wrong n=1 cooldown=5s` /
> `quiz_correct 2/2 -> extend 1min`，与上面的自动操作互相印证。

## 未覆盖

- **Android 14（D2）**：本次只在 Android 11 上跑。
- **设备重启后冷却放行**：设计上用 `elapsedRealtime` + 重启识别，本次只验证了进程强停，
  没有验证整机重启。
- **延长改大后的效果**：规则固定 5/10/20/30，目标只在“答对延长 = 5 分钟”时成立；
  这是 ADR-10 记录的有意取舍，不是缺陷。

## 注意

- 这是 debug 签名包，不能覆盖已装正式版；本机测试前已按上面的顺序卸载正式包，
  口令/恢复码/设置由用户重新配置。
- 没有为这次验收修改题库或兼容性矩阵；D1/D2 的既有结论不受影响。
