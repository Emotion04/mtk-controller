package magicau.mtkcontroller.ui.components

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * 技术细节的可展开区。报错分三层的第二、三层。
 *
 * ## 为什么默认收起
 *
 * 出问题时用户要的是"成没成"，不是"为什么没成"。而失败明细里是键名、回读值、
 * 返回码 —— 这些对用户没有意义，一次铺开就是满屏噪音，把真正的结论盖掉。
 *
 * 但它必须能拿到：用户反馈问题时，**能不能一键复制**决定了他愿不愿意配合。
 * 所以详情藏在一次点击之后，且给复制按钮和长按复制两条路。
 *
 * ## 什么时候用它
 *
 * 任何要把内部标识符（键名、节点路径、回读值、返回码）展示给用户的场合。
 * 摘要留在外面，明细放进来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpandableDetails(
    details: List<String>,
    modifier: Modifier = Modifier,
) {
    if (details.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.action_details_hide else R.string.action_details_show))
            }
            if (open) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(details.joinToString("\n"))) }) {
                    Text(stringResource(R.string.action_copy))
                }
            }
        }
        if (open) {
            Text(
                text = details.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DETAIL_MAX_HEIGHT)
                    .clip(LocalShapeStyle.current.chip)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // 长按复制：报错内容经常要贴给开发者，而用户的第一反应就是长按。
                    // 复制按钮仍然留着 —— 长按是习惯，按钮是发现性，两条路都要有。
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { clipboard.setText(AnnotatedString(details.joinToString("\n"))) },
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** 明细最长就这么高，超出靠滚动 —— 不占满整屏，用户还能看见下面的内容。 */
private val DETAIL_MAX_HEIGHT = 220.dp
