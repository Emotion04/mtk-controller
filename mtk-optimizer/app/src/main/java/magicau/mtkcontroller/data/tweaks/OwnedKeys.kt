package magicau.mtkcontroller.data.tweaks

import magicau.mtkcontroller.R

/**
 * 一个"本软件会写"的系统键。
 *
 * @param table settings 表名（`system` / `secure` / `global`）
 * @param key 键名
 * @param ownerRes 拥有这个键的功能名。备份文件按它分块，恢复时按它给人看
 * @param noteRes 这个键是干什么的。信息图标点开显示它，用户不需要猜英文键名。
 */
data class OwnedKey(
    val table: String,
    val key: String,
    val ownerRes: Int,
    val noteRes: Int?,
)

/**
 * 本软件会写的全部系统键。
 *
 * **为什么必须是登记表，不能各处自己列**：备份要知道"哪些键是我们动过的"，
 * 恢复要知道"能改回去哪些"，诊断报告要列"这台机器上这些键现在是什么"。
 * 三处各写一份必然对不上 —— 而对不上的后果是恢复时漏掉一个键，
 * 用户在不知道的情况下被留在一个改过的状态里。
 *
 * **只登记本软件会写的键。** 不登记整套 settings 数据库：恢复一份我们根本没写过的东西
 * 没有意义，而恢复的风险全部来自"我们写过、但现在值变了"。
 *
 * 新增一个会写键的功能时不用改这里 —— [OwnedKeys.all] 是从 [TouchItems] 和
 * [VivoFeature] 现算的，那两处才是要改的地方。
 */
object OwnedKeys {

    /**
     * 只登记 settings 类的触控旋钮。
     *
     * prop 不在备份范围内：恢复一个 prop 要写回它打开前的值，而"打开前"这个时刻
     * 由每个功能的快照记着，不需要备份重复记一份；更要紧的是 prop 不跨重启，
     * 恢复它意味着在本次开机里反复横跳，没有意义。
     *
     * 但漂移监测要看 prop —— 见 [propIds]。
     */
    private fun touchKeys(): List<OwnedKey> = TouchItems.all().flatMap { item ->
        item.knobs.filter { it.ns != TouchNs.PROP }.map { knob ->
            OwnedKey(
                table = knob.ns.name.lowercase(),
                key = knob.key,
                ownerRes = item.labelRes,
                noteRes = item.detailRes,
            )
        }
    }

    /**
     * 触控 prop 的标识，`"prop <名字>"`。
     *
     * **为什么单独一个列表**：漂移监测原本只看 settings 键，于是五个触控子项
     * （其中四个只写 prop）在监测里完全隐形 —— 用户开它们，监测说"没有任何改动"，
     * 而实际上 prop 已经变了。这不是"没变化"，是"没在看"。
     *
     * 只给监测用，不进备份。所以表名前缀是 `"prop"`，不会和 settings 的三张表混淆，
     * [decodeLine] 也一律对不上它 —— 备份文件里出现 `prop xxx` 会被当非法行丢掉。
     */
    val propIds: List<String> by lazy {
        TouchItems.all().flatMap { item ->
            item.knobs.filter { it.ns == TouchNs.PROP }.map { "prop ${it.key}" }
        }.distinct()
    }

    /** `VivoFeature` 的键名形如 `"system power_save_type"`，拆开即可。 */
    private fun vivoKeys(): List<OwnedKey> = VivoFeature.entries
        .filter { it.values.isNotEmpty() }
        .flatMap { feature ->
            feature.values.keys.map { spec ->
                OwnedKey(
                    table = spec.substringBefore(' '),
                    key = spec.substringAfter(' '),
                    ownerRes = feature.labelRes,
                    noteRes = feature.detailRes,
                )
            }
        }

    /**
     * 由 [TweakCommands] 直接写的键，不走 [TouchItems] 也不走 [VivoFeature]。
     *
     * ## 为什么需要这一组
     *
     * 这份登记表原本只从上面两个来源现算，于是任何"自己写键"的功能都自动脱离
     * 备份 —— 旋转建议就是这么漏的：它确实会写 `secure show_rotation_suggestions`，
     * 但备份里没有它，用户恢复时以为全恢复了，实际上这一项没被碰过。
     *
     * 这不是"漏了一个键"，是**结构上允许存在不被追踪的写入者**。所以补上这一组，
     * 并把规矩说清楚：**新增任何写系统键的功能，必须让键出现在 [all] 里** ——
     * 要么走 VivoFeature / TouchItems，要么加到这里。
     */
    fun directKeys(): List<OwnedKey> = listOf(
        OwnedKey("secure", "show_rotation_suggestions",
            R.string.tweak_rotation_suggestion, R.string.tweak_rotation_suggestion_note),
        OwnedKey("system", "accelerometer_rotation",
            R.string.tweak_auto_rotate, R.string.tweak_auto_rotate_note),
        OwnedKey("system", TweakCommands.KEY_INTERP,
            R.string.tweak_frame_interpolation, R.string.tweak_frame_interpolation_note),
        OwnedKey("system", TweakCommands.KEY_INTERP_SR,
            R.string.tweak_frame_interpolation_sr, R.string.tweak_frame_interpolation_sr_note),
        // 重度游戏 boost 会往这个列表里加/删包名；恢复默认时应该整条清掉。
        OwnedKey("global", "game_driver_opt_in_apps",
            R.string.tweaks_game_boost_title, R.string.game_boost_info),
    )

    /** 全部登记键。顺序就是备份文件里的顺序。 */
    val all: List<OwnedKey> by lazy {
        // distinctBy 去重：两个功能可能声明同一个键。实测现存一对 ——
        // AI 预加载（PRELOAD）和 AI 加速配置（AI）都写 `system ai_turbo_enabled`。
        // 备份只关心"这个键现在是什么值"，与哪个功能写的无关，所以一条就够；
        // 留着两条会让备份文件里同一条出现两次，看的人以为有两个键。
        // 删重时保留排在前的那条（按表名、键名排序后稳定），不影响写哪几个键。
        (touchKeys() + vivoKeys() + directKeys())
            .sortedWith(compareBy({ it.table }, { it.key }))
            .distinctBy { "${it.table} ${it.key}" }
    }

    /** 一个键的唯一标识，恢复时按它在 [all] 里反查。 */
    private fun OwnedKey.id(): String = "$table $key"

    /**
     * 备份文件的一行：`表 <TAB> 键 <TAB> 值`。值是空串表示这个键在本机不存在。
     *
     * 用 TAB 分隔，而且**值是最后一段、整段保留**：settings 的值里可能同时出现逗号、
     * 冒号、等号和引号（`vts_game_para_adjust` 是 `1,6,6,6`，
     * `vivo_touchscreen_back_gesture` 是一整段 JSON），但不会出现 TAB。
     * 所以解析只按前两个 TAB 切，之后全部算值。
     */
    fun encodeLine(owned: OwnedKey, value: String?): String =
        "${owned.table}\t${owned.key}\t${value.orEmpty()}"

    /**
     * 解析备份文件的一行。**认不出的一律忽略。**
     *
     * 这一条是安全边界：备份文件可能被手改过、可能来自别的机型、可能是别的 App 生成的。
     * 只有能在 [all] 里对上的键才允许参与恢复，其它行当不存在。
     * 拥有这个键的功能名从登记表现取，不从文件读 —— 文件里写什么都不影响写哪几个键。
     */
    fun decodeLine(line: String): Pair<OwnedKey, String>? {
        val trimmed = line.trimEnd('\n', '\r')
        if (trimmed.isBlank()) return null
        // 按前两个 TAB 切，值整段保留 —— 值里万一有 TAB 也不丢。
        val first = trimmed.indexOf('\t')
        if (first <= 0) return null
        val second = trimmed.indexOf('\t', first + 1)
        if (second < 0) return null
        val table = trimmed.substring(0, first).trim()
        val key = trimmed.substring(first + 1, second).trim()
        val value = trimmed.substring(second + 1)
        if (key.isBlank()) return null
        val owned = all.firstOrNull { it.table == table && it.key == key } ?: return null
        return owned to value
    }
}
