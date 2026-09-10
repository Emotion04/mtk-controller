package magicau.mtkcontroller.data.gpu

import magicau.mtkcontroller.data.powerhal.PowerHal
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

    data class Outcome(val success: Boolean, val message: String, val handler: Int? = null)

    /**
     * @param minKhz desired floor; null = leave alone
     * @param maxKhz desired ceiling; null = leave alone
     */
    suspend fun apply(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        if (!gpu.controllable) return Outcome(false, "未检测到可用的 GPU 调频接口")
        if (minKhz == null && maxKhz == null) return Outcome(false, "未设置任何频率")

        return when (gpu.channel) {
            GpuChannel.DEVFREQ -> applyDevfreq(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V2 -> applyGpufreqV2(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V1 -> applyGpufreqV1(gpu, minKhz, maxKhz)
            GpuChannel.NONE -> Outcome(false, "未检测到可用的 GPU 调频接口")
        }
    }

    private suspend fun applyDevfreq(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val failures = mutableListOf<String>()
        // Raise the ceiling before lowering the floor, so min <= max always holds.
        maxKhz?.let {
            if (!Sysfs.write("${GpuScanner.DEVFREQ_ROOT}/max_freq", it.toString())) {
                failures += "max_freq"
            }
        }
        minKhz?.let {
            if (!Sysfs.write("${GpuScanner.DEVFREQ_ROOT}/min_freq", it.toString())) {
                failures += "min_freq"
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, "GPU 频率区间已应用")
        } else {
            Outcome(false, "写入失败: ${failures.joinToString()}(可能需要 root)")
        }
    }

    private suspend fun applyGpufreqV2(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        // v2 fixes a single OPP; without a range concept we pin the ceiling
        // (or the floor when no ceiling was given) and report that.
        val target = maxKhz ?: minKhz ?: return Outcome(false, "未设置任何频率")
        val index = gpu.oppIndexFor(target)
            ?: return Outcome(false, "未能把 ${target / 1000} MHz 映射到 OPP 档位")
        val ok = Sysfs.write(GpuScanner.GPUFREQ_V2_FIX_OPP, index.toString())
        return if (ok) {
            Outcome(true, "GPU 已锁定到 OPP $index (${target / 1000} MHz)")
        } else {
            Outcome(false, "写入 fix_target_opp_index 失败(可能需要 root)")
        }
    }

    private suspend fun applyGpufreqV1(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val target = maxKhz ?: minKhz ?: return Outcome(false, "未设置任何频率")
        val ok = Sysfs.write(GpuScanner.GPUFREQ_V1_OPP_FREQ, target.toString())
        return if (ok) {
            Outcome(true, "GPU 已锁定到 ${target / 1000} MHz")
        } else {
            Outcome(false, "写入 gpufreq_opp_freq 失败(可能需要 root)")
        }
    }

    /** Release: undo whatever channel-specific lock we applied. */
    suspend fun release(gpu: GpuInfo, handler: Int): Outcome {
        return when (gpu.channel) {
            GpuChannel.GPUFREQ_V2 -> {
                val ok = Sysfs.write(GpuScanner.GPUFREQ_V2_FIX_OPP, "-1")
                Outcome(ok, if (ok) "GPU 锁定已解除" else "解除失败")
            }

            GpuChannel.GPUFREQ_V1 -> {
                val ok = Sysfs.write(GpuScanner.GPUFREQ_V1_OPP_FREQ, "0")
                Outcome(ok, if (ok) "GPU 锁定已解除" else "解除失败")
            }

            GpuChannel.DEVFREQ -> {
                val ok = Sysfs.write("${GpuScanner.DEVFREQ_ROOT}/max_freq", gpu.maxFreqKhz?.toString() ?: "")
                Outcome(ok, if (ok) "GPU 频率区间已恢复" else "恢复失败")
            }

            GpuChannel.NONE -> Outcome(false, "未检测到可用的 GPU 调频接口")
        }
    }
}
