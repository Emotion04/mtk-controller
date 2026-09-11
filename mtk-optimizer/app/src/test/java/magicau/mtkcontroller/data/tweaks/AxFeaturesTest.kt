package magicau.mtkcontroller.data.tweaks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import magicau.mtkcontroller.data.privilege.DisplayRequest

class AxFeaturesTest {
    @Test fun rejectsDnsUrlsPortsInjectionAndIpAddresses() {
        listOf("", "https://dns.example.com", "dns.example.com:853", "1.1.1.1", "a;id.com", "a'.com", "a..com", "-a.example", "a-.example", "a." )
            .forEach { assertFalse(it, VivoTweaks.validDnsHost(it)) }
        assertTrue(VivoTweaks.validDnsHost("dns.example.com"))
        assertTrue(VivoTweaks.validDnsHost(" DoT.Example.com "))
    }
    @Test fun failedRestoreDoesNotSkipOtherKeys() = runBlocking {
        val visited = mutableListOf<String>()
        val result = SettingBatch.write(linkedMapOf("first" to "null", "second" to "1", "third" to "0")) { key, _ ->
            visited += key
            if (key == "first") error("permission denied")
            // writer 的约定：null = 写成了，非 null = 没写成的原话。
            if (key == "third") null else "拒绝"
        }
        assertEquals(listOf("first", "second", "third"), visited)
        assertEquals(listOf("first", "second"), result.failures)
        // 原话也要在，而且和键名同序 —— 界面的"显示更多"靠它。
        assertEquals(listOf("first", "second"), result.reasons.map { it.substringBefore(" →") })
    }
    @Test fun rmsUsesCurrentRomLayoutAndBoundedLease() {
        val args = DisplayRequest.arguments(120)
        assertEquals(14, args.size)
        assertEquals(120, args[2])
        assertEquals(15000, args[7])
        assertEquals(0, args[8])
        assertEquals(0, args[9])
        assertEquals(0, args[13])
        assertThrows(IllegalArgumentException::class.java) { DisplayRequest.arguments(0) }
        assertThrows(IllegalArgumentException::class.java) { DisplayRequest.arguments(1000) }
    }
}
