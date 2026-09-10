package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/** The kinds of card the home dashboard can show. */
enum class HomeCardType(val title: String, val description: String) {
    QUICK_SETTINGS("快速设置", "触控优化、自动旋转等一键开关"),
    CPU_CHART("CPU 频率曲线", "实时绘制各簇频率变化"),
    ACTIVE_PROFILE("当前方案", "显示生效方案并快速套用/释放"),
    CLUSTER_STATUS("簇状态", "各簇当前频率与设定的上下限"),
    PRIVILEGE("权限状态", "Shizuku 授权与提权模式"),
    GPU_STATUS("GPU 状态", "检测到的 GPU 接口与当前频率"),
}

/** One card instance on the home dashboard. */
@Serializable
data class HomeCard(
    val id: String,
    val type: HomeCardType,
)

/** Default layout used the first time the app runs. */
val DEFAULT_HOME_CARDS: List<HomeCard> = listOf(
    HomeCard("card-quick-settings", HomeCardType.QUICK_SETTINGS),
    HomeCard("card-cpu-chart", HomeCardType.CPU_CHART),
    HomeCard("card-active-profile", HomeCardType.ACTIVE_PROFILE),
    HomeCard("card-cluster-status", HomeCardType.CLUSTER_STATUS),
)
