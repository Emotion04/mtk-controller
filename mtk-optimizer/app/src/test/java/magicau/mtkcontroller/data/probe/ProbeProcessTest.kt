package magicau.mtkcontroller.data.probe

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import magicau.mtkcontroller.data.probe.ProbeProcess
import magicau.mtkcontroller.data.diag.CameraCollector

/** Real child JVM: portable process tests, no phone or shell dependency. */
class ProbeProcessTest {
    private fun command(mode: String): List<String> {
        val java = File(System.getProperty("java.home")!!, "bin/java" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "")
        val locations = listOf(ProbeChild::class.java, kotlin.Unit::class.java).map { File(it.protectionDomain!!.codeSource.location.toURI()).path }
        return listOf(java.path, "-cp", locations.joinToString(File.pathSeparator), ProbeChild::class.java.name, mode)
    }
    @Test fun largeOutputDrainsBothStreamsAndKeepsTail() {
        val r = ProbeProcess.run(command("large"), 15000, 12000)
        assertEquals(0, r.exit); assertTrue(r.bytes > 2_000_000)
        assertTrue(r.truncated); assertTrue(r.output.startsWith("START")); assertTrue(r.output.endsWith("END"))
        assertTrue(r.output.length < 12500)
    }
    @Test fun timeoutRetainsPartialOutput() {
        val r = ProbeProcess.run(command("hang"), 3000, 12000)
        assertTrue(r.timedOut); assertEquals("TIMEOUT", r.status); assertTrue(r.output.contains("START"))
    }
    @Test fun errorsEmptyAndMissingExecutableAreDistinct() {
        assertEquals("DENIED", ProbeProcess.run(command("denied"), 15000).status)
        assertEquals("EMPTY", ProbeProcess.run(command("empty"), 15000).status)
        assertEquals("COMMAND_FAILED", ProbeProcess.run(command("failed"), 15000).status)
        assertEquals("IO_ERROR", ProbeProcess.run(listOf("nonexistent-probe-command-937264")).status)
    }
    @Test fun exactRetentionBoundaryIsNotTruncated() {
        val r = ProbeProcess.run(command("exact"), 15000, 12000)
        assertEquals(12000L, r.bytes); assertFalse(r.truncated); assertEquals(12000, r.output.length)
    }
    @Test fun collectorContinuesAfterUnavailableSourcesAndBoundsReport() {
        var now = 0L
        var calls = 0
        val report = CameraCollector.collect(run = { _, _, _ ->
            calls++
            if (calls % 3 == 0) ProbeProcess.Result("Permission denied", 17, false, 1, false)
            else ProbeProcess.Result("x".repeat(12000), 20000, true, 0, false)
        }, nanoTime = { now }, sleep = { now += it * 1_000_000 })
        assertTrue(report.contains("CAMERA_PROBE_COMPLETE")); assertTrue(report.contains("SELF_CHECK"))
        assertTrue(report.contains("criticalNotFullyRead=[service:media.camera"))
        assertTrue(report.contains("REPORT_BUDGET_EXHAUSTED")); assertTrue(report.length < 240000)
    }
    @Test fun collectorPreservesEarlierEvidenceOnUnexpectedFailure() {
        var calls = 0
        val report = CameraCollector.collect(run = { _, _, _ ->
            if (++calls == 2) error("transport lost")
            ProbeProcess.Result("uid=2000", 8, false, 0, false)
        })
        assertTrue(report.contains("uid=2000")); assertTrue(report.contains("CAMERA_PROBE_FAILED"))
        assertFalse(report.contains("CAMERA_PROBE_COMPLETE"))
    }
    @Test fun collectorKeepsSamplingWhenServiceTimesOut() {
        var now = 0L
        val report = CameraCollector.collect(run = { command, _, _ ->
            now += 1_800_000_000
            if (command.contains("media.camera")) ProbeProcess.Result("partial camera", 14, false, null, true)
            else ProbeProcess.Result("data", 4, false, 0, false)
        }, nanoTime = { now }, sleep = { now += it * 1_000_000 })
        assertTrue(report.contains("status=TIMEOUT")); assertTrue(report.contains("CAMERA_PROBE_COMPLETE"))
        assertTrue(report.contains("criticalNotFullyRead=[service:media.camera]"))
        assertTrue(now < 65_000_000_000)
    }
}

object ProbeChild {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "large" -> { print("START"); repeat(300) { System.out.print("o".repeat(4096)); System.err.print("e".repeat(4096)) }; print("END") }
            "hang" -> { print("START"); System.out.flush(); Thread.sleep(30000) }
            "denied" -> { System.err.print("Permission denied"); kotlin.system.exitProcess(1) }
            "failed" -> { System.err.print("unexpected failure"); kotlin.system.exitProcess(9) }
            "exact" -> print("x".repeat(12000))
        }
    }
}
