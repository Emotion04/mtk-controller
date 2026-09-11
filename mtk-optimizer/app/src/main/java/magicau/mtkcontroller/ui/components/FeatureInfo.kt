package magicau.mtkcontroller.ui.components

import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * 说明按钮。点开是一块**浮层**，不是弹窗。
 *
 * ## 为什么不用 AlertDialog
 *
 * 说明是"顺便看一眼"的东西，而对话框会拿走整个屏幕：背景变暗、焦点被夺、
 * 必须显式关闭才能继续。用户想边看说明边对照旁边那个开关时，这条路就断了。
 *
 * 浮层不动背景、不夺焦点，点别处即消失 —— 和系统里的长按提示是同一类交互。
 *
 * ## 尺寸约定
 *
 * 宽度上限 [MAX_WIDTH]，高度上限 [MAX_HEIGHT] 并允许内部滚动：说明可以长，
 * 但不能长到盖住整屏。超过上限的内容靠滚动看完，而不是把浮层撑满。
 */
@Composable
fun FeatureInfo(title: String, description: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.feature_info_open, title),
                modifier = Modifier.size(19.dp),
            )
        }
        if (open) {
            // 锚在图标右下：icon 在行尾，向左展开才不会顶出屏幕。
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }),
                onDismissRequest = { open = false },
                // focusable = true 才能让"点空白处关闭"生效；这是菜单和提示的
                // 标准行为，用户点一下就走，不会被困住。
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = LocalShapeStyle.current.card,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(max = MAX_WIDTH)
                        .heightIn(max = MAX_HEIGHT),
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Text(title, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 浮层宽度上限。够放下三四行中文，又不至于在窄屏上顶到边。 */
private val MAX_WIDTH = 288.dp

/** 高度上限。超出的说明靠滚动看完，而不是撑满屏幕。 */
private val MAX_HEIGHT = 320.dp

/**
 * 标题 + 行尾说明按钮。用于分组标题那一类，本身没有可点的行。
 *
 * [chipText] 给状态方框留位 —— 状态是行的一部分，不是标题的一部分，所以
 * 它渲染成方框而不是拼进 [title] 的字符串里。
 */
@Composable
fun InfoHeading(
    title: String,
    description: String,
    chipText: String? = null,
    chipTone: StatusTone = StatusTone.Neutral,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        chipText?.let { StatusChip(it, chipTone, Modifier.padding(end = 2.dp)) }
        FeatureInfo(title, description)
    }
}
