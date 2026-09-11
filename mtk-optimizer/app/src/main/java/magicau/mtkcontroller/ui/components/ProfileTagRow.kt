package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * 一排方案标签。放在 CPU 页顶部。
 *
 * ## 为什么是标签而不是一个独立页面
 *
 * 方案的作用是"切一套常用配置"，而配置就在这一页上。放到独立页意味着用户
 * 调完频率要跳出去选方案、再跳回来确认，来回两趟。标签让"选方案"变成这一页
 * 上的一个动作，选中后下面的内容直接变成那个方案的样子。
 *
 * ## 交互约定
 *
 *  - **单击** —— 把方案内容装进编辑区。**不应用**：应用是有副作用的动作，
 *    应该由一个明确的按钮触发，而不是选一下就生效。
 *  - **长按** —— 重命名 / 删除。低频且带破坏性的动作收在长按里，
 *    避免误触，也不占用视觉空间。
 *  - **末尾的 +** —— 把当前编辑状态存成新方案。
 *
 * ## "默认"是内建的，不是存出来的
 *
 * 第一个标签固定是"默认"，代表**没有任何 App 施加的限制**。它不落盘成一份
 * 方案：方案是用户建的东西，可以删、可以改名，而"回到系统默认"必须永远在，
 * 否则用户把方案删光了就找不回干净状态。所以它是这一行里的固定项，
 * [activeIndex] 为 -1 时表示选中它。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileTagRow(
    names: List<String>,
    /** -1 表示当前没有套用任何方案，也就是"默认"。 */
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // 不加内边距：调用方（CPU 页的列表）已经统一留了页边距，
        // 这里再加一层会让标签比下面的卡片多缩进一截，对不齐。
    ) {
        item {
            ProfileTag(
                text = stringResource(R.string.profile_default),
                selected = activeIndex < 0,
                onClick = { onSelect(-1) },
                // 默认项不可改名、不可删除 —— 它不是一个用户对象。
                onLongClick = null,
            )
        }
        items(names.size) { index ->
            ProfileTag(
                text = names[index],
                selected = index == activeIndex,
                onClick = { onSelect(index) },
                onLongClick = { onLongPress(index) },
            )
        }
        item {
            ProfileTag(
                text = "+",
                selected = false,
                onClick = onAdd,
                onLongClick = null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileTag(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    // 选中态用主题色实底：一排标签里"当前用的是哪一个"是这一行的主要信息，
    // 比下面的控件更该被一眼看到。未选中用容器色，保持安静。
    val container: Color = if (selected) scheme.primary else scheme.secondaryContainer
    val content: Color = if (selected) scheme.onPrimary else scheme.onSecondaryContainer

    // 圆角用 chip 档而不是 card 档。**跟着设置走，但不能直接用 cardDp**：
    // 标签只有三十几 dp 高，18 dp 的圆角已经超过高度的一半，会被裁成椭圆 ——
    // 看着就不像"跟随卡片"了。chip 是 cardDp/3，随设置等比例变化，也不会压成胶囊。
    Box(
        modifier = Modifier
            .clip(LocalShapeStyle.current.chip)
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
