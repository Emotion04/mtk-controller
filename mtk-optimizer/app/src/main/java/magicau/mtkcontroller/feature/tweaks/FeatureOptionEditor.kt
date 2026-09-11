package magicau.mtkcontroller.feature.tweaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.TweakValues
import magicau.mtkcontroller.data.tweaks.VivoFeature

/**
 * 配置项的取值控件。
 *
 * 正常页面（显示与交互）和功能试用页**共用这一份**。这几个功能的后端本来就是同一个
 * `VivoTweaks.apply(feature, option)`，界面上不该有两个入口 —— 试用页早期自己传了
 * `option = null`，结果这些项全部返回"请选择性能模式档位"，用户在试用页根本没有选的
 * 地方。共用控件就是为了不再出现这类分叉。
 *
 * @param currentDeviceValue 本机当前值，用于给用户填的项做默认值
 * @param onOptionChange 选项变化回调，参数是传给 `apply()` 的 option 字符串
 */
@Composable
fun FeatureOptionEditor(
    feature: VivoFeature,
    enabled: Boolean,
    currentDeviceValue: String?,
    onOptionChange: (String?) -> Unit,
) {
    when (feature) {
        VivoFeature.DNS -> OutlinedTextField(
            value = currentDeviceValue.orEmpty(), onValueChange = { onOptionChange(it) },
            enabled = enabled, label = { Text(stringResource(R.string.tweaks_dns_host)) },
            placeholder = { Text("dns.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        VivoFeature.CHARGE_LIMIT -> OutlinedTextField(
            value = currentDeviceValue.orEmpty(),
            onValueChange = { onOptionChange(it.filter(Char::isDigit).take(3)) },
            enabled = enabled, label = { Text(stringResource(R.string.vivo_feature_charge_limit)) }, placeholder = { Text("80") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        // 默认带出设备当前值 —— 这组数字随机型而不同，让用户凭空猜不合理。
        VivoFeature.TOUCH_BOOST -> OutlinedTextField(
            value = currentDeviceValue.orEmpty(),
            onValueChange = { onOptionChange(it.filter { c -> c.isDigit() || c == ',' }.take(24)) },
            enabled = enabled, label = { Text(stringResource(R.string.vivo_feature_touch_boost_label)) },
            placeholder = { Text("1,6,6,6") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        VivoFeature.ABE -> ChipRow(
            enabled = enabled, selected = currentDeviceValue,
            options = listOf(
                "off" to stringResource(R.string.tweaks_abe_restrict),
                "balanced" to stringResource(R.string.tweaks_abe_balanced),
            ),
            onSelect = onOptionChange,
        )
        // 档位编号来自固件实测，不是公开语义；只列已知的四个，不虚构别的。
        VivoFeature.PERFORMANCE_MODE -> ChipRow(
            enabled = enabled, selected = currentDeviceValue,
            options = listOf("enhanced" to stringResource(R.string.tweaks_perf_enhanced), "boost_plus" to "BOOST+",
                "monster" to "Monster", "monster_plus" to "Monster+"),
            onSelect = onOptionChange,
        )
        VivoFeature.FRAME_UNLOCK -> ChipRow(
            enabled = enabled, selected = currentDeviceValue,
            options = listOf("60" to "60", "90" to "90", "120" to "120"),
            onSelect = onOptionChange,
        )
        VivoFeature.AI -> ChipRow(
            enabled = enabled, selected = currentDeviceValue,
            options = listOf("1" to stringResource(R.string.tweaks_option_on), "0" to stringResource(R.string.tweaks_option_off)),
            onSelect = onOptionChange,
        )
        else -> Unit
    }
}

@Composable
private fun ChipRow(
    enabled: Boolean,
    selected: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value, onClick = { onSelect(value) },
                enabled = enabled, label = { Text(label) },
            )
        }
    }
}

/** 这项需不需要用户先选一个值。 */
fun VivoFeature.needsOption(): Boolean = when (this) {
    VivoFeature.DNS, VivoFeature.CHARGE_LIMIT, VivoFeature.TOUCH_BOOST,
    VivoFeature.ABE, VivoFeature.PERFORMANCE_MODE, VivoFeature.FRAME_UNLOCK,
    VivoFeature.AI, VivoFeature.HTP,
    -> true
    else -> false
}

/** 没有用户输入时用的默认选项，让试用页能一键跑完。 */
fun VivoFeature.defaultOption(): String? = when (this) {
    VivoFeature.PERFORMANCE_MODE -> "monster"
    VivoFeature.FRAME_UNLOCK -> "120"
    VivoFeature.ABE -> "off"
    VivoFeature.AI -> "1"
    else -> null
}
