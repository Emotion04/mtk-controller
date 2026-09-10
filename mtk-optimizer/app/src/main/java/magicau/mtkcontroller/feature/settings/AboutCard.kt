package magicau.mtkcontroller.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SettingsCard
import java.net.URL

/** Everything shown in the about block. One place to edit contact details. */
private object Author {
    const val NAME = "Emotion04"
    const val QQ = "1695617871"
    const val WECHAT = "1695617871"
    const val EMAIL = "infpc@msn.com"
    const val GITHUB = "https://github.com/Emotion04"

    /** GitHub's avatar endpoint is public and needs no auth. */
    val AVATAR_URL = "https://github.com/$NAME.png?size=200"
}

/**
 * Profile-style about block.
 *
 * Deliberately not a list of dead text: the avatar is fetched live from GitHub
 * and each row is tappable, opening the matching app where one exists (QQ has
 * a URL scheme; WeChat does not, so the id is copied instead).
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val avatar = rememberRemoteAvatar(Author.AVATAR_URL)

    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(bitmap = avatar)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "MTK Controller",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "by ${Author.NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text("v${BuildConfig.VERSION_NAME}") },
                    )
                }
            }
        }

        InsetDivider(startIndent = 16.dp)

        ContactRow(
            icon = Icons.Filled.Person,
            tint = Color(0xFF12B7F5),
            label = "QQ",
            value = Author.QQ,
            actionHint = "打开聊天",
            onClick = {
                val scheme = "mqqwpa://im/chat?chat_type=wpa&uin=${Author.QQ}&version=1&src_type=web"
                if (!context.tryOpen(scheme)) {
                    context.openUrl("https://wpa.qq.com/msgrd?v=3&uin=${Author.QQ}&site=qq&menu=yes")
                }
            },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Share,
            tint = Color(0xFF07C160),
            label = "微信",
            value = Author.WECHAT,
            actionHint = "复制",
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("wechat", Author.WECHAT))
                Toast.makeText(context, "微信号已复制", Toast.LENGTH_SHORT).show()
            },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Email,
            tint = Color(0xFFEA4335),
            label = "邮箱",
            value = Author.EMAIL,
            actionHint = "发邮件",
            onClick = { context.openUrl("mailto:${Author.EMAIL}") },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Star,
            tint = Color(0xFF6A4C93),
            label = "GitHub",
            value = Author.NAME,
            actionHint = "打开主页",
            onClick = { context.openUrl(Author.GITHUB) },
        )

        InsetDivider(startIndent = 16.dp)
        Text(
            text = "versionCode ${BuildConfig.VERSION_CODE} · ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Avatar(bitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(62.dp),
            )
        } else {
            Text(
                "E",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContactRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    value: String,
    actionHint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AssistChip(onClick = onClick, label = { Text(actionHint) })
    }
}

@Composable
private fun rememberRemoteAvatar(url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    return bitmap
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Returns true when some installed app handled the scheme. */
private fun Context.tryOpen(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
