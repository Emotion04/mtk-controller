package magicau.mtkcontroller.data.diag

import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.cpu.CpuFreqNodes
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
enum class CheckCategory(@StringRes val titleRes: Int, val order: Int) {
    PRIVILEGE(R.string.diag_cat_privilege, 0),
    CPU(R.string.diag_cat_cpu, 1),
    GPU(R.string.diag_cat_gpu, 2),
    ENVIRONMENT(R.string.diag_cat_environment, 3),
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
        /** 条目标题。**只有它进资源**；[detail] 是探测结果的原始描述，按数据处理。 */
        val label: Message,
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
                    CheckCategory.PRIVILEGE, Message(R.string.diag_check_shizuku_service),
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
                    CheckCategory.PRIVILEGE, Message(R.string.diag_check_permission),
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
                    CheckCategory.PRIVILEGE, Message(R.string.diag_check_userservice),
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
                    CheckCategory.CPU, Message(R.string.diag_check_powerhal, listOf(PowerHal.SERVICE_NAME)),
                    ok = powerHal,
                    detail = if (powerHal) {
                        "服务可访问 —— 频率控制效果需应用后读回确认"
                    } else {
                        "未找到 —— 非 MTK 设备或权限不足"
                    },
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.CPU, Message(R.string.diag_check_interface),
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
                    CheckCategory.CPU, Message(R.string.diag_check_clusters),
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
                    CheckCategory.CPU, Message(R.string.diag_check_governor),
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
                    CheckCategory.GPU, Message(R.string.diag_check_gpu),
                    ok = gpu.controllable,
                    detail = buildString {
                        // 诊断详情用协议标识，不译成文案 —— 这一栏是排错用的原始字段。
                        append(gpu.channel.name)
                        if (gpu.availableFreqs.isNotEmpty()) append(" · ${gpu.availableFreqs.size} 个频点")
                        if (gpu.governor != null) append(" · governor=${gpu.governor}")
                    },
                )
            )

            // --- environment ----------------------------------------------
            add(
                CapabilityReport.Check(
                    CheckCategory.ENVIRONMENT, Message(R.string.diag_check_device),
                    ok = true,
                    detail = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                )
            )
            add(
                CapabilityReport.Check(
                    CheckCategory.ENVIRONMENT, Message(R.string.diag_check_soc),
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
            // 路径与解析和 CpuControl 的验证、CpuViewModel 的展示共用一份 ——
            // 同一个节点在三处各拼一遍字符串，改节点名时漏一处就静默读错。
            // 这里故意逐条直读、不提权：只想确认"刚写进去的那两个值成了没有"。
            cluster.policy to (
                CpuFreqNodes.parseKhz(Sysfs.read(CpuFreqNodes.min(cluster.policy))) to
                    CpuFreqNodes.parseKhz(Sysfs.read(CpuFreqNodes.max(cluster.policy)))
                )
        }
}
