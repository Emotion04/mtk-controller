package magicau.mtkcontroller.data.tweaks

/**
 * 逐项写入，一项失败不跳过后续。
 *
 * 返回值分成两份是必须的：**哪几项失败**是给人看的结论（进 `Outcome.failures`），
 * **服务端原话**是给人判断"该去哪儿修"的（进 `Outcome.detail`）。
 * 曾经这里只有一份 `List<String>` 键名，于是异常被 `catch (_: Exception) { false }`
 * 整个吞掉 —— 写入是被拒、服务没连上、还是键名不合法，界面上完全看不出来，
 * 点"显示更多"也没有东西可看。现在 writer 直接把原话交出来。
 */
object SettingBatch {

    class Result(
        /** 没写成的键名，供摘要用。 */
        val failures: List<String>,
        /** 每一项失败对应的原话，与 [failures] 同序；供"显示更多"用。 */
        val reasons: List<String>,
    ) {
        val ok: Boolean get() = failures.isEmpty()
    }

    /**
     * @param writer 返回 null 表示写成了；返回非 null 表示没写成，内容就是原话。
     */
    suspend fun write(
        values: Map<String, String>,
        writer: suspend (String, String) -> String?,
    ): Result {
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        for ((key, value) in values) {
            val reason = try { writer(key, value) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
            if (reason != null) {
                failures += key
                reasons += "$key → $reason"
            }
        }
        return Result(failures, reasons)
    }
}
