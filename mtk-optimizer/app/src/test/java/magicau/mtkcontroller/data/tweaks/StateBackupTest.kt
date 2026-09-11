package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登记表与备份编解码的回归测试。
 *
 * 备份是个"能改手机"的功能，所以这里盯的是**安全性质**，不是格式细节：
 * 认不出的键必须被丢掉、值里出现奇怪字符不能解析错、
 * 登记表必须和功能数据一致（否则恢复会漏键）。
 */
class StateBackupTest {

    private fun label(res: Int) = "label-$res"

    @Test
    fun `登记表覆盖触控与系统配置两类键`() {
        val keys = OwnedKeys.all
        // 触控：现在只剩 4 个 settings 旋钮（按下防抖 4 个 + 指针速度）
        assertTrue("应有触控 settings 键", keys.any { it.key == "pointer_speed" })
        assertTrue("应有按下防抖键", keys.any { it.key == "touch_scan_press_time_delay" })
        // 系统配置：性能模式现在只有主键是候选键之一，但都在登记表里
        assertTrue("应有性能模式键", keys.any { it.key == "power_save_type" })
        assertTrue("应有 Monster 可选键", keys.any { it.key == "monster_key_start" })
    }

    @Test
    fun `登记键的键名格式合法`() {
        OwnedKeys.all.forEach { owned ->
            assertTrue("表名必须小写: ${owned.table}", owned.table == owned.table.lowercase())
            assertTrue("表名必须在三张表之内: ${owned.table}", owned.table in setOf("system", "secure", "global"))
            assertTrue("键名不能为空", owned.key.isNotBlank())
            assertTrue("键名里不能有空格: ${owned.key}", !owned.key.contains(' '))
        }
    }

    @Test
    fun `登记键不重复`() {
        val ids = OwnedKeys.all.map { "${it.table} ${it.key}" }
        assertEquals("登记键不许重复，否则备份里同一条出现两次", ids.size, ids.toSet().size)
    }

    @Test
    fun `两个功能写同一个键时，登记表只留一条`() {
        // 实测现存一对：AI 预加载和 AI 加速配置都写 system ai_turbo_enabled。
        // 留两条会让备份文件里同一条出现两次，看的人以为有两个键。
        val ids = OwnedKeys.all.map { "${it.table} ${it.key}" }
        val dupes = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("登记键有重复：$dupes", dupes.isEmpty())
        assertEquals("只准有一个 ai_turbo_enabled", 1,
            OwnedKeys.all.count { it.key == "ai_turbo_enabled" })
    }

    @Test
    fun `只读项仍要进登记表`() {
        // touch_response_delay 是只读项（开关是空动作），但键仍要在登记表里 ——
        // 备份、漂移监测都从 TouchItems.knobs 推导，漏掉它这一项就从所有机制里消失。
        assertTrue(OwnedKeys.all.any { it.key == "touch_response_delay" })
        val item = TouchItems.all().first { it.id == TouchItems.RESPONSE_DELAY }
        assertTrue("只读项必须有旋钮，否则不进登记表", item.knobs.isNotEmpty())
        assertTrue("只读项要标 readOnly", item.readOnly)
    }

    @Test
    fun `漂移监测要覆盖触控 prop`() {
        // 早先只采 settings 键，五个触控子项里四个只写 prop 的全然隐形 ——
        // 用户开了它们，监测报"没有任何改动"。那不是没变化，是没在看。
        val ids = OwnedKeys.propIds
        assertTrue("应有 report_rate prop", ids.any { it.endsWith("debug.touch.report_rate") })
        assertTrue("应有 disable_vsync prop", ids.any { it.endsWith("debug.hwui.disable_vsync") })
        // 前缀必须是 prop，不能和 settings 三张表混
        ids.forEach { assertTrue("prop 标识要以 prop 开头: $it", it.startsWith("prop ")) }
        // 而且不能进备份：备份文件里出现 prop 行必须被丢掉
        assertNull(OwnedKeys.decodeLine("prop	debug.touch.report_rate	360"))
    }

    @Test
    fun `编解码往返不丢值`() {
        val entries = OwnedKeys.all.map { OwnedKey(it.table, it.key, it.ownerRes, it.noteRes) to "v-${it.key}" }
            .map { StateBackup.Entry(it.first, it.second) }
        val text = StateBackup.encode(entries, ::label, uptimeMinutes = 3)
        val back = StateBackup.decode(text)
        assertEquals(entries.size, back.size)
        entries.forEach { original ->
            val found = back.first { it.owned.table == original.owned.table && it.owned.key == original.owned.key }
            assertEquals(original.value, found.value)
        }
    }

    @Test
    fun `值里有逗号冒号等号引号都不影响解析`() {
        // 真实存在的坑：vts_game_para_adjust 是 "1,6,6,6"，
        // vivo_touchscreen_back_gesture 是一整段带冒号和引号的 JSON。
        val owned = OwnedKeys.all.first { it.key == "vts_game_para_adjust" }
        val tricky = """1,6,6,6 and: {"a":"b"} = c, d"""
        val line = OwnedKeys.encodeLine(owned, tricky)
        val decoded = OwnedKeys.decodeLine(line)
        assertNotNull("带特殊字符的值必须能解回来", decoded)
        assertEquals(tricky, decoded!!.second)
    }

    @Test
    fun `空值在两层里的语义`() {
        val owned = OwnedKeys.all.first { it.key == "monster_key_start" }
        // 底层：行级解析保持原样，空值就是空串（不知道这是"不存在"还是"空"）
        val raw = OwnedKeys.decodeLine(OwnedKeys.encodeLine(owned, null))
        assertNotNull(raw)
        assertEquals("行级解析保留空串", "", raw!!.second)
        // 上层：StateBackup 把空串归一成 null，代表"这个键当时不存在"
        val text = StateBackup.encode(listOf(StateBackup.Entry(owned, null)), ::label, uptimeMinutes = 3)
        val back = StateBackup.decode(text)
        assertEquals(1, back.size)
        assertNull("编解码后空值必须是 null，恢复时要删除而不是写空串", back.first().value)
    }

    @Test
    fun `认不出的键一律忽略`() {
        // 备份文件可能来自别的机型、被手改过、或由别的工具生成。
        // 认不出的键必须当不存在，绝不参与恢复。
        assertNull(OwnedKeys.decodeLine("system\tsome_unknown_key=1"))
        assertNull(OwnedKeys.decodeLine("settings\tsystem=1"))
        assertNull(OwnedKeys.decodeLine("注释行"))
        assertNull(OwnedKeys.decodeLine(""))
        assertNull(OwnedKeys.decodeLine("system"))
        // 表名对但键名不在登记表里
        assertNull(OwnedKeys.decodeLine("system\tnot_a_registered_key=1"))
    }

    @Test
    fun `功能名从登记表取，不从文件读`() {
        // 来路不明的备份里若写了个别的功能名，也不能改变写哪几个键。
        val owned = OwnedKeys.all.first { it.key == "power_save_type" }
        val text = StateBackup.encode(listOf(StateBackup.Entry(owned, "6")), ::label, uptimeMinutes = 3)
        // 手工篡改注释行
        val tampered = text.replaceFirst("# label-", "# 被篡改的名字 ")
        val back = StateBackup.decode(tampered)
        assertEquals(1, back.size)
        assertEquals(owned.ownerRes, back.first().owned.ownerRes)
    }

    @Test
    fun `decode 跳过所有注释行`() {
        val owned = OwnedKeys.all.first { it.key == "pointer_speed" }
        val text = StateBackup.encode(listOf(StateBackup.Entry(owned, "7")), ::label, uptimeMinutes = 3)
        // 注释行数量很多（表头 + 功能名），一行都不该被当成数据
        assertTrue("表头有 6 行注释，加功能名 1 行 = 7", text.lines().count { it.startsWith("#") } >= 7)
        assertEquals(1, StateBackup.decode(text).size)
    }

    @Test
    fun `漂移检测只报被动过的键`() {
        val keys = OwnedKeys.all.take(3).map { "${it.table} ${it.key}" }
        val first = mapOf(keys[0] to "1", keys[1] to "2", keys[2] to null)
        val second = mapOf(keys[0] to "1", keys[1] to "9", keys[2] to "0")
        val changed = StateBackup.drift(first, second)
        // keys[0] 没变，不该出现；keys[1] 值变了；keys[2] 从不存在变成存在
        assertEquals(2, changed.size)
        assertEquals(keys[1], changed[0].first)
        assertEquals("2", changed[0].second)
        assertEquals("9", changed[0].third)
        assertEquals(keys[2], changed[1].first)
        assertNull("旧值必须原样带出，不能塞占位符", changed[1].second)
        assertEquals("0", changed[1].third)
    }

    @Test
    fun `两次采样完全相同则没有漂移`() {
        val keys = OwnedKeys.all.take(2).map { "${it.table} ${it.key}" }
        val sample = mapOf(keys[0] to "1", keys[1] to null)
        assertTrue(StateBackup.drift(sample, sample).isEmpty())
    }

    @Test
    fun `备份头部带开机时长，并且不承诺出厂值`() {
        val owned = OwnedKeys.all.first { it.key == "pointer_speed" }
        val text = StateBackup.encode(listOf(StateBackup.Entry(owned, "0")), ::label, uptimeMinutes = 3)
        assertTrue("应有开机时长行", text.lines().any { it.startsWith("# 本次开机已运行") })
        // 这句话是这个功能最重要的免责：备份是快照，不是出厂值保证。
        assertTrue("必须说明不等于出厂值", text.contains("不等于出厂值"))
        // 也不该再出现旧的错误承诺
        assertFalse("不许再写取到的是出厂值", text.contains("取到的是出厂值"))
    }

    // ---------- UI 契约 ----------
    //
    // 重构 UI 最容易丢的不是整块功能，而是"某个键还要显示当前值"这种细节 ——
    // 它只存在于 composable 里，重写的人看不出它曾经存在。所以把这些要求挪到数据上，
    // 再用这些测试钉住：数据缺字段、删字段、加功能忘配文案，都会在这里失败。

    @Test
    fun `每个系统配置项都配齐了界面要用的三段文案`() {
        // labelRes 是行标题，detailRes 是说明，trialHintRes 是试用时该看什么。
        // 任何一项缺 0，界面上就会出现空白或者只有英文键名。
        VivoFeature.entries.forEach { feature ->
            assertTrue("${feature.name} 缺标签文案", feature.labelRes != 0)
            assertTrue("${feature.name} 缺说明文案", feature.detailRes != 0)
            assertTrue("${feature.name} 缺试用提示 —— 试用页会用默认文案，人不知道看哪儿",
                feature.trialHintRes != 0)
        }
    }

    @Test
    fun `每个触控子项都配齐了界面要用的字段`() {
        TouchItems.all().forEach { item ->
            assertTrue("${item.id} 缺标签文案", item.labelRes != 0)
            assertTrue("${item.id} 缺说明文案", item.detailRes != 0)
            // 证据等级不是可选项：不标就等于把"写成功"当成"生效"骗人
            assertTrue("${item.id} 缺证据等级", item.evidence != null)
            // 要显示当前值的项必须有旋钮可读，否则界面上只会出现"读不到"
            if (item.showsValues) {
                assertTrue("${item.id} 标了显示当前值却一个旋钮都没有", item.knobs.isNotEmpty())
            }
            // 只读项必须也标了显示当前值，否则它什么都不显示
            if (item.readOnly) {
                assertTrue("${item.id} 是只读项却不显示当前值", item.showsValues)
            }
        }
    }

    @Test
    fun `要显示当前值的触控项都有明确定义`() {
        // 把"哪些项该显示当前值"钉住。重构时删掉 showsValues 标记，这个测试会失败，
        // 于是不会 silently 丢掉"按下防抖显示四个值"这个细节。
        val expected = setOf(TouchItems.PRESS_DELAY, TouchItems.RESPONSE_DELAY)
        val actual = TouchItems.all().filter { it.showsValues }.map { it.id }.toSet()
        assertEquals("该显示当前值的子项集合变了", expected, actual)
    }

    @Test
    fun `登记表覆盖全部会写键的功能，包括只读项`() {
        // 只读项（response_delay）也必须进登记表，否则备份和漂移监测都看不见它。
        TouchItems.all().forEach { item ->
            item.knobs.filter { it.ns != TouchNs.PROP }.forEach { knob ->
                assertTrue("触控键 ${knob.key} 不在登记表里，备份会漏掉它",
                    OwnedKeys.all.any { it.key == knob.key })
            }
        }
        VivoFeature.entries.filter { it.values.isNotEmpty() }.forEach { feature ->
            feature.values.keys.forEach { spec ->
                val key = spec.substringAfter(' ')
                assertTrue("${feature.name} 的键 $key 不在登记表里",
                    OwnedKeys.all.any { it.key == key })
            }
        }
    }

    @Test
    fun `试用页能列出的项都有试用提示`() {
        // 试用页过滤掉 TIMED 和 AI；剩下的每一项都要有提示，否则人不知道看哪儿。
        val listed = VivoFeature.entries.filter {
            it.lifetime != TweakLifetime.TIMED && it != VivoFeature.AI
        }
        assertTrue("试用页不该是空的", listed.isNotEmpty())
        listed.forEach { assertTrue("${it.name} 缺试用提示", it.trialHintRes != 0) }
    }

    @Test
    fun `性能模式档位的可选键判定`() {
        val enhanced = TweakValues.PERF_LEVELS.first { it.id == "enhanced" }
        val monster = TweakValues.PERF_LEVELS.first { it.id == "monster" }
        // 只有主键时：增强可用，Monster 不可用
        val onlyMain = TweakValues.MAIN_KEYS.toSet()
        assertTrue(enhanced.supported(onlyMain))
        assertFalse("缺 monster_key_start 时 Monster 必须置灰", monster.supported(onlyMain))
        // 键齐了两档都可用
        val all = onlyMain + setOf("system monster_key_start")
        assertTrue(monster.supported(all))
        // 连主键都没有，什么都不给用
        assertFalse(enhanced.supported(emptySet()))
    }

    @Test
    fun `性能模式写系统自己也写的三个主键`() {
        // 真机实测：用户在系统里开"增强"档，三个键同时 1→6。只写一个是错的 ——
        // 早前那么做，依据是一次被 grep 滤掉 secure 表的快照对比。
        val written = TweakValues.performanceMode("enhanced", TweakValues.MAIN_KEYS.toSet())!!
        assertEquals(3, written.size)
        TweakValues.MAIN_KEYS.forEach { key -> assertEquals("6", written[key]) }
    }

    @Test
    fun `可选键只写本机存在的`() {
        val onlyMain = TweakValues.MAIN_KEYS.toSet()
        val written = TweakValues.performanceMode("monster", onlyMain)!!
        assertEquals("可选键本机没有就不写", 3, written.size)
        assertFalse("monster_key_start 本机没有，不许写", written.containsKey("system monster_key_start"))

        val full = onlyMain + setOf("system monster_key_start")
        val written2 = TweakValues.performanceMode("monster", full)!!
        assertEquals(4, written2.size)
        assertEquals("1", written2["system monster_key_start"])
    }

    @Test
    fun `档位可用性按三个主键加可选键判断`() {
        val onlyMain = TweakValues.MAIN_KEYS.toSet()
        assertTrue(TweakValues.PERF_LEVELS.first { it.id == "enhanced" }.supported(onlyMain))
        assertFalse(TweakValues.PERF_LEVELS.first { it.id == "monster" }.supported(onlyMain))
        // 少了三个主键里的任意一个都不给用
        TweakValues.MAIN_KEYS.forEach { missing ->
            val partial = onlyMain - missing
            assertFalse("少 $missing 时不该给用",
                TweakValues.PERF_LEVELS.first { it.id == "enhanced" }.supported(partial))
        }
    }

    @Test
    fun `未知档位返回 null`() {
        assertNull(TweakValues.performanceMode("ultra_beast", TweakValues.MAIN_KEYS.toSet()))
        assertNull(TweakValues.performanceMode(null, emptySet()))
    }

    @Test
    fun `候选键里有两个系统从不读取`() {
        // 它们在 VivoFeature 的候选键里（为了备份覆盖），但不进写入集。
        TweakValues.MAIN_KEYS.forEach { key ->
            assertFalse("主键不该包含 $key", TweakValues.MAIN_KEYS.contains("system game_plus_mode_key"))
            assertFalse("主键不该包含 monster_key_start", key.endsWith("monster_key_start"))
        }
        assertTrue("game_plus_mode_key 仍要在登记表里（备份覆盖）",
            OwnedKeys.all.any { it.key == "game_plus_mode_key" })
    }

    @Test
    fun `档位编号与文案一一对应`() {
        assertEquals(4, TweakValues.PERF_LEVELS.size)
        assertEquals(setOf("enhanced", "boost_plus", "monster", "monster_plus"),
            TweakValues.PERF_LEVELS.map { it.id }.toSet())
        // 编号是外部取值、不是公开语义，任何一项都不许是空的
        TweakValues.PERF_LEVELS.forEach { level ->
            assertTrue("${level.id} 编号不能为空", level.value.isNotBlank())
            assertTrue("${level.id} 要有文案", level.labelRes != 0)
        }
    }
}
