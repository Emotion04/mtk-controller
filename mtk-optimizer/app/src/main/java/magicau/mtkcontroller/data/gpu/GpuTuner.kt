package magicau.mtkcontroller.data.gpu

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.GpuChannel
import magicau.mtkcontroller.domain.model.GpuInfo

/**
 * Applies GPU frequency limits on whichever interface the device exposes.
 *
 * MTK's GPU DVFS is firmware-managed, so vendor power services can overwrite
 * what we set. The foreground service re-applies periodically for that reason.
 */
class GpuTuner {

    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
    )

    /**
     * 读一次 devfreq 的当前 min/max，用于**写之前**落盘原值。
     *
     * 原值由 `ProfileRepository` 持久化，不放在这里的内存字段里 ——
     * 进程被杀之后内存字段就没了，恢复时只剩"硬件范围"可写，
     * 那会把用户或散热压出来的上限覆盖成硬件最高频。
     *
     * @return null = 这两条路径不是 devfreq，或节点读不到。
     */
    suspend fun sampleDevfreqBaseline(): Pair<Long?, Long?>? {
        val root = GpuScanner.DEVFREQ_ROOT
        val min = Sysfs.read("$root/min_freq")?.toLongOrNull()
        val max = Sysfs.read("$root/max_freq")?.toLongOrNull()
        return if (min == null && max == null) null else min to max
    }

    /**
     * @param minKhz desired floor; null = leave alone
     * @param maxKhz desired ceiling; null = leave alone
     */
    suspend fun apply(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        if (!gpu.controllable) return Outcome(false, Message(R.string.gpu_no_interface))
        if (minKhz == null && maxKhz == null) return Outcome(false, Message(R.string.gpu_no_freq_set))

        return when (gpu.channel) {
            GpuChannel.DEVFREQ -> applyDevfreq(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V2 -> applyGpufreqV2(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V1 -> applyGpufreqV1(gpu, minKhz, maxKhz)
            GpuChannel.NONE -> Outcome(false, Message(R.string.gpu_no_interface))
        }
    }

    private suspend fun applyDevfreq(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val failures = mutableListOf<String>()
        val root = GpuScanner.DEVFREQ_ROOT

        // Raise the ceiling before lowering the floor, so min <= max always holds.
        // 写失败时把服务端原话收着 —— 那是判断"该去哪儿修"的唯一依据。
        val writeErrors = mutableListOf<String>()
        maxKhz?.let {
            Sysfs.writeOrNull("$root/max_freq", it.toString())?.let { e ->
                failures += "max_freq"; writeErrors += e
            }
        }
        minKhz?.let {
            Sysfs.writeOrNull("$root/min_freq", it.toString())?.let { e ->
                failures += "min_freq"; writeErrors += e
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, Message(R.string.gpu_range_applied))
        } else {
            Outcome(
                false,
                Message(R.string.gpu_write_failed, listOf(failures.joinToString())),
                detail = writeErrors.joinToString("\n").ifBlank { null },
            )
        }
    }

    private suspend fun applyGpufreqV2(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        // v2 fixes a single OPP; without a range concept we pin the ceiling
        // (or the floor when no ceiling was given) and report that.
        val target = maxKhz ?: minKhz ?: return Outcome(false, Message(R.string.gpu_no_freq_set))
        val index = gpu.oppIndexFor(target)
            ?: return Outcome(false, Message(R.string.gpu_opp_map_failed, listOf(target / 1000)))
        val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V2_FIX_OPP, index.toString())
        return if (writeError == null) {
            Outcome(true, Message(R.string.gpu_locked_opp, listOf(index, target / 1000)))
        } else {
            Outcome(false, Message(R.string.gpu_write_opp_failed), detail = writeError)
        }
    }

    private suspend fun applyGpufreqV1(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val target = maxKhz ?: minKhz ?: return Outcome(false, Message(R.string.gpu_no_freq_set))
        val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V1_OPP_FREQ, target.toString())
        return if (writeError == null) {
            Outcome(true, Message(R.string.gpu_locked_mhz, listOf(target / 1000)))
        } else {
            Outcome(false, Message(R.string.gpu_write_opp_freq_failed), detail = writeError)
        }
    }

    /**
     * 释放 GPU 锁。
     *
     * @param baseline devfreq 的原值（写之前落盘的那份）。null = 没有记录，
     *   退回硬件范围，并如实说明这是兜底而不是原值。
     */
    suspend fun release(gpu: GpuInfo, baseline: Pair<Long?, Long?>?): Outcome {
        return when (gpu.channel) {
            GpuChannel.GPUFREQ_V2 -> {
                val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V2_FIX_OPP, "-1")
                Outcome(
                    writeError == null,
                    Message(if (writeError == null) R.string.gpu_lock_released else R.string.gpu_release_failed),
                    detail = writeError,
                )
            }

            GpuChannel.GPUFREQ_V1 -> {
                val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V1_OPP_FREQ, "0")
                Outcome(
                    writeError == null,
                    Message(if (writeError == null) R.string.gpu_lock_released else R.string.gpu_release_failed),
                    detail = writeError,
                )
            }

            GpuChannel.DEVFREQ -> {
                val root = GpuScanner.DEVFREQ_ROOT
                val (beforeMin, beforeMax) = baseline ?: devfreqHardwareRange()
                val writeErrors = mutableListOf<String>()
                // Ceiling first, so the floor written next can never exceed it.
                beforeMax?.let {
                    Sysfs.writeOrNull("$root/max_freq", it.toString())?.let(writeErrors::add)
                }
                beforeMin?.let {
                    Sysfs.writeOrNull("$root/min_freq", it.toString())?.let(writeErrors::add)
                }
                val ok = writeErrors.isEmpty()
                Outcome(
                    ok,
                    Message(if (ok) R.string.gpu_range_restored else R.string.gpu_restore_failed),
                    detail = writeErrors.joinToString("\n").ifBlank { null },
                )
            }

            GpuChannel.NONE -> Outcome(false, Message(R.string.gpu_no_interface))
        }
    }

    /**
     * Fallback for when no snapshot exists — the app restarted while a range was
     * still in force. The hardware range from `available_frequencies` is the
     * honest answer; reading back the live min/max would return our own lock and
     * call it a restore.
     */
    private suspend fun devfreqHardwareRange(): Pair<Long?, Long?> {
        val freqs = Sysfs.read("${GpuScanner.DEVFREQ_ROOT}/available_frequencies")
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.sorted().orEmpty()
        return (freqs.firstOrNull() to freqs.lastOrNull())
    }
}
