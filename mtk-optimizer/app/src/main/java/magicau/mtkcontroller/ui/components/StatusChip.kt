package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.ui.theme.LocalStatusOk
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * 状态小方框。用来在行尾表达"这一项现在的状态"，替代把它拼进标题。
 *
 * ## 为什么要做成组件，不是拼字符串
 *
 * 之前状态是 `"$label · $badge"` 这样拼进标题的。拼字符串的问题有三个，
 * 而且都是同一个根因：
 *
 *  - **没有视觉层级。** 标题和状态同样粗细同样颜色，用户分不清哪个是名字、
 *    哪个是状态；"·"是标点，不承担表达状态的职责。
 *  - **每种状态长得一样。** "预计无效"和"生效未验证"的严重程度差得远，
 *    拼进标题后都只是一串灰字，用户无法一眼扫出哪个需要在意。
 *  - **改一次要动所有调用点。** 想把状态换成小方框，得改十几处字符串拼接；
 *    做成组件后改这一处，所有调用点同时受益。
 *
 * ## 用法
 *
 * 只在**有状态要说**的时候渲染。正常/已知的状态不必给方框 —— 满屏都是
 * 标记等于没有标记。
 */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val (content, container) = tone.colors()
    Box(
        modifier = modifier
            .clip(LocalShapeStyle.current.chip)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * 状态的严重程度。**用语义分组，不用具体颜色** —— 调用方说自己想表达什么，
 * 由主题决定长什么样，深浅模式与强调色板切换时才不会各写各的。
 */
enum class StatusTone {
    /** 已确认、生效。 */
    Ok,

    /** 生效未验证、需要注意但不严重。 */
    Warn,

    /** 预计无效、失败。 */
    Error,

    /** 未测试、读不到、无法判断。 */
    Neutral,
}

/**
 * 取语义色。四组容器色与内容色都来自主题角色：
 * Ok 走应用自定义的成功色（Material3 没有 success 角色），
 * 其余分别落在 tertiary / error / secondary 容器上。
 */
@Composable
private fun StatusTone.colors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        StatusTone.Ok -> LocalStatusOk.current
        StatusTone.Warn -> scheme.onTertiaryContainer to scheme.tertiaryContainer
        StatusTone.Error -> scheme.onErrorContainer to scheme.errorContainer
        StatusTone.Neutral -> scheme.onSecondaryContainer to scheme.secondaryContainer
    }
}
