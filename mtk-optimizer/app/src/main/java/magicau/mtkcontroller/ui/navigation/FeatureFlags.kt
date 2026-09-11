package magicau.mtkcontroller.ui.navigation

/**
 * 顶层功能开关。**只在一处登记。**
 *
 * 这些开关只决定"用户能不能到达这个入口"，不碰共享层：`CpuControl`、`SettingChannel`、
 * `RecoverySnapshot` 这些永远编译、永远可用。功能 A 关掉不会影响功能 B 的数据。
 *
 * 改开关只需要改这张表并重新打包，不用在页面里加条件。页面内部不应该知道有开关这回事。
 *
 * **功能转正后删掉对应行**，不要留着一个永远为 true 的开关 —— 否则以后没人敢动它。
 */
object FeatureFlags {

    /**
     * 顶层标签的可见性。
     *
     * 当前 Canary 只保留 CPU 页和设置页；首页、工具页的代码仍在，只是入口不出现。
     * 底栏和 Pager 读同一个 [tabs]，少标签时自然等分，没有写死宽度。
     */
    private val enabled = mapOf(
        Destination.HOME to false,
        Destination.CPU to true,
        Destination.TOOLS to false,
        Destination.SETTINGS to true,
    )

    /** 可见的顶层标签，顺序沿用 [Destination] 的声明顺序。 */
    val tabs: List<Destination> = Destination.entries.filter { enabled[it] ?: true }

    fun tabEnabled(destination: Destination): Boolean = enabled[destination] ?: true

    /** 默认落点：第一个可见标签。所有"回不到配置的那个标签"都落到这里。 */
    fun defaultTab(): Destination = tabs.firstOrNull() ?: Destination.SETTINGS
}
