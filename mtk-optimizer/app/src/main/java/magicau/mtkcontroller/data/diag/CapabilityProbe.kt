package magicau.mtkcontroller.data.diag

import magicau.mtkcontroller.data.cpu.CpuScanner
import magicau.mtkcontroller.data.gpu.GpuScanner
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeMode
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.GpuInfo

/** Grouping used by the diagnostics screen. */
enum class CheckCategory(val title: String, val order: Int) {
    PRIVILEGE("提权与权限", 0),
    CPU("CPU 调频", 1),
    GPU("GPU 调频", 2),
    ENVIRONMENT("运行环境", 3),
}

/**
 * Everything we could discover about this device, in one object.
 *
 * This is what the diagnostics screen renders, and it is the single thing to
 * look at when validating the app on a real device for the first time.
 */
data class CapabilityReport(
    val privilege: PrivilegeState = PrivilegeState(),
    val remoteUid: Int? = null,
    val remoteBound: Boolean = false,
    val powerHalAvailable: Boolean = false,
    /** Interface descriptor the binder reported; null when unreachable. */
    val powerHalDescriptor: String? = null,
    val cpuClusters: List<CpuCluster> = emptyList(),
    val gpu: GpuInfo = GpuInfo(),
    val checks: List<Check> = emptyList(),
) {
    data class Check(
        val category: CheckCategory,
        val label: String,
        val ok: Boolean,
        val detail: String,
    ) {
        /** Multi-line details are rendered in a monospace block. */
        val multiline: Boolean get() = detail.contains('\n')
    }

    fun checksByCategory(): Map<CheckCategory, List<Check>> =
        checks.groupBy { it.category }.toSortedMap(compareBy { it.order })
}

class CapabilityProbe(
    private val cpuScanner: CpuScanner = CpuScanner(),
    private val gpuScanner: GpuScanner = GpuScanner(),
) {

    suspend fun probe(): CapabilityReport {
        PrivilegeManager.refresh()
        val privilege = PrivilegeManager.state.value

        val remoteBound = PrivilegeManager.isRemoteReady
        val remoteUid = if (remoteBound) PrivilegeManager.remoteUid() else null

        val powerHal = privilege.mode.canElevate && PowerHal.isAvailableNow()
        // Zero-side-effect identity check: an empty-parcel INTERFACE_TRANSACTION
        // is answered by Binder itself, before any AIDL dispatch. It settles
        // whether this really is MTK's HAL rather than another vendor's service
        // of the same name.
        val descriptor = if (powerHal) runCatching { PowerHal.descriptor() }.getOrNull() else null

        // Diagnostics is where you go to see the truth, so bypass the cache.
        gpuScanner.invalidate()

        val clusters = runCatching { cpuScanner.scan() }.getOrDefault(emptyList())
        val gpu = runCatching { gpuScanner.scan() }.getOrDefault(GpuInfo())

        val checks = buildList {
            // --- privilege -------------------------------------------------
            add(
                CapabilityReport.Check(
                    CheckCategory.PRIVILEGE, "Shizuku 服务",
                    ok = privilege.binderAlive,
                    detail = if (privilege.binderAlive) {
                        "已连接 · 版本 ${privilege.shizukuVersion}"
                    } else {
                        "未运行或未安装"
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.PRIVILEGE, "授权状态",
                    ok = privilege.permissionGranted,
                    detail = when (privilege.mode) {
                        PrivilegeMode.ROOT -> "已授权 · root (uid 0)"
                        PrivilegeMode.ADB -> "已授权 · adb/shell (uid 2000)"
                        PrivilegeMode.AVAILABLE -> "尚未授权"
                        PrivilegeMode.NONE -> "不可用"
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.PRIVILEGE, "提权进程 (UserService)",
                    ok = remoteBound,
                    detail = if (remoteBound) {
                        "已绑定" + (remoteUid?.let { " · 实际 uid $it" } ?: "")
                    } else {
                        "未绑定 —— 需要 Shizuku 授权"
                    },
                )
            )

            // --- CPU -------------------------------------------------------
            add(
                CapabilityReport.Check(
                    CheckCategory.CPU, "PowerHAL (${PowerHal.SERVICE_NAME})",
                    ok = powerHal,
                    detail = if (powerHal) {
                        "可调用 —— 频率上下限可用"
                    } else {
                        "未找到 —— 非 MTK 设备或权限不足"
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.CPU, "接口标识",
                    ok = descriptor == PowerHal.INTERFACE_TOKEN,
                    detail = when {
                        descriptor == null -> "未取到 —— 非 MTK 设备或权限不足"
                        descriptor == PowerHal.INTERFACE_TOKEN -> "$descriptor · 与预期一致"
                        else -> "$descriptor · 与预期不符,可能不是 MTK PowerHAL"
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.CPU, "CPU 簇",
                    ok = clusters.isNotEmpty(),
                    detail = if (clusters.isEmpty()) {
                        "未找到 policy 目录"
                    } else {
                        clusters.joinToString("\n") { cluster ->
                            "${cluster.policy}  CPU ${cluster.cpus.firstOrNull() ?: "?"}-${cluster.cpus.lastOrNull() ?: "?"}" +
                                "  ${cluster.availableFreqs.size} 档" +
                                (cluster.driver?.let { "  [$it]" } ?: "")
                        }
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.CPU, "调速器可写性",
                    ok = clusters.any { it.governorWritable },
                    detail = if (clusters.any { it.governorWritable }) {
                        "可写 —— 可切换 governor"
                    } else {
                        "不可写 —— sysfs 节点通常 root 私有"
                    },
                )
            )

            // --- GPU -------------------------------------------------------
            add(
                CapabilityReport.Check(
                    CheckCategory.GPU, "GPU 接口",
                    ok = gpu.controllable,
                    detail = buildString {
                        append(gpu.channel.displayName)
                        if (gpu.availableFreqs.isNotEmpty()) append(" · ${gpu.availableFreqs.size} 个频点")
                        if (gpu.governor != null) append(" · governor=${gpu.governor}")
                    },
                )
            )

            // --- environment ----------------------------------------------
            add(
                CapabilityReport.Check(
                    CheckCategory.ENVIRONMENT, "设备",
                    ok = true,
                    detail = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.ENVIRONMENT, "SoC",
                    ok = true,
                    detail = listOfNotNull(
                        android.os.Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() },
                        android.os.Build.SOC_MODEL.takeIf { it.isNotBlank() },
                        android.os.Build.HARDWARE.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "未上报" },
                )
            )
        }

        return CapabilityReport(
            privilege = privilege,
            remoteUid = remoteUid,
            remoteBound = remoteBound,
            powerHalAvailable = powerHal,
            powerHalDescriptor = descriptor,
            cpuClusters = clusters,
            gpu = gpu,
            checks = checks,
        )
    }

    /** Sanity read of the live sysfs values, used to verify a PowerHAL apply landed. */
    suspend fun readAppliedLimits(clusters: List<CpuCluster>): Map<String, Pair<Long?, Long?>> =
        clusters.associate { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            cluster.policy to (
                Sysfs.read("$dir/scaling_min_freq")?.toLongOrNull() to
                    Sysfs.read("$dir/scaling_max_freq")?.toLongOrNull()
                )
        }
}
