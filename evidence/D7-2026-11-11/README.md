# D7 · 2026-11-11 · v1.2.6 卸载留痕双机取证

## 背景与思路

用户报「启动软件后把软件删掉，就能成功逃脱监管」。先确认这是 Android 的设计边界：
卸载会连 `/data/data/app.quizgate.tv` 一起清空，悬浮窗、前台服务、通知监听、开机广播
全部消失，普通 APK 内部代码无法阻止。只读探测过 Device Owner / Device Admin 路线
（`dpm set-device-owner` / `set-active-admin` 命令存在，但两台电视已登录账号数 D2=1、
D1=2，Device Owner 要求无账号 / 恢复出厂；`remove-active-admin` 还要求 `android:testOnly`），
家庭场景不可接受，不做硬锁。

用户随后把方向改成「**能不能让家长知道这个软件被卸载过**」，并选定「系统设置锚点」。
于是 v1.2.6 只做**留痕 + 告警**，不拦截、不改口令语义：卸载仍然能卸，但重装后
首装口令面板、首页、设置页都会常驻红字写明累计卸载次数和失联时长。

真正的漏洞其实是「卸过之后看不出来」：原来重装后 `hasPin()==false` 就走首装分支，
孩子自己设个新口令，家长看到的是一个干净的新应用。

## 设备与被测包

| | D1 | D2 |
| --- | --- | --- |
| 型号 | Xiaomi `MiTV-MFTR0` | 同型号 |
| Android | 11（API 30）/ `OS2.0.5.0.RFTMATV` | 14（API 34）/ `OS3.0.103.0.UFTMATV` |
| 连接 | `192.168.1.123:5555` | `192.168.1.88:5555` |
| 被测包 | `build/quizgate-1.2.6.apk`（10206） | 同左 |

验证包 sha256 `a484f784abce6a07131af724ca053f5033490f9038482fe58635d0c321c49a32`
（**验证期产物，不是正式发布包**；`build/quizgate-1.2.6.apk` 这个同名文件后来已被
`publish.sh` 产出的正式包覆盖）。正式发布包是 `dist/quizgate-1.2.6.apk`，
sha256 `bd4f45454f4bec094122704deefa415cf51a316e8ca7eb394f34be1eded15bc1`
（每次 `publish.sh` 都会重签，以最后一次产出的 sha 为准）。

本地按 CI 原文用该真实产物跑过断言：9 个权限全在白名单（基础 5 + 升级 2 + 卸载留痕 2）、
`\.openConnection()` 仍 2 处且都在 `Updater.java`、无 `TEMP` / `TODO-TEST` 残留。

## 关键实测：`Settings.System` 自定义键被两套固件都拒绝

装机时给的授权（任选其一即可，写入会两个命名空间都试）：

```bash
# 首选：双机实测生效
adb shell pm grant app.quizgate.tv android.permission.WRITE_SECURE_SETTINGS
# 兜底：个别固件可能只认这条
adb shell appops set app.quizgate.tv WRITE_SETTINGS allow
```

D1（Android 11）与 D2（Android 14）在 `Settings.System.putString()` 写自定义键时**都抛**：

```
java.lang.IllegalArgumentException: You cannot keep your settings in the secure settings.
```

因此 `Anchor.write()` 捕获该异常后置 `systemUnsupported = true`，本进程不再重试 System 分支，
改由 `pm grant WRITE_SECURE_SETTINGS` 写 `Settings.Secure`，写后立刻读回确认。
自检里的 adb 命令顺序也是按这个结论调成 `pm grant` 在前。

读取时两个命名空间都读、取心跳较新的一份，所以只补了其中一条授权也能工作。
读系统设置不需要任何权限。

验证锚点确实在应用数据之外：

```bash
adb shell settings get secure quizgate_install_anchor
# {"v":1,"first":...,"seen":...,"uninstalls":2,"gap":25443}
adb shell pm uninstall app.quizgate.tv        # 真卸载
adb shell settings get secure quizgate_install_anchor   # 仍在，值没变
```

锚点**只含**首次安装时间、最后心跳、累计卸载次数、最近失联时长，
绝不含口令 / 恢复码 / `pinHash` / 任何凭据派生值（`Settings.Secure` 全局可读，
`adb shell settings get secure …` 不需要 root）。

## 时间轴与日志

D2（Android 14），第一次卸载 → 重装：

```
I QuizGate: anchor_init enabled=false                  ← 未补授权时自动降级，不崩溃
I QuizGate: anchor_system_unsupported: You cannot keep your settings in the secure settings.
I QuizGate: anchor_init enabled=true at=11-11 08:28  ← pm grant 后生效
W QuizGate: anchor_reinstall count=1 last_seen=11-11 08:28 gap=129966ms
{"v":1,"first":1794356900213,"seen":1794357030408,"uninstalls":1,"gap":129966}
```

D2 第二次卸载 → 重装（次数累加，不重置）：

```
W QuizGate: anchor_reinstall count=2 last_seen=11-11 08:32 gap=25443ms
{"v":1,"first":1794356900213,"seen":1794357167520,"uninstalls":2,"gap":25443}
```

D1（Android 11）单独取证，不套 D2 结论：

```
W QuizGate: anchor_reinstall count=1 last_seen=11-11 08:33 gap=38660ms
{"v":1,"first":1794357209865,"seen":1794357248773,"uninstalls":1,"gap":38660}
```

**覆盖升级不误报**：D2 保持 `uninstalls=2`、D1 保持 `uninstalls=1`，
没有重复计数。判据是「系统设置锚点存在」+ `!prefs.hasPin()` + `!prefs.anchorInit()`：
`anchorInit` 存在 `/data/data`，卸载会消失，所以只有真卸载重装才会命中；
「首次安装后还没设口令就重进应用」不会误报。

心跳：`MainActivity.onResume()` 与 `GateService` ticker 每 60 秒各调一次 `Anchor.touch()`。
点「开始计时」后应用界面就关了，只有服务在跑，不刷心跳会让家长看到的「最后在线」
停在打开应用那一刻，失联时长虚高。

## 截图清单

| 文件 | 内容 |
| --- | --- |
| `01_d2_selfcheck_no_grant.png` | 未补授权：自检「✗ 卸载留痕：缺失」+ 红字 adb 指引，其余功能不受影响 |
| `02_d2_selfcheck_anchor.png` | 补 `pm grant` 后：自检五项全绿，`卸载留痕：正常` |
| `03_d2_reinstall_pin.png` | 第一次真卸载重装：首装口令面板标题下常驻红字「⚠ 检测到曾被卸载 1 次 · 请家长确认」 |
| `04_d2_settings_warn.png` | 设置页：「卸载留痕 ⚠ 1 次」+ 完整红字详情（累计次数 / 失联时长 / 首次安装 / 擦不掉） |
| `05_d2_settings_check.png` | 设置页授权自检五项全 ✓（含新增的「卸载留痕：正常」） |
| `06_d2_home_warn_clipped.png` | **修复前**的布局 bug：红字放在状态卡里，最后一行被卡片下边缘裁掉半截 |
| `07_d2_home_warn_fixed.png` | **修复后**：告警行移到卡片外，完整显示「⚠ 检测到曾被卸载 1 次 · 最近失联 2 分钟 → 见「设置」」 |
| `08_d2_reinstall2_pin.png` | 第二次卸载重装：口令面板红字变成「2 次」 |
| `09_d1_reinstall_pin.png` | D1 / Android 11 独立取证：同样显示「⚠ 检测到曾被卸载 1 次」 |
| `10_d2_home_after_pin.png` | 设完口令进首页：常驻告警行仍在 |
| `11_deliver_d2.png` / `12_deliver_d1.png` | 双机恢复交付态：停在「设置 6 位家长口令」 |

## 过程中修掉的两个 UI 问题

1. **`PinPadView` 没有副标题参数**：最初把告警当副标题传给 `showPinOverlay()`，文案被吞。
   也不能塞进 `msg` —— `fail()` 的「口令不正确 / 两次不一致」会把它冲掉，而这类告警不能被冲掉。
   新增 `PinPadView.setNotice()`，在标题下插入独立的常驻红字行。
2. **首页红字被状态卡裁切**（`06` → `07`）：状态卡是权重高度，多两行就把最后一行裁掉半截。
   `homeAuth` / `homeAnchor` 移到卡片外，外层按 `wrap_content` 算高度。

## 交付态恢复

验证结束后两台都卸载 1.2.6、删除 `quizgate_install_anchor` 的 system / secure 键，
装回当时的线上版本 `dist/quizgate-1.2.5.apk`，`pm clear` 清掉测试口令与恢复码，
补齐原有三条授权：

```bash
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set app.quizgate.tv REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
adb shell dumpsys deviceidle whitelist +app.quizgate.tv
```

复核结果：

```
=== 恢复 192.168.1.88:5555 ===
  版本: versionName=1.2.5   锚点: null
  通知: app.quizgate.tv/app.quizgate.tv.NotifProbe
  电池: user,app.quizgate.tv,10085
=== 恢复 192.168.1.123:5555 ===
  版本: versionName=1.2.5   锚点: null
  通知: app.quizgate.tv/app.quizgate.tv.NotifProbe
  电池: user,app.quizgate.tv,10063
```

两台均 `mWakefulness=Awake`，截图确认停在「设置 6 位家长口令」首装交付态。

## 状态

2026-11-11 已完成代码、双机取证与正式发版（`dist/quizgate-1.2.6.apk`、tag `v1.2.6`、
GitHub Release）。本目录记录的是**验证期**过程，不代表正式包的 sha；
正式包指纹见 `CHANGELOG.md` 与 Release 正文。正式发版后两台设备已改刷最终 `dist` 包
并重新回到首装交付态。
