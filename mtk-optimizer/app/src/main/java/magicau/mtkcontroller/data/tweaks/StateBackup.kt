package magicau.mtkcontroller.data.tweaks

import android.content.Context
import android.os.Build
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 系统状态备份：把本软件会写的那些键的当前值导出来，之后再按需写回去。
 *
 * ## 为什么需要它
 *
 * 每个功能的原值存在 App 的 DataStore 里（`VivoTweaks` 的快照、触控的 `touch_originals`）。
 * **App 一清除数据或卸载，这些原值全没，而系统里已经写进去的值还在** —— 这时候就再也
 * 恢复不回去了。而且首次写入时取快照有个天生弱点：如果第一次写入发生时值已经被别的东西
 * 改过（本项目的 `power_save_type 原值=6` 就是这么被污染的），App 会把改过的值当成原值。
 *
 * 导出到文件的备份扛得住卸载。**但它是一个时间点的快照，不是出厂值保证** ——
 * 系统会自行改动其中一部分键（实测 `power_save_type` 跟着性能档在 1 和 6 之间跳，
 * `disable_backpressure` 在无人写入时也从 1 变成过 0）。想知道哪些键不可信，
 * 用 [drift] 测，别靠猜：重启只对 `debug.*` 属性有效，settings 键跨重启、重启不碰。
 *
 * ## 恢复为什么必须逐条确认
 *
 * 恢复 = 用一份旧值覆盖系统当前值。系统自己也在改这些键：真机实测把档位写进去之后，
 * `power_save_type` 出现过 `requested=8 readback=6`（写进去又被框架打回）。
 * 所以"备份里的值"不等于"合法值"，整体覆盖可能把系统正在用的状态改坏。
 *
 * 于是 [restore] 的调用方必须先给用户看一张表（键 / 备份值 / 当前值 / 勾选框），
 * 只写勾过的。[diff] 把"当前值和备份不一样"的排在前面并标出来 ——
 * 那说明这期间有东西改过它，可能是系统，可能是用户手动改的，默认不勾。
 * 本对象绝不自动恢复，也没有"全选一键覆盖"这条路。
 */
object StateBackup {

    private const val MAGIC = "# MTK God 系统状态备份"

    /** 一行备份：键 + 备份里的值。值为 null = 这个键在本机不存在。 */
    data class Entry(val owned: OwnedKey, val value: String?)

    /** 一条待恢复项：备份值、当前值、以及是否默认勾选。 */
    data class RestoreItem(
        val owned: OwnedKey,
        val backupValue: String?,
        val currentValue: String?,
        /** 当前值和备份一致 → 写了也不会变，默认不勾。 */
        val unchanged: Boolean = currentValue == backupValue,
    )

    /**
     * 读出全部登记键的当前值。
     *
     * **读不到的键不写进备份**，而不是记成"不存在"：后者在恢复时会被当成
     * "删掉这个键"，于是一次在没授权时导出的备份，恢复时会删光所有键。
     * 调用方拿到 [Capture.unreadable] 后要告诉用户这次备份不完整。
     *
     * 读取需要 Shizuku —— 走的是用户服务里的 `/system/bin/settings`。
     */
    data class Capture(val entries: List<Entry>, val unreadable: Int)

    suspend fun capture(): Capture {
        val entries = mutableListOf<Entry>()
        var unreadable = 0
        for (owned in OwnedKeys.all) {
            when (val result = SettingChannel.read(owned.table, owned.key)) {
                is SettingChannel.ReadResult.Value -> entries += Entry(owned, result.text)
                SettingChannel.ReadResult.Absent -> entries += Entry(owned, null)
                SettingChannel.ReadResult.Unreadable -> unreadable++
            }
        }
        return Capture(entries, unreadable)
    }

    /**
     * 编码成可导出的文本。按 `OwnedKeys.all` 的顺序，按功能名分块，人能读。
     *
     * @param labelOf 资源 ID → 中文功能名。由调用方传进来（它手里有 Context），
     *   本对象不认 Context —— 这样编解码才能在单元测试里直接跑。
     * @param uptimeMinutes 本次开机已运行多少分钟。判断这份备份可不可信的少数客观信号之一，
     *   由调用方传入而不是在这里读 `SystemClock`：那个调用在单元测试里不会被 mock。
     */
    fun encode(
        entries: List<Entry>,
        labelOf: (Int) -> String,
        uptimeMinutes: Long,
        at: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine(MAGIC)
        appendLine("# 生成时间 ${java.time.Instant.ofEpochMilli(at)}")
        appendLine("# 设备 ${Build.MANUFACTURER} ${Build.MODEL} ${Build.DISPLAY}")
        appendLine("# 软件 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // 开机时长是判断这份备份可不可信的少数客观信号之一：
        // `debug.*` 属性不跨重启，开机越短那一层越接近出厂态；但 settings 键跨重启，
        // 开机时长对它们毫无意义。两个一起看，别只看一个就下结论。
        appendLine("# 本次开机已运行 $uptimeMinutes 分钟")
        appendLine("# 这是一个时间点的快照，不等于出厂值。系统会自行改动其中一部分键。")
        appendLine("# 只包含本软件会写的键。空值 = 该键在本机不存在。")
        appendLine("# 格式：表 <TAB> 键 <TAB> 值。只认登记表里的键，别的行会被忽略。")
        appendLine()
        var lastOwner = -1
        for (entry in entries) {
            if (entry.owned.ownerRes != lastOwner) {
                if (lastOwner != -1) appendLine()
                appendLine("# ${labelOf(entry.owned.ownerRes)}")
                lastOwner = entry.owned.ownerRes
            }
            appendLine(OwnedKeys.encodeLine(entry.owned, entry.value))
        }
    }

    /**
     * 解析导入的文本。**认不出的行一律忽略。**
     *
     * 文件可能来自别的机型、被手改过、或由别的工具生成。只有能在登记表里对上的键
     * 才会出现在结果里，其它行当不存在。所以导入一份来路不明的备份不会让本软件
     * 去写它没登记过的键。
     */
    fun decode(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        if (line.startsWith("#")) return@mapNotNull null
        OwnedKeys.decodeLine(line)?.let { (owned, value) ->
            Entry(owned, value.ifEmpty { null })
        }
    }.toList()

    /**
     * 比对备份与当前值，产出待恢复清单。当前值和备份不一样的排在前面。
     *
     * 这一步只读不写。调用方拿它渲染确认表，用户勾完再调 [restore]。
     */
    suspend fun diff(backup: List<Entry>): List<RestoreItem> {
        val byId = backup.associateBy { "${it.owned.table} ${it.owned.key}" }
        return OwnedKeys.all.map { owned ->
            val id = "${owned.table} ${owned.key}"
            RestoreItem(
                owned = owned,
                backupValue = byId[id]?.value,
                currentValue = SettingChannel.get(owned.table, owned.key)?.trim(),
            )
        }.sortedByDescending { !it.unchanged }
    }

    /**
     * 写入选中的项，并回读核对。
     *
     * **只写调用方传进来的这些。** 不提供"全部恢复"入口 —— 要恢复全部，
     * 调用方必须把每一项都显式列出并让用户看过。
     *
     * 备份值为 null（该键当时不存在）时删除该键，而不是写空串：写空串会留下一个
     * 值为空的键，`settings get` 读到空串而不是 null，之后再也分不清它本来是否存在。
     */
    suspend fun restore(items: List<RestoreItem>): Pair<Int, List<String>> {
        if (items.isEmpty()) return 0 to emptyList()
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return 0 to listOf("需要先授权 Shizuku")
        }
        var ok = 0
        val failures = mutableListOf<String>()
        for (item in items) {
            val target = item.backupValue
            val success = if (target.isNullOrEmpty()) {
                SettingChannel.delete(item.owned.table, item.owned.key)
            } else {
                SettingChannel.put(item.owned.table, item.owned.key, target) &&
                    SettingChannel.get(item.owned.table, item.owned.key)?.trim() == target
            }
            if (success) ok++ else failures += item.owned.key
        }
        return ok to failures
    }

    /**
     * 一次采样：登记键 + 触控 prop 当时的值。
     *
     * prop 必须一起采。早先只采 settings 键，于是五个触控子项里有四个只写 prop 的
     * 在监测里完全隐形 —— 用户开了它们，监测报"没有任何改动"，而 prop 其实已经变了。
     * 那不是"没变化"，是"没在看"。
     *
     * prop 走 `getprop`，读不到记空串（prop 读不到和 settings 的 "null" 是两回事，
     * 这里统一成空串，比的是前后两次，绝对值是什么不重要）。
     */
    suspend fun sample(): Map<String, String?> = buildMap {
        OwnedKeys.all.forEach { put("${it.table} ${it.key}", SettingChannel.get(it.table, it.key)?.trim()) }
        OwnedKeys.propIds.forEach { id ->
            // 必须剥掉用户服务的输出包装。`PrivilegeManager.exec` 返回的是
            // `<stdout>\n[exit] <code>` 原样串，不剥的话每个 prop 值都拖着
            // 一行 `[exit] 0`，界面上就成了"true\n[exit] 0 → false"这种乱东西。
            val raw = PrivilegeManager.exec("getprop ${id.removePrefix("prop ")}")
            put(id, raw?.substringBefore("\n[exit] ")?.trim().orEmpty())
        }
    }

    /**
     * 比对两次采样，返回被动过的键。
     *
     * **为什么需要它**：没法事先知道一份备份干不干净。系统会自行改动其中一部分键
     * （实测 `power_save_type` 跟着性能档 1↔6，`disable_backpressure` 无人写入也变过），
     * 而"重启后"只对 `debug.*` 属性有效，settings 键跨重启、重启不碰。
     *
     * 于是换个问法：不猜，测。两次采样之间本软件什么都没写、用户也没手动改，
     * 值却变了的键，就是外部在动它 —— 这些键的备份值不可信，恢复前必须对照当前值。
     *
     * @param first 开始监测时的采样
     * @param second 停止监测时的采样
     * @return 键 → (旧值, 新值)，按键名排好序
     */
    fun drift(first: Map<String, String?>, second: Map<String, String?>): List<Triple<String, String?, String?>> =
        first.keys.filter { first[it] != second[it] }
            .sorted()
            .map { Triple(it, first[it], second[it]) }

    /** 摘要行，导入成功后给人看一眼这份备份是什么来头。 */
    fun summary(text: String, context: Context): String {
        val entries = decode(text)
        val time = text.lineSequence().firstOrNull { it.startsWith("# 生成时间") }
            ?.removePrefix("# 生成时间")?.trim().orEmpty()
        val device = text.lineSequence().firstOrNull { it.startsWith("# 设备") }
            ?.removePrefix("# 设备")?.trim().orEmpty()
        val owners = entries.map { it.owned.ownerRes }.distinct().size
        return buildString {
            append(context.getString(R.string.tweaks_state_backup_keys, entries.size))
            if (owners > 0) append(context.getString(R.string.tweaks_state_backup_owners, owners))
            if (time.isNotEmpty()) append("\n" + context.getString(R.string.tweaks_state_backup_time, time))
            if (device.isNotEmpty()) append("\n" + context.getString(R.string.tweaks_state_backup_device, device))
        }
    }
}
