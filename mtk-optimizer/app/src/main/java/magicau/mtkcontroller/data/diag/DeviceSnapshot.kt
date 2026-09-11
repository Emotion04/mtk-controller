package magicau.mtkcontroller.data.diag

import android.content.Context
import android.os.Build
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Structured, machine-readable description of one device at one moment.
 *
 * ## Why JSON instead of prose
 *
 * The adaptation report is read by tooling, not by a person. Every field has a
 * stable English name so a report from an older build still parses after the
 * app changes; comments and prose are deliberately absent. Anything a human
 * needs to read goes in the header block, not scattered through the payload.
 *
 * ## One function, two uses
 *
 * The same capture runs at first launch (the clean baseline, persisted and
 * never overwritten) and again at export time. Because both sides use this
 * function, the two objects are directly comparable — which is what makes
 * "what changed since the clean state" answerable without a second format.
 *
 * ## What is deliberately omitted
 *
 * No user content, no installed-app list, no identifiers that survive a
 * reinstall. This file is meant to be sent to a developer, and a diagnostic
 * report is not a reason to hand over more than the diagnosis needs.
 */
object DeviceSnapshot {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    fun capture(
        context: Context,
        report: CapabilityReport?,
        cpuStatus: CpuControl.Status?,
        /**
         * ROM 控制接口的只读快照原文。null = 这次没采。
         *
         * 放进来是为了让**一份适配报告就够用**：它回答"这台 ROM 用什么实现做到了"，
         * 而下面那些节回答"这台机器支持什么"。少了它，适配新机型时得另外再导一次。
         * 只在调用方显式传入时才采 —— 它要跑几条 shell，不是免费的。
         */
        romProbe: String? = null,
    ): JSONObject = JSONObject().apply {
        put("captured_at", iso.format(Date()))
        put("captured_at_ms", System.currentTimeMillis())
        put("timezone", TimeZone.getDefault().id)

        put("app", app())
        put("device", device())
        put("kernel", kernel())
        put("privilege", privilege(report?.privilege))
        put("powerhal", powerHal(report))
        put("telemetry_endpoints", endpoints(context))
        put("cpu", cpu(report, cpuStatus))
        put("gpu", gpu(report))
        put("features", features(context, report))
        put("environment", environment(context))
        // ROM 实现快照。原文整段放进去，不在这里解析 ——
        // 解析规则会随 ROM 变，报告只负责把证据留住。
        romProbe?.let { put("rom_implementation", it) }
    }

    // --- sections ----------------------------------------------------------

    private fun app(): JSONObject = JSONObject().apply {
        put("package", BuildConfig.APPLICATION_ID)
        put("version_name", BuildConfig.VERSION_NAME)
        put("version_code", BuildConfig.VERSION_CODE)
        put("build_type", BuildConfig.BUILD_TYPE)
        put("debuggable", BuildConfig.DEBUG)
    }

    private fun device(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("soc_manufacturer", Build.SOC_MANUFACTURER)
        put("soc_model", Build.SOC_MODEL)
        put("android_release", Build.VERSION.RELEASE)
        put("api_level", Build.VERSION.SDK_INT)
        put("build_id", Build.DISPLAY)
        put("build_fingerprint", Build.FINGERPRINT)
        put("security_patch", Build.VERSION.SECURITY_PATCH)
        put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("is_64_bit", Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
    }

    private fun kernel(): JSONObject = JSONObject().apply {
        put("release", System.getProperty("os.version") ?: "")
        put("arch", System.getProperty("os.arch") ?: "")
        put("java_vm", System.getProperty("java.vm.version") ?: "")
    }

    /**
     * `mode` is the deciding field for adaptation: it says how much the app is
     * allowed to do before any per-feature result is consulted.
     */
    private fun privilege(state: PrivilegeState?): JSONObject {
        val s = state ?: PrivilegeManager.state.value
        return JSONObject().apply {
            put("mode", s.mode.name)
            put("uid", s.uid)
            put("permission_granted", s.permissionGranted)
            put("binder_alive", s.binderAlive)
            put("shizuku_version", s.shizukuVersion)
            put("expected_shell_uid", PrivilegeState.UID_SHELL)
            put("is_root", s.uid == PrivilegeState.UID_ROOT)
            s.message?.let { put("message", it) }
        }
    }

    /**
     * Whether the vendor interface is reachable, and under what identity.
     *
     * The descriptor is recorded because a ROM that renames the interface is a
     * different adaptation case from one where the service is missing entirely.
     */
    private fun powerHal(report: CapabilityReport?): JSONObject = JSONObject().apply {
        put("service_name", "power_hal_mgr_service")
        put("service_found", report?.powerHalAvailable == false || report != null)
        put("interface_expected", "com.mediatek.powerhalmgr.IPowerHalMgr")
        put("interface_reported", report?.powerHalDescriptor ?: JSONObject.NULL)
        put("descriptor_matches", report?.powerHalDescriptor?.contains("powerhalmgr") ?: false)
        put("acquire_opcode", "0x16")
        put("release_opcode", "0x17")
        put("remote_uid", report?.remoteUid ?: JSONObject.NULL)
        put("remote_bound", report?.remoteBound ?: false)
    }

    /**
     * Whether each read-only channel answered, with the raw error when it did not.
     *
     * Adaptation turns on this section more often than on the feature list: the
     * usual cause of "a feature does nothing on my phone" is that its readback
     * channel is absent or SELinux-denied there, not that the write failed. The
     * raw message is kept because "Permission denied" and "No such file" are
     * different adaptation cases with different fixes.
     */
    private fun endpoints(context: Context): JSONArray = JSONArray().apply {
        ENDPOINT_CANDIDATES.forEach { path ->
            val outcome = runCatching {
                val f = java.io.File(path)
                when {
                    !f.exists() -> "absent"
                    !f.canRead() -> "unreadable"
                    else -> "ok"
                }
            }.getOrElse { "error: ${it.javaClass.simpleName}" }
            put(JSONObject().apply {
                put("path", path)
                put("access", outcome)
            })
        }
    }

    /**
     * Nodes whose readability varies by device or ROM. These are the ones the
     * app reads for monitoring, so a device where they fail is a device whose
     * monitoring sections will be empty — worth knowing before chasing a bug
     * that is actually a permission difference.
     */
    private val ENDPOINT_CANDIDATES = listOf(
        "/proc/meminfo",
        "/proc/stat",
        "/sys/block/zram0/mm_stat",
        "/sys/class/thermal/thermal_zone0/type",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq",
    )

    private fun cpu(report: CapabilityReport?, status: CpuControl.Status?): JSONObject = JSONObject().apply {
        val clusters = report?.cpuClusters.orEmpty()
        put("cluster_count", clusters.size)
        put("clusters", JSONArray().apply {
            clusters.forEach { c ->
                put(JSONObject().apply {
                    put("policy", c.policy)
                    put("index", c.index)
                    put("cpus", JSONArray(c.cpus))
                    put("cpu_count", c.cpus.size)
                    put("hw_min_khz", c.minFreqKhz)
                    put("hw_max_khz", c.maxFreqKhz)
                    put("available_freq_count", c.availableFreqs.size)
                    put("available_freq_khz", JSONArray(c.availableFreqs))
                    put("governor", c.currentGovernor ?: JSONObject.NULL)
                    put("governor_writable", c.governorWritable)
                    put("driver", c.driver ?: JSONObject.NULL)
                })
            }
        })
        status?.let { s ->
            put("last_request", JSONObject().apply {
                put("handler", s.handler)
                put("applied_count", s.applied.size)
                // 数组在循环外建：放在里面会被每轮重建，只剩最后一条。
                put("applied", JSONArray().apply {
                    s.applied.forEach { a ->
                        put(JSONObject().apply {
                            put("policy", a.policy)
                            put("min_khz", a.minFreqKhz)
                            put("max_khz", a.maxFreqKhz)
                        })
                    }
                })
                put("verification", s.verification.name)
                put("verification_detail", s.verificationDetail ?: JSONObject.NULL)
                put("observed_limits", s.observedLimits ?: JSONObject.NULL)
            })
        }
    }

    /**
     * GPU is recorded even when absent: "channel NONE" is the answer that
     * explains why the GPU screen is empty on that device.
     */
    private fun gpu(report: CapabilityReport?): JSONObject = JSONObject().apply {
        val g = report?.gpu
        put("channel", g?.channel?.name ?: "UNKNOWN")
        put("controllable", g?.controllable ?: false)
        put("available_freq_count", g?.availableFreqs?.size ?: 0)
        put("current_khz", g?.currentFreqKhz ?: JSONObject.NULL)
        put("hw_min_khz", g?.minFreqKhz ?: JSONObject.NULL)
        put("hw_max_khz", g?.maxFreqKhz ?: JSONObject.NULL)
    }

    /**
     * Per-check results: the direct answer to "what does not work here".
     *
     * `category` 是枚举名，稳定可 grep；`category_title` 与 `label` 是人读的，
     * 由展示侧解析 —— 键与取值都跟以前一致。
     */
    private fun features(context: Context, report: CapabilityReport?): JSONArray = JSONArray().apply {
        report?.checks?.forEach { check ->
            put(JSONObject().apply {
                put("category", check.category.name)
                put("category_title", context.getString(check.category.titleRes))
                put("label", check.label.resolve(context))
                put("ok", check.ok)
                put("detail", check.detail)
            })
        }
    }

    private fun environment(context: Context): JSONArray = JSONArray().apply {
        EnvironmentProbe.findings.forEach { f ->
            put(JSONObject().apply {
                // 报告字段是设备原文与证据；类别名从资源取。
                put("name", context.getString(f.nameRes))
                put("severity", f.severity)
                put("detail", f.detail)
            })
        }
    }
}
