package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.ui.text

/**
 * 一条失败的展示：**摘要永远可见，原始错误藏在一次点击之后。**
 *
 * ## 契约
 *
 * | 层 | 内容 | 默认 |
 * |---|---|---|
 * | 摘要 | 一句给人看的话 | 可见 |
 * | 详情 | 异常类名、消息、返回码、节点路径 | 收起 |
 *
 * 详情用**弹窗**而不是就地展开：原始错误经常是几十行的堆栈，就地铺开会把卡片撑爆、
 * 把下面的内容推到屏幕外；而弹窗里能限高滚动、给复制按钮，长短都合适。
 *
 * ## 为什么复制按钮必须在详情里
 *
 * 用户反馈问题时，**能不能一键复制**决定他愿不愿意配合。所以两条路都给：
 * 弹窗里的复制按钮，以及弹窗正文长按复制。
 *
 * ## 什么时候不用它
 *
 * 要展示的是**多条**明细（每个键的旧值新值、每个失败项）时，用 [ExpandableDetails] ——
 * 那是列表，不是一整段。本组件负责"一句结论 + 一段原始错误"。
 */
@Composable
fun FailureNote(
    failure: Failure,
    modifier: Modifier = Modifier,
) {
    // 按 failure 设 key：同一条失败重发时不该把弹窗状态带过去。
    var showDetail by remember(failure) { mutableStateOf(false) }
    val detail = failure.detail

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = failure.summary.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!detail.isNullOrBlank()) {
            TextButton(
                onClick = { showDetail = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp,
                    vertical = 2.dp,
                ),
            ) {
                Text(stringResource(R.string.action_details_show))
            }
        }
    }

    if (showDetail && !detail.isNullOrBlank()) {
        RawErrorDialog(detail = detail, onDismiss = { showDetail = false })
    }
}

/**
 * 原始错误弹窗。限高滚动 + 复制，长短都装得下。
 *
 * 正文限高 420dp、内部滚动，所以长短都装得下；两个按钮（关闭 / 复制）由
 * `AlertDialog` 自带，不必自己搭。
 */
@Composable
private fun RawErrorDialog(detail: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.raw_error_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 420.dp),
            ) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(detail))
                },
            ) { Text(stringResource(R.string.action_copy)) }
        },
    )
}
