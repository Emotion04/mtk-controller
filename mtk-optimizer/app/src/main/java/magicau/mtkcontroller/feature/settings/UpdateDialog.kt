package magicau.mtkcontroller.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.update.UpdateCenter
import magicau.mtkcontroller.data.update.UpdateInfo
import magicau.mtkcontroller.data.update.UpdateInstaller
import magicau.mtkcontroller.di.AppContainer

/**
 * 发现新版本时的弹窗，启动自动检查与关于页手动检查共用。
 *
 * 只显示清单里真实给出的字段：清单没有 `size` 就不显示大小、也不直接下载（只打开下载页）；
 * 没有直链（不是 .apk 结尾）就不是安装包，按钮退化为“前往下载”。
 */
@Composable
fun UpdateCenterDialog(container: AppContainer) {
    val state by UpdateCenter.state.collectAsStateWithLifecycle()
    val info = state.info ?: return
    if (!state.dialogOpen) return
    UpdateDialog(
        container = container,
        info = info,
        required = state.required,
        progress = state.progress,
        onAction = {
            when {
                state.progress is UpdateInstaller.Progress.Ready -> UpdateCenter.install(container)
                info.directApk -> UpdateCenter.download(container)
                else -> openDownloadPage(container, info.url)
            }
        },
        onLater = { UpdateCenter.dismiss(container) },
    )
}

private val UpdateInfo.directApk: Boolean
    get() = size > 0 && url.substringBefore('?').endsWith(".apk", ignoreCase = true)

private fun openDownloadPage(container: AppContainer, url: String) {
    val context = container.context
    val opened = runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.update_open_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun UpdateDialog(
    container: AppContainer,
    info: UpdateInfo,
    required: Boolean,
    progress: UpdateInstaller.Progress,
    onAction: () -> Unit,
    onLater: () -> Unit,
) {
    val direct = info.directApk
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!required) onLater() },
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.update_new_version, info.versionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.update_version_code, info.versionCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val size = if (info.size > 0) UpdateInstaller.formatSize(context, info.size) else null
                Text(
                    if (size != null) {
                        stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
                            stringResource(R.string.update_separator) + stringResource(R.string.update_package_size, size)
                    } else {
                        stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                when (val p = progress) {
                    is UpdateInstaller.Progress.Downloading -> {
                        val shown = if (p.percent >= 0) p.percent / 100f else null
                        if (shown != null) {
                            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            if (p.total > 0) {
                                stringResource(
                                    R.string.update_downloading,
                                    UpdateInstaller.formatSize(context, p.bytes),
                                    UpdateInstaller.formatSize(context, p.total),
                                )
                            } else {
                                stringResource(R.string.update_downloading_unknown, UpdateInstaller.formatSize(context, p.bytes))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateInstaller.Progress.Ready -> Unit
                    is UpdateInstaller.Progress.Failed -> Text(
                        stringResource(p.reasonRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    UpdateInstaller.Progress.Idle -> Unit
                }
                if (required) {
                    Text(
                        stringResource(R.string.update_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            val downloading = progress is UpdateInstaller.Progress.Downloading
            TextButton(onClick = onAction, enabled = !downloading) {
                Text(
                    when {
                        progress is UpdateInstaller.Progress.Ready -> stringResource(R.string.update_install)
                        downloading -> stringResource(R.string.update_downloading_button)
                        direct -> stringResource(R.string.update_download)
                        else -> stringResource(R.string.update_open_page)
                    },
                )
            }
        },
        dismissButton = if (required) null else {
            { TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) } }
        },
    )
}
