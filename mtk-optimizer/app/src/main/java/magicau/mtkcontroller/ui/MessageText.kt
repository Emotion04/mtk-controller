package magicau.mtkcontroller.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.data.Message

/**
 * 在 Compose 里解析 [Message]。
 *
 * 用 `stringResource` 而不是 `context.getString` —— 前者是 Composition 的一部分，
 * 系统语言变了会重组；后者读一次就定住了，得靠别的东西触发刷新。
 *
 * 参数里的 [Message] 递归解析。`map` 是内联函数，所以里面可以调 @Composable。
 */
@Composable
fun Message.text(): String =
    stringResource(res, *args.map { if (it is Message) it.text() else it }.toTypedArray())
