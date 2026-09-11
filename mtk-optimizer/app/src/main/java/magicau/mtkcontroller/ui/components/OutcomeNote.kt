package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.domain.model.OutcomeKind
import magicau.mtkcontroller.ui.text

/**
 * 操作结果：图标 + 加粗结论 + 灰色细节。
 *
 * 和 [FailureNote] 的分工：这个是"这次操作怎么样了"，需要一眼分出成功/注意/失败；
 * [FailureNote] 是"某件事失败了"的排错入口。CPU 页的结果用它，别处继续用 FailureNote。
 */
@Composable
fun OutcomeNote(
    message: Failure,
    kind: OutcomeKind,
    modifier: Modifier = Modifier,
) {
    val (iconRes, tint, labelRes) = when (kind) {
        OutcomeKind.SUCCESS -> Triple(R.drawable.ic_result_ok, ResultOk, R.string.result_ok)
        OutcomeKind.WARNING -> Triple(R.drawable.ic_result_warning, ResultWarn, R.string.result_warning)
        OutcomeKind.ERROR -> Triple(R.drawable.ic_result_error, MaterialTheme.colorScheme.error, R.string.result_error)
        OutcomeKind.NONE -> Triple(R.drawable.ic_result_ok, MaterialTheme.colorScheme.onSurfaceVariant, R.string.result_ok)
    }
    // 结论里可能有第二行（例如"读回符合请求" + "调速器已切换"）：第一行加粗，
    // 其余行当补充说明，灰色。句子里不再用"·"把所有东西串成一行。
    val lines = message.summary.text().split('\n')
    val detail = message.detail?.takeIf { it.isNotBlank() }
    var expanded by remember(message) { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            if (kind != OutcomeKind.NONE) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = stringResource(labelRes),
                    tint = tint,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    lines.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                lines.drop(1).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (detail != null) {
            // 默认一行都不显示。逐簇 policy 读回是排错证据，不是结论；
            // 摘要已经有警告图标和一句人话，细节留给点开的人。
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier.padding(start = if (kind == OutcomeKind.NONE) 0.dp else 22.dp),
            ) {
                Text(
                    stringResource(if (expanded) R.string.action_collapse else R.string.result_detail_toggle),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = if (kind == OutcomeKind.NONE) 0.dp else 26.dp,
                        top = 2.dp,
                    ),
                )
            }
        }
    }
}

private val ResultOk = androidx.compose.ui.graphics.Color(0xFF2E7D32)
private val ResultWarn = androidx.compose.ui.graphics.Color(0xFFB4531A)
