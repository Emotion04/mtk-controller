package magicau.mtkcontroller.ui.navigation

import magicau.mtkcontroller.R

/**
 * 拖底栏松手后的行为。两种，用户选。
 *
 * ## 为什么要给这个选择
 *
 * "拖一下就换页"和"拖完要停一下才换页"是两种截然不同的手感，取决于用户
 * 把底栏当成什么：
 *
 *  - 当成**快捷跳转**：拖到目标松手就走，越快越好
 *  - 当成**当前位置指示**：拖只是看看，停下来确认了才算数，避免误触换页
 *
 * 两种都合理，取决于习惯，所以做成设置而不是替用户定。
 *
 * ## 共同的部分
 *
 * 两种模式下滑动过程完全一样（页面实时跟随），**区别只在松手那一刻**。
 * 所以这里不涉及两套手势逻辑，只是同一个手势的两种收尾。
 */
enum class TabSwipeMode(val labelRes: Int, val descriptionRes: Int?) {

    /** 松手即切到最近的一页。 */
    NEAREST(R.string.tab_swipe_nearest, null),

    /**
     * 松手弹回原页；**停住不动满半秒再松手**才切换。
     *
     * 计时从最后一次移动算起，而不是从按下算起 —— "滑动后停留"说的是
     * 手停下来之后的那段时间，否则快速拖到一半再按住也算切换，与"停一下确认"的
     * 本意相反。
     */
    HOLD(R.string.tab_swipe_hold, null),

    ;

    companion object {
        fun fromName(name: String?): TabSwipeMode =
            entries.firstOrNull { it.name == name } ?: NEAREST

        /** 停住多久算"确认"。半秒够手指停稳，又不至于拖一下就误判成停留。 */
        const val HOLD_MS = 500L
    }
}
