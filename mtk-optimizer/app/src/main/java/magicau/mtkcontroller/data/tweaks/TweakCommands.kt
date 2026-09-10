package magicau.mtkcontroller.data.tweaks

/**
 * Shell command sets for the system tweaks.
 *
 * The touch commands are recovered verbatim from the reference app's
 * "触控优化" screen, so the behaviour matches what already worked for the user.
 *
 * How these actually work: `debug.touch.report_rate`,
 * `debug.touchscreen.report_rate` and `debug.game_touch_sampling_rate` are
 * MediaTek/vendor touch-firmware knobs — they ask the touch controller to
 * report at a higher rate. `settings put system touch_scan_press_time_delay*`
 * shorten the debounce before a press registers. `debug.sf.*` /
 * `debug.hwui.disable_vsync` affect the render pipeline.
 *
 * Caveat worth surfacing in the UI: these are vendor debug properties, so a
 * given ROM may ignore them. The settings-based keys are the most likely to be
 * honoured on MTK devices; the SurfaceFlinger ones mainly reduce frame pacing
 * latency.
 */
object TweakCommands {

    /** Sampling rates offered by the touch slider, in Hz. */
    val TOUCH_RATES = listOf(120, 180, 240, 300, 360)

    const val DEFAULT_TOUCH_RATE = 360

    fun touchEnable(rateHz: Int = DEFAULT_TOUCH_RATE): List<String> = listOf(
        "setprop debug.hwui.disable_vsync true",
        "setprop debug.cpurend.vsync false",
        "settings put global sys_uidcpupower Deadline",
        "settings put system touch_scan_press_time_delay 300",
        "settings put system touch_scan_press_time_delay_double 200",
        "settings put system touch_scan_press_time_delay_single 300",
        "settings put system touch_scan_press_time_delay_grade 2",
        "setprop debug.touch.report_rate $rateHz",
        "setprop debug.touchscreen.report_rate $rateHz",
        "setprop debug.game_touch_sampling_rate $rateHz",
        "settings put system pointer_speed 7",
        "device_config put activity_manager_native_boot game_touch_sampling_rate $rateHz",
        "device_config put activity_manager_native_boot touchscreen.report_rate $rateHz",
        "device_config put activity_manager_native_boot touch.report_rate $rateHz",
        "settings put system touch_response_delay 0",
        "setprop debug.sf.auto_latch_unsignaled true",
        "setprop debug.sf.disable_backpressure 1",
        "setprop debug.sf.latch_unsignaled 1",
        "setprop debug.sf.enable_hwc_vds 1",
        "setprop debug.sf.hw 0",
        "setprop debug.sf.showupdates 0",
        "setprop debug.sf.showcpu 0",
        "setprop debug.sf.showbackground 0",
        "setprop debug.sf.showfps 0",
    )

    fun touchDisable(): List<String> = listOf(
        "setprop debug.hwui.disable_vsync false",
        "setprop debug.cpurend.vsync true",
        "settings put system touch_scan_press_time_delay 0",
        "settings put system touch_scan_press_time_delay_double 0",
        "settings put system touch_scan_press_time_delay_single 0",
        "settings put system touch_scan_press_time_delay_grade 0",
        "setprop debug.touch.report_rate 0",
        "setprop debug.touchscreen.report_rate 0",
        "setprop debug.game_touch_sampling_rate 0",
        "settings put system pointer_speed 0",
        "device_config delete activity_manager_native_boot game_touch_sampling_rate",
        "device_config delete activity_manager_native_boot touchscreen.report_rate",
        "device_config delete activity_manager_native_boot touch.report_rate",
        "settings put system touch_response_delay 0",
    )

    // --- frame interpolation ------------------------------------------------

    /**
     * Vendor frame-interpolation knob (MTK "gamecube"). The parameter string is
     * the value the reference app wrote; whether it does anything depends on
     * whether the device actually has an interpolation chip.
     */
    private const val INTERP_ENABLE = "\"0:-1:0:0:0\""

    const val KEY_INTERP = "gamecube_frame_interpolation"
    const val KEY_INTERP_SR = "gamecube_frame_interpolation_for_sr"

    fun frameInterpolation(enable: Boolean, superResolution: Boolean = false): String {
        val key = if (superResolution) KEY_INTERP_SR else KEY_INTERP
        return if (enable) {
            "settings put system $key $INTERP_ENABLE"
        } else {
            "settings delete system $key"
        }
    }

    // --- screen rotation ----------------------------------------------------

    /**
     * Android's own "rotate suggestion": while auto-rotate is off, the system
     * keeps the orientation sensor alive and offers a rotate button in the
     * navigation bar when it notices the device has been turned.
     *
     * `Settings.Secure.show_rotation_suggestions` — 1 shows the button, 0 hides it.
     */
    fun rotationSuggestion(enable: Boolean): String =
        "settings put secure show_rotation_suggestions ${if (enable) 1 else 0}"

    fun readRotationSuggestion(): String = "settings get secure show_rotation_suggestions"

    fun autoRotate(enable: Boolean): String =
        "settings put system accelerometer_rotation ${if (enable) 1 else 0}"

    /** 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°. */
    fun forceRotation(rotation: Int): List<String> = listOf(
        "settings put system accelerometer_rotation 0",
        "settings put system user_rotation $rotation",
    )

    fun readAutoRotate(): String = "settings get system accelerometer_rotation"

    // --- thermal brightness -------------------------------------------------

    fun readBrightness(): String = "settings get system screen_brightness"

    fun setBrightness(value: Int): String = "settings put system screen_brightness $value"

    fun readBrightnessMode(): String = "settings get system screen_brightness_mode"
}
