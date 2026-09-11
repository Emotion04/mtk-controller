package magicau.mtkcontroller.data.diag

import android.content.Context
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.log.AppLog
import org.json.JSONObject
import java.io.File

/**
 * The clean state of this device, captured once and never overwritten.
 *
 * ## Why the first run matters
 *
 * A device's most informative moment is before the app has changed anything.
 * Once a frequency request has been applied, a thermal policy switched, or a
 * setting written, the device no longer tells you what it looked like out of
 * the box — and the difference between those two states is precisely what
 * explains "the feature does nothing on my phone".
 *
 * ## Why only the first run
 *
 * The user's workflow is not "open the app, export immediately". They install
 * it, use it for a while, and export days later when something misbehaves.
 * Re-capturing on every launch would overwrite the clean state with an
 * already-modified one, and the report would describe a device that no longer
 * exists anywhere. So this captures **once** and then never writes again, even
 * if the app is force-stopped or updated in between.
 *
 * ## Storage
 *
 * `files/diag/baseline.json`, written through a temp file so an interrupted
 * write cannot leave a half-file behind. Internal storage: no permission, and
 * removed only when the user uninstalls or clears data.
 */
class BaselineSnapshot(
    private val context: Context,
    private val probe: CapabilityProbe,
    private val cpuControl: CpuControl,
) {

    private val dir: File get() = File(context.filesDir, "diag").apply { mkdirs() }
    private val file: File get() = File(dir, "baseline.json")

    fun exists(): Boolean = file.isFile && file.length() > 0

    /** Parsed baseline, or null when it was never captured or is unreadable. */
    fun read(): JSONObject? = runCatching {
        if (!exists()) null else JSONObject(file.readText())
    }.getOrNull()

    /**
     * Capture the clean state if this is the first run. Safe to call on every
     * launch: an existing baseline is left untouched.
     */
    suspend fun captureIfAbsent() {
        if (exists()) {
            AppLog.d(TAG, "基线已存在，跳过（首采时间 ${read()?.optString("captured_at")}）")
            return
        }
        runCatching {
            val snapshot = DeviceSnapshot.capture(
                context = context,
                report = probe.probe(),
                cpuStatus = cpuControl.status.value,
            )
            snapshot.put("kind", "baseline")
            snapshot.put("note", "captured on first launch, before any change by this app")
            writeAtomically(snapshot)
            AppLog.i(TAG, "已记录干净基线快照")
        }.onFailure {
            // 基线采不到不该挡住启动 —— 报告里会少一段，但功能照常。
            AppLog.w(TAG, "基线快照记录失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Temp file then rename: an interrupted write leaves no half-parsed JSON. */
    private fun writeAtomically(snapshot: JSONObject) {
        val tmp = File(dir, "baseline.json.tmp")
        tmp.writeText(snapshot.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "BaselineSnapshot"
    }
}
