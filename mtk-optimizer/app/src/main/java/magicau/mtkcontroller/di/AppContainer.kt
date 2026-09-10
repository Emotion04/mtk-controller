package magicau.mtkcontroller.di

import android.content.Context
import magicau.mtkcontroller.data.cpu.CpuScanner
import magicau.mtkcontroller.data.cpu.CpuTuner
import magicau.mtkcontroller.data.diag.CapabilityProbe
import magicau.mtkcontroller.data.gpu.GpuScanner
import magicau.mtkcontroller.data.gpu.GpuTuner
import magicau.mtkcontroller.data.profile.ProfileRepository
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.data.tweaks.TweakRepository

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
    val cpuTuner = CpuTuner()

    val gpuScanner = GpuScanner()
    val gpuTuner = GpuTuner()

    val capabilityProbe = CapabilityProbe(cpuScanner, gpuScanner)
}
