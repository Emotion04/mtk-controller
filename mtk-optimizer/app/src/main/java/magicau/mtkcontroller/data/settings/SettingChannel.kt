package magicau.mtkcontroller.data.settings

import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.exec.DaemonExec


/**
 * settings 写入的唯一出口。
 *
 * ## 为什么需要它
 *
 * 真机实测（vivo PD2430A）：**`settings put` 与 `cmd settings put` 在这台 ROM 上退出码 0
 * 但不落库** —— system / secure / global 三张表、多个键一致，连一个已存在且有值的键都改不动
 * （`touch_scan_press_time_delay` 写 999 读回仍是 300）。
 *
 * 已定位到具体机制：`adb shell settings put` 走的是 `SettingsProvider.call()`
 * 自定义 shell 入口，`content call --method PUT_system` 同样写不进 —— **`call()` 入口被限制**；
 * 而标准 `ContentProvider.insert()` 是通的。所以不是"系统不认 settings put"，
 * 是 provider 的自定义入口被关、标准插入入口没关。
 *
 * `settings get` / `list` 一直正常，读不受影响。
 *
 * ## 用法
 *
 * - 直接执行：[put] / [delete]，内部做了 content insert 优先 + settings put 兜底
 * - 需要拼命令字符串时（批量命令集）：[putCommand]
 */
object SettingChannel {

    private const val CONTENT = "content insert --uri content://settings"

    /** 生成一条等效于 `settings put <ns> <key> <value>` 的命令字符串。 */
    fun putCommand(namespace: String, key: String, value: String): String =
        "$CONTENT/$namespace --bind name:s:${quote(key)} --bind value:s:${quote(value)}"

    /** 生成一条等效于 `settings delete <ns> <key>` 的命令字符串。 */
    fun deleteCommand(namespace: String, key: String): String {
        val escaped = key.replace("'", "'\\''")
        return "content delete --uri content://settings/$namespace --where \"name='$escaped'\""
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 一次读取的三种结果。**「没有这个键」和「读不到」必须分开** ——
     * 前者是设备事实，后者是"我们现在不知道"；把两者都折成 null，导出备份时
     * 会写成"键不存在"，之后再恢复就会把用户的键删掉。
     */
    sealed interface ReadResult {
        /** 读到了，[text] 是设备上的值（可能是空串）。 */
        data class Value(val text: String) : ReadResult
        /** 设备上确实没有这个键。 */
        data object Absent : ReadResult
        /** 读不到：服务没连、命令失败。**不等于没有这个键。** */
        data object Unreadable : ReadResult
    }

    suspend fun read(namespace: String, key: String): ReadResult {
        val out = DaemonExec.runArgv(listOf("/system/bin/settings", "get", namespace, key))
            ?: return ReadResult.Unreadable
        if (out.exit != 0) return ReadResult.Unreadable
        val text = out.stdout.trim()
        // `settings get` 对不存在的键打印字面量 "null"。
        return if (text == "null") ReadResult.Absent else ReadResult.Value(text)
    }

    /** @return 读回的值；不存在或读不到都返回 null。需要区分时用 [read]。 */
    suspend fun get(namespace: String, key: String): String? = when (val result = read(namespace, key)) {
        is ReadResult.Value -> result.text
        else -> null
    }

    /**
     * 写一个值，然后读回确认。
     *
     * 先试 `content insert`（这台 ROM 上唯一有效的路径），失败再兜底 `settings put`
     * （别的机型上可能反而是它通）。
     *
     * @return 读回值等于所写值才返回 true。**不读回就等于把静默失败当成成功。**
     */
    suspend fun put(namespace: String, key: String, value: String): Boolean {
        val inserted = DaemonExec.runArgv(
            listOf("/system/bin/sh", "-c", putCommand(namespace, key, value)))
        if (inserted == null) {
            AppLog.w("SettingChannel", "$namespace $key content insert 未能执行（服务未连接？）")
            return false
        }
        if (inserted.exit != 0) {
            // 兜底：别的机型上可能是 settings put 通、content insert 不通。
            val put = DaemonExec.runArgv(
                listOf("/system/bin/sh", "-c", "timeout 5 settings put $namespace $key ${quote(value)}"))
            if (put == null || put.exit != 0) {
                AppLog.w("SettingChannel", "$namespace $key 两条路径都失败：insert=${inserted.stderr} put=${put?.stderr}")
                return false
            }
        }
        val readback = get(namespace, key)
        val ok = readback == value
        if (!ok) {
            AppLog.w(
                "SettingChannel",
                "$namespace $key 写入未确认：请求=$value 读回=${readback ?: "不可读"}",
            )
        }
        return ok
    }

    /** 删一个键并读回确认已不存在。 */
    suspend fun delete(namespace: String, key: String): Boolean {
        val deleted = DaemonExec.runArgv(
            listOf("/system/bin/sh", "-c", deleteCommand(namespace, key)))
        if (deleted == null || deleted.exit != 0) {
            val fallback = DaemonExec.runArgv(
                listOf("/system/bin/sh", "-c", "timeout 5 settings delete $namespace $key"))
            if (fallback == null || fallback.exit != 0) {
                AppLog.w("SettingChannel", "$namespace $key 删除失败")
                return false
            }
        }
        val readback = get(namespace, key)
        return readback == null || readback == "null"
    }
}
