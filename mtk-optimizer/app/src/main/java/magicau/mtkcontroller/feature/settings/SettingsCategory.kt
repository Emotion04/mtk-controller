package magicau.mtkcontroller.feature.settings

import magicau.mtkcontroller.R

/**
 * 设置的分组。**一级页面只列这些，具体控件在二级页面。**
 *
 * ## 为什么必须分组
 *
 * 设置项会一直增加，平铺的结果是一屏塞十几行、而且每加一项就更长一点。
 * 分组之后一级页面的长度由**分组数**决定，不由设置项数决定 ——
 * 再多加十项，一级页面还是这几行。
 *
 * ## 分组的依据是"用户想干什么"，不是"数据存在哪"
 *
 * 所以"频率区间模式"（发下限还是发范围）跟"应用方式"放一起，
 * 它俩都在回答"下发什么、什么时候下发"；而不按"它属于 CPU 模块"去分。
 * 用户找设置时想的是"我要改什么行为"，不是"这个键归哪个类"。
 */
enum class SettingsCategory(val labelRes: Int, val subtitleRes: Int) {
    /** App 自身：启动、最近任务、标签交互、后台白名单。 */
    GENERAL(R.string.settings_cat_general, R.string.settings_cat_general_sub),
    /** 视觉：主题、配色、形状、动画、底栏。 */
    APPEARANCE(R.string.settings_cat_appearance, R.string.settings_cat_appearance_sub),
    /** 设备行为：CPU 下发、控制页、触控与显示、厂商性能档。 */
    PERFORMANCE(R.string.settings_cat_performance, R.string.settings_cat_performance_sub),
    NOTIFICATIONS(R.string.settings_cat_notifications, R.string.settings_cat_notifications_sub),
    LAB(R.string.settings_cat_lab, R.string.settings_cat_lab_sub),
    MAINTENANCE(R.string.settings_cat_maintenance, R.string.settings_cat_maintenance_sub),
    BACKUP(R.string.settings_cat_backup, R.string.settings_cat_backup_sub),

    // 「关于」不在这里。它不做成分类：进去只会看到一屏静态信息，
    // 为它多一跳不值得，直接摊在设置首页最底部。
    ;

    companion object {
        fun fromName(name: String?): SettingsCategory =
            entries.firstOrNull { it.name == name } ?: GENERAL
    }
}
