package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.*
import org.junit.Test

class VivoReplyTest {
    @Test fun acceptsSuccessfulCapabilityParcel() {
        assertEquals(2, VivoReply.touchMax("Result: Parcel(00000000 00000002   '........')\n[exit] 0"))
        assertEquals(8, VivoReply.touchMax("Result: Parcel(\n  0x00000000: 00000000 00000008 '........'\n)\n[exit] 0"))
    }
    @Test fun rejectsExceptionsTimeoutsAndOutOfRangeValues() {
        listOf(
            "Result: Parcel(ffffffff 00000002)\n[exit] 0",
            "Result: Parcel(00000000 00000009)\n[exit] 0",
            "Result: Parcel(00000000 00000000)\n[exit] 0",
            "Result: Parcel(00000000 00000002)\n[exit] 124",
            "Result: Parcel(00000000 00000002)\n[stderr] denied\n[exit] 0",
            "service not found\n[exit] 0",
        ).forEach { assertNull(it, VivoReply.touchMax(it)) }
        assertNull(VivoReply.touchMax(null))
    }
    @Test fun rejectsSettingsErrorsEvenWithZeroExitCode() {
        assertNull(VivoReply.output("Error: permission denial\n[exit] 0"))
        assertNull(VivoReply.output("Exception occurred while executing\n[exit] 0"))
        assertEquals("null", VivoReply.output("null\n[exit] 0"))
    }
    @Test fun escapesRestoredSettingsWithoutExecutingShellText() {
        assertEquals("'a'\\''b'", VivoReply.quote("a'b"))
        assertEquals("'$(id);test'", VivoReply.quote("$(id);test"))
    }
}
