package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeatureTrial] 的编解码与报告结构测试。
 *
 * 这份记录要能跨版本、跨机型读出来给人开发用，所以编码格式必须稳定 ——
 * 键值里可能出现逗号、等号、换行，用常见分隔符会解析错。
 */
class FeatureTrialTest {

    private val sample = FeatureTrial.Trial(
        feature = "HAWKEYE",
        label = "HAWKEYE",
        keysBefore = mapOf("system gamecube_hawkeye_effective" to "0"),
        keysAfter = mapOf("system gamecube_hawkeye_effective" to "1"),
        writeOk = true,
        message = "已写入，含逗号，含=等号",
        gateReason = null,
        verdict = FeatureTrial.Verdict.WORKED,
        at = 1_700_000_000_000L,
    )

    /** 真验编解码往返：值与消息里都带分隔符，容易被切错。 */
    @Test
    fun `试用记录能原样往返`() {
        val tricky = sample.copy(
            keysBefore = mapOf("system vts_game_para_adjust" to "1,6,6,6"),
            keysAfter = mapOf("system vts_game_para_adjust" to "1,7,7,7"),
            message = "写前=1,6,6,6 写后=1,7,7,7；第二行",
            gateReason = "未安装 com.vivo.gamecube",
        )
        val decoded = FeatureTrial.decode(FeatureTrial.encode(tricky))
        assertEquals(tricky, decoded)
    }

    @Test
    fun `空值与缺失值不会混淆`() {
        // 键不存在读回来是 null，和"读到空串"必须分开 —— 前者是本机不支持，
        // 后者可能只是 ROM 给了个空值。
        val withNull = sample.copy(keysBefore = mapOf("system missing_key" to null))
        val decoded = FeatureTrial.decode(FeatureTrial.encode(withNull))!!
        assertEquals(mapOf("system missing_key" to null), decoded.keysBefore)
    }

    @Test
    fun `坏行返回 null 而不是崩`() {
        // 存储是 SharedPreferences 里的文本，可能被截断或手工改坏。
        assertNull(FeatureTrial.decode(""))
        assertNull(FeatureTrial.decode("缺字段"))
        assertNull(FeatureTrial.decode("notanumber\u001fHAWKEYE\u001f"))
    }

    @Test
    fun `一条记录只占一行`() {
        // 消息里带换行也必须留在同一行里，否则按行读会把一条记录拆成两条。
        val multiline = sample.copy(message = "第一行\n第二行\n第三行")
        assertFalse(FeatureTrial.encode(multiline).contains("\n"))
    }

    @Test
    fun `判定枚举覆盖所有状态`() {
        // 界面上每个判定按钮都要有对应状态，漏一个就会出现"点了没反应"
        val expected = setOf("PENDING", "WORKED", "NO_EFFECT", "UNSURE", "UNSUPPORTED")
        assertEquals(expected, FeatureTrial.Verdict.entries.map { it.name }.toSet())
    }

    @Test
    fun `不支持的门禁必须留下原因`() {
        val blocked = sample.copy(gateReason = "未安装 com.vivo.gamecube", verdict = FeatureTrial.Verdict.UNSUPPORTED)
        assertEquals("未安装 com.vivo.gamecube", blocked.gateReason)
        assertEquals(FeatureTrial.Verdict.UNSUPPORTED, blocked.verdict)
        assertFalse(blocked.gateReason.isNullOrBlank())
    }

    @Test
    fun `每一项都要有专属观察提示`() {
        // 早前这里允许"trialHintRes 为 0，靠默认文案兜底"，而那 11 项落到默认文案
        // （"观察这项管的那一处有没有变化"）用户根本不知道看哪儿 —— 等于没提示。
        // 现在要求全部有专属提示，缺一条这个测试就红。
        val noHint = VivoFeature.entries.filter { it.trialHintRes == 0 }.map { it.name }
        assertTrue("这些项没有专属观察提示：$noHint", noHint.isEmpty())
    }

    @Test
    fun `观察提示都指向用户能感知的现象`() {
        // 提示是给人看的，不能出现内部键名或异常类名
        VivoFeature.entries.forEach { feature ->
            assertTrue("${feature.name} 缺少名称资源", feature.labelRes != 0)
        }
    }
}
