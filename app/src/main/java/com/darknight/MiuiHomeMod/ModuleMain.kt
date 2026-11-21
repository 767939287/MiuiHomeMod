package com.darknight.MiuiHomeMod

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * MiuiHomeMod —— 平板版 MIUI 桌面（com.miui.home）增强模块。
 *
 * 1. 隐藏桌面底部 Dock 栏：不碰 Dock 控制器的生命周期（跳过 initialize 会让桌面崩溃），
 *    只把 Dock 悬浮窗与 HotSeats 的高度压成 1px，并让上滑手势不再识别 Dock。
 *    思路同 HyperCeiler 的 HideDock / HotSeatsHeight。
 * 2. Dock 关掉后空出来的底部空间不会被浪费：把桌面网格向下扩展一行，
 *    cellSize 尽量保持不变，网格高度填满原 Dock 区域。
 *
 * 仅平板设备生效（Utilities.isPadDevice()），手机端不做任何修改。
 */
class ModuleMain : XposedModule() {

    private companion object {
        const val TAG = "MiuiHomeMod"
        const val TARGET_PACKAGE = "com.miui.home"

        const val UTILITIES = "com.miui.home.launcher.common.Utilities"
        const val APPLICATION = "com.miui.home.launcher.Application"
        const val DEVICE_CONFIG = "com.miui.home.launcher.DeviceConfig"
        const val GESTURE_TRACKER = "com.miui.home.recents.GestureTouchEventTracker"
        const val GRID_CONTROLLER = "com.miui.home.launcher.grid.GridController"
        const val GRID_CONFIG_BUILDER = "com.miui.home.launcher.grid.GridConfig\$GridConfigBuilder"

        /** Dock 被压到的高度，用 1px 而不是 0，避免内部算出负尺寸。 */
        const val HIDDEN_DOCK_HEIGHT = 1

        /** 允许为了塞下新的一行而缩小的最大比例，超过就放弃扩展。 */
        const val MAX_SHRINK_PERCENT = 12
    }

    /**
     * 注意：不能在 onPackageReady 里直接调 Utilities/DeviceConfig 这类依赖 Application 的类，
     * 它们会在 <clinit> 里访问 Application.getInstance().getResources()，
     * 此时 Application 还没创建，会抛 ExceptionInInitializerError 让桌面直接崩溃。
     * 所以这里只 hook Application#onCreate，把所有实际安装推迟到 Application 就绪之后。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        val cl = param.classLoader
        try {
            val appCls = Class.forName(APPLICATION, false, cl)
            val onCreate = appCls.getDeclaredMethod("onCreate")
            hook(onCreate).intercept { chain ->
                try {
                    installHooks(cl)
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "安装 hook 失败", t)
                }
                chain.proceed()
            }
            log(Log.INFO, TAG, "已 hook $APPLICATION#onCreate()，等待 Application 就绪")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook Application#onCreate 失败", t)
        }
    }

    private fun installHooks(cl: ClassLoader) {
        if (!isPadDevice(cl)) {
            log(Log.INFO, TAG, "非平板设备，不启用任何 hook")
            return
        }
        var applied = 0
        if (hookDockSize(cl)) applied++
        if (hookDockGesture(cl)) applied++
        if (hookExpandWorkspaceGrid(cl)) applied++
        log(Log.INFO, TAG, "hook 应用完成: $applied/3")
    }

    /** 平板判定，依次尝试 Utilities.isPadDevice() / DeviceConfig.USE_LIST_HOTSEATS。 */
    private fun isPadDevice(cl: ClassLoader): Boolean {
        try {
            val cls = Class.forName(UTILITIES, false, cl)
            val method = cls.getDeclaredMethod("isPadDevice")
            method.isAccessible = true
            val result = method.invoke(null) as Boolean
            log(Log.INFO, TAG, "$UTILITIES.isPadDevice() = $result")
            return result
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "isPadDevice() 不可用，回退到 USE_LIST_HOTSEATS: $t")
        }
        try {
            val cls = Class.forName(DEVICE_CONFIG, false, cl)
            val field = cls.getDeclaredField("USE_LIST_HOTSEATS")
            field.isAccessible = true
            val result = field.getBoolean(null)
            log(Log.INFO, TAG, "$DEVICE_CONFIG.USE_LIST_HOTSEATS = $result")
            return result
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "无法判断设备类型", t)
        }
        return false
    }

    /**
     * 隐藏 Dock：把 Dock 悬浮窗高度和 HotSeats 高度都压成 1px。
     * 保留 DockControllerImpl.initialize() 的完整执行，避免状态机/窗口管理缺失导致桌面崩溃。
     */
    private fun hookDockSize(cl: ClassLoader): Boolean {
        return try {
            val cls = Class.forName(DEVICE_CONFIG, false, cl)
            hook(cls.getDeclaredMethod("getHotSeatsHeight")).intercept { HIDDEN_DOCK_HEIGHT }
            hook(cls.getDeclaredMethod("getDockWindowHeight")).intercept { HIDDEN_DOCK_HEIGHT }
            log(Log.INFO, TAG, "已 hook getHotSeatsHeight/getDockWindowHeight -> $HIDDEN_DOCK_HEIGHT")
            true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "隐藏 Dock 窗口失败", t)
            false
        }
    }

    /** 上滑手势不再把 Dock 算进去，直接走最近任务。 */
    private fun hookDockGesture(cl: ClassLoader): Boolean {
        return try {
            val cls = Class.forName(GESTURE_TRACKER, false, cl)
            hook(cls.getDeclaredMethod("isTouchCountAndHotSeatSupport")).intercept { false }
            log(Log.INFO, TAG, "已 hook isTouchCountAndHotSeatSupport() -> false")
            true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "拦截 Dock 手势失败", t)
            false
        }
    }

    /**
     * 桌面网格扩展：在 GridController 下发网格配置前，把当前生效的 workspace 配置
     * 换成"多一行、无 dock 高度"的副本。CellLayout、翻页指示条、图标放置都读同一个
     * 配置对象，因此三者会保持一致。
     */
    private fun hookExpandWorkspaceGrid(cl: ClassLoader): Boolean {
        return try {
            val controllerCls = Class.forName(GRID_CONTROLLER, false, cl)
            val notifyMethod = controllerCls.getDeclaredMethod("notifyGridChange").apply {
                isAccessible = true
            }
            val activeField = controllerCls.getDeclaredField("mActiveGridConfigInWorkspace").apply {
                isAccessible = true
            }
            val suggestField = controllerCls.getDeclaredField("mSuggestFullScreenGridConfig").apply {
                isAccessible = true
            }

            hook(notifyMethod).intercept { chain ->
                try {
                    val controller = chain.thisObject
                    val active = activeField.get(controller)
                    if (active != null) {
                        val expanded = expandConfig(active, cl)
                        if (expanded != null) {
                            activeField.set(controller, expanded)
                            suggestField.set(controller, expanded)
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "扩展桌面网格失败", t)
                }
                chain.proceed()
            }

            // Application 阶段 GridController 的配置已经算好，这里主动刷一次（会走上面的 hook）
            try {
                val instance = controllerCls.getMethod("getInstance").invoke(null)
                if (instance != null && activeField.get(instance) != null) {
                    notifyMethod.invoke(instance)
                    log(Log.INFO, TAG, "网格配置已扩展 -> ${activeField.get(instance)}")
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "首次刷新网格配置失败", t)
            }

            log(Log.INFO, TAG, "已 hook $GRID_CONTROLLER#notifyGridChange()")
            true
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "注册网格扩展 hook 失败", t)
            false
        }
    }

    /**
     * 复制一份 GridConfig：行数 +1，dockBarHeight 归零，指示条贴底，
     * cellSize 在原值放不下的情况下才等比缩小（最多 [MAX_SHRINK_PERCENT]%）。
     */
    private fun expandConfig(config: Any, cl: ClassLoader): Any? {
        val cls = config.javaClass
        val countY = intOf(cls, config, "CountY")
        val cellSize = intOf(cls, config, "CellSize")
        val height = intOf(cls, config, "Height")
        val top = intOf(cls, config, "Top")
        val bottom = intOf(cls, config, "Bottom")
        val dockBarHeight = intOf(cls, config, "DockBarHeight")
        val indicatorBarHeight = intOf(cls, config, "IndicatorBarHeight")

        if (dockBarHeight <= 0) return null // 已经扩展过（或本来就是无 dock 配置）
        if (countY <= 0 || cellSize <= 0 || height <= 0) return null

        val available = height - top - bottom - indicatorBarHeight
        if (available <= 0) {
            log(Log.WARN, TAG, "可用高度异常: available=$available")
            return null
        }

        val newCountY = countY + 1
        val newCellSize = minOf(cellSize, available / newCountY)
        if (newCellSize * 100 < cellSize * (100 - MAX_SHRINK_PERCENT)) {
            log(
                Log.WARN, TAG,
                "放不下新的一行（cellSize $cellSize -> $newCellSize），放弃扩展"
            )
            return null
        }

        val builderCls = Class.forName(GRID_CONFIG_BUILDER, false, cl)
        val builder = builderCls.getDeclaredConstructor().newInstance()
        val intSetter = { name: String, value: Int ->
            builderCls.getMethod("set$name", Integer.TYPE).invoke(builder, value)
        }

        intSetter("Width", intOf(cls, config, "Width"))
        intSetter("Height", height)
        intSetter("CountX", intOf(cls, config, "CountX"))
        intSetter("CountY", newCountY)
        intSetter("Top", top)
        intSetter("Left", intOf(cls, config, "Left"))
        intSetter("Bottom", bottom)
        intSetter("CellSize", newCellSize)
        intSetter("IndicatorBarHeight", indicatorBarHeight)
        intSetter("DockBarHeight", 0)
        intSetter("IconSize", intOf(cls, config, "IconSize"))
        intSetter("IndicatorMarginBottom", bottom)

        val expanded = builderCls.getMethod("build").invoke(builder) ?: return null
        val expandedCls = expanded.javaClass

        try {
            val name = cls.getMethod("getName").invoke(config) as String?
            expandedCls.getMethod("setName", String::class.java).invoke(expanded, name)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "复制网格名称失败: $t")
        }

        try {
            val customField = cls.getDeclaredField("customConfig").apply { isAccessible = true }
            val custom = customField.get(config)
            if (custom != null) {
                expandedCls.getMethod("setCustomConfig", customField.type).invoke(expanded, custom)
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "复制 customConfig 失败: $t")
        }

        return expanded
    }

    private fun intOf(cls: Class<*>, target: Any, name: String): Int =
        cls.getMethod("get$name").invoke(target) as Int
}
