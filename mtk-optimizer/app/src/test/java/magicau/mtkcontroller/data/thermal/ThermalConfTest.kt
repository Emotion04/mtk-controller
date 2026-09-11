package magicau.mtkcontroller.data.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码 + 解析 + 中文摘要三层。
 *
 * 明文样本取自真机 `/vendor/etc/thermal/`（归档在 `docs/thermal-configs/plain/`）。
 * 钉住的铁律：**这三层都是机械设备，不预设任何档位的含义** ——
 * 这正是当初把"哪一档干什么"写死在代码里、系统一更新就成假话之后换掉的做法。
 */
class ThermalConfTest {

    // thermal_policy_00.conf 的相关段（真机明文）
    private val plain00 = """
        [policy_type]
        permanent= No

        [LTF]
        thermal_zone= soc_max

        [mtk-cpu-table-mapping]
        mode= enabled
        sensor= tz_game_shell
        cluster= 0
        threshold= [37000, 40000, 43000, 46000, 48000, 51000]
        hysteresis= [2000, 2000, 2000, 2000, 2000, 2000]
        freq= [1800000, 1700000, 1600000, 1500000, 1500000, 1300000]

        [mtk-cpu-table-mapping]
        mode= enabled
        sensor= tz_game_shell
        cluster= 2
        threshold= [37000, 40000, 43000, 46000, 48000, 51000]
        hysteresis= [2000, 2000, 2000, 2000, 2000, 2000]
        freq= [2600000, 2200000, 2000000, 1900000, 1700000, 1200000]

        [mtk-skin-control]
        sensor= tz_game_shell
        steady_target_tj= 65000

        [thermalhal-threshold]
        type= SKIN
        threshold= [40000, 42000, 45000, 48000, 60000, 80000]
    """.trimIndent()

    /** 01 根本没有 mtk-cpu-table-mapping 段。 */
    private val plain01 = """
        [policy_type]
        permanent= No

        [LTF]
        thermal_zone= soc_max

        [vtskin-param]
        sensor= vtskin1
        mode= disabled

        [mtk-skin-control]
        sensor= ap_ntc
        steady_target_tj= 91500
    """.trimIndent()

    /** 18 单档、阈值 20000（恒真）。 */
    private val plain18 = """
        [policy_type]
        permanent= No

        [mtk-cpu-table-mapping]
        mode= enabled
        sensor= tz_shell
        cluster= 0
        threshold= [20000]
        hysteresis= [2000]
        freq= [1800000]

        [mtk-cpu-table-mapping]
        mode= enabled
        sensor= tz_shell
        cluster= 2
        threshold= [20000]
        hysteresis= [2000]
        freq= [2100000]
    """.trimIndent()

    // thermal_policy_00.conf 开头三行，密文取自真机
    private val cipher00Head = "[qqomh\$f!'pf_\n" +
        "#!Ki\$ukyujnfpwA%_l 5 ujlw%vvtrcz\"lw%y qul!xdpnj'iotft#vjhvw\"\n" +
        "pftpesku!F Oq\n"

    @Test
    fun `解码器还原已知明文`() {
        assertEquals(
            "[policy_type]\n" +
                "# If permanent= Yes, this policy is still valid after reboot\n" +
                "permanent= No\n",
            ThermalConfDecoder.decode(cipher00Head),
        )
    }

    @Test
    fun `解码器只保证字符在字母表内 不负责判断对错`() {
        // 一段合法明文同样落在字母表内，靠输出形状判不出来 ——
        // "是不是这份配置"由结构校验负责，那个校验在 reader 里。
        val plain = "[policy_type]\npermanent= No\n"
        assertTrue(ThermalConfDecoder.decode(plain) != plain)
    }

    @Test
    fun `换行复位计数器`() {
        // 逐行复位是这套编码的关键：同样三个字符在行首和行中结果必须不同
        val a = ThermalConfDecoder.decode("abc")
        val b = ThermalConfDecoder.decode("abc\nabc")
        assertTrue(a != null && b != null)
        assertFalse(a == b)
    }

    @Test
    fun `解析 00 的三簇降频梯`() {
        val cpu = ThermalPolicyParser.parse(0, plain00).cpuLadders
        assertEquals(setOf(0, 2), cpu.keys)

        val big = cpu.getValue(2)
        assertEquals(listOf(37, 40, 43, 46, 48, 51), big.thresholdsC)
        assertEquals(listOf(2600, 2200, 2000, 1900, 1700, 1200), big.freqsMHz)
    }

    @Test
    fun `解析 00 的永久标志与稳态温度`() {
        val policy = ThermalPolicyParser.parse(0, plain00)
        assertEquals(false, policy.permanent)
        assertEquals(65, policy.steadyTargetTJC)
        assertEquals(true, policy.cpuMappingEnabled)
        assertEquals(listOf(40, 42, 45, 48, 60, 80), policy.skinThresholdsC)
    }

    @Test
    fun `解析 01 没有 CPU 频率表`() {
        val policy = ThermalPolicyParser.parse(1, plain01)
        assertTrue(policy.cpuLadders.isEmpty())
        assertNull(policy.cpuMappingEnabled)
        assertEquals(92, policy.steadyTargetTJC)
    }

    @Test
    fun `解析 18 的单档恒真锁频`() {
        val big = ThermalPolicyParser.parse(18, plain18).cpuLadders.getValue(2)
        assertEquals(listOf(20), big.thresholdsC)
        assertEquals(listOf(2100), big.freqsMHz)
    }

    @Test
    fun `摘要只翻译不评价`() {
        val summary = ThermalPolicySummary.brief(ThermalPolicyParser.parse(0, plain00))
        assertTrue(summary.contains("37°C→2.6G"))
        assertTrue(summary.contains("51°C→1.2G"))
        // 产品侧早就把"稳态温度目标"改成了"目标温度"（稳态是控制论词汇，理由写在
        // ThermalPolicySummary.brief 的注释里），而这里一直断言旧说法 ——
        // 测试套件从初版提交起就没编译过，所以这个反向断言错了很久没人发现。
        assertTrue(summary.contains("目标温度"))
        // 不许出现评价性措辞 —— 那是我当初写死档位含义的老毛病。
        // **只保留这一份通用清单**：别为某个具体词再单开一条反向断言，
        // 那是在提醒别人去用它（此地无银三百两）。
        listOf("建议", "推荐", "最好", "较好", "适合", "不要用", "首选").forEach { word ->
            assertFalse("摘要不该出现评价：$word", summary.contains(word))
        }
    }

    @Test
    fun `摘要对无频率表的档位说清楚`() {
        val summary = ThermalPolicySummary.brief(ThermalPolicyParser.parse(1, plain01))
        assertTrue(summary.contains("没有 CPU 频率表"))
    }

    @Test
    fun `阶梯表在温度之间沿用上一档`() {
        val ladder = FreqLadder(listOf(40, 50), listOf(2000, 1500))
        assertEquals(2000, ladder.freqAt(40))
        assertEquals(2000, ladder.freqAt(45))
        assertEquals(1500, ladder.freqAt(50))
        assertEquals(1500, ladder.freqAt(60))
        assertEquals(2000, ladder.freqAt(30))
    }

    @Test
    fun `关闭的 CPU 表不作为生效内容展示`() {
        val plain = """
            [mtk-cpu-table-mapping]
            mode= disabled
            cluster= 2
            threshold= [46000]
            freq= [3200000]
        """.trimIndent()
        val summary = ThermalPolicySummary.brief(ThermalPolicyParser.parse(8, plain))
        assertTrue(summary.contains("CPU 频率表已关闭"))
        assertTrue(summary.contains("不参与控制"))
        assertFalse("46°C 的表是 disabled，不能按生效内容列出来", summary.contains("46°C→3.2G"))
    }

    @Test
    fun `完整版也只翻译不评价`() {
        val full = ThermalPolicySummary.full(ThermalPolicyParser.parse(0, plain00))
        listOf("建议", "推荐", "最好", "较好", "适合", "不要用", "首选").forEach { word ->
            assertFalse("完整版不该出现评价：$word", full.contains(word))
        }
        // 完整版应包含 brief 没有的参数级信息
        assertTrue(full.contains("隔离阈值") || full.contains("皮温分级阈值") || full.contains("重启后"))
        assertTrue(full.contains(ThermalPolicySummary.brief(ThermalPolicyParser.parse(0, plain00))))
    }

    @Test
    fun `解码失败时摘要直接给出原因`() {
        assertEquals("读不到文件", ThermalPolicySummary.brief(ThermalPolicy(1, decodeError = "读不到文件")))
    }
}
