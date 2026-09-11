package magicau.mtkcontroller.data.tweaks

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.settings.SettingChannel
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.exec.DaemonExec
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 重度游戏 boost：对用户自选的单个应用，把相关的"系统会不会限制它"开关一起放开。
 *
 * 用的都是 **Android 原生机制**，不是厂商私有接口：
 *  - `appops RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND`：允许后台运行
 *  - `cmd deviceidle whitelist +pkg`：深度睡眠（Doze）时不冻结它
 *  - `cmd activity set-standby-bucket pkg active`：待机分桶进 active，网络/Job/闹钟配额最高
 *  - `settings global game_driver_opt_in_apps`：让它用更新的 GPU 驱动。
 *    **注意：天玑用的是 Mali/Immortalis，没有可更新的 GPU 驱动机制**（那是高通 Adreno 的），
 *    所以这一项在目标机型上预计不产生任何效果。仍然写它，但状态与结果里都会说出来。
 *
 * **不做** `cmd game mode performance` 与 `game_overlay` 帧率覆写 —— 那两者让平台重推
 * 自己的一套性能策略，会和本软件的 CPU 频率句柄打架（PowerHAL 合并规则只能收紧）。
 * 这里只做"别限制它"，不动频率。
 *
 * 包名必须来自用户从已安装应用里自选并过 `pm path` 校验；不内置任何硬编码名单自动动作。
 * appops 授予和 deviceidle 白名单是粘性且不可见的权限变更，所以逐项读原值、逐项恢复。
 *
 * **关键安全性质：任何一项读不到原值，就整项不写。** 宁可少放开一个开关，也不要留下
 * 一个没有回头路的授予。
 */
class GameBoost(
    private val context: Context,
    /** 写系统键之前记原值；由 TweakRepository 注入，恢复默认靠它。 */
    private val rememberOwned: suspend (String, String, String) -> Unit = { _, _, _ -> },
) {

    companion object {
        private const val PREFS = "game_boost_baselines"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** 用户选了哪个应用。 */
    var selected: String
        get() = prefs.getString("selected", "").orEmpty()
        set(value) { prefs.edit().putString("selected", value).commit() }

    suspend fun hasSnapshot(): Boolean = mutex.withLock { prefs.contains("active") }

    suspend fun status(): String {
        val pkg = selected
        if (pkg.isBlank()) return context.getString(R.string.tweaks_game_boost_status_no_app)
        val bucket = run(listOf("/system/bin/cmd", "activity", "get-standby-bucket", pkg))
        val whitelisted = run(listOf("/system/bin/cmd", "deviceidle", "whitelist")).output.contains(pkg)
        val drivers = run(listOf("/system/bin/settings", "get", "global", "game_driver_opt_in_apps"))
        val driverListed = drivers.output.trim().let { it.isNotBlank() && it != "null" }
        return buildString {
            append(context.getString(R.string.tweaks_game_boost_standby_bucket)).append(bucket.output.trim().ifBlank { context.getString(R.string.tweaks_game_boost_unreadable) })
            append(context.getString(R.string.tweaks_game_boost_doze_prefix)).append(if (whitelisted) context.getString(R.string.tweaks_game_boost_doze_in) else context.getString(R.string.tweaks_game_boost_doze_out))
            append(context.getString(R.string.tweaks_game_boost_driver_prefix)).append(if (driverListed) context.getString(R.string.tweaks_game_boost_driver_in) else context.getString(R.string.tweaks_game_boost_driver_out))
            // 这一项在天玑上预计不产生任何效果，别让用户以为叠了一层 buff。
            append("\n").append(context.getString(R.string.game_boost_driver_caveat))
        }
    }

    suspend fun apply(): TweakRepository.Outcome = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val pkg = selected
            if (pkg.isBlank()) return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_pick_app))
            if (!PrivilegeManager.state.value.mode.canElevate) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.tweak_need_shizuku))
            }
            if (prefs.contains("active")) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_pending))
            }
            if (run(listOf("/system/bin/pm", "path", pkg)).output.contains("package:").not()) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_no_path))
            }

            // 逐项读原值；任何一项读不到就整项不写。
            val appopsBg = appopsMode(pkg, "RUN_IN_BACKGROUND")
            val appopsAny = appopsMode(pkg, "RUN_ANY_IN_BACKGROUND")
            val bucketResult = run(listOf("/system/bin/cmd", "activity", "get-standby-bucket", pkg))
            val bucket = bucketResult.output.trim().takeIf { bucketResult.ok && it.isNotBlank() && it != "null" }
            val whitelisted = run(listOf("/system/bin/cmd", "deviceidle", "whitelist")).output.contains(pkg)
            val drivers = run(listOf("/system/bin/settings", "get", "global", "game_driver_opt_in_apps"))
            val driversValue = drivers.output.trim().takeIf { drivers.ok && it.isNotBlank() && it != "null" }

            val unreadable = buildList {
                if (appopsBg == null) add("appops RUN_IN_BACKGROUND")
                if (appopsAny == null) add("appops RUN_ANY_IN_BACKGROUND")
                if (bucket == null) add(context.getString(R.string.tweaks_game_boost_step_bucket))
            }
            if (unreadable.isNotEmpty()) {
                return@withContext TweakRepository.Outcome(
                    false,
                    Message(R.string.game_boost_unreadable, listOf(unreadable.joinToString("、"))),
                )
            }

            // **先落盘原值，再动系统。** 原来这些只写进未提交的 editor，
            // 失败分支里的 restoreLocked 从 prefs 读，什么也读不到 ——
            // 等于"回滚"是空转，然后把 active 清掉，快照彻底丢失。
            val saved = prefs.edit()
                .putString("appops_bg", appopsBg!!)
                .putString("appops_any", appopsAny!!)
                .putString("bucket", bucket!!)
                .putBoolean("whitelisted", whitelisted)
                .putString("game_drivers", driversValue ?: "")
                .putBoolean("active", true)
                .commit()
            if (!saved) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_save_failed))
            }

            val failures = mutableListOf<String>()
            val reasons = mutableListOf<String>()
            fun step(name: String, result: Run) {
                if (result.ok) return
                failures += name
                // 服务端原话是判断"该去哪儿修"的唯一依据，只给一个布尔等于把现场丢了。
                reasons += "$name → ${result.output.trim().ifBlank { context.getString(R.string.tweaks_game_boost_no_output) }}"
            }
            step("appops RUN_IN_BACKGROUND", run(listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")))
            step("appops RUN_ANY_IN_BACKGROUND", run(listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")))
            step(context.getString(R.string.tweaks_game_boost_step_doze), run(listOf("/system/bin/cmd", "deviceidle", "whitelist", "+$pkg")))
            step(context.getString(R.string.tweaks_game_boost_step_bucket), run(listOf("/system/bin/cmd", "activity", "set-standby-bucket", pkg, "active")))
            if (driversValue != null) step("Game Driver", setGameDrivers(driversValue, pkg, add = true))

            if (failures.isNotEmpty()) {
                // 回滚。**只有全部恢复成功才清快照**；否则保留原值，
                // 用户还能点一次"恢复"，而不是被卡在一个改过、又没有记录的状态里。
                val (restoreFailures, restoreReasons) = restoreLocked(pkg)
                if (restoreFailures.isEmpty()) {
                    prefs.edit().remove("active")
                        .remove("appops_bg").remove("appops_any").remove("bucket")
                        .remove("whitelisted").remove("game_drivers").commit()
                } else {
                    failures += context.getString(R.string.tweaks_game_boost_step_cleanup)
                    reasons += restoreReasons
                }
                return@withContext TweakRepository.Outcome(
                    false,
                    Message(R.string.game_boost_partial, listOf(failures.joinToString("、"))),
                    detail = reasons.joinToString("\n"),
                    failures = failures,
                )
            }


            TweakRepository.Outcome(
                true,
                Message(
                    R.string.game_boost_applied,
                    listOf(
                        pkg,
                        if (driversValue != null) Message(R.string.game_boost_driver_suffix) else "",
                        Message(R.string.game_boost_driver_caveat),
                    ),
                ),
            )
        }
    }

    suspend fun restore(): TweakRepository.Outcome = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val pkg = selected
            if (pkg.isBlank()) return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_no_app))
            if (!prefs.contains("active")) {
                return@withContext TweakRepository.Outcome(false, Message(R.string.game_boost_no_snapshot))
            }
            val (failures, reasons) = restoreLocked(pkg)
            prefs.edit().remove("active").apply {
                remove("appops_bg"); remove("appops_any"); remove("bucket")
                remove("whitelisted"); remove("game_drivers")
            }.commit()
            if (failures.isEmpty()) {
                TweakRepository.Outcome(true, Message(R.string.game_boost_restored))
            } else {
                TweakRepository.Outcome(
                    false,
                    Message(R.string.game_boost_restore_partial, listOf(failures.joinToString("、"))),
                    detail = reasons.joinToString("\n").ifBlank { null },
                    failures = failures,
                )
            }
        }
    }

    /** 调用方须已持锁。返回没能恢复的项名，以及每一项的原话。 */
    private suspend fun restoreLocked(pkg: String): Pair<List<String>, List<String>> {
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        fun step(name: String, result: Run) {
            if (result.ok) return
            failures += name
            reasons += "$name → ${result.output.trim().ifBlank { context.getString(R.string.tweaks_game_boost_no_output) }}"
        }

        val bg = prefs.getString("appops_bg", null)
        if (bg != null) step("appops RUN_IN_BACKGROUND", run(listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", bg)))
        val any = prefs.getString("appops_any", null)
        if (any != null) step("appops RUN_ANY_IN_BACKGROUND", run(listOf("/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", any)))

        val wasWhitelisted = prefs.getBoolean("whitelisted", false)
        step(context.getString(R.string.tweaks_game_boost_step_doze),
            run(listOf("/system/bin/cmd", "deviceidle", "whitelist", if (wasWhitelisted) "+$pkg" else "-$pkg")))

        val bucket = prefs.getString("bucket", null)
        if (bucket != null && bucket.isNotBlank())
            step(context.getString(R.string.tweaks_game_boost_step_bucket), run(listOf("/system/bin/cmd", "activity", "set-standby-bucket", pkg, bucket)))

        val drivers = prefs.getString("game_drivers", null)
        if (drivers != null && drivers.isNotBlank()) step("Game Driver", setGameDrivers(drivers, pkg, add = false))

        return failures to reasons
    }

    /**
     * `appops get` 输出形如 `RUN_IN_BACKGROUND: allow`，可能还带 `; time=...`。
     * 解析不出就返回 null —— 不猜。
     */
    private suspend fun appopsMode(pkg: String, op: String): String? {
        val result = run(listOf("/system/bin/cmd", "appops", "get", pkg, op))
        if (!result.ok) return null
        val line = result.output.lineSequence().firstOrNull { it.contains(op, ignoreCase = true) } ?: return null
        val mode = line.substringAfter(':', "").trim().substringBefore(';').trim()
        return mode.takeIf { it in setOf("allow", "ignore", "deny", "default") }
    }

    /**
     * `game_driver_opt_in_apps` 是逗号分隔列表。只增删这个包名，其余原样保留 ——
     * 盲目覆写会丢掉用户自己加过的其他包。
     */
    /**
     * `game_driver_opt_in_apps` 是逗号分隔列表。只增删这个包名，其余原样保留 ——
     * 盲目覆写会丢掉用户自己加过的其他包。
     *
     * 返回最后一次执行的 [Run]：写失败时它是服务端的原话，读回不符时它就是读回值。
     * 两者都是设备自己说的话，正是"显示更多"里该出现的东西。
     */
    private suspend fun setGameDrivers(original: String, pkg: String, add: Boolean): Run {
        // 写之前记原值：恢复默认只删有记录的键。这一条和触控/厂商功能同一条规矩。
        rememberOwned("game_driver_opt_in_apps", "global", "game_driver_opt_in_apps")
        val list = original.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val updated = if (add) (list + pkg).distinct() else list.filter { it != pkg }
        val value = updated.joinToString(",")
        // 走 SettingChannel：这台 ROM 上 `settings put` 退出 0 但不落库，
        // 只有 `content insert` 真的写进去。原来这里直接用 settings put，
        // 靠后面那次读回兜住 —— 能发现失败，但那等于明知有失效路径还先用它。
        val wrote = if (value.isEmpty()) {
            run(listOf("/system/bin/sh", "-c", SettingChannel.deleteCommand("global", "game_driver_opt_in_apps")))
        } else {
            run(listOf("/system/bin/sh", "-c", SettingChannel.putCommand("global", "game_driver_opt_in_apps", value)))
        }
        if (!wrote.ok) return wrote
        val readback = run(listOf("/system/bin/settings", "get", "global", "game_driver_opt_in_apps"))
        val read = readback.output.trim()
        return if (if (value.isEmpty()) read == "null" || read.isBlank()
            else read.split(',').map { it.trim() }.contains(pkg)
        ) readback else Run(read, ok = false)
    }

    private class Run(val output: String, val ok: Boolean)

    /**
     * 必须经 [DaemonExec] 进 Shizuku 用户服务（uid 2000 / shell）执行。
     *
     * `appops set` / `deviceidle whitelist` / `set-standby-bucket` 都要 shell 权限：
     * 在 App 进程里跑只会每个都失败，界面却显示"已放开"。
     */
    private suspend fun run(argv: List<String>): Run {
        val out = DaemonExec.runArgv(argv)
            ?: return Run(context.getString(R.string.tweaks_game_boost_no_service), false)
        val ok = out.exit == 0 && out.stderr.isBlank()
        return Run(if (out.stdout.isNotBlank()) out.stdout else out.stderr, ok)
    }
}
