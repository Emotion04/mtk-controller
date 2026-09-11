package magicau.mtkcontroller.data.tweaks

import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.exec.DaemonExec

/**
 * SurfaceFlinger 相位偏移：让 App 动笔的时刻相对 vsync 节拍器提前或延后多少。
 *
 * **它是个时序微调，不是"越快越好"的开关。** 调对了 App 能赶上这一帧；调错了反而赶不上，
 * 直接掉帧。而且 `getprop` 读到的值不等于生效 —— 只有掉不掉帧能告诉你，没有客观读数。
 *
 * 原值一旦被写 0 覆盖就找不回来，所以每个键都先 `getprop` 存原值再写，恢复时写回原值
 * 而不是写 0。
 *
 * `debug.` 前缀的属性**不跨重启保留**（只有 `persist.` 才保留），所以"本次开机有效"；
 * 这同时也意味着重启本身就是恢复。
 * 各家的 SurfaceFlinger 可能改过或删掉这些属性，写了不报错也不做事 —— 写后必须 getprop
 * 回读，读不回就是本机不认。
 *
 * **读写作必须经 [DaemonExec] 送到 Shizuku 用户服务里跑**：`setprop` 只接受 shell/root，
 * 在 App 进程里调必然失败，所以早先这个功能整个是失效的。
 */
object PhaseOffset {

    data class Key(val prop: String, @StringRes val labelRes: Int, val maxNs: Long)

    val keys = listOf(
        Key("debug.sf.early_phase_offset_ns", R.string.phase_offset_global, 6_000_000),
        Key("debug.sf.early_app_phase_offset_ns", R.string.phase_offset_app, 6_000_000),
        Key("debug.sf.early_gl_phase_offset_ns", R.string.phase_offset_gl, 6_000_000),
        Key("debug.sf.high_fps_early_phase_offset_ns", R.string.phase_offset_high_fps, 6_100_000),
    )

    /** 所有键的当前值；读不到的记为 null，恢复时跳过而不是写空。 */
    suspend fun snapshot(): Map<String, String?> = keys.associate { it.prop to readProp(it.prop) }

    /** 读一个属性的当前值；null = 读不到（不是"值不存在"）。 */
    suspend fun readProp(prop: String): String? {
        val out = DaemonExec.runArgv(listOf("/system/bin/getprop", prop)) ?: return null
        if (out.exit != 0) return null
        // getprop 对未设置的属性返回空串。空串与"读不到"要分开：空串可以写，读不到不能猜。
        return out.stdout.trim()
    }

    private suspend fun write(prop: String, value: String): Boolean {
        val out = DaemonExec.runArgv(listOf("/system/bin/setprop", prop, value)) ?: return false
        if (out.exit != 0) return false
        return readProp(prop) == value
    }

    /**
     * 一次写操作的结果：**文案 + 成没成**。
     *
     * 以前 `set` 返回的是人可读的 `String`，调用方拿 `result.startsWith("OK")` 判成功 ——
     * 而这个函数**从来不返回以 "OK" 开头的串**，所以每一次成功的写入都被显示成红色错误。
     * 同类写法在 `restore` 那边是 `!report.contains("失败")`：拿中文子串当成功标记，
     * 改一次文案就静默失效。
     *
     * 文案本身是 [Message] 而不是 `String`：这里拿不到 `Context`，也不该拿。
     */
    data class Result(val message: Message, val success: Boolean)

    /** 逐项恢复里的一项。`prop` 是设备属性名（数据），由展示边界拼在前面。 */
    data class ItemResult(val prop: String, val message: Message, val success: Boolean)

    /** 写一个键。 */
    suspend fun set(prop: String, ns: Long): Result {
        val key = keys.firstOrNull { it.prop == prop }
            ?: return Result(Message(R.string.phase_unknown_key), false)
        if (ns !in 0..key.maxNs) {
            return Result(Message(R.string.phase_out_of_range, listOf(key.maxNs)), false)
        }
        val value = ns.toString()
        if (!write(prop, value)) {
            return Result(Message(R.string.phase_write_unconfirmed), false)
        }
        return Result(Message(R.string.phase_set_ok, listOf(prop, value)), true)
    }

    /**
     * 按快照逐项恢复，跳过读不到原值的键。
     *
     * "跳过"不算失败（没东西可恢复，不是恢复动作失败），所以 [ItemResult.success] 为 true ——
     * 这与改动前 `contains("失败")` 的判定一致，保留下来。
     */
    suspend fun restore(snapshot: Map<String, String?>): List<ItemResult> =
        snapshot.map { (prop, original) ->
            when {
                original == null ->
                    ItemResult(prop, Message(R.string.phase_restore_skipped), true)
                write(prop, original) ->
                    ItemResult(prop, Message(R.string.phase_restore_ok, listOf(original)), true)
                else ->
                    ItemResult(prop, Message(R.string.phase_restore_failed, listOf(original)), false)
            }
        }
}
