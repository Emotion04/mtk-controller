package magicau.mtkcontroller.feature.settings

/**
 * 设置页的全部回调。
 *
 * ## 为什么要收成一个对象
 *
 * 设置项的每个开关都要一个回调，现在有三十多个。原来它们作为**具名参数**一路传下去，
 * 于是每一层都得抄一遍同样长的签名 —— 调用点三处、接线三十行，加一个设置就要改三处。
 * 收成一个对象之后：**加一项设置只改这个类 + 真正用到它的那个二级页面**，
 * 一级页面和导航层不用动。
 *
 * ## 所有字段都有默认值
 *
 * 默认空实现，是为了让预览、测试和"只关心其中一项"的调用点能只写它们要的那几行。
 *
 * 命名沿用 `onXxx`，与 Compose 惯例一致。
 */
data class SettingsActions(
    // --- 通用 ---
    val onSetDefaultTab: (magicau.mtkcontroller.ui.navigation.Destination) -> Unit = {},
    val onSetApplyMode: (magicau.mtkcontroller.domain.model.ApplyMode) -> Unit = {},
    val onSetReapplyInterval: (Long) -> Unit = {},
    /** 锁屏时释放还是保持 CPU 频率请求。 */
    val onSetScreenOffPolicy: (magicau.mtkcontroller.domain.model.ScreenOffPolicy) -> Unit = {},
    /** 隐藏 / 恢复最近任务卡片。 */
    val onSetHideFromRecents: (Boolean) -> Unit = {},
    /** 开机后自动按上一次范围下发一次。 */
    val onSetApplyOnBoot: (Boolean) -> Unit = {},
    val onSetFreqRangeMode: (magicau.mtkcontroller.data.tweaks.TweakCommands.RangeMode) -> Unit = {},
    val onSetFreqHardLimits: (Boolean) -> Unit = {},
    val onSetFreqThermalCompanion: (Boolean) -> Unit = {},
    val onSetTouchBackend: (magicau.mtkcontroller.data.tweaks.TouchBackend) -> Unit = {},
    val onSetBrightnessBackend: (magicau.mtkcontroller.data.tweaks.BrightnessBackend) -> Unit = {},
    /** 频率配合项（调度器搭配），跟着区间一起下发。 */
    val onSetFreqCompanion: (String?) -> Unit = {},
    /**
     * 加入 / 移出电池优化白名单。
     *
     * 直接改系统（不走系统界面）；改完由 ViewModel 回读，界面显示的是回读结果，
     * 不是"命令已发出"。
     */
    val onSetBatteryWhitelist: (Boolean) -> Unit = {},
    /** 打开系统自带的电池优化设置页。 */
    val onOpenBatterySettings: () -> Unit = {},
    /** 从系统页返回后重读白名单，避免显示旧状态。 */
    val onRefreshBatteryWhitelist: () -> Unit = {},

    // --- 外观与行为 ---
    val onSetThemeMode: (magicau.mtkcontroller.domain.model.ThemeMode) -> Unit = {},
    val onSetMotionSpeed: (magicau.mtkcontroller.ui.theme.MotionSpeed) -> Unit = {},
    val onSetShapeStyle: (magicau.mtkcontroller.ui.theme.ShapeStyle) -> Unit = {},
    val onSetPalette: (magicau.mtkcontroller.ui.theme.AppPalette) -> Unit = {},
    val onSetNavBarStyle: (magicau.mtkcontroller.domain.model.NavBarStyle) -> Unit = {},
    val onSetNavBarCustomPalette: (magicau.mtkcontroller.ui.theme.AppPalette) -> Unit = {},
    val onSetTabSwipeMode: (magicau.mtkcontroller.ui.navigation.TabSwipeMode) -> Unit = {},
    val onSetCpuLayoutMode: (magicau.mtkcontroller.domain.model.CpuLayoutMode) -> Unit = {},
    val onSetRangeControlMode: (magicau.mtkcontroller.domain.model.RangeControlMode) -> Unit = {},
    val onSetNavBarVisibility: (magicau.mtkcontroller.domain.model.NavBarVisibility) -> Unit = {},
    val onSetTabRestoreMode: (magicau.mtkcontroller.domain.model.TabRestoreMode) -> Unit = {},
    val onSetCustomHue: (Int) -> Unit = {},
    val onSetCustomConcentration: (Int) -> Unit = {},
    /** 显示与交互的各项开关（触控、插帧、旋转建议、高温降亮度）。 */
    val onOpenDisplayTweaks: () -> Unit = {},
    /** 厂商性能档（BOOST / BOOST+ / Monster）。切换会交出 CPU 控制。 */
    val onOpenPerformanceMode: () -> Unit = {},

    // --- 实验室 ---
    val onOpenLab: () -> Unit = {},
    val onOpenAppThermal: () -> Unit = {},
    val onOpenTests: () -> Unit = {},
    val onOpenArchive: () -> Unit = {},
    val onOpenExport: () -> Unit = {},

    // --- 通知 ---
    val onOpenNotifications: () -> Unit = {},
    val onOpenChangelog: () -> Unit = {},
    /** 关于页「检查更新」：结果由 UpdateCenter 的弹窗呈现。 */
    val onCheckUpdate: () -> Unit = {},

    // --- 维护与诊断 ---
    val onOpenDiagnostics: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onSetLogLevel: (magicau.mtkcontroller.data.log.LogLevel) -> Unit = {},

    // --- 备份与恢复 ---
    val onOpenSmartBackup: () -> Unit = {},
    val onExportBackup: (android.net.Uri) -> Unit = {},
    val onPrepareImport: (android.net.Uri) -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
    val onToggleExportCategory: (magicau.mtkcontroller.domain.model.BackupCategory, Boolean) -> Unit =
        { _, _ -> },
    val onToggleImportCategory: (magicau.mtkcontroller.domain.model.BackupCategory, Boolean) -> Unit =
        { _, _ -> },

    // --- 授权 ---
    val onRequestAuth: () -> Unit = {},
    /** 删除本软件写过的系统设置，回到 ROM 默认值。 */
    val onResetAllToDefaults: () -> Unit = {},
    // --- 通知 ---
    val onSetKeepAlive: (Boolean) -> Unit = {},
    val onSetNotifShowProfile: (Boolean) -> Unit = {},
    val onSetNotifShowCpu: (Boolean) -> Unit = {},
    val onSetNotifShowTemp: (Boolean) -> Unit = {},
    val onSetNotifQuickSwitch: (Boolean) -> Unit = {},
)
