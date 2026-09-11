package magicau.mtkcontroller.data

import android.content.Context
import androidx.annotation.StringRes

/**
 * 一条资源 + 它的参数 —— 数据层给上层的**结论说明**。
 *
 * ## 为什么要有这个类型
 *
 * 控制层过去把结论写成中文 `String`（`Outcome(false, "未检测到 CPU 簇")`）。
 * 那**不是偷懒，是当时唯一的写法**：`CpuControl`、`GpuTuner`、`PerfLockController`
 * 都拿不到 `Context`，没有 `getString` 可用，要产出一句人能读的话只剩字面量。
 *
 * 代价是文案散在代码里：既进不了 `res/values/strings.xml`，也管不了措辞，
 * 检查器还看不见（它不在 `Text(` 附近）。这个类型把「哪一条资源、填什么参数」
 * 和「解析成什么字符串」分开 —— 控制层只给前者，展示边界做后者。
 *
 * ## 嵌套
 *
 * 参数里可以再放 [Message]，[resolve] 递归解析。这是为了下面这种拼接：
 *
 * ```kotlin
 * Outcome(false, Message(R.string.release_failed, listOf(released.message)))
 * // strings.xml: 无法释放上一次请求:%1$s
 * ```
 *
 * 过去写成 `"无法释放上一次请求:${released.message}"`，两段中文焊死在一个字面量里，
 * 译文里没法调语序。拆成模板 + 参数之后，语序由译文决定。
 *
 * ## 界面层
 *
 * Compose 里用 `Message.text()`（见 `ui/MessageText.kt`）—— 它走 `stringResource`，
 * 语言切换时会重组；非 Compose 的展示边界与日志用 [resolve]。
 */
data class Message(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
) {
    /** 非 Compose 展示边界与日志用。参数里的 [Message] 递归解析。 */
    fun resolve(context: Context): String {
        if (args.isEmpty()) return context.getString(res)
        val resolved = args.map { if (it is Message) it.resolve(context) else it }
        return context.getString(res, *resolved.toTypedArray())
    }

    /**
     * **刻意不给出可读文本。**
     *
     * Kotlin 的字符串模板接受 `Any?`，所以 `"…${outcome.message}"` 这种写法
     * 编译器**不会报错**，会悄悄把 `Message` 的 `toString()` 拼进日志或界面 ——
     * 那是最难发现的一类回归。返回一个显眼的标记，让漏掉的地方在日志和界面上
     * 直接露出来，而不是产出一串看不出对错的 `Message(res=2131755008, args=[])`。
     *
     * 要文本请用 [resolve]、`Message.text()` 或 `AppLog.text`。
     */
    override fun toString(): String = "[未解析的文案 #$res]"
}
