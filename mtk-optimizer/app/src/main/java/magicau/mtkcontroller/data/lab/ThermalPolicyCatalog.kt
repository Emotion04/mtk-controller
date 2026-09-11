package magicau.mtkcontroller.data.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 一个可选的值，附带上它的来历。
 *
 * @param note 这个值是干什么的。**没有依据就写"未知"** —— 不许编。
 */
data class LabChoice(
    val value: Int,
    val label: String,
    val note: String,
)

/**
 * 温控策略索引的**真实**来源：`/vendor/etc/thermal/` 下有哪些
 * thermal_policy_NN.conf 文件。
 *
 * 以前这里写死 `range = 0..19`，是猜的：这台机器只有 13 份文件，
 * 滑杆却有 20 个位置，多出来 7 个是空号（拖上去 HAL 找不到文件，什么也不发生）。
 *
 * **这个类是实验室对 [magicau.mtkcontroller.data.thermal.ThermalPolicyReader] 的适配层，
 * 不是第二份实现。** 列目录曾经在这里各写了一遍，于是同一个 `/vendor/etc/thermal`
 * 有两种枚举代码、两种失败语义 —— Reader 用 `listOrNull` 把"没列成"和"没有文件"
 * 分开（`CpuViewModel` 靠这个区别报错），这里用 `list` 直接把 null 吞成空列表。
 * 现在只保留 Reader 那一份。
 *
 * **这个类只回答"设备上有哪些档位"这一个问题**，不回答"每一档是什么意思" ——
 * 那是分析结论，会随 ROM 版本变化，属于文档而不是代码。各档含义见
 * `docs/thermal/facts.md`；配置来源见 `docs/thermal/configs/README.md`。
 *
 * 曾经有一份随包的 `assets/thermal-policies.md` 讲各档含义，已删：
 * 它自己标着"可能已过时"，而设备上的 conf 现读现解才是不过时的那一份。
 */
object ThermalPolicyCatalog {

    /** 列目录，取出真实存在的索引。读不到就返回空，让调用方按"未知"处理。 */
    suspend fun indices(): List<Int> =
        magicau.mtkcontroller.data.thermal.ThermalPolicyReader.availableIndices()

    /**
     * 给界面用的选项表。**索引就是文件名里那个编号，不重新编号** ——
     * 这样 ROM 升级新增文件会自动出现，编号也和任何外部资料对得上。
     */
    suspend fun choices(): List<LabChoice> = indices().map { index ->
        LabChoice(
            value = index,
            label = index.toString().padStart(2, '0'),
            // 含义不进代码：只有编号是可验证的事实，"这一档干什么"会随 ROM 变。
            note = "",
        )
    }

    /**
     * 默认选中哪一档。
     *
     * 不写"某个索引最好" —— 那是实测结论，ROM 升级就可能失效。取
     * [PREFERRED] 里第一个设备上真有的，纯粹为了首屏不落在明显压低保的那一档。
     */
    val PREFERRED: List<Int> = listOf(1, 8, 9, 10, 11, 2, 3, 5, 7, 12, 13)

    fun initialIndex(available: List<Int>): Int =
        PREFERRED.firstOrNull { it in available } ?: available.firstOrNull() ?: 0

    /**
     * 设备上**最接近“放开 CPU 温控”**的那一档，没有合适的不返回。
     *
     * 不写死编号，也不看“首档频率”或目标温度 —— 真机配置里这两者都会骗人：
     * `thermal_policy_07` 首档 3.3 GHz，但 42°C 时只剩 2.0 GHz；
     * `00` 首档只有 2.6 GHz，42°C 却有 2.2 GHz。
     *
     * 判据直接取自 CPU 频率表：**在 40–50°C 区间取每个整数温度的允许频率，求平均**。
     * 平均值越高，这一档在常用温度区间里放得越松。完全没有 CPU 频率表段的
     * 排第一，有段但 `mode=disabled` 的排第二 —— 两者都不压频，但后者的段还在，
     * 固件是否始终遵守 disabled 没有运行时证据。
     *
     * 局限：不同策略可能用不同传感器（`ap_ntc` / `tz_shell` / `tz_game_shell`），
     * 它们的摄氏度不是同一个物理量；这里只做相对比较，不据此断言绝对温度。
     */
    suspend fun looseIndex(): Int? {
        val indices = magicau.mtkcontroller.data.thermal.ThermalPolicyReader.availableIndices()
        if (indices.isEmpty()) return null
        val policies = indices.mapNotNull { index ->
            runCatching { magicau.mtkcontroller.data.thermal.ThermalPolicyReader.read(index) }.getOrNull()
        }.filter { it.decodeError == null }
        return pickLoose(policies)?.index ?: indices.firstOrNull()
    }

    /**
     * 从已解开的策略里挑最宽松的一档。
     *
     * 比较是**分层的**，不是一个加权分数：
     *
     * 1. **完全没有** `[mtk-cpu-table-mapping]` 段的优先；
     * 2. 有段但 `mode=disabled`、或表为空 —— 温度不压 CPU，排第二；
     * 3. 有生效频率表的，比 40–50°C 的平均允许频率；
     * 4. 都没有生效表时，`isolate` 关闭的优先 —— 核心隔离也是限制手段；
     * 5. 再看目标结温、皮肤阈值，越高越宽松；
     * 6. 仍然并列就选编号小的。
     *
     * **为什么把“没有段”和“段被关闭”分开**：真机上 01 没有这一段，
     * 08/09 有段但 `mode=disabled`。两者温度都不压频，但关闭只是配置值，
     * 固件是否遵守没有运行时证据；而 01 的段根本不存在，更接近“没有这张表”。
     * 2026-10-22 起优先 01，避免用户看到 08 里那段 46°C 的表就以为它在生效。
     *
     * **不写死编号**：哪些档没有表、哪一档 isolate 关闭，都随 ROM 变。
     */
    fun pickLoose(policies: List<magicau.mtkcontroller.data.thermal.ThermalPolicy>):
        magicau.mtkcontroller.data.thermal.ThermalPolicy? =
        policies.maxWithOrNull { a, b -> compareRanks(rank(a), rank(b)) }

    /** 从低到高比较：越"宽松"的维度排在越后面越大。 */
    private fun compareRanks(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    private fun rank(p: magicau.mtkcontroller.data.thermal.ThermalPolicy): List<Int> {
        val hasLadder = p.cpuLadders.values.any { !it.isEmpty }
        // 2 = 连段都没有；1 = 有段但关闭/空；0 = 有生效表。
        val tableRank = when {
            p.cpuMappingEnabled == null && !hasLadder -> 2
            p.cpuMappingEnabled == false || !hasLadder -> 1
            else -> 0
        }
        val noActiveTable = tableRank > 0
        val avg = if (noActiveTable) Int.MAX_VALUE else (averageAllowedFreq(p) ?: Int.MIN_VALUE)
        return listOf(
            tableRank,
            avg,
            // isolate 关闭 = 少一层核心限制。
            if (p.isolateEnabled == false) 1 else 0,
            p.maxTargetTJC ?: 0,
            p.minTargetTJC ?: 0,
            p.skinThresholdsC.maxOrNull() ?: 0,
            // 取最大时，-index 越大代表编号越小 → 并列时优先小编号。
            -p.index,
        )
    }

    /**
     * 40–50°C 每 1°C 的允许频率（kHz）平均值；null = 这一档根本不压 CPU 频率。
     *
     * 拆出来是为了能单测，也为了让判据可读：它就是一个物理量，不是打分。
     */
    fun averageAllowedFreq(policy: magicau.mtkcontroller.data.thermal.ThermalPolicy): Int? {
        if (policy.cpuMappingEnabled == false) return null
        // 取硬件最高频率的那一簇（性能核）。没有频率表的策略同样视为不压 CPU。
        val ladder = policy.cpuLadders.values
            .filter { !it.isEmpty }
            .maxByOrNull { it.freqsMHz.maxOrNull() ?: Int.MIN_VALUE }
            ?: return null
        val pairs = ladder.thresholdsC.zip(ladder.freqsMHz).sortedBy { it.first }
        if (pairs.isEmpty()) return null
        var sum = 0L
        var n = 0
        for (tempC in 40..50) {
            val t = tempC * 1000
            var allowed = pairs.first().second
            for ((threshold, freq) in pairs) {
                if (threshold <= t) allowed = freq
            }
            sum += allowed
            n++
        }
        return (sum / n).toInt()
    }

    /**
     * 读当前生效的皮肤节流阈值，例如 "41/44/46/48/60/80 °C"。
     *
     * 来源：`dumpsys thermalservice` 的 `Temperature static thresholds from HAL:`
     * 里 `mName=SKIN` 那一行的 `mHotThrottlingThresholds`。
     * **这是唯一无 root 能看出"当前生效哪一组温控策略"的通道** ——
     * 阈值由当前加载的那份 conf 的 `[thermalhal-threshold]` 段决定，
     * 换策略它立刻变。
     *
     * 只读数值，不在这里做"这组阈值对应哪份文件"的判断 —— 那是会随 ROM
     * 变的结论，属于 `docs/thermal/facts.md` 而不是代码。
     */
    suspend fun skinThresholdText(): String? = withContext(Dispatchers.IO) {
        val out = PrivilegeManager.exec("dumpsys thermalservice") ?: return@withContext null
        val line = out.lineSequence().firstOrNull { "mName=SKIN" in it } ?: return@withContext null
        // 不用正则：Kotlin 普通字符串里 \[ 是非法转义，而这里只是取两个定界符之间的一段。
        val marker = "mHotThrottlingThresholds=["
        val from = line.indexOf(marker)
        if (from < 0) return@withContext null
        val bodyStart = from + marker.length
        val to = line.indexOf(']', bodyStart)
        if (to < 0) return@withContext null
        val hot = line.substring(bodyStart, to)
        val degrees = hot.split(',').mapNotNull { token ->
            token.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.toInt()
        }
        if (degrees.isEmpty()) null else degrees.joinToString("/") + " °C"
    }
}
