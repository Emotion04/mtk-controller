package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.data.sysfs.Sysfs

/**
 * cpufreq 那三个限制节点的**路径**与**类型化解析**。
 *
 * ## 为什么单独放一份
 *
 * "拼路径 → 读 → toLongOrNull"这一套以前在三个地方各写一遍：
 * `CpuControl.readSnapshot`（批量读、允许提权补读，用于验证）、
 * `CpuViewModel.readCaps`（逐条直读，只用于页面展示）、`CpuScanner.scan`
 * （顺带读硬件能力）。抄三份就会漂，而最贵的一种漂法是**读取身份不同**：
 * 验证那条路提权补读得到，展示那条路只按 App 身份读 —— 于是"控制验证通过、
 * 界面却显示未知"，用户看到的是一个自相矛盾的屏幕，而且两边都不报错。
 *
 * 所以路径和解析只留一份在这里，节点改名只改一处。
 *
 * ## 什么**不**该在这里统一
 *
 * **读取策略。** 直读还是批量读、要不要提权补读，是业务区别，不是实现细节：
 *
 * | 调用方 | 读法 | 为什么 |
 * |---|---|---|
 * | `CpuControl` 验证 | 批量 + 提权补读 | 要看到系统**真正生效**的值，否则验证没有意义 |
 * | `CpuViewModel` 展示 | 逐条直读 | 要反映 **App 身份**能看到的值；读不到就显示未知 |
 * | `CpuScanner` 能力发现 | 直读 + 提权补读 | 问的是"这台机器有没有这个节点" |
 * | `CpuScanner.currentFreqs` | 只读 `scaling_cur_freq` | 1 Hz 采样器用，别为"统一"多读两个节点 |
 * | `CapabilityProbe.readAppliedLimits` | 逐条直读 | 只想确认"写进去的那两个值成了没有" |
 *
 * 为了整齐把展示也改成提权补读，等于把"读不到"显示成"读到了" ——
 * 那正是界面只显示可信字段那条规矩禁止的事。
 */
object CpuFreqNodes {

    /** 当前软下限。 */
    fun min(policy: String): String = node(policy, "scaling_min_freq")

    /** 当前软上限。 */
    fun max(policy: String): String = node(policy, "scaling_max_freq")

    /** 当前实际频率。 */
    fun cur(policy: String): String = node(policy, "scaling_cur_freq")

    /**
     * 硬件下限。**这是"这颗芯片能做到多低"，不是"现在被限制到多低"** ——
     * 两者是不同的问题，别拿这一个替代另一个（紧急恢复要的是前者，
     * 验证下发结果要的是后者）。
     */
    fun hardwareMin(policy: String): String = node(policy, "cpuinfo_min_freq")

    /** 硬件上限。 */
    fun hardwareMax(policy: String): String = node(policy, "cpuinfo_max_freq")

    /** 一个簇的三个限制节点，顺序固定：下限、上限、当前。 */
    fun limits(policy: String): List<String> = listOf(min(policy), max(policy), cur(policy))

    private fun node(policy: String, name: String): String =
        "${CpuScanner.CPUFREQ_ROOT}/$policy/$name"

    /**
     * 这个簇的 cpufreq 目录。给"要读的节点不在上面那六个里"的调用方用
     * （调速器、`related_cpus`、`affected_cpus`、原始测试面板里用户手输的节点）。
     *
     * 上面那些具名访问器覆盖不到的，走这里 —— 至少目录不再各拼一遍。
     */
    fun dir(policy: String): String = "${CpuScanner.CPUFREQ_ROOT}/$policy"

    /**
     * 解析成 kHz。读不到、不是数字，都是 `null`。
     *
     * **不要用 0 顶替。** 0 在这里不是"没读到"，是个合法频率值；
     * 拿 0 充数会让界面显示一个并不存在的 0 MHz，也会让验证误判成冲突。
     */
    fun parseKhz(raw: String?): Long? = raw?.trim()?.toLongOrNull()

    /**
     * 按 App 身份逐个直读三个限制值。
     *
     * 给"只想如实反映 App 能看到什么"的调用方用（见类注释里的表）。
     * 不批量、不提权 —— 这两件事在这里都是故意的。
     */
    suspend fun readDirect(policy: String): Triple<Long?, Long?, Long?> {
        val min = parseKhz(Sysfs.read(min(policy)))
        val max = parseKhz(Sysfs.read(max(policy)))
        val cur = parseKhz(Sysfs.read(cur(policy)))
        return Triple(min, max, cur)
    }
}
