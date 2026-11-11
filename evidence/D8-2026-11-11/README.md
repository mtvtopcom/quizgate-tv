# D8 · 2026-11-11 · 遥控器失灵与桌面旧 logo 取证（v1.2.7 修复）

## 用户报障

1. D2（`192.168.1.88` / Android 14）在「请拍下这串恢复码」面板上遥控器无响应，
   两个按钮都按不动，升级流程测不下去。
2. 电视「应用区」里显示的仍是**旧 logo**，而包里明明已经换成新 logo。

> 隐私：恢复码是明文且只在展示时出现，本目录**不放含恢复码的整屏截图**。
> `01` / `02` 是按钮区域的裁剪图；用户发来的那张照片没有入库。

## 取证一：焦点根本不在按钮上（D2，只读）

```bash
adb -s 192.168.1.88:5555 shell uiautomator dump /sdcard/qg_focus.xml
adb -s 192.168.1.88:5555 exec-out cat /sdcard/qg_focus.xml
```

关键节点：

```
FrameLayout [0,0][1920,1080] focusable=true  focused=true   <== 恢复码面板根容器
  TextView '我已拍照保存'          focusable=true  focused=false
  TextView '换一组（旧码立即作废）' focusable=true  focused=false
```

`focused=true` 的是 overlay 的**根 FrameLayout**，两个按钮始终 `focused=false`。
`RecoveryFlow.codePanel()` 结尾那句 `ok.post(ok::requestFocus)` 在这套固件上
抢不回来（和项目里记过的「Android 14 上 `requestFocus()` 是假成功」同源）。
`MainActivity.dispatchKeyEvent()` 当时把方向键 / OK 全交给 `super` → 焦点树 →
容器判 `Unhandled key`，家长看到的就是「遥控器失灵」。

## 取证二：弹层优先级写反了

`blockFocusBelow()` 与 `dispatchKeyEvent()` 都写死了 `upgrade > codeOverlay > recovery > gate`。
但设完口令的实际顺序是：

```
hidePinOverlay() → maybeShowUpdate() 创建 upgrade
showRecoveryCode() 再把 codeOverlay 盖在 upgrade 上面
```

后加的恢复码面板在最上层，焦点和按键却仍优先送给**看不见的那一层**升级提示。
`uiautomator dump` 里两个 overlay 同时存在时尤其容易踩到。

## 取证三：升级弹层还有两个独立问题

用低版本号验证包（`versionCode=10205`，线上是 10206）在 D1 上复现「设完口令 →
恢复码面板 → 关掉后露出升级提示」的完整叠层场景：

- `UpgradeView` 同样用 `primary.post(primary::requestFocus)`，同一台 Android 14 上会重演失灵；
- 1.2.6 的升级说明有八行，把 `立即更新` / `以后再说` 两个按钮**顶出 1080p 屏幕**：
  截图 `03` 只看得到文字，按钮完全不可见。家长看不见按钮，等于升级入口不存在。

## 取证四：桌面为什么一直显示旧 logo

| 版本 | 图标资源 |
| --- | --- |
| 1.2.4 及之前 | `res/drawable/ic_launcher.xml`（96×96 矢量简笔电视机 + `banner.xml`） |
| 1.2.5 / 1.2.6 | `res/drawable/ic_launcher.png`（新 logo）、`banner.png` |

`aapt` 按**去掉扩展名的文件名**分配资源名，`.xml` → `.png` 是**同名替换**。
小米桌面 `com.mitv.tvhome` 按包名 / 资源名缓存图标，键没变就不重新拉，
所以覆盖升级、甚至卸载重装都还是老图。

用 `unzip -p dist/quizgate-1.2.5.apk res/drawable/ic_launcher.png` 导出后看图，
确认 1.2.5 / 1.2.6 包里**都是新 logo**——问题不在包，在桌面缓存。

## 修复（v1.2.7，代码已完成，尚未发布）

1. `RecoveryFlow.CodePanel`：两个按钮 `focusable=false`，自绘黄色选中框，
   自己路由 上/下 与 OK；BACK 返回 `false` 交调用方。Activity 与遮罩里的
   恢复流程共用这一套，不再依赖焦点树。
2. `UpgradeView`：同样自绘选中 + 自路由（左/右/上/下 + OK），尊重按钮
   `enabled`；说明文字 `setMaxLines(4)` + 省略号，保证两个按钮始终在屏内。
3. `MainActivity.topOverlay()`：按 `addView` 顺序倒着找最上层弹层，
   `blockFocusBelow()` 和 `dispatchKeyEvent()` 都用它分发；BACK 的 DOWN/UP 都吞，
   不让系统把 Activity finish 掉。
4. 图标资源改名 `ic_launcher_v2.png` / `banner_v2.png`（旧文件删除，
   `GateService` 的通知小图标同步改引用），让桌面缓存键变化。

## D1 真机回归（Android 11，`192.168.1.123`）

用低版本号验证包（`10205`，与线上 10206 形成版本差）走完整链路：

| 步骤 | 结果 | 截图 |
| --- | --- | --- |
| 设 6 位口令两遍 | 恢复码面板出现，「我已拍照保存」有黄色选中框 | `01` |
| 按 DOWN | 黄色选中框移到「换一组」 | `02` |
| 按 UP | 选中框回到「我已拍照保存」 | 与 `01` 同帧 |
| 按 OK | 面板关闭，露出升级提示 | `03`（修复前按钮被挤出屏幕） |
| 限制说明文字为 4 行后重进 | 两个按钮完整可见，「立即更新」高亮 | `04` |
| 按 RIGHT | 选中框移到「以后再说」 | `05` |
| 按 LEFT | 选中框回到「立即更新」 | 与 `04` 同帧 |
| 按 BACK | 升级提示关闭，回到首页，未触发下载 | `06` |

`/sdcard/Download/` 复核没有 quizgate 安装包，说明自路由没有误触「立即更新」。
`uiautomator dump` 在这两台小米电视上偶发 `ERROR: could not get idle state`，
按项目规矩改用 `screencap` 逐帧取证，不把 dump 失败当成设备能力问题。

## 状态

- 最终验证包 `build/quizgate-1.2.7.apk`（`10207`），本地断言：9 权限全在白名单、
  `\.openConnection()` 仍 2 处、无临时代码残留。
- **1.2.7 尚未正式发布**：没有 `dist/` 正式产物、tag、GitHub Release，
  生产 `version.json` 与 GitHub Latest 仍是 `1.2.6 / 10206`。
- D1 已恢复 `dist/quizgate-1.2.6.apk` 干净首装态：`versionName=1.2.6`、
  锚点 `uninstalls=0`、四条授权齐、无红字（`07`），测试口令与恢复码已清。
- D2 保持 `1.2.5` 待升级机不动，供用户继续走 OTA 升级流程。

## 桌面图标仍不刷新时的兜底（需用户确认后执行）

```bash
# 温和：重启桌面，让它重新拉图标（无损）
adb shell am force-stop com.mitv.tvhome
# 仍不刷新才用：清桌面数据（会重置桌面布局与排序，先跟用户确认）
adb shell pm clear com.mitv.tvhome
```
