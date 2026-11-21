package com.darknight.MiuiHomeMod

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * MiuiHomeMod —— 平板版 MIUI 桌面（com.miui.home）增强模块。
 *
 * 1. 关闭桌面底部 Dock 栏：hook DockControllerImpl#isDockEnabled() 恒为 false，
 *    initialize() 整段跳过 —— 不创建 Dock 悬浮窗口、不挂 HotSeats、不注册底部触摸热区。
 * 2. Dock 关闭后空出来的底部空间不会被浪费：把桌面网格向下扩展一行，
 *    cellSize 尽量保持不变，网格高度填满原 Dock 区域。
 *
 * 仅平板设备生效（Utilities.isPadDevice()），手机端不做任何修改。
 */
class ModuleMain : XposedModule() {

    private companion object {
        const val TAG = "MiuiHomeMod"
        const val TARGET_PACKAGE = "com.miui.home"

        const val UTILITIES = "com.miui.home.launcher.common.Utilities"
        const val DEVICE_CONFIG = "com.miui.home.launcher.DeviceConfig"
        const val DOCK_CONTROLLER_IMPL = "com.miui.home.launcher.dock.DockControllerImpl"
        const val GRID_CONTROLLER = "com.miui.home.launcher.grid.GridController"
        const val GRID_CONFIG_BUILDER = "com.miui.home.launcher.grid.GridConfig\$GridConfigBuilder"

        /** 允许为了塞下新的一行而缩小的最大比例，超过就放弃扩展。 */
        const val MAX_SHRINK_PERCENT = 12
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        val cl = param.classLoader
        try {
            if (!isPadDevice(cl)) {
                log(Log.INFO, TAG, "非平板设备，不启用任何 hook")
                return
            }
            hookDisableDock(cl)
            hookExpandWorkspaceGrid(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "初始化 hook 失败", t)
        }
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

    /** 关闭 Dock：让 DockControllerImpl 认为 dock 未启用，initialize() 直接返回。 */
    private fun hookDisableDock(cl: ClassLoader) {
        val cls = Class.forName(DOCK_CONTROLLER_IMPL, true, cl)
        val method = cls.getDeclaredMethod("isDockEnabled")
        hook(method).intercept { false }
        log(Log.INFO, TAG, "已 hook $DOCK_CONTROLLER_IMPL#isDockEnabled() -> false")
    }

    /**
     * 桌面网格扩展：在 GridController 下发网格配置前，把当前生效的 workspace 配置
     * 换成"多一行、无 dock 高度"的副本。CellLayout、翻页指示条、图标放置都读同一个
     * 配置对象，因此三者会保持一致。
     */
    private fun hookExpandWorkspaceGrid(cl: ClassLoader) {
        val controllerCls = Class.forName(GRID_CONTROLLER, true, cl)
        val notifyMethod = controllerCls.getDeclaredMethod("notifyGridChange")
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
                        log(Log.INFO, TAG, "桌面网格已扩展 -> $expanded")
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "扩展桌面网格失败", t)
            }
            chain.proceed()
        }
        log(Log.INFO, TAG, "已 hook $GRID_CONTROLLER#notifyGridChange()")
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

        val builderCls = Class.forName(GRID_CONFIG_BUILDER, true, cl)
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
