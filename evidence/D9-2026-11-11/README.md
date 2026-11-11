# D9 · 2026-11-11 · v1.2.8 五项界面调整取证

## 本次改动

1. **首页待机状态模块**：去掉黄色描边与圆角卡片（原来和按钮长得一样，家长以为「待机」能按），
   改成「左侧色条 + 上下细线」的状态读数；待机时一个黄色都不留，计时中黄条、锁屏红条。
2. **设置模块**：授权自检后面的日志文字收进独立「运行日志」子页；删掉页底的
   「开始计时」「结束本次会话」；顶部与底部各加一个「← 返回首页」。
3. **开始 / 结束按钮只留在首页**。
4. **结束本次会话要过口令**：会话进行中点「结束本次会话」先弹 6 位口令，输错不结束。
5. **答题页右下角**显示 logo + 「答对再看」。

## 取证环境（必须说明）

两台真机（D1 `192.168.1.123` / Android 11、D2 `192.168.1.88` / Android 14）本次
**不在线**：`ping` 100% 丢包、`adb connect` 超时，只有 ARP 表项，判断为待机/断网。
所以下面的截图全部来自**本机 Android 14 TV 模拟器**：

```
avd: tvprobe   image: android-34/default/arm64-v8a
1920x1080 / dpi 320（有效视口 960x540dp，与真机 1080p 一致）
```

> 模拟器不是真机：**遥控器按键路由、悬浮窗层级、小米固件行为都不能用这份证据替代**。
> `docs/compatibility.md` 里已注明「真机回归待做」，发版前必须在 D1/D2 各走一遍。

## 截图与对应结论

| 文件 | 证明什么 |
| --- | --- |
| `01_home_idle.png` | 待机态：灰色左条、`--:--` 灰字、**没有黄色边框**，整体不再像可选项 |
| `02_home_running.png` | 计时中：左条变黄、时间白字等宽（数字不跳动）、守护状态独立一行 |
| `03_settings_top_back.png` | 设置页顶部「← 返回首页」+ 提示「BACK 也可返回」，焦点默认落在这里 |
| `04_settings_log_row.png` | 设置页新增「运行日志」行（查看 ›），页底已无「开始计时 / 结束本次会话」 |
| `05_log_page.png` | 日志独立子页：权限、守护服务、会话快照、版本、卸载留痕、崩溃计数 |
| `06_log_page_bottom.png` | 日志页底部：adb 命令 + 底部返回按钮；按 DOWN 焦点落到返回键并自动滚到底 |
| `07_end_session_pin.png` | 会话进行中点「结束本次会话」→ 弹口令面板；输错显示「口令不正确」且**会话继续** |
| `08_quiz_brand.png` | 答题页右下角 logo + 「答对再看」，题干两行时也不重叠 |
| `09_quiz_answered.png` | 答对态（绿块 + 延长提示）下品牌角标仍在右下角 |
| `10_settings_bottom_back.png` | 设置页最底部「← 返回首页」，OK 后回首页（已验证） |
| `11_pin_panel_fit.png` | 口令面板压缩后 540dp 视口能完整显示标题、说明、键盘与「忘记口令？」 |

## 操作命令（可复现）

```bash
# 模拟器
$ANDROID_HOME/emulator/emulator -avd tvprobe -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb install -r build/quizgate-1.2.8.apk
adb shell appops set app.quizgate.tv SYSTEM_ALERT_WINDOW allow

# 首装设 6 位口令（测试用，只在模拟器里；截图不含口令与恢复码）
for i in 1 2 3 4 5 6; do adb shell input keyevent 8; sleep 0.2; done   # 第一遍
for i in 1 2 3 4 5 6; do adb shell input keyevent 8; sleep 0.2; done   # 第二遍

# 结束会话要口令：输错（222222）→ 面板留在原地；输对 → 回待机
# 出题：设置里把「本次时长」改成 1 分钟 → 开始计时 → 约 60s 后遮罩出现
adb shell input keyevent 20   # DOWN
adb shell input keyevent 22   # RIGHT
adb shell input keyevent 23   # OK
```

## 隐私

`05` / `06` 里的 `pin=set rc=unsaved` 只是状态字符串，不含口令或恢复码。
本目录**没有**恢复码面板的整屏截图（设完口令那一刻会显示明文恢复码），
测试口令 111111 只存在于模拟器，卸载即清。
