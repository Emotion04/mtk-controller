package magicau.mtkcontroller.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.ui.theme.LocalStatusOk
import magicau.mtkcontroller.ui.theme.LocalShapeStyle
import magicau.mtkcontroller.ui.theme.LocalMotion

/**
 * 授权状态卡片。放在设置页顶部，让用户一眼看出现在能不能用。
 *
 * 两个状态，用底色区分而不是文字描述：红＝不可用，绿＝可用。
 * 点未授权的那一张就是授权入口，不再需要用户自己去翻菜单找开关。
 * 授权成功后的状态变化是当前设备上最重要的反馈，所以做颜色过渡而不是硬切。
 */
@Composable
fun ShizukuStatusCard(
    privilege: PrivilegeState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = privilege.permissionGranted
    val scheme = MaterialTheme.colorScheme
    // 颜色只从语义角色取，组件里不出现任何 Color(0x…)：这两个目标值会随深浅
    // 模式与强调色板切换，写死就等于在另一半模式下颜色是错的。
    val ok = LocalStatusOk.current
    val targetContainer = if (ready) ok.second else scheme.errorContainer
    val targetContent = if (ready) ok.first else scheme.onErrorContainer
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = LocalMotion.current.slowSpec(),
        label = "authContainer",
    )
    val content by animateColorAsState(
        targetValue = targetContent,
        animationSpec = LocalMotion.current.slowSpec(),
        label = "authContent",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(LocalShapeStyle.current.card)
            .then(if (ready) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)),
        shape = LocalShapeStyle.current.card,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(LocalShapeStyle.current.iconBox)
                    .background(content.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (ready) R.string.shizuku_status_ready else R.string.shizuku_status_needed,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                AnimatedVisibility(
                    visible = ready,
                    enter = fadeIn(LocalMotion.current.baseSpec()) + expandVertically(LocalMotion.current.baseSpec()),
                    exit = fadeOut(LocalMotion.current.fastSpec()) + shrinkVertically(LocalMotion.current.fastSpec()),
                ) {
                    Text(
                        text = stringResource(R.string.shizuku_status_ready_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.78f),
                    )
                }
                AnimatedVisibility(
                    visible = !ready,
                    enter = fadeIn(LocalMotion.current.baseSpec()) + expandVertically(LocalMotion.current.baseSpec()),
                    exit = fadeOut(LocalMotion.current.fastSpec()) + shrinkVertically(LocalMotion.current.fastSpec()),
                ) {
                    Text(
                        text = stringResource(R.string.shizuku_status_needed_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.78f),
                    )
                }
            }
            if (!ready) {
                Text(
                    text = stringResource(R.string.shizuku_status_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}
