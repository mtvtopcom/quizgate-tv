# D10 · 2026-11-11 · v1.2.9 双机独立取证（D1 / D2 各自直连）

## 本次验证的六项

1. 设置页顶部整块删除，抬头直接「设置」。
2. 本次时长 1/5/10/15/30 + 自定义（1–240）；答对延长 1/2/3/5/10 + 自定义（1–60）。
3. 年级分小学（1–6）/ 初中（初一~初三）。
4. 点「开始计时」先查悬浮窗 / 通知使用权 / 电池白名单，缺哪项跳哪项。
5. 首页最下一行不再被裁。
6. 锁屏态按 HOME 的处置。

## 设备与连接方式

| 机器 | 地址 | 系统 | 验证前版本 |
| --- | --- | --- | --- |
| D1 | `192.168.1.123:5555` | Android 11（SDK 30） | 1.2.6 / 10206 |
| D2 | `192.168.1.88:5555` | Android 14（SDK 34） | 1.2.8 / 10208 |

两台都走**各自独立的 adb 连接**，没有任何中转。D2 的 ICMP 被过滤
（`ping 192.168.1.88` 全丢），但 5555 的 TCP 通，`adb connect 192.168.1.88:5555` 直接成功——
之前那次失败是 macOS 缓存了一条 `REJECT` 主机路由（`route -n get` 里 `<UP,HOST,REJECT>`），
不是电视的问题。

> 早前一轮为了让 D2 连上，曾在 D1 上起过 `nc` 中转；那一轮的结论只用到 D2，
> 本次已全部改用直连复测，D1 与 D2 的结果互不依赖。

## D2（Android 14）：全流程通过

| 文件 | 结论 |
| --- | --- |
| `21_d2_direct_settings.png` | 设置页抬头直接「设置」，顶部无返回 / 品牌 / 状态行；本次时长 30、延长 +5、小学·3年级、守护状态「待机·授权齐全」 |
| `22_d2_direct_home.png` | 首页最下一行「方向键选择 · OK 确认 · …」完整可见 |
| `09_d2_mask_grade8.png` | 1 分钟到点出题，初二的勾股题（证明抽题下限生效，没回落到小学题） |
| `23_d2_direct_after_home.png` | **锁定态按 HOME 之后**：仍是同一张遮罩（窗口 id 未变、画面同一帧） |
| `01_d2_settings_top_removed.png` | （前一轮）设置页同一结论，另一次取帧 |
| `02_d2_duration_options.png` | （前一轮）时长 1/5/10/15/30 + 自定义，当前值黄色高亮 |
| `03_d2_custom_keypad_8min.png` | （前一轮）自定义键盘，遥控器数字键按出 8 |
| `04_d2_settings_values.png` | （前一轮）8 分钟 / +3 分钟 / 初中·初二，焦点留在刚编辑的行 |
| `06_d2_auth_gate_missing_notif.png` | （前一轮）缺通知使用权：该项 ✗ 并自动选中，说明带完整 adb 命令 |
| `07_d2_notif_access_page.png` | （前一轮）「去开启」跳到 `NotificationAccessActivity`，遥控器可开开关 |
| `08_d2_auth_gate_all_green.png` | （前一轮）返回后自动重查，三项全 ✓，按钮变「开始计时 / 取消」 |
| `11_d2_delivery_state.png` | （前一轮）测试后清回「设置 6 位家长口令」 |

**HOME 的两种结局（D2 实测）**：

1. 后台还留着 `MainActivity` 任务时按 HOME → `WIN DEATH` 整个进程被杀；
   有通知使用权时 `NotifProbe` 0.1s 内 RESTORE、遮罩 <1s 回来。
2. 只剩遮罩、没有 Activity 时按 HOME → **进程不被杀，窗口保持焦点**，画面不变。

## D1（Android 11）：界面项通过，HOME 一项被真遥控器打断

| 文件 | 结论 |
| --- | --- |
| `13_d1_settings_top_removed.png` | 设置页抬头「设置」，顶部模块已删；本次时长 30、延长 +5、小学·3年级、守护状态「待机·授权齐全」 |
| `14_d1_duration_options.png` | 时长面板 1/5/10/15/30 + 自定义，30 高亮，底部提示完整 |
| `15_d1_custom_keypad_7min.png` | 自定义键盘输入 7，显示「7 分钟」，键盘完整 |
| `16_d1_grade_junior.png` | 年级第二级「初中」：初一 / 初二 / 初三，初一高亮 |
| `17_d1_home_bottom_line.png` | 首页最下一行完整可见（与 D2 一致） |
| `18_d1_auth_gate_hint.png` | 授权面板：通知使用权 ✗ 自动选中；**按钮下方多一行**「电视里找不到这一页时，用电脑执行上面那条 adb 命令即可」 |
| `19_d1_mask_grade8.png` | 1 分钟到点出题，初二几何题（平行四边形）；遮罩版面正常 |
| `20_d1_end_session_pin.png` | 口令结束会话面板完整，含「忘记口令？用恢复码重置」 |
| `12_d1_pin_setup_after_upgrade.png` | 1.2.6 → 1.2.9 覆盖升级后数据保留（原库无口令 → 直接进设置口令） |

### D1 上没有「通知使用权」设置页

```bash
adb -s 192.168.1.123:5555 shell cmd package resolve-activity --brief \
  -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
# No activity found
```

点「去开启」时 `ACTION_NOTIFICATION_LISTENER_SETTINGS` 与 `ACTION_SETTINGS` 都启动失败
（logcat 里只有两条 `START`，界面仍停在本应用），只能靠 adb 命令。
所以这次在授权面板底部补了一行常驻兜底提示——toast 一闪而过，说不清这件事。

### HOME 一项为什么没结论

两次都在到点后按了 HOME，但都在我按键前 ~2 秒，**有人在用实体遥控器按了 OK**
（`interceptKeyTq keycode=KEYCODE_DPAD_CENTER name=小米蓝牙语音遥控器`，
15:16:43 / 15:22:55），题目被答对、遮罩按设计收起，于是 HOME 落到的是桌面而不是遮罩。
D1 的 HOME 行为与 D2 走同一段代码，但**按项目规矩，没有干净取证就不下结论**。
等 D1 完全空闲（遥控器静止 ≥1 分钟）时可以补一次：开始 1 分钟计时 → 到点 → 只发一次
`input keyevent 3` → 看 `mCurrentFocus` 是否仍是 `QuizGateMask` / 是否出现
`resurrect_restore`。

## 关键日志

**未授权时按 HOME（升级前 D2 的原始故障现场）**：

```
WIN DEATH: Window{127bb7e u0 QuizGateMask}
Got obituary of 29593:app.quizgate.tv
TvMgr-CentralService: currentPkg:com.mitv.tvhome ... lstPkgName:app.quizgate.tv
```

进程被杀、没有通知监听把它拉回来 → 停在桌面，这就是家长看到的「时间不走」。

**补齐通知使用权后按 HOME（D2）**：

```
14:38:30.319 AppsRelaunchController: Refused to start foreground service(...) caller=app.quizgate.tv
14:38:30.341 QuizGate: service_create phase=已锁定 remaining=0:00 ... pin=set rc=unsaved safe=off
14:38:30.341 QuizGate: onStartCommand action=app.quizgate.tv.RESTORE
14:38:30.417 QuizGate: mask_add ok
mCurrentFocus=Window{40c465a u0 QuizGateMask}
```

从按键到遮罩重新挂上 < 1 秒。

**答题与结束**：

```
QuizGate: solved +5min -> 5:01
QuizGate: service_destroy phase=未开始 remaining=1:00 gap=0s pin=set rc=saved safe=off
```

**升级检查（两台真机结果一致）**：

```
QuizGate: update_check_fail https://7789.cn/y/quizgate-tv/ -> SSLPeerUnverifiedException: Hostname 7789.cn not verified:
QuizGate: update_check_src https://shitang.mianmiangou.cn/static/quizgate-tv/ remote=10206 (1.2.6)
QuizGate: update_check self=10209 remote=10206 (1.2.6)
```

主地址证书不匹配（只有 `*.mianmiangou.cn`），回落旧目录成功；remote 比 self 低，
按设计不弹提示。**证书补齐之前，这条回落会一直存在**。

## 顺带确认

1. **`pm clear` 会清掉通知使用权**（`enabled_notification_listeners` 变空），
   悬浮窗 appops 和电池白名单保留。恢复交付态要按「clear → 补三条」做。
2. **升级不丢数据**：D1 1.2.6 → 1.2.9、D2 1.2.8 → 1.2.9 都是覆盖安装，
   口令 / 恢复码 / 时长 / 年级 / 会话快照全部沿用。
3. **两台最终的交付态**：都 `pm clear` + 补齐三条授权，停在「设置 6 位家长口令」。
