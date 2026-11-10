# v1.0.0 — 首个公开发布

> 到点出一题，答对换 N 分钟。给中国电视用的「续时闸门」。

## 这个版本能做什么

家长设 30 分钟 → 孩子随便看 → 最后 30 秒右下角读秒 → 到点全屏出题 → 答对 +N 分钟接着看 → 家长用 6 位口令结束。

全程只用遥控器，不需要输入法，不需要手机 App。

## 三条实测数据（不是推断）

这个项目从第一行代码起就在真机上取证，下面每条都有原始输出可复核。

**① 按 HOME 不逃逸**

注入 `KEYCODE_HOME` 后，遮罩既不消失也不失焦：

```
按 HOME 前：mCurrentFocus=Window{8903aeb u0 QuizGateMask}
按 HOME 后：mCurrentFocus=Window{8903aeb u0 QuizGateMask}
```

**② 电源键强停后 150ms 被系统拉回**

小米固件在待机时执行 `killAllThirdApp forcestop`，把第三方应用置为 `stopped=true`——被强停的包不收广播、`START_STICKY` 也无效。但系统会重新绑定已授权的通知监听，顺带把进程拉回来：

```
14:03:27.015  killAllThirdApp forcestop packageName:app.quizgate.tv
14:03:27.163  QuizGate: notif_connected        ← 148ms 后新进程
14:06:49.071  resurrect_deferred（屏幕未亮，等亮屏再起服务）
14:07:12.235  resurrect_restore ok via screen_on
14:07:12.277  service_create phase=计时中 remaining=1:18
```

注意中间那行：**屏幕暗着时绝不启动前台服务**，否则会把待机中的电视点亮（这是踩过的坑）。

**③ 重启后会话自动接续**

```
重启前：phase=1  remaining=596165
adb reboot
14:31:39  lock at 0 → mask_add ok    ← 会话自己续跑完 596 秒，正常出题
```

## 隐私：不联网

`AndroidManifest.xml` 里没有 `INTERNET` 权限。这不是承诺，是结构：

```bash
aapt2 dump permissions quizgate-1.0.0.apk
# 只有 SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE
#      POST_NOTIFICATIONS / RECEIVE_BOOT_COMPLETED
```

CI 里有一条断言会检查这一点，加回来就会红。

## 安装前必须知道的三件事

1. **要用电脑执行两条 adb 命令**，否则守护不生效。小米电视固件把「显示在其他应用上层」和「通知使用权」的 UI 入口藏起来了：

```bash
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener app.quizgate.tv/app.quizgate.tv.NotifProbe
```

2. **防不住卸载**。这是 Android 的设计，任何第三方应用都防不住。

3. **只在一台机器上验证过**。见 [兼容性矩阵](../docs/compatibility.md)，未测机型一律标「未验证」。

## 已知限制

| 限制 | 状态 |
| --- | --- |
| 忘口令无恢复码，只能卸载重装 | 计划中 |
| 无崩溃熔断（SAFE 模式） | 计划中 |
| 遮罩期间不暂停下层音频 | 已知 |
| 题库每年级 8 题（共 72 题）+ 口算兜底 | 只够验证流程 |
| 不做每日总时长上限、多孩子档案、远程控制 | 设计如此 |

## 安装

```bash
# 校验
shasum -a 256 quizgate-1.0.0.apk
# 期望：394b010719d011a74bda369ba33222fbdece17319f3e3a3e8733bda3f691112c

adb install -r quizgate-1.0.0.apk
```

签名指纹（升级必须一致）：

```
证书 SHA-256
06:12:E6:31:8D:4C:AB:A8:70:A1:16:B5:64:40:2E:15:12:D3:2C:FB:14:60:73:08:1B:07:BD:F2:8F:8E:3C:B8
```

## 从测试版升级

v1.0.0 换用了正式发布密钥，与之前的 debug 签名不兼容，**必须先卸载**：

```bash
adb uninstall app.quizgate.tv
adb install -r quizgate-1.0.0.apk
```

这会清空设置（口令、时长），重新设一次即可。之后所有版本可平滑升级。

## 许可

代码 Apache-2.0，题库 CC BY-SA 4.0。题库为原创撰写，不含教材原文。
素材台账见 [docs/provenance.md](../docs/provenance.md)。
