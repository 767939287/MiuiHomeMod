# MiuiHomeMod

平板版 MIUI 桌面（`com.miui.home`）增强模块，基于 [libxposed](https://github.com/libxposed/api) API 102。
**仅平板设备生效**，手机端不做任何修改。

## 功能

1. **隐藏桌面 Dock 栏**
   平板上的 Dock 是一条独立悬浮窗口（`Floating Dock`，`type=2997`，贴底）。
   **不改 Dock 控制器的生命周期**（跳过 `initialize()` 会让桌面直接崩溃），只做两件事：
   把 `DeviceConfig.getHotSeatsHeight()` / `getDockWindowHeight()` 压成 1px，
   并让 `GestureTouchEventTracker#isTouchCountAndHotSeatSupport()` 返回 `false`（上滑不再进 Dock）。
   思路同 HyperCeiler 的 `HideDock` + `HotSeatsHeight`。

2. **网格向下扩展一行**
   Dock 的高度本来是从桌面网格里扣掉的（`cellSize = (height - top - bottom - dockBar - indicator) / countY`），
   Dock 关掉后底部会空出一条。本模块在下发网格配置前换上一份"行数 +1、`dockBarHeight = 0`、
   指示条贴底"的副本，`cellSize` 尽量保持原值；只有实在塞不下时才等比缩小，且最多缩 12%，
   否则放弃扩展。

## 原理（hook 点）

| 目标 | 说明 |
| --- | --- |
| `com.miui.home.launcher.common.Utilities#isPadDevice()` | 回退的平板判定（首选屏幕最小宽度 ≥ 600dp） |
| `com.miui.home.launcher.DeviceConfig#getHotSeatsHeight()` | 返回 1px，HotSeats 不再占高度 |
| `com.miui.home.launcher.DeviceConfig#getDockWindowHeight()` | 返回 1px，Dock 悬浮窗高度几乎为零 |
| `com.miui.home.recents.GestureTouchEventTracker#isTouchCountAndHotSeatSupport()` | 返回 `false`，上滑手势跳过 Dock |
| `com.miui.home.launcher.grid.GridController#notifyGridChange()` | 下发前把 `mActiveGridConfigInWorkspace` 换成扩展后的 `GridConfig` |

`CellLayout`、翻页指示条、图标放置逻辑读的都是同一个 `GridConfig`，所以三者保持一致。

## 构建

不用本地装 Android SDK，直接走 GitHub Actions：push 到 `main`、发 `v*` tag 或在 Actions 页面手动
`workflow_dispatch`，产物在 workflow 的 Artifacts 里；打 tag 时会自动发布 Release 并附上 APK。

本地构建（需要 JDK 21 + Android SDK `platforms;android-37`、`build-tools;37.0.0`）：

```bash
./gradlew :app:assembleRelease
```

## 安装

1. 设备需已安装支持 libxposed API 101/102 的框架（如 LSPosed）。
2. 安装 APK 后在框架里启用模块，作用域勾选 **系统桌面（`com.miui.home`）**。
3. 重启桌面或重启设备。

## 注意

- **hook 安装时机**：`onPackageReady` 阶段不能碰 `Utilities` / `DeviceConfig` 这类类，它们的静态初始化会访问
  `Application.getInstance().getResources()`，那时 Application 还没建好，会抛 `ExceptionInInitializerError`
  让桌面启动即崩。本模块只在 `onPackageReady` 里 hook `Application#onCreate`，实际安装推迟到 Application 就绪之后。

- 只在平板（`Utilities.isPadDevice() == true`）上生效，手机上装上也不会有任何行为变化。
- 网格多一行后，桌面图标的坐标可能超过原来的 `countY`，卸载模块前建议把新增那一行的图标先移走。
- Release APK 使用 debug 签名，仅用于自用。

## 作者

桜咲（[767939287](https://github.com/767939287)）

## License

[Apache-2.0](LICENSE)
