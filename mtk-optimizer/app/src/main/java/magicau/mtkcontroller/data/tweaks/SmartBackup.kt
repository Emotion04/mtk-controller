package magicau.mtkcontroller.data.tweaks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmartBackup(
    val schema: Int = 1,
    val kind: String = "mtk-smart-platform",
    val device: String,
    val rom: String,
    val capturedAt: Long,
    val values: Map<String, String>,
    val missing: List<String> = emptyList(),
    val source: String = "首次读取（不是出厂默认值）",
)

object SmartBackupCodec {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    const val MAX_BYTES = 128 * 1024
    const val AI_KEY = "system ai_turbo_enabled"
    fun validate(backup: SmartBackup): SmartBackup {
        require(backup.schema == 1 && backup.kind == "mtk-smart-platform") { "不支持的智算平台备份格式" }
        require(backup.device.isNotBlank() && backup.rom.isNotBlank() && backup.capturedAt > 0) { "设备或时间信息缺失" }
        require(backup.values.isNotEmpty() && backup.values.keys.all { it in SmartPlatform.keys }) { "备份包含未知配置或没有有效数据" }
        require(backup.values.values.all { it.length <= 160 && '\n' !in it && '\r' !in it }) { "配置值格式错误" }
        require(backup.missing.all { it in SmartPlatform.keys } && backup.missing.none { it in backup.values }) { "缺失项记录错误" }
        require(backup.values[AI_KEY] == null || backup.values[AI_KEY] in listOf("0", "1", "null")) { "AI 配置值不受支持" }
        return backup
    }
    fun decode(text: String): SmartBackup {
        require(text.toByteArray().size <= MAX_BYTES) { "备份文件过大" }
        return validate(json.decodeFromString<SmartBackup>(text))
    }
    fun encode(backup: SmartBackup): String = json.encodeToString(validate(backup))
    fun compatible(backup: SmartBackup, device: String, rom: String) = backup.device == device && backup.rom == rom
    fun restoreValue(backup: SmartBackup, device: String, rom: String): String {
        validate(backup)
        require(compatible(backup, device, rom)) { "机型或 ROM 与备份不一致，禁止恢复；可保留档案" }
        return backup.values[AI_KEY] ?: error("备份缺少 AI 配置，无法恢复")
    }
}

/** 保证写入前落盘；写入异常也尝试回滚，不将异常当成成功。 */
object SmartRestore {
    /**
     * 写目标值，失败就写回原值。返回 (写成了吗, 回滚成功了吗)。
     *
     * [write] 返回 null 表示写成，非 null 是没写成的原话 —— 与 [SettingBatch] 同一约定。
     */
    suspend fun run(target: String, read: suspend () -> String?, save: suspend (String) -> Unit,
                    write: suspend (String) -> String?): Pair<Boolean, Boolean> {
        val before = read() ?: error("无法读取当前配置，未写入")
        save(before)
        // 抛异常和"写失败"都不能算成写成了 —— runCatching 的 getOrNull 会把两者
        // 都变成 null，所以成功与否要单独看 isSuccess。
        val attempt = runCatching { write(target) }
        val ok = attempt.isSuccess && attempt.getOrNull() == null
        val rollback = runCatching { write(before) }
        val rolledBack = ok || (rollback.isSuccess && rollback.getOrNull() == null)
        return ok to rolledBack
    }
}
