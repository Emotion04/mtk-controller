package magicau.mtkcontroller.data.tweaks

import android.content.Context
import android.os.Build
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.settings.SettingChannel

/**
 * 功能逐个试用：一次只开一项，由人判断有没有变化，结果记下来并可导出。
 *
 * ## 为什么需要它
 *
 * 大批功能"写进去了但不知道有没有用"：`settings` 键读回永远等于写入值，`service call`
 * 退出码 0 也可能是空操作。**没有任何自动探针能回答"系统理不理睬"** —— 只有人能。
 * 于是适配新机型只能一项一项手动试，而这个过程以前不留痕，下次换机型重来一遍。
 *
 * ## 导出给谁看
 *
 * 导出的是**自给自足的文本**：设备信息、每个键在不在/当前值、门禁结果、每项的试用判定。
 * 拿到它就能直接开发新机型的适配，不需要再问"你手机上那个键是多少"。
 */
object FeatureTrial {

    enum class Verdict {
        /** 还没判定 */
        PENDING,
        /** 观察到预期变化 */
        WORKED,
        /** 写了，什么都没发生 */
        NO_EFFECT,
        /** 看不出 */
        UNSURE,
        /** 本机不支持（门禁没过） */
        UNSUPPORTED,
    }

    data class Trial(
        val feature: String,
        val label: String,
        val keysBefore: Map<String, String?>,
        val keysAfter: Map<String, String?>,
        val writeOk: Boolean,
        val message: String,
        val gateReason: String?,
        val verdict: Verdict,
        val at: Long,
    )

    private const val PREFS = "feature_trial_journal"
    private const val KEY_JOURNAL = "journal"
    private const val LIMIT = 64_000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun history(context: Context): String =
        prefs(context).getString(KEY_JOURNAL, "").orEmpty()

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_JOURNAL).commit()
    }

    /**
     * 编码成一行。用 `unit separator` 分隔字段、`record separator` 分隔键值对 ——
     * 设置值里可能出现逗号、等号、换行，用常见分隔符会解析错。
     */
    /** 包内可见，供单元测试直接验编解码往返。 */
    internal fun encode(t: Trial): String = buildString {
        append(t.at).append('\u001f')
        append(t.feature).append('\u001f')
        append(t.label).append('\u001f')
        append(t.writeOk).append('\u001f')
        append(t.gateReason ?: "").append('\u001f')
        append(t.verdict.name).append('\u001f')
        append(t.keysBefore.entries.joinToString("\u001e") { "${it.key}=${it.value ?: "~null~"}" }).append('\u001f')
        append(t.keysAfter.entries.joinToString("\u001e") { "${it.key}=${it.value ?: "~null~"}" }).append('\u001f')
        append(t.message.replace('\n', ' '))
    }

    /**
     * 记录一次试用：读原值 → 执行写入 → 读新值 → 存档。
     *
     * 键值的采集放在这里而不是调用方，因为调用方（ViewModel）不该知道
     * "settings get" 的细节，而采集失败时必须留下痕迹。
     */
    suspend fun applyOnce(
        context: Context,
        vivo: VivoTweaks,
        feature: VivoFeature,
        gateReason: String?,
        option: String? = null,
    ): String {
        val before = readKeys(feature)
        val outcome = if (gateReason != null) {
            TweakRepository.Outcome(false, Message(R.string.trial_unsupported, listOf(gateReason)))
        } else {
            vivo.apply(feature, restore = false, option = option, force = false)
        }
        val after = readKeys(feature)
        record(
            context = context,
            feature = feature,
            keysBefore = before,
            keysAfter = after,
            writeOk = outcome.success,
            message = outcome.message.resolve(context),
            gateReason = gateReason,
        )
        return outcome.message.resolve(context)
    }

    private suspend fun readKeys(feature: VivoFeature): Map<String, String?> =
        feature.values.keys.associateWith { key ->
            SettingChannel.get(key.substringBefore(' '), key.substringAfter(' '))
        }

    /** 记录一次试用。已存在同一功能的旧记录则替换，避免列表无限增长。 */
    fun record(
        context: Context,
        feature: VivoFeature,
        keysBefore: Map<String, String?>,
        keysAfter: Map<String, String?>,
        writeOk: Boolean,
        message: String,
        gateReason: String?,
    ) {
        val verdict = if (gateReason != null) Verdict.UNSUPPORTED else Verdict.PENDING
        val trial = Trial(
            feature = feature.name,
            label = feature.name,
            keysBefore = keysBefore,
            keysAfter = keysAfter,
            writeOk = writeOk,
            message = message,
            gateReason = gateReason,
            verdict = verdict,
            at = System.currentTimeMillis(),
        )
        // 同一功能只保留最新一条：旧的判定对新一轮试用没有意义。
        val kept = history(context).lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { line -> line.split('\u001f').getOrNull(1) == feature.name }
            .joinToString("\n")
        prefs(context).edit().putString(KEY_JOURNAL, (kept + "\n" + encode(trial)).trimStart('\n').takeLast(LIMIT)).commit()
    }

    /** 给人判定用的：更新某一项的结论。 */
    fun setVerdict(context: Context, feature: VivoFeature, verdict: Verdict) {
        val lines = history(context).lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\u001f')
                if (parts.getOrNull(1) == feature.name) {
                    // 只换 verdict 那一列（下标 5），其余原样保留。
                    parts.toMutableList().also { if (it.size > 5) it[5] = verdict.name }.joinToString("\u001f")
                } else line
            }
            .joinToString("\n")
        prefs(context).edit().putString(KEY_JOURNAL, lines).commit()
    }

    fun trials(context: Context): List<Trial> =
        history(context).lineSequence().filter { it.isNotBlank() }.mapNotNull(::decode).toList()

    /** 包内可见，供单元测试直接验编解码往返。 */
    internal fun decode(line: String): Trial? {
        val p = line.split('\u001f')
        if (p.size < 9) return null
        fun pairs(raw: String) = raw.split('\u001e').filter { it.isNotBlank() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            k to if (v == "~null~") null else v
        }
        return Trial(
            at = p[0].toLongOrNull() ?: return null,
            feature = p[1],
            label = p[2],
            writeOk = p[3].toBooleanStrictOrNull() ?: false,
            gateReason = p[4].takeIf { it.isNotBlank() },
            verdict = runCatching { Verdict.valueOf(p[5]) }.getOrDefault(Verdict.PENDING),
            keysBefore = pairs(p[6]),
            keysAfter = pairs(p[7]),
            message = p[8],
        )
    }

    /**
     * 导出文本。**自给自足**：设备、ROM、每个键的真实状态、每项的判定都在里面，
     * 不需要再向使用者追问。
     */
    suspend fun report(context: Context): String = buildString {
        appendLine("# MTK God 功能试用报告")
        appendLine("# 生成时间: ${java.time.Instant.now()}")
        appendLine()
        section("设备")
        appendLine("manufacturer : ${Build.MANUFACTURER}")
        appendLine("model        : ${Build.MODEL}")
        appendLine("device       : ${Build.DEVICE}")
        appendLine("product      : ${Build.PRODUCT}")
        appendLine("hardware     : ${Build.HARDWARE}")
        appendLine("board        : ${Build.BOARD}")
        appendLine("soc_manu     : ${Build.SOC_MANUFACTURER}")
        appendLine("soc_model    : ${Build.SOC_MODEL}")
        appendLine("display      : ${Build.DISPLAY}")
        appendLine("fingerprint  : ${Build.FINGERPRINT}")
        appendLine("android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("patch        : ${Build.VERSION.SECURITY_PATCH}")
        appendLine("abis         : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("app          : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        section("键域实况")
        appendLine("# 这是适配新机型最有用的一节：本机 ROM 到底提供了哪些键。")
        appendLine("# 不存在 = 本机不支持该项；存在但值异常 = ROM 给了但语义可能不同。")
        appendLine()
        for (feature in VivoFeature.entries) {
            appendLine("-- ${feature.name}  门禁=${feature.gate ?: "仅按键判断"}")
            for ((key, wanted) in feature.values) {
                val current = SettingChannel.get(key.substringBefore(' '), key.substringAfter(' '))
                appendLine("   $key = ${current ?: "(不存在或读不到)"}   本软件写入值=${wanted.ifBlank { "(由用户填写)" }}")
            }
        }
        appendLine()
        section("能力门禁")
        appendLine("# 门禁判据：查包是否安装、prop 是否等于给定值、键是否存在。")
        for (feature in VivoFeature.entries) {
            val gate = feature.gate ?: continue
            appendLine("-- ${feature.name}")
            gate.packages.forEach { pkg ->
                val installed = runCatching {
                    context.packageManager.getPackageInfo(pkg, 0); true
                }.getOrDefault(false)
                appendLine("   package $pkg = ${if (installed) "已安装" else "未安装"}")
            }
            gate.props.forEach { (prop, want) ->
                appendLine("   prop $prop = ${probeProp(prop) ?: "(读不到)"}  需要=$want")
            }
        }
        appendLine()
        section("试用记录")
        val trials = trials(context)
        if (trials.isEmpty()) {
            appendLine("(还没有试用记录)")
        } else {
            trials.sortedBy { it.feature }.forEach { t ->
                appendLine("-- ${t.feature}  判定=${t.verdict.name}  写入=${if (t.writeOk) "成功" else "失败"}")
                appendLine("   试用时间: ${java.time.Instant.ofEpochMilli(t.at)}")
                t.gateReason?.let { appendLine("   门禁未过: $it") }
                t.keysBefore.forEach { (k, v) -> appendLine("   写前 $k = ${v ?: "(不存在)"}") }
                t.keysAfter.forEach { (k, v) -> appendLine("   写后 $k = ${v ?: "(不存在)"}") }
                appendLine("   软件返回: ${t.message}")
            }
        }
        appendLine()
        section("操作流水")
        appendLine(tweakJournal(context))
    }

    private suspend fun probeProp(prop: String): String? =
        VivoReply.output(PrivilegeManager.exec("getprop $prop"))?.takeIf { it.isNotBlank() }

    private fun tweakJournal(context: Context): String =
        context.getSharedPreferences("tweak_journal", Context.MODE_PRIVATE)
            .getString("history", "(空)").orEmpty()

    private fun StringBuilder.section(title: String) {
        appendLine("=".repeat(60))
        appendLine("== $title")
        appendLine("=".repeat(60))
    }
}
