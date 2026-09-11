package magicau.mtkcontroller.data.privilege

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.diag.BatteryReportParser
import magicau.mtkcontroller.data.exec.DaemonExec

/**
 * 拉起系统页面与拨号盘。
 *
 * **必须经 [DaemonExec] 用 `am start` 启动**：设置里的这些 Activity 多数没有 exported，
 * 在 App 进程里 `startActivity` 会直接被 SecurityException 拒掉；而 `am` 跑在 shell 下
 * 有这个权限。失败原因直接给用户，不吞掉。
 */
object Shortcuts {

    suspend fun openPage(context: Context, component: String): String? {
        val (pkg, cls) = component.split('/').let {
            it[0] to it.getOrElse(1) { "" }.removePrefix(".")
        }
        val target = if (cls.isEmpty()) pkg else "$pkg.$cls"
        val out = DaemonExec.runArgv(listOf("/system/bin/am", "start", "-n", "$pkg/$target"))
            ?: return context.getString(R.string.shortcut_no_service, context.getString(R.string.priv_shortcut_page))
        if (out.exit != 0) {
            val reason = (out.stderr.ifBlank { out.stdout }).lineSequence()
                .firstOrNull { it.contains("Error", true) || it.contains("Exception", true) }
                ?: out.stderr.ifBlank { out.stdout }.lineSequence().firstOrNull()
            return context.getString(
                R.string.shortcut_page_failed,
                reason?.take(160) ?: context.getString(R.string.shortcut_no_ui),
            )
        }
        return null
    }

    suspend fun dialCode(context: Context, code: String): String? {
        val encoded = code.map { if (it == '*') "%2A" else if (it == '#') "%23" else "$it" }.joinToString("")
        val out = DaemonExec.runArgv(listOf("/system/bin/am", "start", "-a", "android.intent.action.DIAL", "-d", "tel:$encoded"))
            ?: return context.getString(R.string.shortcut_no_service, context.getString(R.string.priv_shortcut_dialer))
        if (out.exit != 0) {
            val reason = (out.stderr.ifBlank { out.stdout }).lineSequence().firstOrNull()
            return context.getString(
                R.string.shortcut_dial_failed,
                reason?.take(160) ?: context.getString(R.string.shortcut_no_dialer),
            )
        }
        return null
    }
}

/** 用系统文件选择器取一份 zip，并把它的内容交给电池报告解析器。 */
object FilePicker {

    /** 返回 null = 用户没选；否则返回人可读的解析结果。 */
    fun pickAndParse(context: Context, uri: Uri): String {
        val name = queryName(context, uri) ?: context.getString(R.string.picker_unknown_file)
        // 直接读 content:// 流，不要求先复制到本机 —— bugreport 可能有几十 MB。
        val parsed = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // bugreport 是 zip；用 File 接口的解析器需要真实路径，所以改成流式取最大 txt。
                BatteryReportParser.parseStream(input, name)
            }
        }.getOrElse {
            return context.getString(R.string.picker_parse_failed, it.javaClass.simpleName, it.message.orEmpty())
        } ?: return context.getString(R.string.picker_unreadable)
        // 成没成由解析器说了算，不去猜拼出来的文字里是什么。
        val head = parsed.message.resolve(context)
        val body = if (parsed.present) parsed.body else ""
        return listOf(name, head, body).filter { it.isNotEmpty() }.joinToString("\n")

    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) c.getString(index) else null
        }
    }.getOrNull()

    /** 供 UI 记住最近一次解析是否失败。 */
    fun validate(context: Context, name: String): String? = when {
        !name.endsWith(".zip", true) -> context.getString(R.string.picker_not_zip)
        else -> null
    }
}

/** 供 UI 直接读取一段 shell 输出（拨号暗码解释等）。经用户服务执行，App 进程读不全。 */
object QuickRead {
    suspend fun run(command: List<String>): String? {
        val out = DaemonExec.runArgv(command) ?: return null
        return if (out.exit == 0 && out.stderr.isBlank()) out.stdout else null
    }
}
