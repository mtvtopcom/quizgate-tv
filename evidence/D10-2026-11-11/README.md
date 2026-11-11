# D10 · 2026-11-11 · v1.2.9 真机取证（D2 / Android 14 全流程）

## 本次验证的六项

1. 设置页顶部整块删除，抬头直接「设置」。
2. 本次时长 1/5/10/15/30 + 自定义（1–240）；答对延长 1/2/3/5/10 + 自定义（1–60）。
3. 年级分小学（1–6）/ 初中（初一~初三）。
4. 点「开始计时」先查悬浮窗 / 通知使用权 / 电池白名单，缺哪项跳哪项。
5. 首页最下一行不再被裁。
6. 锁屏态按 HOME 的处置。

## 设备与连接方式（重要）

| 机器 | 地址 | 系统 | 验证前版本 |
| --- | --- | --- | --- |
| D1 | `192.168.1.123:5555` | Android 11（SDK 30） | 1.2.6 / 10206 |
| D2 | `192.168.1.88:5555` | Android 14（SDK 34） | 1.2.8 / 10208 |

**这台 Mac 直连不到 D2**：`ping 192.168.1.88` 100% 丢包、ARP 拿不到（`arp` 显示
incomplete），macOS 还缓存了一条 `REJECT` 主机路由，`adb connect` 报 `Host is down`。
但 D1 能 ping 通、能连上 D2 的 5555。于是用 D1 做了一层 TCP 中转：

```bash
adb -s 192.168.1.123:5555 forward tcp:15555 tcp:15555
adb -s 192.168.1.123:5555 shell "nc -p 15555 -L nc 192.168.1.88 5555"
adb connect 127.0.0.1:15555        # → product:freeguy model:MiTV_MFTR0
```

截图、日志、按键都是经这条链路拿到的，结论对 D2 本体有效；只是命令有约 100ms 额外延迟。

> D1 本次只完成了「装 1.2.9 + 升级后数据保留」的确认（`12_d1_pin_setup_after_upgrade.png`）。
> 开始跑 UI 时 D1 前台被一个第三方视频播放器占用（`com.ghosten.player`），
> 为保证不干扰正在播放的内容，**没有继续往 D1 发按键**，UI 回归待它空闲后补。

## 截图与结论

| 文件 | 证明什么 |
| --- | --- |
| `01_d2_settings_top_removed.png` | 设置页顶部直接是「设置」，返回按钮 / 品牌 / 「未开始 · 待机」全部消失 |
| `02_d2_duration_options.png` | 本次时长 1/5/10/15/30 + 自定义，当前值黄色高亮，底部范围提示完整 |
| `03_d2_custom_keypad_8min.png` | 「自定义」进 3×4 键盘，遥控器数字键直接按出 8，顶部显示「8 分钟」 |
| `04_d2_settings_values.png` | 保存后：本次时长 8 分钟、答对延长 +3 分钟、题目年级「初中 · 初二」，焦点留在刚编辑的那一行 |
| `05_d2_home_bottom_line.png` | 首页最下一行「方向键选择 · OK 确认 · …」完整可见，没有被屏幕边缘裁掉 |
| `06_d2_auth_gate_missing_notif.png` | 缺通知使用权时的授权面板：该项 ✗ 并自动选中，说明里带完整 adb 命令，「仍然开始」可用 |
| `07_d2_notif_access_page.png` | 按 OK「去开启」跳到 `com.android.tv.settings.privacy.NotificationAccessActivity`，家长可用遥控器打开开关 |
| `08_d2_auth_gate_all_green.png` | 返回应用后 `onResume` 自动重查：三项全 ✓，按钮变成「开始计时 / 取消」 |
| `09_d2_mask_grade8.png` | 1 分钟到点出题，题目是初二的勾股定理（证明抽题下限生效，没回落到小学题） |
| `10_d2_after_home_key.png` | 锁定态按 HOME 之后的画面：仍是遮罩（与 `09` 同一帧） |
| `11_d2_delivery_state.png` | 测试后 `pm clear` + 补授权，回到「设置 6 位家长口令」的干净交付态 |
| `12_d1_pin_setup_after_upgrade.png` | D1 从 1.2.6 覆盖升级到 1.2.9 后，数据保留（原库无口令 → 直接进设置口令） |

## 关键日志

**未授权时按 HOME（升级前 D2 的原始故障现场）**：

```
WIN DEATH: Window{127bb7e u0 QuizGateMask}
Got obituary of 29593:app.quizgate.tv
TvMgr-CentralService: currentPkg:com.mitv.tvhome ... lstPkgName:app.quizgate.tv
```

进程被杀、没有通知监听把它拉回来 → 停在桌面，这就是家长看到的「时间不走」。

**补齐通知使用权后按 HOME**：

```
14:38:30.319 AppsRelaunchController: Refused to start foreground service(...) caller=app.quizgate.tv
14:38:30.341 QuizGate: service_create phase=已锁定 remaining=0:00 ... pin=set rc=unsaved safe=off
14:38:30.341 QuizGate: onStartCommand action=app.quizgate.tv.RESTORE
14:38:30.417 QuizGate: mask_add ok
mCurrentFocus=Window{40c465a u0 QuizGateMask}
```

从按键到遮罩重新挂上 < 1 秒。

**只挂遮罩、没有 Activity 在后台时再按 HOME**：进程没有被杀，
`mCurrentFocus` 仍是同一个 `QuizGateMask` 窗口，画面不变（`10` 与 `09` 同一帧）。

**答题与结束**：

```
QuizGate: solved +3min -> 3:01
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

## 顺带确认的两件事

1. **`pm clear` 会清掉通知使用权**（`enabled_notification_listeners` 变空），
   但保留悬浮窗 appops 和电池白名单。以后恢复交付态要按「clear → 补三条」的顺序做，
   别只补 appops。
2. **升级不丢数据**：D1 1.2.6 → 1.2.9、D2 1.2.8 → 1.2.9 都是 `adb install -r` 覆盖，
   口令 / 恢复码 / 时长 / 年级 / 当前会话全部沿用（D2 升级后仍停在原来的遮罩上）。
