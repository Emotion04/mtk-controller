package magicau.mtkcontroller.feature.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabControl
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.lab.LabRisk
import magicau.mtkcontroller.ui.theme.isDarkScheme
import magicau.mtkcontroller.ui.theme.adaptiveAccent

private val RiskLow = Color(0xFF2E7D32)
private val RiskMedium = Color(0xFFB4531A)
private val RiskHigh = Color(0xFF9C4146)
private val StateOk = Color(0xFF2E7D32)
private val StateBad = Color(0xFF9C4146)
private val StateNeutral = Color(0xFF6B6B68)

/**
 * The canary lab: features whose protocol is understood but which have not been
 * shown to work on *this* device.
 *
 * Each card answers the same three questions in the same order — how risky is
 * it, has it been observed working here, and what exactly did the device do when
 * tried. Opening the screen applies nothing; a card only sends when the user
 * presses 测试.
 */
@Composable
fun LabScreen(
    state: LabUiState,
    onOpenPanel: () -> Unit,
    onValueChange: (LabFeature, String, Int) -> Unit,
    onHoldChange: (LabFeature, Boolean) -> Unit,
    onTest: (LabFeature) -> Unit,
    onResetAll: () -> Unit,
    onClearObservations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "协议上成立,但没在你这台机器上验证过。每张卡片都会读回内核节点," +
                        "直接告诉你有没有生效 —— 测出来能用就可以移出实验室。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.powerHalReady) {
                    Text(
                        "PowerHAL 当前不可用,下面的写入不会成功。请先确认 Shizuku 已授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenPanel, modifier = Modifier.weight(1f)) {
                        Text("只读面板")
                    }
                    OutlinedButton(onClick = onResetAll, modifier = Modifier.weight(1f)) {
                        Text("恢复全部")
                    }
                }
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.groups.forEach { (category, items) ->
            item(key = "header-${category.name}") { CategoryHeader(category) }
            items.forEach { item ->
                item(key = item.feature.key) {
                    FeatureCard(
                        item = item,
                        clusters = state.clusters.map { it.policy },
                        onValueChange = { policy, value -> onValueChange(item.feature, policy, value) },
                        onHoldChange = { onHoldChange(item.feature, it) },
                        onTest = { onTest(item.feature) },
                    )
                }
            }
        }

        item {
            OutlinedButton(onClick = onClearObservations, modifier = Modifier.fillMaxWidth()) {
                Text("清除全部观测结果")
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: LabCategory) {
    Text(
        category.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun FeatureCard(
    item: LabItemState,
    clusters: List<String>,
    onValueChange: (String, Int) -> Unit,
    onHoldChange: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val feature = item.feature

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(feature.label, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("风险 ${feature.risk.label}", riskColor(feature.risk))
                    VerdictBadge(item.verdict)
                }
            }

            Text(
                feature.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            feature.node?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feature.danger?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (item.supported == false) {
                Text(
                    "本机没有这个节点 —— 功能不可用,或节点名 / ID 已随 BSP 变更。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Controls(
                feature = feature,
                item = item,
                clusters = clusters,
                onValueChange = onValueChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("长期保持", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (item.hold) {
                            "请求永久有效。必须用「恢复全部」撤销,否则会一直生效。"
                        } else {
                            "20 秒后由系统自动撤销。测试够用了,而且就算 App 崩了、" +
                                "句柄丢了,也不会留下卡死的频率。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.hold) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = item.hold, onCheckedChange = onHoldChange)
            }

            Button(
                onClick = onTest,
                enabled = !item.busy && item.supported != false,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (item.busy) "测试中…" else "测试") }

            item.verdict?.let { verdict ->
                Text(
                    verdict.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = verdictColor(verdict.stateEnum()),
                )
            }
        }
    }
}

@Composable
private fun Controls(
    feature: LabFeature,
    item: LabItemState,
    clusters: List<String>,
    onValueChange: (String, Int) -> Unit,
) {
    when (feature.control) {
        LabControl.TOGGLE -> {
            val key = item.values.keys.firstOrNull() ?: LabViewModel.GLOBAL
            val checked = (item.values[key] ?: 0) != 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (checked) "开" else "关", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = checked, onCheckedChange = { onValueChange(key, if (it) 1 else 0) })
            }
        }

        LabControl.CHOICE -> SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val key = item.values.keys.firstOrNull() ?: LabViewModel.GLOBAL
            val current = item.values[key] ?: 0
            feature.valueChoices.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = current == value,
                    onClick = { onValueChange(key, value) },
                    shape = SegmentedButtonDefaults.itemShape(index, feature.valueChoices.size),
                ) { Text(label) }
            }
        }

        LabControl.SLIDER -> {
            val keys = if (feature.perCluster) clusters else listOf(LabViewModel.GLOBAL)
            keys.forEach { policy ->
                val value = item.values[policy] ?: feature.range.first
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (feature.perCluster) policy else "目标值",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "$value${feature.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { onValueChange(policy, it.roundToInt()) },
                        valueRange = feature.range.first.toFloat()..feature.range.last.toFloat(),
                    )
                }
            }
        }

        LabControl.READONLY -> Unit
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    val tint = color.adaptiveAccent(isDarkScheme())
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun VerdictBadge(verdict: LabController.Verdict?) {
    Badge(verdict?.stateEnum()?.label ?: LabController.State.UNTESTED.label, verdictColor(verdict?.stateEnum() ?: LabController.State.UNTESTED))
}

@Composable
private fun riskColor(risk: LabRisk): Color = when (risk) {
    LabRisk.LOW -> RiskLow
    LabRisk.MEDIUM -> RiskMedium
    LabRisk.HIGH -> RiskHigh
}

@Composable
private fun verdictColor(state: LabController.State): Color = when (state) {
    LabController.State.WORKING -> StateOk
    LabController.State.NO_EFFECT, LabController.State.UNSUPPORTED -> StateBad
    LabController.State.UNKNOWN, LabController.State.UNTESTED -> StateNeutral
}
