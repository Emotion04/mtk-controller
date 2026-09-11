package magicau.mtkcontroller.domain.model

import kotlinx.serialization.Serializable

/**
 * The kinds of card the home dashboard can show.
 *
 * **不带界面文案。** 卡片标题与说明由界面层按常量取资源（见 `ui/OptionLabels.kt`）——
 * `domain` 拿不到字符串资源，这里只给身份。
 */
enum class HomeCardType {
    QUICK_SETTINGS,
    CPU_CHART,
    ACTIVE_PROFILE,
    CLUSTER_STATUS,
    PRIVILEGE,
    TEST_CENTER,
    GPU_STATUS,
    FREQ_GAUGE,
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
    HomeCard("card-test-center", HomeCardType.TEST_CENTER),
)
