package magicau.mtkcontroller.data

import android.content.Context
import magicau.mtkcontroller.data.log.AppLog

/**
 * 一条失败：**一句给人看的话** + **可选的原始错误**。
 *
 * ## 为什么必须是两个字段，不能是一个 String
 *
 * 过去各处把失败写成一个 `String`，于是同一句里既要照顾用户又要照顾排错：
 * 「档位列表没读到：XxxException。请确认已授权 Shizuku；刚更新过的话重启一次」——
 * 异常类名、授权检查、更新排障全挤在一行里，像把开发者的排障笔记直接搬上了界面。
 *
 * 拆开之后两边各自说该说的：
 *
 *  - [summary] 是**结论**，面向用户，简短，不带内部标识符。它回答"成没成"。
 *  - [detail] 是**证据**，面向排错，原样保留（异常类名、消息、返回码、节点路径）。
 *    默认收起，用户点"显示更多"才看，可复制。
 *
 * ## 界面和日志都要有
 *
 * 展示用 `ui/components/FailureNote.kt`：摘要永远可见、详情藏在一次点击之后、永远给复制。
 *
 * **同时必须落日志。** 用户在界面上看一眼就关掉弹窗的话，详情只存在于那一瞬间；
 * 而反馈问题时能拿出来的只有导出的诊断报告。所以构造失败时顺手写一条
 * （见 [from]）—— 界面是主路径，日志是备份，两条都要通。
 *
 * ## 没有 detail 的时候
 *
 * 完全合法 —— 大多数失败用户不需要看原始错误。此时 [FailureNote] 只显示摘要，
 * 不画"显示更多"。
 */
data class Failure(
    val summary: Message,
    val detail: String? = null,
) {
    /** 摘要解析成文本。展示边界用；Compose 里走 `Message.text()`。 */
    fun summaryText(context: Context): String = summary.resolve(context)

    companion object {
        /**
         * 捕获到异常时用它：**构造失败的同时写一条日志。**
         *
         * 一行替代"拼 detail + 调 AppLog"两件事，也就不会出现"界面上有、报告里没有"
         * 这种对不上的情况。日志级别取 WARN：失败要被导出的报告收录，但不该当错误刷屏。
         *
         * 只放原始证据（异常类名 + 消息），**不重复摘要** —— 摘要进日志反而让
         * 同一行里出现两遍人话。
         */
        fun from(tag: String, summary: Message, error: Throwable): Failure {
            val detail = detailOf(error)
            AppLog.w(tag, detail)
            return Failure(summary, detail)
        }

        /**
         * 原始错误的规整形式：`类名: 消息`。
         *
         * 消息缺失时只留类名，不塞占位词 —— 占位词是给人看的，而这一串的读者是
         * 排错的人，类名本身就够了。
         */
        fun detailOf(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() }
                ?.let { "${error.javaClass.simpleName}: $it" }
                ?: error.javaClass.simpleName
    }
}
