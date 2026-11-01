# jmgo-aerial-helper

> 本项目不是 Aerial 官方项目，和 Aerial 作者无关，也不包含 Aerial 的代码，只是配合 [Aerial](https://github.com/pengchujin/aerial-tv) 使用。Aerial 相关的问题请到 [Aerial 仓库](https://github.com/pengchujin/aerial-tv/issues) 反馈，辅助应用的问题请在本仓库提 issue。

坚果投影上配合 [Aerial](https://github.com/pengchujin/aerial-tv) 使用的小辅助应用（包名 `app.kiro.aerialboot`，显示名「Aerial 开机修复」）。它解决了三个问题，这些问题 Aerial 本身在坚果固件上还处理不了：

1. **重启后黑屏或停在原桌面**：开机时 Aerial 按组件名启动，会落进普通（standard）任务，坚果固件会把这种任务的图层一直藏起来，导致 Aerial 拿不到焦点，反复无响应。辅助应用改用主页（HOME）方式拉起 Aerial，画面就正常了。详见 [aerial-tv#986](https://github.com/pengchujin/aerial-tv/issues/986)。
2. **遥控器关机再开机后停在原桌面**：关机时 `com.jmgo.hippo` 会强制结束所有第三方应用，再开机后 Aerial 的桌面守护还在启用列表里，但不会重新绑定。辅助应用发现守护没绑定时，会用 [#793](https://github.com/pengchujin/aerial-tv/issues/793#issuecomment-6498286218) 里的 hippo 广播把守护关掉再打开。
3. **控制中心亮度条无效**（1.2 起）：Aerial 的亮度条只写 `picture_backlight`，坚果光源不读这个值。辅助应用监听这个值，换算后调用坚果光机接口 `set(512, 值)`。详见 [aerial-tv#1067](https://github.com/pengchujin/aerial-tv/issues/1067)。

应用没有界面，也**没有网络权限**。

## 已验证

| 设备 | 系统 | 固件 | Aerial |
|---|---|---|---|
| 坚果投影 S621 | Android 13 / SDK 33 | 1.1.31.19 | 1.72 standard（开机修复）、1.76 standard（亮度转接） |

验证过：adb 重启、遥控器关机再开机、拔电后冷启动，三种情况都能进入 Aerial；亮度条拖动后光源跟着变。

**其他坚果机型没有验证过**，例如 N1 / S700（Android 11）。用到的坚果未公开接口在别的机型或固件上可能不同，请自行测试，风险自负。

## 安装

前提：

- 已安装 Aerial，并且已经按 [#793 的方案](https://github.com/pengchujin/aerial-tv/issues/793#issuecomment-6498286218) 启用过一次 Aerial 桌面守护。
- 电脑能用 adb 连上投影。

从 [Releases](../../releases) 下载 `aerialbootfix-<版本>.apk`，核对 SHA-256 后执行：

```sh
adb install -r aerialbootfix-1.5.apk

# 授权：后台启动界面（拉起 Aerial 用）
adb shell appops set app.kiro.aerialboot SYSTEM_ALERT_WINDOW allow
# 授权：读取前台应用（判断前台是不是原桌面，避免打扰你自己打开的应用）
adb shell appops set app.kiro.aerialboot GET_USAGE_STATS allow
# 启用通知监听服务（被 hippo 强制结束后系统会重新绑定它，借此在开机后恢复）
adb shell cmd notification allow_listener app.kiro.aerialboot/app.kiro.aerialboot.KeeperService
```

装好后重启一次投影验证。

## 卸载

```sh
adb uninstall app.kiro.aerialboot
```

卸载后不会留下任何改动。辅助应用不修改系统默认桌面，不停用、不卸载任何系统应用，也不写系统设置；它唯一会改的状态是 Aerial 守护的启用（通过 hippo 广播），和光源亮度（只在你拖 Aerial 亮度条时）。

## 工作方式

- **什么时候检查**：开机完成、通知监听服务绑定、亮屏时，各开始一轮，在之后约 75 秒内检查 6 次。
- **每次检查做什么**：
  - 守护没有绑定：发 hippo 广播关掉再打开。
  - 前台是坚果原桌面：用主页方式拉起 Aerial。
  - 前台已经是 Aerial：每轮最多拉起一次，并且只在开始后的 12 秒内。之后不再拉起，避免打断你在 Aerial 里的操作。
  - 前台是其他应用：不打扰。
  - 读不到前台：不打扰。只有系统刚开机不到 5 分钟时，才按停在原桌面处理。
- **怎么判断前台**：从系统的应用使用记录里，找最近 24 小时内最后一个切到前台的应用。
- **亮度转接**：监听 `Settings.Global.picture_backlight`（0 到 100）。值变化时按坚果的 10 档换算（0、11、22 … 88、100），通过 Binder 发给坚果光机服务。只在值变化时下发，开机时不会改你的光源亮度。

### 用到的坚果未公开接口

| 用途 | 接口 |
|---|---|
| 启用、停用 Aerial 守护 | 广播 `action.jmgo.request.accessibility.service`，包 `com.jmgo.hippo`，参数 `compontentNameStr`（固件原样拼写）、`enabled` |
| 取光机服务 | `ServiceManager.getService("jmgomiddle-01")`，描述符 `com.jmgo.middleware.service.IJmGOSystemService`，事务 5 |
| 写光源亮度 | 描述符 `com.jmgo.middleware.dlp.IJmGODlpManagerService`，事务 1，参数 `512, 值` |
| 读光源亮度（诊断用） | 同上，事务 2，参数 `512` |

## 排查

```sh
# 看辅助应用日志
adb logcat -s AerialBootFix

# 手动触发一轮检查
adb shell am broadcast -a app.kiro.aerialboot.FIX -n app.kiro.aerialboot/.ManualReceiver

# 读当前光源亮度，结果写进日志
adb shell am broadcast -a app.kiro.aerialboot.FIX -n app.kiro.aerialboot/.ManualReceiver --es op get_light
```

手动入口要求调用方有 `DUMP` 权限，只有 adb shell 能调用，普通应用调不了。

## 已知限制

- 冷启动时坚果原桌面可能一闪而过，固件把开机桌面写死成了自家桌面，不改固件绕不开。
- 亮度转接是单向的：在坚果面板里调过光源后，Aerial 滑块显示的还是它自己记的数。
- 如果 Aerial 以后在自己的代码里处理了这些问题，就不再需要这个应用。

## 自己编译

需要 JDK 17、Android platform 33 的 `android.jar`、build-tools 34（`d8`、`aapt2`、`zipalign`、`apksigner`）。工具目录结构见 `build.sh` 开头的注释。

```sh
TC=/path/to/tools KS=my.keystore KS_PASS=****** KS_ALIAS=mykey bash build.sh
```

自己编译的包签名和 Release 里的不同，不能直接覆盖安装，需要先卸载。

## 许可证

MIT
