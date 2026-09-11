package magicau.mtkcontroller.data.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import magicau.mtkcontroller.data.settings.SettingChannel

/**
 * [SettingChannel] 的命令生成测试。
 *
 * 钉住的是一条容易回归的铁律：**settings 写入只能走 `content insert`**。
 * 这台 ROM 上 `settings put` 退出码 0 但不落库，谁把它写回去，功能就会静默失效。
 */
class SettingChannelTest {

    @Test
    fun `put 命令用 content insert 而不是 settings put`() {
        val command = SettingChannel.putCommand("system", "touch_scan_press_time_delay", "300")
        assertTrue(command.startsWith("content insert --uri content://settings/system"))
        assertTrue(command.contains("--bind name:s:'touch_scan_press_time_delay'"))
        assertTrue(command.contains("--bind value:s:'300'"))
        assertFalse(command.contains("settings put"))
    }

    @Test
    fun `delete 命令用 content delete 加 where`() {
        val command = SettingChannel.deleteCommand("secure", "settings_battery_charge_director")
        assertTrue(command.startsWith("content delete --uri content://settings/secure"))
        assertTrue(command.contains("--where \"name='settings_battery_charge_director'\""))
        assertFalse(command.contains("settings delete"))
    }

    @Test
    fun `命名空间透传`() {
        listOf("system", "secure", "global").forEach { ns ->
            val command = SettingChannel.putCommand(ns, "k", "v")
            assertTrue("命名空间 $ns 没进 URI", command.contains("content://settings/$ns"))
        }
    }

    @Test
    fun `键名和值里的单引号必须转义`() {
        // 不转义会提前结束引号段，把命令截断成另一个意思。
        val command = SettingChannel.putCommand("system", "a'b", "c'd")
        assertTrue(command.contains("--bind name:s:'a'\\''b'"))
        assertTrue(command.contains("--bind value:s:'c'\\''d'"))
    }

    @Test
    fun `带逗号和点号的值原样保留`() {
        // vts_game_para_adjust 是 1,6,6,6；动画倍率是 0.5 —— 都不能被拆开或改写。
        val adjust = SettingChannel.putCommand("system", "vts_game_para_adjust", "1,6,6,6")
        assertTrue(adjust.contains("--bind value:s:'1,6,6,6'"))
        assertTrue(SettingChannel.putCommand("global", "animator_duration_scale", "0.5")
            .contains("--bind value:s:'0.5'"))
    }

    @Test
    fun `delete 命令的 where 也转义单引号`() {
        val command = SettingChannel.deleteCommand("system", "a'b")
        assertTrue(command.contains("name='a'\\''b'"))
        // where 子句必须带引号，否则空值或特殊字符会让 SQL 片段失效。
        assertTrue(command.contains("\"name='"))
        assertTrue(command.trimEnd().endsWith("\""))
    }
}
