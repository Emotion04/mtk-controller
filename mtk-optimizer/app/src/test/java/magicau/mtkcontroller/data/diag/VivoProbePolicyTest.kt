package magicau.mtkcontroller.data.diag

import magicau.mtkcontroller.data.privilege.VivoProbePolicy
import org.junit.Assert.*
import org.junit.Test

class VivoProbePolicyTest {
    @Test fun cameraComparisonUsesDifferentResourceFromLimitAndShortDuration() {
        for (value in listOf(9, 71)) {
            val policy = VivoProbePolicy.select(value)
            assertEquals(0xF0100000.toInt(), policy.resource)
            assertEquals(value, policy.value)
            assertEquals(2000, policy.durationMs)
        }
        assertEquals(0xF0000C00.toInt(), VivoProbePolicy.select(0).resource)
        assertEquals(88000003, VivoProbePolicy.select(0).value)
        val sourceMax = VivoProbePolicy.select(100)
        assertEquals(5000, sourceMax.durationMs)
        assertEquals(listOf(
            0x40804000 to 4095,
            0x40804100 to 4095,
            0x40804200 to 4095,
        ), sourceMax.commands)
        assertThrows(IllegalStateException::class.java) { VivoProbePolicy.select(72) }
    }
}
