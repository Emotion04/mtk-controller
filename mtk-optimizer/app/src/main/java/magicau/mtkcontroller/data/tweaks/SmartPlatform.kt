package magicau.mtkcontroller.data.tweaks

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 一个"候选能力"：这台机器上有这么些键和服务，但没接进控制。
 *
 * [id] 是稳定标识，界面按它分岔；**不按显示文本比较** —— 那类比较在文案一改就失效，
 * 而且编译期发现不了。
 * [titleRes] / [noteRes] 是给用户看的名字与说明：`data/` 不产出中文（见 `docs/dev/wording.md`）。
 */
data class SmartEngine(
    val id: String,
    val titleRes: Int,
    val keys: List<String>,
    val services: List<String>,
    val noteRes: Int,
)

object SmartPlatform {
    private const val DUMP_LIMIT = 16384
    val engines = listOf(
        SmartEngine("ai", R.string.smart_engine_ai, listOf("system ai_turbo_enabled", "global ai_turbo_enabled", "secure vivo_aie_privacy_switch"), listOf("aiengine_server"), R.string.smart_engine_ai_note),
        SmartEngine("compile", R.string.smart_engine_compile, emptyList(), emptyList(), R.string.smart_engine_compile_note),
        SmartEngine("core", R.string.smart_engine_core, listOf("system cpu_boost_mode", "system gpu_performance_level", "system app_launch_boost_enable", "global app_launch_boost_enable"), listOf("rms", "sps", "vboost", "vendor.vivoperfservice"), R.string.smart_engine_core_note),
        SmartEngine("network", R.string.smart_engine_network, listOf("system network_boost_enabled", "system game_network_boost"), listOf("netboosterd"), R.string.smart_engine_network_note),
        SmartEngine("snap", R.string.smart_engine_snap, emptyList(), emptyList(), R.string.smart_engine_snap_note),
        SmartEngine("game", R.string.smart_engine_game, listOf("global game_acceleration_enabled", "system game_accelerator_level", "secure vivo_gamecube_ai_strategy"), listOf("gamewatch_server"), R.string.smart_engine_game_note),
        SmartEngine("cooling", R.string.smart_engine_cooling, listOf("system vivo_gamethermal_switch"), emptyList(), R.string.smart_engine_cooling_note),
        SmartEngine("animation", R.string.smart_engine_animation, listOf("system turbo_cpu_gpu_animation", "system touch_boost_enabled"), emptyList(), R.string.smart_engine_animation_note),
        SmartEngine("render", R.string.smart_engine_render, listOf("system compositing_rendering_accelerator", "system render_accelerator", "system rendering_accelerator", "system gpu_rendering_acceleration", "system graphics_pre_render", "system vivo_graphics_pre_rendering"), listOf("vivo_gfx_display_service"), R.string.smart_engine_render_note),
        SmartEngine("vsync", R.string.smart_engine_vsync, listOf("system vsync_frame_sync", "system vsync_touch_response"), emptyList(), R.string.smart_engine_vsync_note),
        SmartEngine("vgpu", R.string.smart_engine_vgpu, listOf("system vgpu_enable_status"), listOf("vivo_display_enhance"), R.string.smart_engine_vgpu_note),
        // 这几个从"一键开关包"里移出来：语义没验证过，而空值写进去会把设备名清空、
        // 把状态栏图标黑名单清空。只读展示，让用户自己决定要不要在别处改。
        SmartEngine("candidates", R.string.smart_engine_candidates, listOf(
            "system flash_charge_animation_style",
            "secure vivo_qs_center_theme_style",
            "system device_name",
            "secure icon_blacklist",
        ), emptyList(), R.string.smart_engine_candidates_note),
    )
    val keys = engines.flatMap { it.keys }.distinct()
    val services = engines.flatMap { it.services }.distinct()


    fun parse(raw: String?): Map<String, String> {
        if (raw == null || !raw.trimEnd().endsWith("[exit] 0") || raw.contains("[stderr]")) return emptyMap()
        val body = raw.substringBeforeLast("[exit]")
        return body.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size != 4 || parts[2] != "0") return@mapNotNull null
            val index = parts[1].toIntOrNull() ?: return@mapNotNull null
            val name = when (parts[0]) {
                "K" -> keys.getOrNull(index)
                "S" -> services.getOrNull(index)
                else -> null
            } ?: return@mapNotNull null
            name to parts[3].take(160)
        }.toMap()
    }

    suspend fun inspect(): Map<String, String> {
        // 固定白名单，只读；每项 1 秒、整批 30 秒，失败不补写或重试。
        val commands = keys.mapIndexed { i, key ->
            "v=\$(timeout 1 settings get $key 2>/dev/null); r=\$?; printf 'K|$i|%s|%s\\n' \"\$r\" \"\$v\""
        } + services.mapIndexed { i, service ->
            "v=\$(timeout 1 service check $service 2>/dev/null); r=\$?; printf 'S|$i|%s|%s\\n' \"\$r\" \"\$v\""
        }
        return parse(PrivilegeManager.exec("timeout 30 sh -c ${VivoReply.quote(commands.joinToString("; "))}"))
    }

    fun dumpSummary(raw: String?): String {
        if (raw == null) return "未取得输出（连接失败）"
        val body = raw.substringBeforeLast("[exit]", raw).trimEnd()
        val complete = raw.trimEnd().endsWith("[exit] 0") && body.contains("[dump-exit]")
        return body.take(DUMP_LIMIT) + if (!complete || body.length > DUMP_LIMIT)
            "\n[摘要不完整：截断、超时或执行失败；不能据此判断权限足够]" else ""
    }

    suspend fun serviceReport(): String = buildString {
        appendLine("\n服务摘要（每项最多 16384 字节；仅查询，不代表控制权限）")
        for (service in services + "thermalservice") {
            // 状态标记放在被限流的管道内；标记缺失必须显式报告不完整。
            val command = "{ timeout 3 dumpsys $service 2>&1; printf '\\n[dump-exit] %s\\n' \$?; } | head -c ${DUMP_LIMIT + 1}"
            val raw = PrivilegeManager.exec("timeout 5 sh -c ${VivoReply.quote(command)}")
            appendLine("\n[$service]\n${dumpSummary(raw)}")
        }
    }

    /** 导出的只读报告。报告正文是固定格式的字段名与设备原文，按数据处理；标题除外。 */
    fun report(context: android.content.Context, values: Map<String, String>): String = buildString {
        appendLine("智算平台只读检测 · ${java.time.Instant.now()}")
        appendLine("候选项来自本机分析，未证明适用于当前 ROM。键存在/服务在线不等于引擎生效。")
        engines.forEach { engine ->
            appendLine("\n${context.getString(engine.titleRes)}：${context.getString(engine.noteRes)}")
            (engine.keys + engine.services).forEach {
                appendLine("$it = ${values[it] ?: "未读到（失败、超时或未检测）"}")
            }
        }
    }
}
