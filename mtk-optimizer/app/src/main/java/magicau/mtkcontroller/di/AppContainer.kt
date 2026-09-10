package magicau.mtkcontroller.di

import android.content.Context
import magicau.mtkcontroller.data.backup.BackupRepository
import magicau.mtkcontroller.data.cpu.CpuScanner
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.cpu.CpuControlStore
import magicau.mtkcontroller.data.diag.CapabilityProbe
import magicau.mtkcontroller.data.gpu.GpuScanner
import magicau.mtkcontroller.data.gpu.GpuTuner
import magicau.mtkcontroller.data.profile.ProfileRepository
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.data.tweaks.TweakRepository
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.feature.cpu.CpuReapplyService
import kotlinx.coroutines.flow.first

/**
 * Hand-rolled dependency container. Kept deliberately small; Hilt can replace
 * it later without touching call sites, since ViewModels take this type.
 */
class AppContainer(appContext: Context) {

    /** Exposed so services and ViewModels can start background work. */
    val context: Context = appContext.applicationContext

    val profileRepository = ProfileRepository(context)
    val settingsRepository = SettingsRepository(context)
    val tweakRepository = TweakRepository(context)

    val cpuScanner = CpuScanner()

    /** The single entry point for CPU frequency control. */
    private val cpuControlStore = CpuControlStore(context)
    val cpuControl = CpuControl(cpuControlStore)

    val gpuScanner = GpuScanner()
    val gpuTuner = GpuTuner()

    val capabilityProbe = CapabilityProbe(cpuScanner, gpuScanner)

    val backupRepository = BackupRepository(profileRepository, settingsRepository)

    /**
     * Bring the re-apply service in line with the current mode.
     *
     * Called after a successful apply and on release. The container owns this
     * because all three ViewModels that can apply CPU limits need it, and the
     * mode/interval live in preferences rather than in any of them.
     */
    suspend fun syncCpuReapply() {
        val mode = ApplyMode.fromName(settingsRepository.applyMode.first())
        if (mode == ApplyMode.POLLING) {
            CpuReapplyService.start(context, settingsRepository.reapplyIntervalMs.first())
        } else {
            CpuReapplyService.stop(context)
        }
    }
}
