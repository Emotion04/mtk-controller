package magicau.mtkcontroller.data.privilege

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.exec.DaemonExec

/**
 * 编译前的只读状态。
 *
 * 为什么需要它：`cmd package compile` 会覆盖系统 bg-dexopt-job 的结果，系统稍后也可能
 * 再覆盖回来。反复编译不会损坏产物（odex 原地覆盖，不追加副本），但会白干 —— 发热和
 * 存储都付出了，最后谁跑得晚谁定级别。
 *
 * 还有一类更隐蔽的：`speed-profile` 依赖使用记录文件。文件不存在或过期时它会静默退化，
 * 表现是"编译成功、读播没变化"，跟 ROM 没关系。所以这里连 profile 一起读。
 *
 * 只读，不推断"值不值得编译"，只报现在是什么状态。
 */
object CompileStatus {

    /** @param odexKb / vdexKb null 表示该文件不存在；profileBytes null 表示读不到。 */
    data class State(
        val apkPath: String?,
        val odexKb: Long?,
        val vdexKb: Long?,
        val apkMtimeMs: Long?,
        val odexMtimeMs: Long?,
        val profileBytes: Long?,
        /**
         * 一行结论。**是 `Message` 不是 `String`** —— 它是给用户看的文案，
         * 而这里拿不到 `Context`（也不该拿：数据层不该依赖资源系统）。
         * 构造 `Message` 不需要 `Context`，解析留给展示边界。
         */
        val note: Message,
    ) {
        /** odex 比 apk 新，说明编译发生在最近一次安装或更新之后。 */
        val compiledAfterUpdate: Boolean
            get() = odexMtimeMs != null && (apkMtimeMs == null || odexMtimeMs >= apkMtimeMs)

        /** odex 比 apk 旧，应用更新过但没重新编译。 */
        val stale: Boolean get() = apkMtimeMs != null && odexMtimeMs != null && apkMtimeMs > odexMtimeMs

        val hasCompiledArtifact: Boolean get() = odexKb != null
    }

    /** @param pkg 目标应用。读不到任何东西时照样返回一个 State，note 说明卡在哪。 */
    suspend fun check(pkg: String): State {
        val apk = DaemonExec.runArgv(listOf("/system/bin/pm", "path", pkg))
        val apkPath = if (apk != null && apk.exit == 0) {
            apk.stdout.lineSequence().firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.trim()
        } else null
        if (apkPath == null) {
            return State(null, null, null, null, null, null,
                if (apk == null) Message(R.string.compile_status_no_service)
                else Message(R.string.compile_status_no_path, listOf(pkg)))
        }
        val dir = apkPath.substringBeforeLast('/')

        // 一次 stat 拿到 oat 目录下所有产物的体积和修改时间。用 glob 而不是 find -exec，
        // 老版本 toybox 的 find 不一定支持 -exec ... +。
        val oat = DaemonExec.run(
            "for f in '${dir.replace("'", "'\\''")}'/oat/*/*; do [ -f \"\$f\" ] && stat -c '%s %Y %n' \"\$f\"; done 2>/dev/null")
        var odexKb: Long? = null
        var vdexKb: Long? = null
        var odexMtimeMs: Long? = null
        for (line in oat?.stdout?.lineSequence().orEmpty()) {
            val parts = line.trim().split(' ')
            if (parts.size < 3) continue
            val size = parts[0].toLongOrNull() ?: continue
            val mtimeSec = parts[1].toLongOrNull() ?: continue
            val path = parts[2]
            when {
                path.endsWith(".odex") -> {
                    odexKb = (odexKb ?: 0L) + size / 1024
                    odexMtimeMs = maxOf(odexMtimeMs ?: 0L, mtimeSec * 1000)
                }
                path.endsWith(".vdex") -> vdexKb = (vdexKb ?: 0L) + size / 1024
            }
        }

        val apkMtime = DaemonExec.runArgv(listOf("/system/bin/stat", "-c", "%Y", apkPath))
        val apkMtimeMs = apkMtime?.stdout?.trim()?.toLongOrNull()?.times(1000)

        // 使用记录文件位置随版本变过；两处都试，读不到不猜测。
        val profile = DaemonExec.run(
            "stat -c %s '/data/misc/profiles/cur/0/${pkg.replace("'", "'\\''")}/primary.prof' 2>/dev/null || " +
                "stat -c %s '/data/misc/profiles/ref/${pkg.replace("'", "'\\''")}/primary.prof' 2>/dev/null")
        val profileBytes = profile?.stdout?.trim()?.toLongOrNull()

        return State(apkPath, odexKb, vdexKb, apkMtimeMs, odexMtimeMs, profileBytes,
            note(odexKb, vdexKb, apkMtimeMs, odexMtimeMs, profileBytes))
    }

    /** 一行结论。只讲状态，不讲"建议编译"或"不建议"。 */
    internal fun note(
        odexKb: Long?,
        vdexKb: Long?,
        apkMtimeMs: Long?,
        odexMtimeMs: Long?,
        profileBytes: Long?,
    ): Message {
        // 产物大小那一段是 ASCII（"odex 12 MiB"），不是文案，保持拼好的 String 当参数。
        val sizes = listOfNotNull(
            odexKb?.let { "odex ${it / 1024} MiB" },
            vdexKb?.takeIf { it > 0 }?.let { "vdex ${it / 1024} MiB" },
        ).joinToString(" · ")

        // vdex 是验证信息，odex 才是编译出来的机器码。两者都没有才算没编译。
        val status = when {
            odexKb == null && vdexKb == null -> R.string.compile_note_none
            odexKb == null -> R.string.compile_note_vdex_only
            odexMtimeMs == null -> R.string.compile_note_no_odex_time
            apkMtimeMs == null -> R.string.compile_note_no_apk_time
            apkMtimeMs > odexMtimeMs -> R.string.compile_note_stale
            else -> R.string.compile_note_fresh
        }
        val profile = when (profileBytes) {
            null -> Message(R.string.compile_note_profile_unreadable)
            0L -> Message(R.string.compile_note_profile_empty)
            else -> Message(R.string.compile_note_profile_size, listOf(profileBytes / 1024))
        }

        // 两套模板，免得产物不存在时拼出一个开头的分隔符。
        return if (sizes.isEmpty()) {
            Message(R.string.compile_note_plain, listOf(Message(status), profile))
        } else {
            Message(R.string.compile_note_full, listOf(sizes, Message(status), profile))
        }
    }
}
