package magicau.mtkcontroller.data.diag

import androidx.annotation.StringRes
import magicau.mtkcontroller.R

/**
 * 拨号暗码。
 *
 * 这些是零提权的小工具：用 DIAL 动作把号码送到拨号盘，由用户自己按拨打键 ——
 * 不用 `CALL` 动作，那会真的拨出去，暗码是操作而普通号码是通话，两者不该混。
 *
 * **号码是设备原文**（各 ROM 自己认，实测只在部分机器上有效），所以留字面量；
 * **说明是用户文案**，所以持资源 id。
 */
object SystemShortcuts {

    val DARK_CODES: List<Pair<String, Int>> = listOf(
        "*#*#112#*#*" to R.string.dark_code_bugreport,
        "*#*#225#*#*" to R.string.dark_code_calendar,
        "*#*#426#*#*" to R.string.dark_code_gms,
        "*#*#6484#*#*" to R.string.dark_code_mtk_engineer,
        "*#*#2846579#*#*" to R.string.dark_code_engineer_menu,
        "*#06#" to R.string.dark_code_imei,
        "*#*#4636#*#*" to R.string.dark_code_usage,
        "*#*#7780#*#*" to R.string.dark_code_reset_preview,
    )
}
