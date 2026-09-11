package magicau.mtkcontroller.data.lab

import magicau.mtkcontroller.data.thermal.FreqLadder
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "最宽松的档位"靠配置内容判断，不靠编号。
 *
 * 编号在不同机型、不同 ROM 上含义不同；写死一个数字迟早选错。
 */
class ThermalLooseIndexTest {

    private val withCpu = ThermalPolicy(
        index = 2,
        cpuLadders = mapOf(0 to FreqLadder(listOf(45), listOf(2000))),
        cpuMappingEnabled = true,
        maxTargetTJC = 95_000,
    )

    private val noCpu = ThermalPolicy(
        index = 7,
        cpuLadders = emptyMap(),
        cpuMappingEnabled = null,
        maxTargetTJC = 300_000,
    )

    @Test
    fun `没有 CPU 频率表的档位视为不压频`() {
        assertEquals(null, ThermalPolicyCatalog.averageAllowedFreq(noCpu))
    }

    @Test
    fun `用 40 到 50 度的平均允许频率比较，而不是首档频率`() {
        // 首档高、但很快掉下来的，平均不如首档低但稳的。
        val spiky = withCpu.copy(
            index = 1,
            cpuLadders = mapOf(0 to FreqLadder(listOf(37, 41), listOf(3_300_000, 1_200_000))),
        )
        val steady = withCpu.copy(
            index = 2,
            cpuLadders = mapOf(0 to FreqLadder(listOf(40), listOf(2_200_000))),
        )
        val a = ThermalPolicyCatalog.averageAllowedFreq(spiky)!!
        val b = ThermalPolicyCatalog.averageAllowedFreq(steady)!!
        assertTrue("steady=$b spiky=$a", b > a)
    }

    @Test
    fun `关闭 CPU 映射视为不压频`() {
        assertEquals(null, ThermalPolicyCatalog.averageAllowedFreq(withCpu.copy(cpuMappingEnabled = false)))
    }

    @Test
    fun `完全没有表优于表被关闭`() {
        // 本机 01 没有 [mtk-cpu-table-mapping]；08/09 有段但 mode=disabled。
        // 两者的段都不生效，但 01 的段根本不存在，优先选它。
        val one = noCpu.copy(index = 1, isolateEnabled = true)
        val eight = ThermalPolicy(
            index = 8,
            cpuLadders = mapOf(0 to FreqLadder(listOf(46), listOf(3_200_000))),
            cpuMappingEnabled = false,
            isolateEnabled = false,
        )
        val nine = eight.copy(index = 9)
        assertEquals(1, ThermalPolicyCatalog.pickLoose(listOf(eight, nine, one))?.index)
    }

    @Test
    fun `表都被关闭时 isolate 关闭的更宽松`() {
        val eight = ThermalPolicy(
            index = 8,
            cpuLadders = mapOf(0 to FreqLadder(listOf(46), listOf(3_200_000))),
            cpuMappingEnabled = false,
            isolateEnabled = false,
        )
        val nine = eight.copy(index = 9)
        val other = eight.copy(index = 10, isolateEnabled = true)
        assertEquals(8, ThermalPolicyCatalog.pickLoose(listOf(other, nine, eight))?.index)
    }

    @Test
    fun `完全并列时选编号最小的`() {
        val a = noCpu.copy(index = 1)
        val b = noCpu.copy(index = 8)
        val c = noCpu.copy(index = 9)
        assertEquals(1, ThermalPolicyCatalog.pickLoose(listOf(c, b, a))?.index)
    }

    @Test
    fun `permanent 不改变宽松程度`() {
        assertEquals(
            ThermalPolicyCatalog.averageAllowedFreq(withCpu),
            ThermalPolicyCatalog.averageAllowedFreq(withCpu.copy(permanent = true)),
        )
    }
}
