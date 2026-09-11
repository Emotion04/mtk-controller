package magicau.mtkcontroller.ui.components

import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/**
 * Header for the screens reached from Settings.
 *
 * The title is centred in the bar rather than placed after the back button,
 * which is the platform convention and keeps the three sub-screens consistent
 * with each other. Width is reserved on both sides so a long title cannot push
 * the text off-centre.
 */
@Composable
fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 右侧操作区，与返回键对称。
     *
     * 顶栏右侧放操作是通用做法：用户找"这一页还能干什么"先看右上角。
     * 放进内容区（比如和别的入口并排）会被当成页面内容的一部分，
     * 而且要滚到那儿才看得见。
     */
    actions: @Composable RowScope.() -> Unit = {},
    /** 见 [SubScreenScaffold]。 */
    leadingIcon: ImageVector? = null,
    leadingDescription: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp),
        ) {
            Icon(
                imageVector = leadingIcon ?: Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = leadingDescription ?: stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Symmetric padding keeps the title's centre on the bar's centre,
            // and clears the back button at the same time.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp),
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * Top bar plus content, the shared frame for every screen reached from Settings.
 *
 * 内容区**不在这里加滚动** —— 有些二级页的内容是 LazyColumn，外面再包一层
 * verticalScroll 会因为高度无约束而崩。滚动由各页自己决定；这里只负责
 * **收缩视口**，让"滚到底"的判定落在悬浮底栏之上。
 */
@Composable
fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 顶栏右侧操作区。见 [SubScreenTopBar]。 */
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * 顶栏左侧图标。null = 用默认的返回箭头。
     *
     * 编辑态下换成一个「×」：那个位置点下去是"放弃编辑"，不是"返回上一页"，
     * 沿用返回箭头会让人以为编辑已经保存了。
     */
    leadingIcon: ImageVector? = null,
    leadingDescription: String? = null,
    content: @Composable () -> Unit,
) {
    // 每个页面必须有**自己的不透明背景**。导航库的页面默认透明，滑动时两页
    // 同时绘制，旧页的文字和新页的文字会叠在一起 —— 看起来像半透明重影。
    // 主题里那层 Surface 包的是整个 NavHost，是所有页共享的一块底，
    // 挡不住这种叠加。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SubScreenTopBar(
            title = title,
            onBack = onBack,
            actions = actions,
            leadingIcon = leadingIcon,
            leadingDescription = leadingDescription,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                // 留白跟**全应用同一个值**走（见 LocalNavBarClearance）：
                // 底栏固定时内容真的让开，自动隐藏时铺满即可。
                .padding(bottom = LocalNavBarClearance.current),
        ) {
            content()
        }
    }
}
