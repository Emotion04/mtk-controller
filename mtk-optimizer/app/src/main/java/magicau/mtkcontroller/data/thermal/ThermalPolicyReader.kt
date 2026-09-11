package magicau.mtkcontroller.data.thermal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.sysfs.Sysfs

/** 一簇/一路的降频梯：温度每上一档，频率落到对应那一行。 */
data class FreqLadder(
    val thresholdsC: List<Int>,
    val freqsMHz: List<Int>,
    /**
     * 这条梯子读的是哪一路温度。
     *
     * **不同档位会用完全不同的传感器** —— 真机上见过 `tz_game_shell` 与 `ap_ntc`
     * 两种，一路的 45°C 与另一路的 45°C 不是同一个物理量。展示温度时必须一起给出，
     * 否则读者会拿两档的摄氏度直接比较。
     */
    val sensor: String? = null,
) {
    val isEmpty: Boolean get() = thresholdsC.isEmpty() || freqsMHz.isEmpty()

    /**
     * 某一温度下这张表允许的最高频率。
     *
     * 配置是**阶梯**：温度超过某一档后沿用那一档的频率，直到下一档。
     * 表格/曲线都必须用这个函数取值，不能只看“这个温度有没有写在同一行” ——
     * 各核簇的阈值点并不相同，直接 indexOf 会把仍在生效的上一档显示成“—”。
     */
    fun freqAt(tempC: Int): Int? {
        if (isEmpty) return null
        val pairs = thresholdsC.zip(freqsMHz).sortedBy { it.first }
        var value = pairs.first().second
        for ((threshold, freq) in pairs) {
            if (threshold <= tempC) value = freq else break
        }
        return value
    }
}

/**
 * 刚从设备上读出来的一份温控策略。
 *
 * **不含任何预设的档位含义** —— 所有字段都是从 `/vendor/etc/thermal/`
 * 现读现解的。系统更新配置、换机型，这里就是当下的事实。
 */
data class ThermalPolicy(
    val index: Int,
    val decodeError: String? = null,
    val permanent: Boolean? = null,
    val sensor: String? = null,
    val cpuLadders: Map<Int, FreqLadder> = emptyMap(),
    val cpuMappingEnabled: Boolean? = null,
    val gpuLadder: FreqLadder? = null,
    val isolateEnabled: Boolean? = null,
    val isolateThresholdsC: List<Int> = emptyList(),
    val skinControlSensor: String? = null,
    /**
     * 温控闭环想稳住的温度，以及它允许的调整范围。
     *
     * 这是**闭环目标**，不是"超过就降频"的阈值 —— 三个值要一起看，
     * 单给 [steadyTargetTJC] 会被误读成一条限制线。
     */
    val steadyTargetTJC: Int? = null,
    val minTargetTJC: Int? = null,
    val maxTargetTJC: Int? = null,
    val skinLevels: List<String> = emptyList(),
    val skinThresholdsC: List<Int> = emptyList(),
    val chargerThresholdsC: List<Int> = emptyList(),
    val chargerCurrentsMA: List<Int> = emptyList(),
    val vtskinModes: Map<String, String> = emptyMap(),
)

/**
 * 现读现解 `/vendor/etc/thermal/thermal_policy_NN.conf`。
 *
 * 为什么不由代码预写每档的作用：那是会过期的结论 —— 系统更新配置或换机型后
 * 就成假话。这里只做"读出来、解开、结构化"这件事，含义交给界面按参数现场生成。
 */
object ThermalPolicyReader {

    private const val DIR = "/vendor/etc/thermal"
    private const val PREFIX = "thermal_policy_"
    private const val SUFFIX = ".conf"

    /** 真机上出现过的段名。只用于"解出来的像不像配置"，与任何档位的含义无关。 */
    private val KNOWN_SECTIONS = setOf(
        "policy_type", "ltf", "mtk-cpu-table-mapping", "mtk-gpu-table-mapping",
        "mtk-cpu-isolate", "mtk-skin-control", "thermalhal-threshold",
        "charger-cooler", "vtskin-param",
    )

    /** 设备上有哪些索引，按文件名列，不猜。 */
    suspend fun availableIndices(): List<Int> = availableIndicesOrNull() ?: emptyList()

    /**
     * 同 [availableIndices]，但**没列成目录时返回 null**。
     *
     * 空列表有两种完全不同的含义：目录列出来了、里面没有配置文件；或者压根没列成
     * （提权不可用、服务端抛异常）。界面必须能分开这两者 —— 报成"没有档位"会让
     * 用户以为这台机器就是如此，实际只是没读到。
     */
    suspend fun availableIndicesOrNull(): List<Int>? = withContext(Dispatchers.IO) {
        Sysfs.listOrNull(DIR)?.mapNotNull { name ->
            name.takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
                ?.removePrefix(PREFIX)?.removeSuffix(SUFFIX)?.toIntOrNull()
        }?.distinct()?.sorted()
    }

    /** 读并解析一份。解不开返回带 [ThermalPolicy.decodeError] 的对象。 */
    suspend fun read(index: Int): ThermalPolicy = withContext(Dispatchers.IO) {
        val path = "$DIR/$PREFIX${"%02d".format(index)}$SUFFIX"
        val raw = Sysfs.read(path)
        if (raw.isNullOrBlank()) {
            return@withContext ThermalPolicy(index, decodeError = "读不到文件")
        }
        val plain = ThermalConfDecoder.decode(raw) ?: run {
            return@withContext ThermalPolicy(index, decodeError = "解码失败，可能换过混淆方式")
        }
        // 结构校验：解出来的东西必须含已知段名，否则当解码失败。
        // 判据是**格式**（段名）而不是内容含义 —— 这样厂商改了配置也不会误判。
        val sections = plain.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") && it.endsWith("]") }
            .map { it.trim('[', ']').lowercase() }
            .toSet()
        if (sections.none { it in KNOWN_SECTIONS }) {
            return@withContext ThermalPolicy(index, decodeError = "解出来的内容不像温控配置，可能换过混淆方式")
        }
        parse(index, plain)
    }

    private fun parse(index: Int, plain: String): ThermalPolicy =
        ThermalPolicyParser.parse(index, plain)

    /** 从设备现读一份。 */
    suspend fun readPlain(index: Int): ThermalPolicy = read(index)
}

/**
 * 纯解析：明文 → [ThermalPolicy]。**不做任何 IO**，方便直接喂片段测试。
 *
 * 字段名全部对照真机明文（见 `docs/thermal/configs/field-reference.md`）。
 */
object ThermalPolicyParser {

    fun parse(index: Int, plain: String): ThermalPolicy {
        var permanent: Boolean? = null
        var ltfSensor: String? = null
        val cpu = mutableMapOf<Int, FreqLadder>()
        var cpuEnabled: Boolean? = null
        var gpu: FreqLadder? = null
        var isolateEnabled: Boolean? = null
        val isolateT = mutableListOf<Int>()
        var skinSensor: String? = null
        var steadyTJ: Int? = null
        val skinLevels = mutableListOf<String>()
        val skinT = mutableListOf<Int>()
        val chargeT = mutableListOf<Int>()
        val chargeC = mutableListOf<Int>()
        val vtskin = mutableMapOf<String, String>()

        // cpu / gpu 梯是"多行凑一条"，用这几个临时变量攒着
        var section = ""
        var ladderType = ""      // "cpu" or "gpu"
        var cluster = -1
        var thRaw = ""
        var freqRaw = ""
        var ladderSensor = ""
        var vtskinSensor = ""
        var minTargetTJ: Int? = null
        var maxTargetTJ: Int? = null

        fun flushLadder() {
            val th = intList(thRaw).map { it / 1000 }
            val fr = intList(freqRaw).map { it / 1000 }
            if (th.isNotEmpty() && fr.isNotEmpty()) {
                val ladder = FreqLadder(th, fr, ladderSensor.ifBlank { null })
                if (ladderType == "gpu") gpu = ladder
                else if (cluster >= 0) cpu[cluster] = ladder
            }
            cluster = -1; thRaw = ""; freqRaw = ""; ladderSensor = ""
        }

        for (raw in plain.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                flushLadder()
                section = line.trim('[', ']').lowercase()
                continue
            }
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=', "").trim()

            when (section) {
                "policy_type" -> if (key == "permanent") {
                    permanent = when (value.lowercase()) {
                        "yes" -> true
                        "no" -> false
                        else -> null
                    }
                }

                "ltf" -> if (key == "thermal_zone") ltfSensor = value

                "mtk-cpu-table-mapping", "mtk-gpu-table-mapping" -> {
                    ladderType = if (section.startsWith("mtk-gpu")) "gpu" else "cpu"
                    when (key) {
                        "mode" -> if (ladderType == "cpu") {
                            cpuEnabled = value.equals("enabled", true)
                        }
                        "cluster" -> { flushLadder(); cluster = value.toIntOrNull() ?: -1 }
                        "threshold" -> thRaw = value
                        "freq" -> freqRaw = value
                        // 每条梯子自己声明用哪路温度。**必须留着** —— 不同档位会用
                        // 完全不同的传感器（机身上的一路 vs 主板热敏电阻），
                        // 两者的摄氏度不可比。读表的人得知道这张表说的是哪一路。
                        "sensor" -> ladderSensor = value
                    }
                }

                "mtk-cpu-isolate" -> when (key) {
                    "mode" -> isolateEnabled = value.equals("enabled", true)
                    "threshold" -> isolateT += intList(value)
                }

                "mtk-skin-control" -> when (key) {
                    "sensor" -> skinSensor = value
                    // 这三个是一组：稳态目标是闭环要稳到的点，上下界是它允许的调整范围。
                    // 只显示稳态目标而不显示范围，用户会以为那是"超过就降频"的那条线。
                    "steady_target_tj" -> steadyTJ = value.toIntOrNull()
                    "min_target_tj" -> minTargetTJ = value.toIntOrNull()
                    "max_target_tj" -> maxTargetTJ = value.toIntOrNull()
                }

                "thermalhal-threshold" -> {
                    // type= SKIN 的那组 threshold 才反映当前策略；CPU/GPU/NPU 那些是 HAL 硬编码。
                    when (key) {
                        "type" -> if (!value.equals("SKIN", true)) section = ""
                        "level" -> skinLevels += stringList(value)
                        "threshold" -> skinT += intList(value)
                    }
                }

                "charger-cooler" -> when (key) {
                    "threshold" -> chargeT += intList(value)
                    "current" -> chargeC += intList(value)
                }

                "vtskin-param" -> when (key) {
                    "sensor" -> vtskinSensor = value
                    "mode" -> vtskin[vtskinSensor] = value
                }
            }
        }
        flushLadder()

        return ThermalPolicy(
            index = index,
            permanent = permanent,
            sensor = ltfSensor ?: skinSensor,
            cpuLadders = cpu,
            cpuMappingEnabled = cpuEnabled,
            gpuLadder = gpu,
            isolateEnabled = isolateEnabled,
            isolateThresholdsC = isolateT.map { it / 1000 },
            skinControlSensor = skinSensor,
            steadyTargetTJC = steadyTJ?.let { millisToC(it) },
            minTargetTJC = minTargetTJ?.let { millisToC(it) },
            maxTargetTJC = maxTargetTJ?.let { millisToC(it) },
            skinLevels = skinLevels,
            skinThresholdsC = skinT.map { it / 1000 },
            chargerThresholdsC = chargeT.map { it / 1000 },
            chargerCurrentsMA = chargeC,
            vtskinModes = vtskin,
        )
    }

    /** 毫摄氏 → 摄氏度，四舍五入。91500 是 91.5 °C，截断会变成 91。 */
    private fun millisToC(millis: Int): Int = Math.round(millis / 1000.0).toInt()

    private fun intList(raw: String): List<Int> =
        raw.trim('[', ']').split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun stringList(raw: String): List<String> =
        raw.trim('[', ']').split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
