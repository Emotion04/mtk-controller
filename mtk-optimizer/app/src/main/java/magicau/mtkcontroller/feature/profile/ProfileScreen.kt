package magicau.mtkcontroller.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.domain.model.Profile
import magicau.mtkcontroller.feature.cpu.EmptyState

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onApply: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onRename: (Profile, String) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    var renaming by remember { mutableStateOf<Profile?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("方案", style = MaterialTheme.typography.titleLarge)
                Text(
                    "把一组频率上下限存成方案,之后一键套用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.profiles.isEmpty()) {
            item {
                EmptyState(
                    title = "还没有方案",
                    body = "到 CPU 页面调好各簇的上下限,再点「保存为方案」。",
                )
            }
        } else {
            items(state.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == state.activeId,
                    busy = state.busy,
                    onApply = { onApply(profile) },
                    onDelete = { onDelete(profile) },
                    onRename = { renaming = profile },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRelease,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("释放当前调频") }

                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    renaming?.let { profile ->
        RenameDialog(
            initial = profile.name,
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(profile, it)
                renaming = null
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    active: Boolean,
    busy: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (active) AssistChip(onClick = {}, label = { Text("生效中") })
            }

            Text(
                text = buildString {
                    append("${profile.clusters.size} 个簇")
                    profile.clusters.take(3).forEach { c ->
                        append(" · ${c.policy} ")
                        append("${c.minFreqKhz / 1000}-${c.maxFreqKhz / 1000} MHz")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("一键套用")
                }
                OutlinedButton(onClick = onRename, enabled = !busy) { Text("重命名") }
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名方案") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { initial }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
