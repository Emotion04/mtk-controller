package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.*
import org.junit.Test

class TweakValuesTest {
    @Test fun animationAcceptsEveryTenthAndRejectsInvalidValues() {
        for (i in 0..100) assertEquals("${i / 10}.${i % 10}", TweakValues.animation("${i / 10}.${i % 10}"))
        listOf(null, "", "NaN", "Infinity", "-0.1", "10.1", "0.15", "1;id").forEach { assertNull(TweakValues.animation(it)) }
        assertEquals("1.0", TweakValues.animation("1.00"))
    }
    @Test fun equivalentAnimationReadbackDoesNotBecomeFalseFailure() {
        assertTrue(TweakValues.matches("global animator_duration_scale", "1.0", "1"))
        assertFalse(TweakValues.matches("global animator_duration_scale", "0.1", "0.2"))
        assertFalse(TweakValues.matches("system vivo_gamethermal_switch", "1.0", "1"))
        assertFalse(TweakValues.matches("global animator_duration_scale", "1", null))
    }
    @Test fun legacyAnimationSnapshotKeyIsUnchanged() {
        assertEquals(setOf("global animator_duration_scale"), VivoFeature.ANIMATION.values.keys)
        val all = listOf(VivoFeature.ANIMATION, VivoFeature.WINDOW_ANIMATION, VivoFeature.TRANSITION_ANIMATION)
        assertEquals(3, all.flatMap { it.values.keys }.toSet().size)
        assertNull(VivoTweaks.conflictingFeature(VivoFeature.WINDOW_ANIMATION, setOf(VivoFeature.ANIMATION)))
    }
    @Test fun htpIsRestrictedToModulePresetsAndExperimentsRejectOtherKeys() {
        assertNull(TweakValues.htp("custom:2:99"))
        assertEquals("48-52-58-1000-1000-1000", TweakValues.htp("medium")!!["system pem_htp_thred"])
        assertThrows(IllegalArgumentException::class.java) {
            SettingExperiment.script(mapOf("global low_power" to "0"), mapOf("global low_power" to "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingExperiment.script(VivoFeature.GAME_THERMAL.values, emptyMap())
        }
    }

    @Test fun chargeLimitOnlyAcceptsNormalIntegers() {
        assertEquals("80", TweakValues.chargeLimit("80"))
        assertEquals("1", TweakValues.chargeLimit(" 1 "))
        assertEquals("100", TweakValues.chargeLimit("100"))
        listOf(null, "", "0", "101", "-1", "abc", "80.5", "1e2", "80;id").forEach {
            assertNull("应该拒绝 $it", TweakValues.chargeLimit(it))
        }
    }

    @Test fun chargeLimitFeatureHasNoBuiltInValue() {
        // 取值语义不明，所以枚举里不放任何预设值 —— 否则用户什么都不选也会写一个错值。
        assertEquals(setOf(""), VivoFeature.CHARGE_LIMIT.values.values.toSet())
        assertTrue(VivoFeature.CHARGE_LIMIT.allowMissing)
    }

    @Test fun touchAdjustValuesMustBeCommaSeparatedIntegers() {
        // 真机现值是 1,6,6,6，外部实现是 1,7,7,7 和 0,3,3,3 —— 三者互不相同，
        // 说明机型相关，所以只校验形式，不规定内容。
        assertEquals("1,6,6,6", TweakValues.touchAdjust("1,6,6,6"))
        // 用户可能带空格输入；归一化成不带空格再拼命令，避免把空格带进 shell。
        assertEquals("1,6,6,6", TweakValues.touchAdjust("1, 6, 6, 6"))
        listOf(null, "", "1", "1,2", "1,2,3,4,5,6,7", "a,b,c,d", "1;id", "1,2,-3,4", "1,2,1000,4")
            .forEach { assertNull("应该拒绝 $it", TweakValues.touchAdjust(it)) }
    }

    @Test fun touchBoostDoesNotHardcodeTheReferenceValue() {
        // 照抄外部实现的 1,7,7,7 会覆盖掉本机的 1,6,6,6。
        assertEquals(setOf(""), VivoFeature.TOUCH_BOOST.values.values.toSet())
        assertTrue(VivoFeature.TOUCH_BOOST.allowMissing)
    }

    @Test fun directPowerWritesOnlyTheDocumentedKey() {
        // 其余几个键的写入值没有可靠来源，猜一个值写进影响电池的键，读回也是成功的。
        assertEquals(mapOf("secure settings_battery_charge_director" to "1"), VivoFeature.DIRECT_POWER.values)
        assertNull(VivoTweaks.conflictingFeature(VivoFeature.DIRECT_POWER, setOf(VivoFeature.CHARGE_LIMIT)))
    }
}
