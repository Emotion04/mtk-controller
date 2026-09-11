package magicau.mtkcontroller.data.powerhal

import magicau.mtkcontroller.data.Message

/**
 * 一次受控请求的结果。
 *
 * ## 为什么抽出来
 *
 * 这四行原来在三个类里各写一遍：`PerfLockController`、`CpuControl`、
 * `ThermalPolicyController`。字段和注释完全相同，改一处忘两处。
 *
 * ## `success` 只说明请求被接受
 *
 * PowerHAL 的 release 是 oneway，返回值证明不了请求真的消失了；acquire 也是。
 * **效果要另外判断** —— `CpuControl` 读回内核，`ThermalPolicyController` 比对
 * 控制器自己的状态。别把这里的 `success` 当成"已经生效"。
 */
data class RequestOutcome(
    val success: Boolean,
    val message: Message,
    /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
    val detail: String? = null,
)
