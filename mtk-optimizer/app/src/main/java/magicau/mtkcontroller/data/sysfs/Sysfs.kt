package magicau.mtkcontroller.data.sysfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.exec.DaemonExec
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import java.io.File

/**
 * Small helper over the kernel's text-file interfaces.
 *
 * Reads go through plain File I/O first — most cpufreq/procfs nodes are
 * world-readable, so no privilege is needed and it is far cheaper than a
 * shell round-trip. Writes always go through the elevated shell.
 */
object Sysfs {

    /** 提权服务不可用时 [writeOrNull] 的返回值。**不是界面文案**，是诊断标记。 */
    private const val NO_SERVICE = "privilege service unavailable"

    /**
     * 取出用户服务输出里的正文；**没有 `[exit]` 标记就返回 null。**
     *
     * 服务端抛异常时回的是 `ERROR: …`，不带标记（见 `RuntimeUserService.exec`
     * 的 catch 分支）。用 `substringBefore("[exit]")` 取会把整句错误当正文，于是：
     *
     *  - `readElevated` 把错误文本当成文件内容返回 —— 调用方以为读到了值；
     *  - `write` 因为"正文非空"报告成功 —— 一次失败的写入显示成成功；
     *  - `list` 把错误行当文件名，再被前缀过滤掉 —— **变成空列表**，
     *    和"目录里真的没有文件"一模一样。
     *
     * 三种都是静默的：调用方看不出哪里错了。`DaemonExec.parse` 与
     * `VivoReply.output` 早就要求这个标记，这里补齐同一规矩。
     *
     * **剥标记只有一份实现，就在 [DaemonExec.parse]。** 这里直接用它，不再写第二套：
     * 服务端的包装是 `<stdout>\n[stderr] <stderr>\n[exit] <code>`，两处各写一遍的
     * 后果是行为会悄悄分叉 —— 曾经这里不剥 `[stderr]`，于是命令写到标准错误的内容
     * 会混进"文件内容"和"文件名"里，而 `DaemonExec` 那边早就剥掉了。
     * 规矩相同的事不要实现两次。
     */
    private fun body(raw: String): String? = DaemonExec.parse(raw)?.stdout

    /** Announces each file in a batched read. */
    private const val MARKER = "@@@node "

    /**
     * 把一轮 shell 的输出按 [MARKER] 切成一块一块，每块 = 一个路径 + 它后面所有行。
     *
     * ## 为什么要有它
     *
     * 一次读 N 个节点只跑一段 shell（每个 `cat` 前先 `echo` 一行标记），
     * 输出要靠这个骨架切回"哪几行属于哪个节点"。这个状态机原来在
     * [readMany] 和 [states] 里各写一遍，而两份的换行处理还不一样
     * （`appendLine()` vs `append('\n')`）—— 那种分叉没有任何理由，只会漂。
     *
     * **块内怎么解释由调用方决定**：[readMany] 把整块当原文，
     * [states] 先看第一行是不是退出码、剩下的才是输出。
     * 统一的是切块，不是语义。
     *
     * 不用局部 fun（inline 函数里不允许），所以收尾那块重复了两次。
     */
    private inline fun markerBlocks(payload: String, onBlock: (path: String, lines: List<String>) -> Unit) {
        var current: String? = null
        val buffer = StringBuilder()
        for (line in payload.lines()) {
            if (line.startsWith(MARKER)) {
                current?.let { onBlock(it, buffer.toString().split('\n')) }
                buffer.clear()
                current = line.removePrefix(MARKER).trim()
            } else if (current != null) {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        current?.let { onBlock(it, buffer.toString().split('\n')) }
    }

    /** Per-node exit status in a batched [states] call. */
    private const val STATE = "@@@status "

    /**
     * Direct read from the app process. Returns null when unreadable.
     *
     * Suspends onto IO because these are real kernel file reads: on the main
     * thread a handful of them per frame is enough to drop frames.
     */
    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            File(path).takeIf { it.canRead() }?.readText()?.trim()
        }.getOrNull()
    }

    /**
     * Read many nodes in one go.
     *
     * Direct reads first; whatever failed is fetched in a **single** elevated
     * call. The obvious loop — read, fall back to the shell, read the next —
     * spawns a process per unreadable node, and on a device that lacks most of
     * the nodes an exploratory screen probes, that is dozens of process spawns
     * per visit. That is expensive enough to be felt as stutter everywhere, not
     * just on that screen.
     *
     * Returns null for a path that could not be read by either route.
     */
    suspend fun readMany(paths: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        val result = paths.distinct().associateWith { path ->
            runCatching { File(path).takeIf { it.canRead() }?.readText()?.trim() }.getOrNull()
        }.toMutableMap()

        val missing = result.filterValues { it == null }.keys.toList()
        if (missing.isEmpty()) return@withContext result

        // One script, one process. Each block is announced by a marker line so
        // the values can be told apart without relying on their contents.
        val script = missing.joinToString("; ") { path ->
            "echo '${escape(MARKER + path)}'; cat '${escape(path)}' 2>/dev/null"
        }
        val out = PrivilegeManager.exec(script) ?: return@withContext result
        val payload = body(out) ?: return@withContext result

        markerBlocks(payload) { path, lines ->
            result[path] = lines.joinToString("\n").trim().ifBlank { null }
        }
        result
    }

    /** Read through the elevated shell (for nodes our own uid cannot open). */
    suspend fun readElevated(path: String): String? {
        val out = PrivilegeManager.exec("cat '${escape(path)}' 2>/dev/null") ?: return null
        val payload = body(out) ?: return null
        return payload.trim().ifBlank { null }
    }

    suspend fun exists(path: String): Boolean {
        val out = PrivilegeManager.exec("test -e '${escape(path)}' && echo yes || echo no")
        return out?.let { body(it) }?.trim() == "yes"
    }

    /** Write a value through the elevated shell. Returns true on success. */
    suspend fun write(path: String, value: String): Boolean = writeOrNull(path, value) == null

    /**
     * 同 [write]，但**写失败时返回服务端的原话**，成功返回 null。
     *
     * 为什么值得多一个函数：写入失败的原始错误（Permission denied、Read-only
     * file system、No such file）是用户判断"该去哪儿修"的唯一依据。只回一个布尔，
     * 界面就只剩"写入失败"四个字，点"显示更多"也没有东西可看。
     */
    suspend fun writeOrNull(path: String, value: String): String? {
        val cmd = "echo '${escape(value)}' > '${escape(path)}'"
        val out = PrivilegeManager.exec("$cmd 2>&1") ?: return NO_SERVICE
        val payload = body(out) ?: return out.trim()
        return payload.trim().ifBlank { null }
    }

    /**
     * 列目录。**返回 null = 没列成**（提权不可用或命令失败），不是"目录为空"。
     *
     * 这两件事必须分开：温控档位列表就靠它，把"没列成"报成"没有档位"会让
     * 界面上一片空白，而用户完全看不出是权限问题还是这台机器真的没有。
     * 与 [NodeState] 是同一条规矩 —— 不知道不等于确定没有。
     */
    suspend fun listOrNull(path: String): List<String>? {
        val out = PrivilegeManager.exec("ls -1 '${escape(path)}' 2>/dev/null") ?: return null
        val payload = body(out) ?: return null
        return payload.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 列目录；列不成时按空列表处理。只在"确认过提权可用"之后用。 */
    suspend fun list(path: String): List<String> = listOrNull(path) ?: emptyList()

    /**
     * Decide whether we can really write [path] by writing its own current
     * value back to it.
     *
     * Reading the mode bits is not a reliable proxy: a node can be mode 664
     * root:system and still be unwritable for us once SELinux and the actual
     * group membership are accounted for. Only an attempted write tells the
     * truth. Writing the value the file already holds is a no-op for every
     * cpufreq node we probe — `store_scaling_governor` returns early when the
     * requested governor is the active one — so this is safe to call.
     *
     * Returns false when the node is unreadable or the write is rejected.
     */
    suspend fun probeWritable(path: String): Boolean {
        val current = readElevated(path)?.trim()?.lineSequence()?.firstOrNull()?.trim()
        if (current.isNullOrEmpty()) return false
        return write(path, current)
    }

    /**
     * Whether a node is present, absent, or merely unreadable.
     *
     * The third value is the point. [readMany] collapses every failure into
     * null, which is right for a value and wrong for a capability question:
     * a node behind a permission bit exists exactly as much as one we can
     * open. Reporting "cannot read" as "does not exist" hides controls that
     * work — the Lab greys out its thermal policy slider for exactly that
     * reason on a device where `/proc/perfmgr` is SELinux-denied while the
     * PowerHAL opcode behind it answers fine.
     */
    enum class NodeState { PRESENT, ABSENT, UNREADABLE }

    /**
     * Classify [paths] against the kernel, in a single elevated call.
     *
     * The shell's own error text is the discriminator: `No such file or
     * directory` means the node really is gone, `Permission denied` means it
     * is there and we are not allowed to touch it. Only the first is
     * reported as ABSENT.
     *
     * When privilege is unavailable the answer is UNREADABLE, never ABSENT —
     * not knowing is not the same as being sure.
     */
    suspend fun states(paths: List<String>): Map<String, NodeState> = withContext(Dispatchers.IO) {
        val wanted = paths.distinct()

        val result = mutableMapOf<String, NodeState>()
        val missing = mutableListOf<String>()
        for (path in wanted) {
            // 只看能不能打开它自己：目录的 isDirectory / canRead 只反映权限位，
            // 而 SELinux 可以在权限位全开的情况下拒掉搜索。
            // /proc/perfmgr 就是 dr-xr-xr-x 却 ls 不了 —— 能不能列出内容，
            // 只能下面那一次 cat 说了算。
            //
            // App 自己能打开就够判"存在"了 —— 打开过即是证据。但它**不能**用来
            // 佐证别人的"不存在"，见下面 shellStates 处。
            val readable = runCatching { File(path).canRead() }.getOrDefault(false)
            if (readable) result[path] = NodeState.PRESENT else missing += path
        }
        // 全都是 PRESENT：一条"不存在"的判词都没有，父目录佐证也就无从谈起。
        // 这条路一次 shell 都不起。
        if (missing.isEmpty()) return@withContext resolve(wanted, result)

        // 父目录必须和子文件走**同一条路**（提权 shell）。判"文件不存在"的是 shell，
        // 拿 App 进程自己的 canRead() 去佐证它，是两个身份在对话：SELinux 按域授权，
        // 同一个目录完全可能 App 读得动而 shell 搜不动。那时候 shell 对子文件说的
        // ENOENT 是"不许你知道"，却被父目录的 PRESENT 佐证成"真的没有"——
        // 于是能用的功能被置灰，而这正是 absenceIsProven 想防的那件事。
        val parents = missing.map { File(it).parent }.filterNotNull().distinct() - missing.toSet()
        val batch = missing + parents

        // 只有提权 shell 的判词才进这里。parentStates 只查这张表。
        val shellStates = mutableMapOf<String, NodeState>()
        val script = batch.joinToString("; ") { path ->
            "echo '${escape(MARKER + path)}'; o=\$(cat '${escape(path)}' 2>&1); echo \"$STATE\$?\"; echo \"\$o\""
        }
        val raw = PrivilegeManager.exec(script)
        val payload = raw?.let { body(it) }
        if (payload == null) {
            // 服务端整段失败：一律报"不可读"，绝不报"不存在"。
            batch.forEach {
                result[it] = NodeState.UNREADABLE
                shellStates[it] = NodeState.UNREADABLE
            }
        } else {
            // 同一套切块骨架，和 [readMany] 共用 [markerBlocks]。
            // 区别只在这一块多一个退出码行，且翻译成 NodeState 而不是原文。
            markerBlocks(payload) { path, lines ->
                val state = if (lines.firstOrNull()?.removePrefix(STATE)?.trim()?.toIntOrNull() == 0) {
                    NodeState.PRESENT
                } else {
                    classifyCatFailure(lines.drop(1).joinToString("\n"))
                }
                result[path] = state
                shellStates[path] = state
            }
        }

        // 父目录顺带被探出来了：先用它判子文件的 ENOENT 作不作数，再丢掉这些额外条目。
        for (path in wanted) {
            if (result[path] != NodeState.ABSENT) continue
            // 没有父目录（路径就在根下）时无从佐证，一律按"不可读"收。
            val parent = File(path).parent
            if (parent == null || !absenceIsProven(shellStates[parent] ?: NodeState.UNREADABLE)) {
                result[path] = NodeState.UNREADABLE
            }
        }
        resolve(wanted, result)
    }

    /** 只返回调用方要的路径，探针自己加的父目录条目不外泄。 */
    private fun resolve(
        wanted: List<String>,
        probed: Map<String, NodeState>,
    ): Map<String, NodeState> = wanted.associateWith { probed[it] ?: NodeState.UNREADABLE }

    /**
     * `No such file or directory` 到底能不能证明节点不存在。
     *
     * **父目录读不到时不能。** SELinux 拒掉目录搜索时，里层文件返回的是 ENOENT
     * 而不是 EACCES —— 内核故意如此，以免泄露路径是否存在。真机实测：
     * `/proc/perfmgr` 是 Permission denied，里面的 `thermal_policy`
     * 却是 No such file or directory，`test -e` 也说 absent。
     * 曾据此把实验室的温控策略滑杆误判成"本机没有这个节点"并置灰。
     */
    internal fun absenceIsProven(parent: NodeState): Boolean = parent == NodeState.PRESENT

    /**
     * Shell's complaint about a node we could not read, and what it means.
     *
     * Anything that is not a plain "it isn't there" is UNREADABLE, so an
     * unfamiliar error can never disable a control.
     */    internal fun classifyCatFailure(message: String): NodeState = when {
        // 打开得开、只是里面不是文件 —— 这个目录确实存在而且搜得动。
        "Is a directory" in message -> NodeState.PRESENT
        "No such file or directory" in message -> NodeState.ABSENT
        "Not a directory" in message -> NodeState.ABSENT
        else -> NodeState.UNREADABLE
    }

    private fun escape(raw: String) = raw.replace("'", "'\\''")
}
