package magicau.mtkcontroller.feature.expiry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.R
import magicau.mtkcontroller.ui.text

/**
 * 过期后的唯一界面。
 *
 * **它不是弹窗**：主界面根本不组合，所以没有返回、没有深链、没有入口可以绕过。
 * 只留两个安全出口 —— 撤掉还在生效的请求、把本软件写过的设置恢复默认。
 * 这两个动作不进入任何功能页，也不依赖功能开关。
 */
@Composable
fun ExpiryScreen(
    viewModel: ExpiryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.expiry_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.expiry_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.expiry_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::releaseAll,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.expiry_release)) }
        OutlinedButton(
            onClick = viewModel::restoreDefaults,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.expiry_restore)) }
        state.message?.let { failure ->
            Text(
                failure.summary.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
