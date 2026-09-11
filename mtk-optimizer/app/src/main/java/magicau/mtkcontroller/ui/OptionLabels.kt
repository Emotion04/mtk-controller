package magicau.mtkcontroller.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.CpuLayoutMode
import magicau.mtkcontroller.domain.model.GpuChannel
import magicau.mtkcontroller.domain.model.HomeCardType
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.NavBarVisibility
import magicau.mtkcontroller.domain.model.RangeControlMode
import magicau.mtkcontroller.domain.model.TabRestoreMode
import magicau.mtkcontroller.domain.model.ThemeMode

/**
 * `domain/` 类型在界面上的文案。
 *
 * ## 为什么不写在枚举里
 *
 * `domain` **不依赖 Android**，也就拿不到字符串资源（见 `docs/dev/architecture.md`）。
 * 所以这些枚举只保留身份 —— `SYSTEM`、`FIXED`、`SEGMENT` —— 由本页按常量映射到资源。
 * 这和「界面只显示可信字段」是同一条线：数据层给数据，展示层组织文案。
 *
 * ## 为什么集中一处，不在各页面各写一个 `when`
 *
 * 页面各写一份的话，新增常量时总有人漏。集中到这里之后，`when` 用作表达式，
 * **编译器会因不穷尽而报错** —— 漏不掉。
 *
 * `data/` 和 `ui/` 里的枚举（如 `TouchBackend`、`AppPalette`）可以直接持
 * `labelRes`，那里不禁止引用资源；两处写法不同是有意的，不是遗漏。
 */
@get:StringRes
val TabRestoreMode.labelRes: Int
    get() = when (this) {
        TabRestoreMode.KEEP -> R.string.tab_restore_keep
        TabRestoreMode.RESET -> R.string.tab_restore_reset
    }

@get:StringRes
val TabRestoreMode.descriptionRes: Int
    get() = when (this) {
        TabRestoreMode.KEEP -> R.string.tab_restore_keep_desc
        TabRestoreMode.RESET -> R.string.tab_restore_reset_desc
    }

@get:StringRes
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }

/** 用户已删除这些冗余说明；返回 null 时不渲染信息按钮。 */
val ThemeMode.descriptionRes: Int? get() = null

@get:StringRes
val ApplyMode.labelRes: Int
    get() = when (this) {
        ApplyMode.SINGLE -> R.string.apply_mode_single
        ApplyMode.POLLING -> R.string.apply_mode_polling
    }

val ApplyMode.descriptionRes: Int? get() = null

@get:StringRes
val CpuLayoutMode.labelRes: Int
    get() = when (this) {
        CpuLayoutMode.PER_CLUSTER -> R.string.cpu_layout_per_cluster
        CpuLayoutMode.UNIFIED -> R.string.cpu_layout_unified
    }

val CpuLayoutMode.descriptionRes: Int? get() = null

@get:StringRes
val RangeControlMode.labelRes: Int
    get() = when (this) {
        RangeControlMode.SEGMENT -> R.string.range_control_segment
        RangeControlMode.SLIDER -> R.string.range_control_slider
    }

val RangeControlMode.descriptionRes: Int? get() = null

@get:StringRes
val NavBarVisibility.labelRes: Int
    get() = when (this) {
        NavBarVisibility.FIXED -> R.string.nav_visibility_fixed
        NavBarVisibility.AUTO_HIDE -> R.string.nav_visibility_auto_hide
    }

val NavBarVisibility.descriptionRes: Int?
    get() = when (this) {
        NavBarVisibility.FIXED -> R.string.nav_visibility_fixed_desc
        NavBarVisibility.AUTO_HIDE -> null
    }

@get:StringRes
val NavBarStyle.labelRes: Int
    get() = when (this) {
        NavBarStyle.COLORFUL -> R.string.nav_style_colorful
        NavBarStyle.FOLLOW_THEME -> R.string.nav_style_follow_theme
        NavBarStyle.CUSTOM -> R.string.nav_style_custom
    }

@get:StringRes
val NavBarStyle.descriptionRes: Int
    get() = when (this) {
        NavBarStyle.COLORFUL -> R.string.nav_style_colorful_desc
        NavBarStyle.FOLLOW_THEME -> R.string.nav_style_follow_theme_desc
        NavBarStyle.CUSTOM -> R.string.nav_style_custom_desc
    }

@get:StringRes
val GpuChannel.displayNameRes: Int
    get() = when (this) {
        GpuChannel.NONE -> R.string.gpu_channel_none
        GpuChannel.GPUFREQ_V2 -> R.string.gpu_channel_gpufreq_v2
        GpuChannel.GPUFREQ_V1 -> R.string.gpu_channel_gpufreq_v1
        GpuChannel.DEVFREQ -> R.string.gpu_channel_devfreq
    }

@get:StringRes
val HomeCardType.titleRes: Int
    get() = when (this) {
        HomeCardType.QUICK_SETTINGS -> R.string.home_card_quick_settings
        HomeCardType.CPU_CHART -> R.string.home_card_cpu_chart
        HomeCardType.ACTIVE_PROFILE -> R.string.home_card_active_profile
        HomeCardType.CLUSTER_STATUS -> R.string.home_card_cluster_status
        HomeCardType.PRIVILEGE -> R.string.home_card_privilege
        HomeCardType.TEST_CENTER -> R.string.home_card_test_center
        HomeCardType.GPU_STATUS -> R.string.home_card_gpu_status
        HomeCardType.FREQ_GAUGE -> R.string.home_card_freq_gauge
    }

@get:StringRes
val HomeCardType.descriptionRes: Int
    get() = when (this) {
        HomeCardType.QUICK_SETTINGS -> R.string.home_card_quick_settings_desc
        HomeCardType.CPU_CHART -> R.string.home_card_cpu_chart_desc
        HomeCardType.ACTIVE_PROFILE -> R.string.home_card_active_profile_desc
        HomeCardType.CLUSTER_STATUS -> R.string.home_card_cluster_status_desc
        HomeCardType.PRIVILEGE -> R.string.home_card_privilege_desc
        HomeCardType.TEST_CENTER -> R.string.home_card_test_center_desc
        HomeCardType.GPU_STATUS -> R.string.home_card_gpu_status_desc
        HomeCardType.FREQ_GAUGE -> R.string.home_card_freq_gauge_desc
    }

/**
 * 簇名，例如「核心 0–3」。
 *
 * **用核心编号，不用 `policy` 前缀** —— 那是内核的设备节点名，用户不认识，
 * 而且同一台机器上换 ROM 也可能变。核心编号才是用户能对上号的东西。
 * 取不到核号时退回节点名：**宁可知不知道，也不编一个**。
 */
@Composable
fun CpuCluster.displayLabel(): String = when {
    cpus.isEmpty() -> policy
    cpus.size == 1 -> stringResource(R.string.cpu_cluster_core, cpus.first())
    else -> stringResource(R.string.cpu_cluster_cores, cpus.first(), cpus.last())
}
