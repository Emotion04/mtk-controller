package magicau.mtkcontroller.data.diag

import org.junit.Assert.*
import org.junit.Test
import magicau.mtkcontroller.data.probe.ProbeProcess

class RomControlProbeTest {
    @Test fun configSelectionRejectsTraversalDiagnosticsAndExecutableFiles() {
        val input = "/vendor/etc/power.xml\n/vendor/etc/power.xml\n/vendor/etc/../../data/private.xml\n/data/local/tmp/perf.xml\n/vendor/etc/perf.sh\nPermission denied\n/odm/etc/perf/config.json"
        assertEquals(listOf("/vendor/etc/power.xml", "/odm/etc/perf/config.json"), RomControlProbe.configPaths(input))
        assertEquals(12, RomControlProbe.configPaths((1..30).joinToString("\n") { "/vendor/etc/perf$it.xml" }).size)
    }
    @Test fun deniedServiceDoesNotPreventConfigCollection() {
        var readConfig = false
        val report = RomControlProbe.collect { command, _, _ ->
            when {
                command.contains("vendor.vivoperfservice") -> ProbeProcess.Result("Permission denied", 17, false, 1, false)
                command.last().contains("[ -f") -> ProbeProcess.Result("/vendor/etc/powerhint.xml", 25, false, 0, false)
                command.last() == "/vendor/etc/powerhint.xml" -> {
                    readConfig = true
                    assertEquals("/vendor/etc/powerhint.xml", command.last())
                    ProbeProcess.Result("<config/>", 9, false, 0, false)
                }
                else -> ProbeProcess.Result("", 0, false, 0, false)
            }
        }
        assertTrue(readConfig); assertTrue(report.contains("status=DENIED"))
        assertTrue(report.contains("<config/>")); assertTrue(report.contains("ROM_PROBE_COMPLETE"))
    }
    @Test fun repeatedTimeoutsStillFinishWithoutFrequencyWrites() {
        var calls = 0
        val report = RomControlProbe.collect { command, timeout, _ ->
            calls++
            assertEquals(1800L, timeout)
            assertFalse(command.contains("setprop"))
            assertFalse(command.contains("call"))
            ProbeProcess.Result("partial", 7, false, null, true)
        }
        assertEquals(11, calls)
        assertTrue(report.contains("ROM_PROBE_COMPLETE issues=11 configs=0"))
    }
    @Test fun sourceFailureDoesNotAbortAndQosContentMustBeRecognized() {
        val report = RomControlProbe.collect { command, _, _ ->
            when {
                command.last() == "/dev/thermal/thermal_data_summary.conf" -> error("unreadable")
                command.last() == "-h" -> ProbeProcess.Result("qos commands", 12, false, 0, false)
                command.last() == "5" -> ProbeProcess.Result("unrecognized command", 20, false, 0, false)
                else -> ProbeProcess.Result("ok", 2, false, 0, false)
            }
        }
        assertTrue(report.contains("status=IO_ERROR"))
        assertTrue(report.contains("QOS_CONTENT_CONFIRMED=false"))
        assertTrue(report.contains("ROM_PROBE_COMPLETE"))
    }
}
