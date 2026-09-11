package magicau.mtkcontroller.feature.settings

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.tweaks.TweakCommands
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SectionLabel
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow

/**
 * 归档：旧版入口与备选方案。
 *
 * ## 为什么单独一页，而不是删掉
 *
 * 这里的每一项都**仍然能用**。它们被挪走的原因是有了更好的做法，不是坏了 ——
 * 比如频率配合项针对的"只用频率请求时高频上不去"，现在由温控档位那条路解决，
 * 但配合项对别的机型未必无效。删掉等于替用户做了判断，而判断依据只在我们这边。
 *
 * ## 为什么按测试/配置分
 *
 * 两类东西的使用方式不同：**测试**是去看一眼就走的，**配置**是改完就长期生效的。
 * 混在一列里，用户分不清点下去会发生什么。
 *
 * ## 这里不该继续变大
 *
 * 新功能不进这一页。只有当某个做法被明确替代、而又不能删时，才挪进来。
 */
@Composable
fun ArchiveScreen(
    freqCompanionLabel: String,
    onSetFreqCompanion: (String?) -> Unit,
    onOpenTests: () -> Unit,
    onOpenFeatureTrial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var companionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.archive_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.archive_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )

        SectionLabel(stringResource(R.string.archive_group_config))
        SettingsCard {
            SettingsRow(
                icon = Icons.Default.Build,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.freq_companion_title),
                subtitle = freqCompanionLabel,
                onClick = { companionDialog = true },
            )
        }

        SectionLabel(stringResource(R.string.archive_group_test))
        SettingsCard {
            SettingsRow(
                icon = Icons.Default.List,
                iconTint = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.archive_feature_trial),
                subtitle = stringResource(R.string.archive_feature_trial_sub),
                onClick = onOpenFeatureTrial,
            )
            InsetDivider()
            SettingsRow(
                icon = Icons.Default.Info,
                iconTint = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.archive_tests),
                subtitle = stringResource(R.string.archive_tests_sub),
                onClick = onOpenTests,
            )
        }
    }

    if (companionDialog) AlertDialog(
        onDismissRequest = { companionDialog = false },
        title = { Text(stringResource(R.string.freq_companion_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.freq_companion_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                // "关闭"也列出来：当前没选时要能显式回到不带配合项的状态。
                val options: List<String?> =
                    listOf(null) + TweakCommands.Companion.entries.map { c -> c.name }
                options.forEach { id ->
                    val label = if (id == null) stringResource(R.string.freq_companion_off)
                    else stringResource(TweakCommands.Companion.valueOf(id).labelRes)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = false,
                            onClick = { companionDialog = false; onSetFreqCompanion(id) },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { companionDialog = false }) {
                Text(stringResource(R.string.action_got_it))
            }
        },
    )
}
