package magicau.mtkcontroller.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 本软件改过的系统键的原值。**全项目只有这一份。**
 *
 * ## 为什么要有它
 *
 * 「打开前存原值、关闭时写回」这条规则原来被实现了三遍：触控子项存在
 * `touch_originals`，厂商功能存在 `vivo_tweak_baselines`，游戏加速又有一套自己的存活标记。
 * 三处存的东西、判断规则完全一样，而这个仓库的规矩是同一规矩只写一遍。
 *
 * 规则本身只有三条，都在这里：
 *
 * 1. **第一次写入前取一次，之后不再取。** 第二次读到的已经是自己写的值，
 *    拿它当原值就永远回不到真原值。
 * 2. **「原键不存在」要单独记。** 值记成 `null`，恢复时删键而不是写 0 ——
 *    写 0 会在系统里凭空建一个原本没有的键。
 * 3. **恢复成功后释放那条记录。** 还留着的要么是等重试的，要么是功能还开着。
 *
 * ## 存储
 *
 * DataStore 持久化，跨进程重启保留，只在卸载或清除数据时丢失。
 * 键空间按功能分（[TOUCH] / [VIVO]），同一台机器上不同功能不会互相覆盖。
 * **它记的是本机原值，不是用户的选择** —— 所以不进导出备份：
 * 一台机器的原值导到另一台机器上，指的不是同一个东西。
 */
class RecoverySnapshot(
    private val store: Flow<Map<String, String>>,
    private val write: suspend (Map<String, String>) -> Unit,
) {

    companion object {
        /** 触控子项。条目名是 `itemId`，键名是 `命名空间.键名`。 */
        const val TOUCH = "touch"

        /** 厂商功能。条目名是特性名，键名同上。 */
        const val VIVO = "vivo"

        /**
         * 直接写、没有独立数据定义的系统开关（旋转建议、自动旋转、插帧）。
         *
         * 条目名就是键名。以后新增同类开关，只要**写之前**调一次
         * `RecoverySnapshot.capture(DIRECT, 键名, …)`，恢复默认就能自动覆盖到它。
         */
        const val DIRECT = "direct"

        /** 一条记录在存储里的名字。 */
        fun token(space: String, entry: String, key: String): String = "$space|$entry|$key"

        /** 「原键不存在」，和「值为空串」是两件事。 */
        private const val ABSENT = "\u0000absent"
    }

    /** 这条记录存的值；`null` 表示没取过快照，或原键当时不存在。 */
    suspend fun original(space: String, entry: String, key: String): String? =
        store.first()[token(space, entry, key)]?.takeIf { it != ABSENT }

    /** 原键在取快照时是否存在。没取过快照时返回 false。 */
    suspend fun isPresent(space: String, entry: String, key: String): Boolean {
        val raw = store.first()[token(space, entry, key)] ?: return false
        return raw != ABSENT
    }

    /** 这个条目有没有取过快照。 */
    suspend fun hasEntry(space: String, entry: String): Boolean =
        store.first().keys.any { it.startsWith("$space|$entry|") }

    /**
     * 为这些键取快照，**已有的记录不动**。
     *
     * @param read 读一个键当前的值；`null` 表示这个键现在不存在。
     * @return 这次新记录的条数。0 说明全都存过了。
     */
    suspend fun capture(
        space: String,
        entry: String,
        keys: Collection<String>,
        read: suspend (String) -> String?,
    ): Int {
        val current = store.first().toMutableMap()
        val fresh = mutableMapOf<String, String>()
        for (key in keys) {
            val token = token(space, entry, key)
            if (token in current) continue
            // 读不到也记：跳过会让下次再取一次，那时读到的可能已经是自己写的值。
            fresh[token] = read(key) ?: ABSENT
        }
        if (fresh.isEmpty()) return 0
        write(current + fresh)
        return fresh.size
    }

    /**
     * 恢复成功后释放这些记录。
     *
     * @return 删掉的条数。为 0 说明这个条目本来就没记录（例如只读项从没被打开过）。
     */
    suspend fun release(space: String, entry: String, keys: Collection<String>): Int {
        val prefix = "$space|$entry|"
        val current = store.first()
        val prefixKeys = keys.map { prefix + it }.toSet()
        val kept = current.filterKeys { it !in prefixKeys }
        if (kept.size == current.size) return 0
        write(kept)
        return current.size - kept.size
    }
}
