package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfHandleStorage
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CpuControlTest {
    /**
     * 真 Context。`CpuControl` 拿它解析资源，而 Context 有 103 个抽象方法，
     * 纯 JUnit 伪造不出来 —— 这是加 Robolectric 的原因。
     */
    private val context: android.content.Context =
        RuntimeEnvironment.getApplication()

    private val cluster = CpuCluster("policy4", 1, availableFreqs = listOf(1100000, 1400000, 2400000, 2700000, 2850000))
    private val low = ClusterSetting("policy4", 1100000, 1400000)
    private val high = ClusterSetting("policy4", 2700000, 2850000)

    @Before fun quietLogcat() { AppLog.setMirrorToLogcat(false) }


    private class Memory : CpuSettingsStore, PerfHandleStorage {
        var saved = emptyList<ClusterSetting>()
        var requested = emptyList<ClusterSetting>()
        var enabled = false
        var handle = 0
        override suspend fun baseline(): String? = null
        override suspend fun setBaseline(value: String?) = Unit
        override suspend fun lastApplied() = saved
        override suspend fun setLastApplied(settings: List<ClusterSetting>) { saved = settings }
        override suspend fun lastRequested() = requested.ifEmpty { saved }
        override suspend fun setLastRequested(settings: List<ClusterSetting>) { requested = settings }
        override suspend fun controlEnabled() = enabled || saved.isNotEmpty()
        override suspend fun setControlEnabled(enabled: Boolean) { this.enabled = enabled }
        override suspend fun get(name: String) = handle
        override suspend fun set(name: String, value: Int) { handle = value }
    }

    private fun nodes(lo: Long?, hi: Long?, cur: Long? = lo) = mapOf(
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq" to lo?.toString(),
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq" to hi?.toString(),
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq" to cur?.toString(),
    )





    @Test fun `collapsed high range is not reported as verified`() = runBlocking {
        val memory = Memory()
        val lock = PerfLockController(
            memory, "cpu",
            { _, _ -> PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )
        val cpu = CpuControl(context, memory, lock, { nodes(2400000, 2400000) }, {})
        val result = cpu.apply(listOf(cluster), listOf(high))
        assertTrue(result.success) // The request is held and must remain releasable.
        assertEquals(CpuVerification.CONFLICT, cpu.status.value.verification)
        // 冲突原因属于**证据**，走 detail（界面里默认收起、点"显示更多"才看）。
        // 结论那一句只说"冲突"，不把逐簇读数摊在首页。
        assertTrue("冲突原因必须在 detail 里", result.detail.orEmpty().contains(context.getString(R.string.cpu_conflict_max_below_min)))
        assertTrue(
            "结论第一行仍是冲突摘要",
            result.message.resolve(context).startsWith(
                context.getString(R.string.cpu_verify_summary_conflict),
            ),
        )
    }

    @Test fun `missing final sample cannot reuse earlier success`() = runBlocking {
        val memory = Memory()
        var reads = 0
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ -> PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) }),
            { if (++reads < 3) nodes(1100000, 1400000) else emptyMap() }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(CpuVerification.UNREADABLE, cpu.status.value.verification)
    }

    @Test fun `range normalization keeps only the soft pair by default`() = runBlocking {
        // 2026-10-14 真机实测（policy7 逐个单独发）：软 MAX 与硬 MAX_HL 给同一个值，
        // scaling_max_freq 都被压到那个值；软 MIN 与硬 MIN_HL 同样都能抬下限。
        // 四个单独发都生效、释放都干净。**软硬等效，所以默认只发软的那一对。**
        // 旧的"必须带硬下限"结论来自 2026-09-24 那次：那一例真正的原因是请求值
        // 超出系统包络（约 2000-2300），换硬的下限同样过不去，不是软硬之分。
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {})
        // 反向输入（min>max）要被归一化成 floor/ceiling
        cpu.apply(listOf(cluster), listOf(low.copy(minFreqKhz = 1400000, maxFreqKhz = 1100000)))
        assertEquals(listOf("0x00400100" to "1100000", "0x00404100" to "1400000"), sent)
        assertEquals(listOf(low), memory.saved)
        // 定点请求同样是两项相等，且不含硬的那一对
        cpu.apply(listOf(cluster), listOf(low.copy(maxFreqKhz = 1100000)))
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.second == "1100000" })
        assertTrue("默认不发硬限", sent.none { it.first == "0x00408100" || it.first == "0x0040C100" })
    }

    @Test fun `hard limits are added only when the user turns them on`() = runBlocking {
        // 换机型时若固件只认硬的那一对，用户打开这个开关就有四项。
        // 顺序必须是软的在前、硬的在后 —— 读日志的人按这个顺序对账。
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {}, hardLimits = { true })
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(listOf("0x00400100" to "1100000", "0x00404100" to "1400000",
            "0x00408100" to "1100000", "0x0040C100" to "1400000"), sent)
    }

    @Test fun `sched companions are appended verbatim without policy offset`() = runBlocking {
        // 配合项不是按簇资源，绝不能被加上 policyIndex * 0x100。
        // 加错了 opcode 会跳到别的资源上，而且不报错，只在日志里留一条没人理的 hint。
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })
        val companion = listOf(PowerHal.SCHED_CLOSE_PELT_ENABLE to "0", PowerHal.SCHED_UCLAMP_MIN_TA to "70")
        val cpu = CpuControl(
            context, memory, lock, { nodes(1100000, 1400000) },
            schedCompanions = { companion },
        )
        cpu.apply(listOf(cluster), listOf(low))
        // 频率两项（默认不发硬限）+ 配合两项，且配合项 opcode 原样
        assertEquals(4, sent.size)
        assertTrue(companion.all { (opcode, value) ->
            sent.contains(PowerHal.fixedCommandId(opcode) to value)
        })
        assertTrue("频率项仍在最前", sent.first().first == "0x00400100")
    }

    @Test fun `no companion leaves request untouched`() = runBlocking {
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })
        // 不传 companion = 默认关，且硬限默认也不发（2026-10-14 实测软硬等效）
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(2, sent.size)
        assertTrue(sent.none { it.first.startsWith("0x1000c") || it.first.startsWith("0x0140") })
    }

    @Test fun `release waits for verification and queued polling cannot resurrect settings`() = runBlocking {
        val memory = Memory()
        val sampling = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var acquisitions = 0
        val lock = PerfLockController(memory, "cpu", { _, _ -> acquisitions++; PowerHal.Result.Success(acquisitions, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) })
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {
            if (!sampling.isCompleted) { sampling.complete(Unit); resume.await() }
        })
        val applying = async { cpu.apply(listOf(cluster), listOf(low)) }
        sampling.await()
        val releasing = async { cpu.release(listOf(cluster)) }
        yield()
        val polling = async { cpu.reapply(listOf(cluster)) }
        yield()
        assertFalse(releasing.isCompleted)
        resume.complete(Unit)
        applying.await(); releasing.await(); polling.await()
        assertEquals(1, acquisitions)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
        assertTrue(cpu.status.value.applied.isEmpty())
    }

    @Test fun `cancel after acquire still saves releasable handle and settings`() = runBlocking {
        val memory = Memory()
        val acquired = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val lock = PerfLockController(memory, "cpu", { _, _ -> acquired.complete(Unit); resume.await(); PowerHal.Result.Success(42, Message(R.string.powerhal_applied)) })
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {})
        val applying = launch { cpu.apply(listOf(cluster), listOf(low)) }
        acquired.await()
        applying.cancel()
        resume.complete(Unit)
        applying.join()
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
        assertEquals(42, cpu.status.value.handler)
    }

    @Test fun `ceiling override is a conflict even when the floor matches`() = runBlocking {
        val memory = Memory()
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ -> PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) }),
            { nodes(1100000, 2400000, 2000000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(CpuVerification.CONFLICT, cpu.status.value.verification)
        assertTrue(cpu.status.value.verificationDetail!!.contains(context.getString(R.string.cpu_conflict_out_of_range)))
    }

    @Test fun `satisfied bounds are observed without reacquiring`() = runBlocking {
        val memory = Memory()
        var acquisitions = 0
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(acquisitions, Message(R.string.powerhal_applied))
        }), { nodes(1100000, 1400000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        repeat(3) { cpu.reapply(listOf(cluster)) }
        assertEquals(1, acquisitions)
        assertEquals(CpuVerification.VERIFIED, cpu.status.value.verification)
    }

    @Test fun `range pinned by lower system ceiling retries without floor`() = runBlocking {
        val memory = Memory()
        val sent = mutableListOf<List<Pair<String, String>>>()
        val lock = PerfLockController(
            memory, "cpu",
            { pairs, _ -> sent += pairs; PowerHal.Result.Success(sent.size, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )
        // 第一次读回被系统上限压成 2700-2700；重试（只压上限）后能读到 1200-2700。
        val cpu = CpuControl(context, memory, lock, {
            if (sent.size <= 1) nodes(2700000, 2700000) else nodes(1200000, 2700000)
        }, {})
        val result = cpu.apply(listOf(cluster), listOf(high))

        assertTrue(result.success)
        assertEquals("应当自动重试一次", 2, sent.size)
        assertTrue(
            "第一次带下限: ${sent[0]}",
            sent[0].any { it.first == "0x00400100" },
        )
        assertFalse(
            "重试不能继续锁下限，否则仍会钉在系统上限",
            sent[1].any { it.first == "0x00400100" || it.first == "0x00408100" },
        )
        assertTrue("重试仍要压上限", sent[1].any { it.first == "0x00404100" })
        assertTrue(result.message.resolve(context).contains("系统当前上限低于请求下限"))
        // 重放状态跟着实际下发走（不再锁下限），但“上次要求”保留用户原始下限，
        // 供快捷开关/亮屏恢复重新尝试。
        assertEquals(1_100_000, memory.saved.single().minFreqKhz)
        assertEquals(2_700_000, memory.requested.single().minFreqKhz)
    }

    @Test fun `quick toggle remembers last request after release`() = runBlocking {
        val memory = Memory()
        var acquisitions = 0
        val lock = PerfLockController(
            memory, "cpu",
            { _, _ -> acquisitions++; PowerHal.Result.Success(acquisitions, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {})
        assertTrue(cpu.apply(listOf(cluster), listOf(low)).success)
        assertTrue(cpu.release(listOf(cluster)).success)

        assertTrue("释放后没有活动请求", cpu.lastApplied().isEmpty())
        assertEquals("快捷开关要能拿回上一次范围", listOf(low), cpu.lastRequested())
        assertTrue("锁屏自动释放不应清掉用户意图", cpu.controlEnabled())

        assertTrue(cpu.applyLastRequested(listOf(cluster)).success)
        assertEquals(listOf(low), cpu.lastApplied())
    }

    @Test fun `disable clears intent but keeps last requested`() = runBlocking {
        val memory = Memory()
        val lock = PerfLockController(
            memory, "cpu",
            { _, _ -> PowerHal.Result.Success(1, Message(R.string.powerhal_applied)) },
            { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) },
        )
        val cpu = CpuControl(context, memory, lock, { nodes(1100000, 1400000) }, {})
        assertTrue(cpu.apply(listOf(cluster), listOf(low)).success)
        assertTrue(cpu.disable(listOf(cluster)).success)

        assertFalse("用户主动释放后不应再自动恢复", cpu.controlEnabled())
        assertEquals("范围仍留给下一次手动开启", listOf(low), cpu.lastRequested())
    }

    @Test fun `failed release prevents acquire and retains settings and handle`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        var acquisitions = 0
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(99, Message(R.string.powerhal_applied))
        }, { PowerHal.Result.Failure(Message(R.string.powerhal_call_failed)) }), { nodes(1100000, 1400000) }, {})
        assertFalse(cpu.apply(listOf(cluster), listOf(high)).success)
        assertFalse(cpu.release(listOf(cluster)).success)
        assertEquals(0, acquisitions)
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
    }

    @Test fun `failed replacement after release stops old replay`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        var acquisitions = 0
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Failure(Message(R.string.powerhal_call_failed))
        }, { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) }), { nodes(1100000, 1400000) }, {})
        assertFalse(cpu.apply(listOf(cluster), listOf(high)).success)
        cpu.reapply(listOf(cluster))
        assertEquals(1, acquisitions)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
        assertTrue(cpu.release(listOf(cluster)).success)
    }

    @Test fun `missing limits cannot hide observed frequency outside request`() {
        val reading = CpuLimitReading("policy4", null, null, 2400000)
        assertEquals(CpuVerification.CONFLICT, reading.verify(high))
        assertTrue(reading.conflictReason(high).resolve(context).contains(context.getString(R.string.cpu_conflict_partial)))
    }

    @Test fun `crossing a lower effective ceiling is constrained rather than verified`() {
        val request = low.copy(maxFreqKhz = 2850000)
        val reading = CpuLimitReading("policy4", 1100000, 2400000, 1500000)
        assertEquals(CpuVerification.CONSTRAINED, reading.verify(request))
    }

    @Test fun `probe expires and restores original point settings`() = runBlocking {
        val original = low.copy(maxFreqKhz = low.minFreqKhz)
        val memory = Memory().apply { handle = 42; saved = listOf(original) }
        val durations = mutableListOf<Int>()
        val requests = mutableListOf<List<Pair<String, String>>>()
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { pairs, duration ->
            requests += pairs; durations += duration; PowerHal.Result.Success(100 + durations.size, Message(R.string.powerhal_applied))
        }, { PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) }), { nodes(1100000, 1100000) }, {})
        assertTrue(cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.HARD_RANGE) {}.success)
        assertEquals(listOf(8000, 0), durations)
        assertEquals("1100000", requests.first().first { it.first == "0x00408100" }.second)
        assertEquals("1400000", requests.first().first { it.first == "0x0040C100" }.second)
        assertEquals(listOf(original), memory.saved)
    }

    @Test fun `load failure during sampling stops workers before releasing request`() = runBlocking {
        val memory = Memory()
        var running = false
        var releases = 0
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu",
            { _, _ -> PowerHal.Result.Success(42, Message(R.string.powerhal_applied)) }, {
                assertFalse(running); releases++; PowerHal.Result.Success(0, Message(R.string.powerhal_applied))
            }), { nodes(1100000, 1400000) }, {}, {
                running = true
                AutoCloseable { running = false }
            })
        try {
            cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.RANGE_LOAD) {
                if (it.startsWith("测试 1/8")) error("thermal stop")
            }
            org.junit.Assert.fail("should propagate sampling failure")
        } catch (e: IllegalStateException) { assertEquals("thermal stop", e.message) }
        assertFalse(running)
        assertEquals(1, releases)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
    }

    @Test fun `loaded modes preserve corresponding frequency requests`() {
        assertEquals(CpuProbeMode.RANGE.pairs(2, 2500000, 3100000), CpuProbeMode.RANGE_LOAD.pairs(2, 2500000, 3100000))
        assertEquals(CpuProbeMode.HARD_RANGE.pairs(2, 2500000, 3100000), CpuProbeMode.HARD_RANGE_LOAD.pairs(2, 2500000, 3100000))
        assertEquals(CpuProbeMode.POINT.pairs(2, 2500000, 3100000), CpuProbeMode.POINT_LOAD.pairs(2, 2500000, 3100000))
    }

    @Test fun `busy sampler handles guest idle resets and missing access`() {
        val sampler = CpuBusySampler()
        assertEquals("cpu0=?", sampler.sample("cpu0 10 0 0 10 0 0 0 0 8 0"))
        assertEquals("cpu0=50%", sampler.sample("cpu0 20 0 0 20 0 0 0 0 18 0"))
        assertEquals("cpu0=?", sampler.sample("cpu0 1 0 0 1 0 0 0 0"))
        assertTrue(sampler.sample(null).contains("不可读"))
        assertEquals("cpu0=?", sampler.sample("cpu0 30 0 0 30 0 0 0 0"))
    }

    @Test fun `read only probe does not release or acquire`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu",
            { _, _ -> error("must not acquire") }, { error("must not release") }),
            { nodes(1100000, 1400000) }, {})
        cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.OBSERVE) {}
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
    }

    @Test fun `cancelling probe releases experiment and restores previous request`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        val active = CompletableDeferred<Unit>()
        var acquisitions = 0
        var running = false
        val cpu = CpuControl(context, memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(100 + acquisitions, Message(R.string.powerhal_applied))
        }, { assertFalse(running); PowerHal.Result.Success(0, Message(R.string.powerhal_applied)) }), { nodes(1100000, 1400000) }, {
            if (acquisitions == 1 && !active.isCompleted) { active.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }, { running = true; AutoCloseable { running = false } })
        val task = launch { cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.RANGE_LOAD) {} }
        active.await(); task.cancel(); task.join()
        assertEquals(2, acquisitions)
        assertEquals(listOf(low), memory.saved)
        assertFalse(running)
    }
}
