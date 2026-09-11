package magicau.mtkcontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.theme.adaptiveAccent
import magicau.mtkcontroller.ui.theme.isDarkScheme
import androidx.compose.material3.Switch
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * Grouped settings card: a clean white panel lifted off the light grey ground
 * by a soft shadow rather than an outline — no hard stroke anywhere.
 *
 * Rows inside are separated by a hairline that stops short of both edges, so
 * the group reads as one object instead of a stack of separate boxes.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = LocalShapeStyle.current.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
    ) {
        Column(content = content)
    }
}

/** Thin, semi-transparent separator that does not reach the card edges. */
@Composable
fun InsetDivider(startIndent: Dp = 62.dp) {
    Box(
        modifier = Modifier
            .padding(start = startIndent, end = 18.dp)
            .height(0.7.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** A row: rounded tinted icon tile, title/subtitle, optional trailing content. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        iconPainter = rememberVectorPainter(icon),
        iconTint = iconTint,
        title = title,
        subtitle = subtitle,
        trailingText = trailingText,
        trailing = trailing,
        onClick = onClick,
    )
}

/**
 * [iconPainter] 给"图标集里没有、只能自己画矢量图"的场合用。
 *
 * 项目只依赖 material-icons-core（56 个），像"实验室烧瓶"这种语义明确却没有的图形，
 * 与其引 extended（涨几十 MB），不如在 `res/drawable/` 画一个。两条重载分开是为了
 * 让调用点写起来还是 `SettingsRow(icon = …, …)` 的样子。
 */
@Composable
fun SettingsRow(
    iconPainter: Painter,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base

    // Section accents are picked to read on a white card; on a dark card the
    // deeper ones (navy, forest, plum) disappear into the tile, so lift them
    // here once rather than at every call site.
    val tint = iconTint.adaptiveAccent(isDarkScheme())

    Row(
        modifier = clickable.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(LocalShapeStyle.current.iconBox)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trailingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/** Section caption above a group, so groups don't need their own headers. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
    )
}

/**
 * `SettingsCard` 里的无图标开关行：自带 16dp 水平内边距。
 *
 * 新增设置项时优先用它或 [SettingsRow] + `trailing = { Switch(...) }`，
 * 不要把 [ToggleRow] 直接放进 `SettingsCard` —— 那个组件没有内边距，
 * 只给已经补过 16dp 的 `ToolCard` 一类容器用。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ToggleRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.padding(horizontal = 16.dp),
        enabled = enabled,
        subtitle = subtitle,
    )
}

/**
 * 带开关的设置行。**全应用只有这一个实现。**
 *
 * **不自带水平内边距。** 它只给已经有 16dp 内边距的容器用（`ToolCard`、首页卡片）；
 * `SettingsCard` 里的行请用 [SettingsRow] + `trailing = { Switch(...) }` ——
 * `SettingsRow` 自己带 16dp，混用会让新行贴到卡片边缘。
 *
 * 之前 `ToggleRow` 在首页和调校页各写了一份，签名和布局都不同 —— 同一个名字
 * 两种长相。合并成一个：所有槽位可选，调用方只填用得上的。
 *
 * ## 行高按 Material 3
 *
 * 有副标题两行 → 72dp；只有标题一行 → 56dp。**行高由内容决定，不靠堆说明撑开**：
 * 说明长了进 [info] 的浮层，不写进副标题。
 *
 * @param icon 可选的分类图标。重要选项才给，全给就等于没有分类。
 * @param subtitle 一行补充说明。超过一行请改写或移进 [info]。
 * @param chipText 状态方框的文字。**状态走这里，不要拼进 [title]。**
 * @param info 说明浮层的正文。给了才渲染 ⓘ。
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    chipText: String? = null,
    chipTone: StatusTone = StatusTone.Neutral,
    info: String? = null,
    /**
     * 读不到当前状态。只在开关前放一个低透明度问号，**不写文字** ——
     * 「未知」是一种状态标记，不需要一句解释。
     */
    unknown: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (subtitle == null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(LocalShapeStyle.current.iconBox)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        chipText?.let { StatusChip(it, chipTone) }
        info?.let { FeatureInfo(title, it) }
        if (unknown) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_unknown),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(16.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
