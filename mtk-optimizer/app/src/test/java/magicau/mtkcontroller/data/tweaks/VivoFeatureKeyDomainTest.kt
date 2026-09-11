package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置项键域的不变量。
 *
 * 每一项都手写在枚举里，加新项时容易撞上已有的 settings 键 —— 撞了不报错，只会两个入口
 * 同时管一个值，快照互相覆盖。这些断言把这类错误挡在编译期之外。
 *
 * 例外不销毁：本文件维护一张"有意重叠"清单，每条写明理由。清单之外的任何重叠都算回归。
 */
class VivoFeatureKeyDomainTest {

    private val smartPlatformKeys: Set<String> = SmartPlatform.engines.flatMap { it.keys }.toSet()

    /** 有意共用的键 → 理由。写在这里就等于承认，不承认的一律判失败。 */
    private val intentionalOverlap = mapOf(
        // PRELOAD 与 AI 都写这个键：两者语义就是同一个开关，apply 里由
        // conflictingFeature 拦住"两个同时挂着未恢复快照"。
        "system ai_turbo_enabled" to "PRELOAD 与 AI 是同一个开关，互斥已覆盖",
        // GAME_THERMAL 的 20 秒实验改这个键，智算平台只读探测也列着它。实验期间
        // 只读页可能显示实验值，20 秒后自愈 —— 记录在案，不装作没有。
        "system vivo_gamethermal_switch" to "20 秒实验键，只读探测会短期显示实验值",
    )

    @Test
    fun `共用键必须被互斥机制挡住`() {
        val collisions = mutableListOf<String>()
        for (key in VivoFeature.entries.flatMap { it.values.keys }.distinct()) {
            val owners = VivoFeature.entries.filter { key in it.values.keys }
            if (owners.size < 2) continue
            for (a in owners) for (b in owners) {
                if (a == b) continue
                if (VivoTweaks.conflictingFeature(a, setOf(b)) == null) {
                    collisions += "$key：${a.name} 与 ${b.name} 之间没有互斥"
                }
            }
        }
        assertTrue("共用键缺少互斥：$collisions", collisions.isEmpty())
    }

    @Test
    fun `与只读探测键的重叠只允许清单内那几条`() {
        val written = VivoFeature.entries.flatMap { it.values.keys }.distinct()
        val overlap = written.filter { it in smartPlatformKeys }
        assertEquals(
            "只读探测键的重叠必须是清单内的：$overlap",
            intentionalOverlap.keys.toList().sorted(),
            overlap.sorted(),
        )
    }

    @Test
    fun `空取值只能出现在 option 驱动的配置项里`() {
        // 空串会走 settings put key ''，等于把键清空。只有 apply 里显式用 option 顶替的
        // 配置项才允许空取值，否则就是一键清空。
        val emptyValueFeatures = VivoFeature.entries.filter { f -> f.values.values.any(String::isEmpty) }
        assertEquals(
            "空取值出现在未接线的配置项里：${emptyValueFeatures.map { it.name }}",
            setOf(
                VivoFeature.PERFORMANCE_MODE, VivoFeature.FRAME_UNLOCK, VivoFeature.DNS,
                VivoFeature.CHARGE_LIMIT, VivoFeature.TOUCH_BOOST,
            ),
            emptyValueFeatures.toSet(),
        )
        // 这几个每一项都必须在 apply 的取值分支里有名，漏一个就写空串。
        emptyValueFeatures.forEach { feature ->
            assertFalse("${feature.name} 没有任何取值来源", feature.values.isEmpty())
        }
    }

    @Test
    fun `任何开关都不含会把设备名或图标黑名单清空的键`() {
        // 回归：这些键曾经以空串写进去，结果是把设备名和状态栏图标黑名单清空。
        // 现在它们只在智算平台只读展示，不进任何可写集合。
        //
        // 原来这条测试查的是一个叫 QUICK_SWITCHES 的分组常量，**那个常量在生产代码里
        // 从来不存在** —— 于是这条回归测试从初版提交起就没编译过，一次都没跑。
        // 改成查全部开关：不变量本来就应该对每一个成立，而不是只对某一个分组。
        val wipeOut = listOf(
            "system flash_charge_animation_style",
            "system device_name",
            "secure icon_blacklist",
        )
        VivoFeature.entries.forEach { feature ->
            wipeOut.forEach { key ->
                assertFalse("${feature.name} 不该写 $key", key in feature.values)
            }
            // 预设空串只允许出现在 allowMissing 的项上 —— 那是"由用户按本机语义填"，
            // 不是"替用户清空"。其余项出现空值就是 bug。
            feature.values.forEach { (key, value) ->
                if (value.isEmpty()) {
                    assertTrue("${feature.name} 的 $key 是空预设，必须 allowMissing", feature.allowMissing)
                }
            }
        }
    }

    @Test
    fun `新接入的三项都是持久且标注生效未验证`() {
        listOf(VivoFeature.TOUCH_BOOST, VivoFeature.HAWKEYE, VivoFeature.NETWORK_BOOST).forEach { feature ->
            assertEquals("${feature.name} 应是持久配置", TweakLifetime.PERSISTENT, feature.lifetime)
            // 这些都没有系统侧读数能证明生效，不能标成 CONFIRMED。
            assertEquals("${feature.name} 应标生效未验证", Evidence.WRITTEN, feature.evidence)
            assertTrue("${feature.name} 应允许键缺失", feature.allowMissing)
        }
    }

    @Test
    fun `触控调整不硬编码外部实现的取值`() {
        // 外部实现写 1,7,7,7，本机现值是 1,6,6,6 —— 三者互不相同说明机型相关。
        // 照抄参考值会覆盖本机现值，所以枚举里不放任何预设。
        assertEquals(setOf(""), VivoFeature.TOUCH_BOOST.values.values.toSet())
        assertEquals(1, VivoFeature.TOUCH_BOOST.values.size)
        assertTrue(VivoFeature.TOUCH_BOOST.allowMissing)
    }

    @Test
    fun `直驱与充电互不共用键`() {
        assertNull(VivoTweaks.conflictingFeature(VivoFeature.DIRECT_POWER, setOf(VivoFeature.CHARGE_LIMIT)))
        assertNull(VivoTweaks.conflictingFeature(VivoFeature.CHARGE_LIMIT, setOf(VivoFeature.DIRECT_POWER)))
    }

    @Test
    fun `每个配置项都有文案资源`() {
        // labelRes/detailRes 是资源 id，0 表示漏配；漏了在运行期崩而不是编译期。
        for (feature in VivoFeature.entries) {
            assertTrue("${feature.name} 缺少名称资源", feature.labelRes != 0)
            assertTrue("${feature.name} 缺少说明资源", feature.detailRes != 0)
        }
        for (evidence in Evidence.entries) {
            assertTrue("${evidence.name} 缺少徽标资源", evidence.labelRes != 0)
        }
    }
}
