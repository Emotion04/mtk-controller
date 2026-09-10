package magicau.mtkcontroller.data.gpu

import android.os.SystemClock
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.GpuChannel
import magicau.mtkcontroller.domain.model.GpuInfo
import magicau.mtkcontroller.domain.model.GpuOpp

/**
 * Probes whichever GPU frequency interface this device happens to expose.
 *
 * MediaTek has shipped at least three over time, so we try them in order of
 * preference and report what we actually found rather than assuming.
 *
 * Results are cached briefly: channel detection costs several shell round-trips
 * and the interface a device exposes never changes while it is running, so the
 * dashboard's sampler should not pay for it every second.
 */
class GpuScanner {

    companion object {
        const val GPUFREQ_V2_OPP_TABLE = "/proc/gpufreqv2/gpu_working_opp_table"
        const val GPUFREQ_V2_FIX_OPP = "/proc/gpufreqv2/fix_target_opp_index"
        const val GPUFREQ_V1_OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump"
        const val GPUFREQ_V1_OPP_FREQ = "/proc/gpufreq/gpufreq_opp_freq"
        const val DEVFREQ_ROOT = "/sys/class/devfreq/mtk-mali"
        const val GED_UTILIZATION = "/sys/kernel/ged/hal/gpu_utilization"

        private const val CACHE_TTL_MS = 10_000L
    }

    @Volatile
    private var cached: GpuInfo? = null

    @Volatile
    private var cachedAtMs = 0L

    /** Force the next [scan] to re-probe, e.g. from diagnostics. */
    fun invalidate() {
        cached = null
        cachedAtMs = 0L
    }

    suspend fun scan(): GpuInfo {
        val hit = cached
        if (hit != null && SystemClock.elapsedRealtime() - cachedAtMs < CACHE_TTL_MS) return hit
        return scanUncached().also {
            cached = it
            cachedAtMs = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun scanUncached(): GpuInfo {
        // GPUFreq v2 — current-generation Dimensity.
        if (Sysfs.exists(GPUFREQ_V2_FIX_OPP)) {
            val opps = parseOppTable(Sysfs.read(GPUFREQ_V2_OPP_TABLE))
            return GpuInfo(
                channel = GpuChannel.GPUFREQ_V2,
                availableFreqs = opps.map { it.freqKhz }.distinct().sorted(),
                currentFreqKhz = opps.firstOrNull { it.active }?.freqKhz,
                oppTable = opps,
            )
        }

        // GPUFreq v1 — older MediaTek.
        if (Sysfs.exists(GPUFREQ_V1_OPP_FREQ)) {
            val opps = parseOppTable(Sysfs.read(GPUFREQ_V1_OPP_DUMP))
            return GpuInfo(
                channel = GpuChannel.GPUFREQ_V1,
                availableFreqs = opps.map { it.freqKhz }.distinct().sorted(),
                currentFreqKhz = opps.firstOrNull { it.active }?.freqKhz,
                oppTable = opps,
            )
        }

        // Standard Mali devfreq — gives real min/max knobs.
        if (Sysfs.exists(DEVFREQ_ROOT)) {
            val freqs = Sysfs.read("$DEVFREQ_ROOT/available_frequencies")
                ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.sorted().orEmpty()
            return GpuInfo(
                channel = GpuChannel.DEVFREQ,
                availableFreqs = freqs,
                currentFreqKhz = Sysfs.read("$DEVFREQ_ROOT/cur_freq")?.toLongOrNull(),
                minFreqKhz = Sysfs.read("$DEVFREQ_ROOT/min_freq")?.toLongOrNull(),
                maxFreqKhz = Sysfs.read("$DEVFREQ_ROOT/max_freq")?.toLongOrNull(),
                governor = Sysfs.read("$DEVFREQ_ROOT/governor"),
            )
        }

        return GpuInfo(channel = GpuChannel.NONE)
    }

    /**
     * OPP tables look like `[0] freq: 1100000, ...` or plain whitespace columns,
     * depending on driver version. Pull every number that looks like kHz.
     */
    private fun parseOppTable(raw: String?): List<GpuOpp> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val freq = Regex("(\\d{5,8})").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val index = Regex("^\\s*\\[(\\d+)]").find(line)?.groupValues?.get(1)?.toIntOrNull()
            GpuOpp(
                index = index ?: -1,
                freqKhz = freq,
                active = line.contains("cur") || line.contains("current") || line.contains("active"),
            )
        }.distinctBy { it.freqKhz }
    }
}
