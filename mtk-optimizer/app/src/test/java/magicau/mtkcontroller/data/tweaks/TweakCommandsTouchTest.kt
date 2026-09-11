package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触控子项的回归测试。
 *
 * 拆开之后，"命令长什么样"这件事从函数挪进了数据，测试的重心也跟着挪：
 * 不再是盯某几行函数的输出，而是盯**数据里的不变量**。这些不变量一旦被破坏，
 * 在真机上都是"开关打不开"或"写进去没效果"这类难查的现象。
 */
class TweakCommandsTouchTest {

    private val everything: List<TouchKnob> = TouchItems.all().flatMap { it.knobs }

    /** 没有快照时命令长什么样 —— 也就是"从没被本软件打开过"那一条路。 */
    private fun commands(on: Boolean): List<String> =
        TouchItems.all().flatMap { item ->
            item.knobs.map { knob -> knob.command(on, knob.offTarget(item.id, emptyMap())) }
        }

    @Test
    fun `device_config 一条都不许有`() {
        // 真机实测：device_config put 抛 SecurityException（flag 未进 allowlist），退出码 255。
        // 以前它混在 20 多条命令里，被 runAll 当成整体失败，触控开关因此永远打不开。
        (commands(true) + commands(false)).forEach {
            assertFalse("不该出现 device_config: $it", it.contains("device_config"))
        }
    }

    @Test
    fun `不许碰全局调度器和硬件合成`() {
        // 两条都是拆开时故意排除的：
        // sys_uidcpupower 是全局调度策略，打开后别的开关有没有效果就说不清了；
        // debug.sf.hw 0 关掉硬件合成，改的是整条显示合成方式，功耗与画面风险都由用户担。
        (commands(true) + commands(false)).forEach {
            assertFalse("全局调度器不许碰: $it", it.contains("sys_uidcpupower"))
            assertFalse("硬件合成不许碰: $it", it.contains("debug.sf.hw"))
            assertFalse("不许和 ROM 自己的设置对着干: $it", it.contains("enable_hwc_vds"))
        }
    }

    @Test
    fun `settings 写入一律走 content insert`() {
        // 真机实测：settings put / cmd settings put 在这台 ROM 上退出码 0 但值不落库，
        // 连一个已存在且有值的键都改不动。整条 settings 写入路径必须换成 content insert。
        val settingsWrites = everything.filter { it.ns != TouchNs.PROP }
        assertTrue("应当有 settings 旋钮", settingsWrites.isNotEmpty())
        settingsWrites.forEach { knob ->
            val command = knob.command(true, knob.offTarget("x", emptyMap()))
            assertFalse("不该再用 settings put: $command", command.contains("settings put"))
            assertTrue("应使用 content insert: $command", command.startsWith("content insert --uri content://settings/"))
            assertTrue("应绑定 name: $command", command.contains("--bind name:s:"))
            assertTrue("应绑定 value: $command", command.contains("--bind value:s:"))
            // 键名与值都要加引号，否则带空格或撇号的值会被 shell 拆开。
            assertTrue("键名要加引号: $command", command.contains("--bind name:s:'${knob.key}'"))
        }
    }

    @Test
    fun `prop 旋钮一律走 setprop`() {
        everything.filter { it.ns == TouchNs.PROP }.forEach { knob ->
            assertTrue(knob.command(true, "0").startsWith("setprop ${knob.key} "))
            assertTrue(knob.readCommand() == "getprop ${knob.key}")
        }
    }

    @Test
    fun `只读项没有开关语义，但仍带旋钮`() {
        // 只读项（response_delay）的开关是空动作：开=关=出厂都是 0。
        // 但它仍要带旋钮，否则登记表、备份、漂移监测都推导不到它。
        val item = TouchItems.all().first { it.id == TouchItems.RESPONSE_DELAY }
        assertTrue("只读项要标 readOnly", item.readOnly)
        assertTrue("只读项仍要有旋钮", item.knobs.isNotEmpty())
        assertTrue("界面靠 readOnly 区分，不靠旋钮数量",
            TouchItems.all().none { it.readOnly && it.knobs.isEmpty() })
    }

    @Test
    fun `兜底关态与开态都非空，且不是占位`() {
        // 兜底值只在"从没被本软件打开过"时用，所以必须是个能写进去的真值，
        // 不能是 "restore" 这类占位 —— 那需要另一套机制，数据里没有。
        everything.forEach { knob ->
            assertTrue("兜底关态不能为空: ${knob.key}", knob.fallbackOff.isNotEmpty())
            assertTrue("开态不能为空: ${knob.key}", knob.onValue.isNotEmpty())
            assertFalse("兜底关态不能是占位: ${knob.key}", knob.fallbackOff == "null")
        }
    }

    @Test
    fun `子项 id 唯一且稳定`() {
        val items = TouchItems.all()
        assertEquals("子项 id 必须唯一，改名等于用户丢一次配置", items.size, items.map { it.id }.toSet().size)
        // 这些 id 会写进用户的 DataStore，改名前要有迁移；这里先把它们钉住。
        assertEquals(
            setOf("report_rate", "press_delay", "pointer_speed", "response_delay", "render_vsync", "sf_latch"),
            items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `每个子项都有文案和旋钮`() {
        TouchItems.all().forEach { item ->
            assertTrue("${item.id} 缺少标签资源", item.labelRes != 0)
            assertTrue("${item.id} 缺少说明资源", item.detailRes != 0)
            assertTrue("${item.id} 没有旋钮", item.knobs.isNotEmpty())
        }
    }

    @Test
    fun `回读期望值与写入值一致`() {
        // 回读判据必须就是写入值本身：说过渡话（比如把 prop 的空串当失败）会让核对失真。
        everything.forEach { knob ->
            assertEquals(knob.onValue, knob.expectedRead(true, knob.offTarget("x", emptyMap())))
            assertEquals(
                knob.offTarget("x", emptyMap()),
                knob.expectedRead(false, knob.offTarget("x", emptyMap())),
            )
        }
    }

    @Test
    fun `关闭一律写回快照，没有快照才用兜底值`() {
        // 这是拆开时踩到的真陷阱：touch_scan_press_time_delay 出厂就是 300，而它的
        // "关闭"语义确实是 0。写死 0 的话，用户点一下"关闭"就把一台本来调好的机器改差了，
        // 而读回还是成功的，看不出任何异常。
        val delay = TouchItems.all().first { it.id == TouchItems.PRESS_DELAY }.knobs.first()
        val originals = mapOf(delay.originalKey(TouchItems.PRESS_DELAY) to "300")
        assertEquals("有快照必须用快照", "300", delay.offTarget(TouchItems.PRESS_DELAY, originals))
        assertEquals("没有快照才退回兜底", "0", delay.offTarget(TouchItems.PRESS_DELAY, emptyMap()))

        // 换一台出厂是 0 的机器，同一个快照键要能表达不同的原值。
        assertEquals("0", delay.offTarget(TouchItems.PRESS_DELAY, mapOf(delay.originalKey(TouchItems.PRESS_DELAY) to "0")))
    }

    @Test
    fun `出厂值非零的旋钮都留有快照键`() {
        // 每个旋钮都必须能拿到原值键，否则关闭时无从恢复，只能写兜底值。
        TouchItems.all().forEach { item ->
            item.knobs.forEach { knob ->
                assertTrue(
                    "原值键要能区分不同项的不同旋钮: ${knob.key}",
                    knob.originalKey(item.id).startsWith("${item.id}|"),
                )
            }
        }
        // 同一项的两个旋钮，原值键不许撞车。
        val item = TouchItems.all().first { it.id == TouchItems.PRESS_DELAY }
        assertEquals(item.knobs.size, item.knobs.map { it.originalKey(item.id) }.toSet().size)
    }

    @Test
    fun `档位取值来自固定白名单`() {
        // rateHz 直接拼进命令字符串，所以只能接受白名单值，不能是自由输入。
        assertEquals(listOf(120, 180, 240, 300, 360), TweakCommands.TOUCH_RATES)
        listOf(120, 360).forEach { rate ->
            TouchItems.all(rate).first { it.id == TouchItems.REPORT_RATE }.knobs.forEach { knob ->
                assertEquals("$rate", knob.onValue)
                assertEquals("0", knob.fallbackOff)
            }
        }
    }

    @Test
    fun `三个 report_rate prop 是同一个档位`() {
        // 三个 prop 语义相同（都请求触控控制器提高上报率），分开写不同值只会互相覆盖。
        val values = TouchItems.all().first { it.id == TouchItems.REPORT_RATE }.knobs.map { it.onValue }
        assertEquals("三个 prop 必须同档", 1, values.toSet().size)
        assertEquals(TouchItems.DEFAULT_RATE.toString(), values.toSet().single())
    }

    @Test
    fun `settingsPut 生成的命令可安全解析`() {
        val command = TweakCommands.settingsPut("system", "vts_game_para_adjust", "1,7,7,7")
        assertTrue(command.startsWith("content insert --uri content://settings/system "))
        assertTrue(command.contains("--bind name:s:'vts_game_para_adjust'"))
        assertTrue(command.contains("--bind value:s:'1,7,7,7'"))
        // 值里的单引号必须转义，否则会提前结束引号段。
        assertTrue(TweakCommands.settingsPut("secure", "k", "a'b").contains("'a'\\''b'"))
    }

    @Test
    fun `前后值都记录，没动的项也要报`() {
        // 这是"开一下看有没有变化"的数据基础：只记失败项的话，
        // 最有用的一条信息（实际发生了什么）就被丢掉了。
        val moved = TweakRepository.KnobChange("press_delay", "pointer_speed", "0", "7")
        val noop = TweakRepository.KnobChange("press_delay", "touch_scan_press_time_delay", "300", "300")
        assertTrue(moved.changed)
        assertFalse("出厂即目标值时不许报成有变化", noop.changed)
        assertTrue(moved.describe().contains("0 → 7"))
        assertTrue(noop.describe().contains("无变化"))
        // 空值要显示成"空"而不是空白一片
        val cleared = TweakRepository.KnobChange("report_rate", "debug.touch.report_rate", "360", "")
        assertTrue(cleared.describe().contains("360 → "))
    }
    @Test
    fun `数值项写的是用户填的数，不是预设`() {
        val item = TouchItems.all().first { it.id == TouchItems.PRESS_DELAY }
        val knob = item.numericKnobs.first { it.key == "touch_scan_press_time_delay" }
        val command = knob.command(on = true, offValue = "0", onValue = "250")
        assertTrue("应写入 250: $command", command.contains("value:s:'250'"))
        assertFalse("不该写预设 300: $command", command.contains("'300'"))
    }

    @Test
    fun `数值项的关闭仍然是恢复原值，不是恢复成填过的数`() {
        // 关闭 = 写回打开前的快照。用户把防抖调到 250 之后再关掉，
        // 设备应该回到出厂 300，而不是停在 250。
        val knob = TouchItems.all().first { it.id == TouchItems.PRESS_DELAY }
            .numericKnobs.first { it.key == "touch_scan_press_time_delay" }
        val itemId = TouchItems.PRESS_DELAY
        val originals = mapOf(knob.originalKey(itemId) to "300")
        val command = knob.command(on = false, offValue = knob.offTarget(itemId, originals), onValue = "250")
        assertTrue("应恢复 300: $command", command.contains("value:s:'300'"))
    }

    @Test
    fun `数值范围都包住预设值`() {
        // 范围不包住预设值时，界面一打开框里就是红的。
        TouchItems.all().flatMap { item ->
            item.numericKnobs.map { item.id to it }
        }.forEach { (id, knob) ->
            val range = knob.numberRange!!
            assertTrue("$id/${knob.key} 的预设 ${knob.onValue} 不在 $range 里", knob.onValue.toInt() in range)
        }
    }
}
