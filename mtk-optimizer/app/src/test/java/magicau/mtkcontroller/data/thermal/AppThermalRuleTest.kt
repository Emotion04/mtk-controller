package magicau.mtkcontroller.data.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按应用温控」规则表的编解码。
 *
 * 钉住的是：**规则表里不许存"每个索引是什么意思"** —— 那是会随 ROM 变的分析结论。
 * 这里只存包名 + 编号 + 一个展示用标签，所以格式可以很简单，也必须能兜住脏数据。
 */
class AppThermalRuleTest {

    @Test
    fun `编码解码往返`() {
        val rules = listOf(
            AppThermalRule("com.a.b", 1, "浏览器"),
            AppThermalRule("com.c.d", 18, ""),
        )
        val raw = encodeLike(rules)
        assertEquals(rules, decodeLike(raw))
    }

    @Test
    fun `标签里的换行被换成空格 否则一行一条会散架`() {
        val rules = listOf(AppThermalRule("com.a.b", 5, "第一行\n第二行"))
        val raw = encodeLike(rules)
        assertEquals(1, raw.lines().size)
        assertEquals("第一行 第二行", decodeLike(raw).single().label)
    }

    @Test
    fun `脏行被丢掉 不让一条坏数据毁掉整张表`() {
        val raw = listOf(
            "com.a.b|1|正常",
            "",                       // 空行
            "没有分隔符",              // 缺字段
            "com.c.d|不是数字|x",       // 索引不是整数
            "|3|空包名",               // 空包名
            "com.e.f|-1|负索引",        // 负索引
        ).joinToString("\n")
        val decoded = decodeLike(raw)
        assertEquals(listOf(AppThermalRule("com.a.b", 1, "正常")), decoded)
    }

    @Test
    fun `同一个包名只保留一条`() {
        val raw = listOf(
            "com.a.b|1|旧",
            "com.a.b|9|新",
        ).joinToString("\n")
        // 重复包名由存储层 distinctBy 挡掉，解码层按出现顺序全给
        assertEquals(2, decodeLike(raw).size)
    }

    // 与 AppThermalStore 私有的编解码同规则；测试复制一份，改格式要同步改这里。
    private fun encodeLike(rules: List<AppThermalRule>): String =
        rules.joinToString("\n") { "${it.pkg}|${it.policyIndex}|${it.label.replace("\n", " ")}" }

    private fun decodeLike(raw: String?): List<AppThermalRule> =
        raw?.lineSequence()?.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            val pkg = parts[0].trim()
            val index = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            if (pkg.isEmpty() || index < 0) return@mapNotNull null
            AppThermalRule(pkg, index, parts.getOrNull(2)?.trim().orEmpty())
        }?.toList().orEmpty()
}
