package magicau.mtkcontroller.feature.changelog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.changelog.ChangelogEntry
import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.theme.isDarkScheme
import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING

/**
 * 更新日志：App 内的原生页面，不是 WebView。
 *
 * 静态托管只负责放 JSON；时间轴、徽章、字号、主题全部由 Compose 画。
 */
@Composable
fun ChangelogScreen(
    viewModel: ChangelogViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Column(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 6.dp,
            bottom = SUB_SCREEN_EXTRA_PADDING,
        ),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (state.remote) R.string.changelog_source_remote else R.string.changelog_source_local),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = viewModel::refresh,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Text(
                        stringResource(R.string.changelog_refresh),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                state.error?.let { FailureNote(failure = it) }
            }
        }

        if (state.entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.changelog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        itemsIndexed(state.entries, key = { _, entry -> "${entry.versionName}|${entry.date}" }) { index, entry ->
            VersionBlock(
                entry = entry,
                isLatest = index == 0,
                isLast = index == state.entries.lastIndex,
                isCurrent = entry.versionName == BuildConfig.VERSION_NAME,
            )
        }
    }
}

@Composable
private fun VersionBlock(
    entry: ChangelogEntry,
    isLatest: Boolean,
    isLast: Boolean,
    isCurrent: Boolean,
) {
    val accent = if (isLatest) MutedOlive else MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = Modifier.fillMaxWidth()) {
        // 时间轴节点与贯穿竖线；节点对齐版本标题，最后一条不画向下的线。
        Box(
            modifier = Modifier
                .width(18.dp)
                .drawBehind {
                    val x = 5.dp.toPx()
                    val cy = 11.dp.toPx()
                    val r = 4.dp.toPx()
                    if (!isLast) {
                        drawLine(
                            color = line,
                            start = Offset(x, cy + r + 3.dp.toPx()),
                            end = Offset(x, size.height),
                            strokeWidth = 0.75.dp.toPx(),
                        )
                    }
                    drawCircle(color = accent, radius = r, center = Offset(x, cy))
                },
        ) { Spacer(Modifier.width(18.dp)) }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 4.dp else 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v${entry.versionName.ifBlank { entry.versionCode.toString() }}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLatest) MutedOlive else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    entry.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.changelog_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            // 按类型归组：一个版本里每种标签只出现一次，即使 JSON 里
            // 同类型条目没有相邻。组内保持原顺序。
            val grouped = linkedMapOf<String, MutableList<String>>()
            entry.notes.forEach { note ->
                grouped.getOrPut(note.type) { mutableListOf() }.add(note.text)
            }
            val rows = grouped.flatMap { (type, texts) ->
                texts.mapIndexed { i, text -> Triple(type, i == 0, text) }
            }
            rows.forEachIndexed { rowIndex, (type, first, text) ->
                NoteRow(type = type, text = text, showBadge = first)
                if (rowIndex != rows.lastIndex) DottedDivider()
            }
        }
    }
}

@Composable
private fun NoteRow(type: String, text: String, showBadge: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (type.isNotBlank()) {
            if (showBadge) NoteBadge(type) else Spacer(Modifier.width(BadgeWidth))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private val BadgeWidth = 40.dp

private data class BadgeStyle(
    val labelRes: Int,
    val bg: Color,
    val fg: Color,
    val border: Color,
)

@Composable
private fun badgeStyle(type: String): BadgeStyle {
    val dark = isDarkScheme()
    return when (type.lowercase()) {
        "feat" -> if (dark) BadgeStyle(R.string.changelog_type_feat, Color(0xFF1E2A18), Color(0xFFA8D48A), Color(0xFF3C5530))
        else BadgeStyle(R.string.changelog_type_feat, Color(0xFFE9F2DC), Color(0xFF5E7A3C), Color(0xFFC9DCB4))
        "fix" -> if (dark) BadgeStyle(R.string.changelog_type_fix, Color(0xFF2B1A1C), Color(0xFFF0A0A6), Color(0xFF5A3035))
        else BadgeStyle(R.string.changelog_type_fix, Color(0xFFFBE4E5), Color(0xFFB2545A), Color(0xFFE7BFC2))
        "perf" -> if (dark) BadgeStyle(R.string.changelog_type_perf, Color(0xFF18222F), Color(0xFF9CC2E8), Color(0xFF2E4660))
        else BadgeStyle(R.string.changelog_type_perf, Color(0xFFE3EEF9), Color(0xFF4A6FA5), Color(0xFFBFD3E8))
        "refactor" -> if (dark) BadgeStyle(R.string.changelog_type_refactor, Color(0xFF262117), Color(0xFFC9B58A), Color(0xFF4C402B))
        else BadgeStyle(R.string.changelog_type_refactor, Color(0xFFF3EBDD), Color(0xFF8A6D3B), Color(0xFFDCCBB0))
        "docs" -> if (dark) BadgeStyle(R.string.changelog_type_docs, Color(0xFF17222B), Color(0xFF8FB8D8), Color(0xFF2C4454))
        else BadgeStyle(R.string.changelog_type_docs, Color(0xFFE6F0F8), Color(0xFF3F6D94), Color(0xFFC2D8E8))
        "chore" -> if (dark) BadgeStyle(R.string.changelog_type_chore, Color(0xFF24211C), Color(0xFFBDB3A2), Color(0xFF453F35))
        else BadgeStyle(R.string.changelog_type_chore, Color(0xFFF0ECE4), Color(0xFF7D7464), Color(0xFFD8CFC1))
        else -> BadgeStyle(
            R.string.changelog_type_other,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun NoteBadge(type: String) {
    val style = badgeStyle(type)
    Box(
        modifier = Modifier
            .width(BadgeWidth)
            .height(20.dp)
            .background(style.bg, RoundedCornerShape(6.dp))
            .border(1.dp, style.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(style.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = style.fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun DottedDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = BadgeWidth + 8.dp, top = 7.dp, bottom = 7.dp)
            .height(0.5.dp)
            .background(if (isDarkScheme()) Color(0xFF2A2E35) else Color(0xFFE5E7EB)),
    )
}

private val MutedOlive = Color(0xFF65764A)
private val OutlineVariant = Color(0x33000000)
