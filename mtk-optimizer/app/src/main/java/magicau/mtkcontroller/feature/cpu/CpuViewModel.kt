package magicau.mtkcontroller.feature.cpu

import magicau.mtkcontroller.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.data.cpu.CpuProbeMode
import magicau.mtkcontroller.data.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.cpu.CpuVerification
import magicau.mtkcontroller.data.cpu.CpuFreqNodes
import magicau.mtkcontroller.data.cpu.CpuLoadRunner
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeMode
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.domain.model.OutcomeKind
import magicau.mtkcontroller.domain.model.Profile
import java.util.UUID
import magicau.mtkcontroller.data.thermal.ThermalPolicyReader
import magicau.mtkcontroller.data.lab.ThermalPolicyCatalog
import magicau.mtkcontroller.data.thermal.ThermalPolicySummary
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import magicau.mtkcontroller.domain.model.CpuLayoutMode
import magicau.mtkcontroller.domain.model.RangeControlMode


/** One cluster's editable state. Indices point into [CpuCluster.availableFreqs]. */
data class ClusterEdit(
    val cluster: CpuCluster,
    val minIndex: Int,
    val maxIndex: Int,
    val governor: String?,
) {
    val minFreqKhz: Long get() = cluster.availableFreqs.getOrElse(minIndex) { cluster.minFreqKhz }
    val maxFreqKhz: Long get() = cluster.availableFreqs.getOrElse(maxIndex) { cluster.maxFreqKhz }
}

/**
 * Whether frequency control can be applied right now, and if not, why.
 *
 * This is recomputed whenever the privilege state changes instead of being
 * sampled once when the screen first opened — that snapshot was why the Apply
 * button stayed greyed out after Shizuku had in fact been authorised.
 */
data class PowerHalStatus(
    val checked: Boolean = false,
    val available: Boolean = false,
    val reason: String? = null,
)

/** 一簇的内核频率约束。读不到就是 null，界面显示"读不到"而不是猜一个值。 */
data class ClusterCaps(
    val policy: String,
    val minKhz: Int?,
    val maxKhz: Int?,
    val curKhz: Int?,
)

data class CpuUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val edits: List<ClusterEdit> = emptyList(),
    val powerHal: PowerHalStatus = PowerHalStatus(),
    val activeProfileName: String? = null,
    /** 全部方案，供 CPU 页顶部标签用。 */
    val profiles: List<Profile> = emptyList(),
    /** 已**套用**的方案。首页显示"当前方案"用它。 */
    val activeProfileId: String? = null,
    /**
     * 编辑区当前装着哪份方案。CPU 页标签的高亮用它，**不是** [activeProfileId]。
     *
     * 两个不是一回事：点标签只装载、不应用，应用要点下面的按钮。
     * 高亮必须跟着"我正在看/改哪一份"，跟着"已应用哪一份"的话，
     * 点任何标签都还是「默认」亮着 —— 用户看到的就是"点了没反应"。
     * null 表示编辑区就是默认状态。
     */
    val editorProfileId: String? = null,
    /** 本次操作的结果。摘要与原始错误分两层，界面用 `OutcomeNote` 展示。 */
    val message: Failure? = null,
    /** 结果的性质：决定图标和标题颜色。 */
    val messageKind: OutcomeKind = OutcomeKind.NONE,
    val probing: Boolean = false,
    val probeReport: String = "",
    val probeSaveNote: String = "",
    val probeStage: String = "",
    val configArchive: String = "",

    /** 手动负载是否在跑。 */
    val loadActive: Boolean = false,
    /** 负载已运行秒数。 */
    val loadSeconds: Int = 0,
    /** 这次负载占了哪些核。 */
    /**
     * 负载期间各簇的实时频率，`policy to MHz`。
     *
     * **必须显示**：只告诉用户"负载在跑"没用，他要看的是频率有没有被顶上去。
     */
    val loadFreqs: Map<String, Int> = emptyMap(),
    /** 每簇的内核上/下限与当前频率，只读展示。 */
    val caps: List<ClusterCaps> = emptyList(),
    /** 设备上有哪些温控档位可选。现读，不预写。 */
    val thermalIndices: List<Int> = emptyList(),
    /**
     * 档位读取失败。null = 读成功了（哪怕结果是空的）。
     *
     * 摘要和原始错误分开带：[Failure.summary] 是给人看的一句话，
     * [Failure.detail] 是异常类名，默认收起、可复制。界面用 `FailureNote` 展示，
     * 不要把原始错误拼进摘要里。
     */
    val thermalReadError: Failure? = null,
    /** 各档位的一句话摘要，跟着 thermalIndices 一起读出来。 */
    val thermalBriefs: Map<Int, String> = emptyMap(),
    /**
     * 当前**实际生效**的皮肤温控阈值，形如 "40/42/45/48/60/80 °C"。
     *
     * 它和 [thermalBriefs] 不是一回事：摘要说"这一档是什么阶梯"，
     * 这个说"现在加载的是哪一档"。每份 `thermal_policy_NN.conf` 的
     *    `[thermalhal-threshold]` 段各不相同，所以这组数字能反指当前策略。
     * 无 root 时这是唯一能做到这件事的通道（见 docs/thermal/facts.md 的 THERM-013）。
     *
     * 原来它只在实验室显示，而用户选档位的地方是 CPU 页 —— 那边看不到
     * "我选了 03，实际加载的是哪一份"。已移到这两处都显示。
     */
    val thermalSkinThreshold: String? = null,
    /** 参数原文。只在用户打开「显示详细参数」时读 —— 它比摘要长得多。 */
    val thermalFull: Map<Int, String> = emptyMap(),
    /**
     * 结构化解析结果，供说明页画表格用。
     *
     * 摘要 [thermalBriefs] 是翻好的句子，适合一行带过；表格要按列对齐，
     * 只能拿结构化的数据自己排版，所以两份都留着。
     */
    val thermalPolicies: Map<Int, ThermalPolicy> = emptyMap(),
    /** 是否显示参数原文。默认关：摘要够回答"这档限到多少"。 */
    val showThermalDetail: Boolean = false,

    /** 温控策略索引**是否已生效**。与频率范围各自独立。 */
    val thermalPolicyActive: Boolean = false,
    /** **已生效**的温控策略索引；null = 没开。 */
    val thermalPolicyIndex: Int? = null,
    /**
     * 编辑区里**选中**的档位。与 [thermalPolicyIndex] 不是一回事。
     *
     * 选档只记录，不立即下发 —— 与频率区间一样，等用户点「应用」。
     * 之前是选一下就下发，两个后果：没授权时点了没反应（看着像坏按钮），
     * 以及已经开着时换档被 `enable` 的 alreadyOn 挡掉（选了 02 还显示 01）。
     */
    val thermalIndexEdit: Int? = null,

    /** 控制区排布：分卡片 / 合并卡片。用户设置，不是设备事实。 */
    val layoutMode: CpuLayoutMode = CpuLayoutMode.PER_CLUSTER,

    /** 频率区间的调节方式：分段条 / 双向滑块。同样是用户设置。 */
    val rangeControlMode: RangeControlMode = RangeControlMode.SEGMENT,
)

class CpuViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CpuUiState())
    val state: StateFlow<CpuUiState> = _state.asStateFlow()

    /** 用户是否在这一页主动选过方案。见 [CpuUiState.editorProfileId]。 */
    private var editorTouched = false
    private var probeJob: Job? = null
    private val probeDirectory get() = java.io.File(container.context.filesDir, "cpu-probes")

    fun exportProbe(uri: android.net.Uri, report: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val output = container.context.contentResolver.openOutputStream(uri, "wt")
                        ?: error(container.context.getString(R.string.cpu_probe_open_target_failed))
                    if (report.startsWith("ROM_ZIP:")) {
                        val name = report.removePrefix("ROM_ZIP:")
                        require(name.matches(Regex("rom-configs-[0-9]+\\.zip")))
                        output.use { target -> java.io.File(probeDirectory, name).inputStream().use { it.copyTo(target) } }
                    } else output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
            }
            _state.value = _state.value.copy(probeSaveNote = if (result.isSuccess)
                container.context.getString(R.string.cpu_probe_export_done)
            else container.context.getString(R.string.cpu_probe_export_failed, result.exceptionOrNull()?.message))
        }
    }

    fun probeAll(index: Int) = runProbe(index, listOf(CpuProbeMode.OBSERVE,
        CpuProbeMode.RANGE, CpuProbeMode.RANGE_LOAD, CpuProbeMode.HARD_RANGE_LOAD,
        CpuProbeMode.POINT_LOAD, CpuProbeMode.RANGE))

    fun stopProbe() { probeJob?.cancel() }

    fun collectRomConfigs() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, probing = true, configArchive = "", probeReport = "", probeSaveNote = container.context.getString(R.string.cpu_probe_finding_files))
        probeJob = viewModelScope.launch {
            // ui-check:ignore 诊断报告的头部，本来就是给开发者看的数据行
            val manifest = StringBuilder("MTK God ${magicau.mtkcontroller.BuildConfig.VERSION_NAME}\nROM=${android.os.Build.DISPLAY}\n")
            val hashes = linkedMapOf<String, String>()
            var paths = emptyList<String>()
            val name = "rom-configs-${System.currentTimeMillis()}.zip"
            val temp = java.io.File(probeDirectory, "$name.tmp")
            try {
                paths = PrivilegeManager.discoverRomImplementation()
                withContext(Dispatchers.IO) {
                    check(probeDirectory.isDirectory || probeDirectory.mkdirs())
                    java.util.zip.ZipOutputStream(temp.outputStream()).use { zip ->
                        for ((index, path) in paths.withIndex()) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            _state.value = _state.value.copy(probeStage = container.context.getString(R.string.cpu_probe_stage_read_impl, index + 1, paths.size, path))
                            // 每次仅保留一个文件；同名库按序号区分，原路径写入清单。
                            val bytes = try { PrivilegeManager.readFullRomConfig(index) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                manifest.appendLine("FAILED $path ${e.javaClass.simpleName}: ${e.message}")
                                continue
                            }
                            val entry = "${index}-${path.substringAfterLast('/')}"
                            val hash = magicau.mtkcontroller.data.diag.RomConfigTransfer.sha(bytes)
                            zip.putNextEntry(java.util.zip.ZipEntry(entry))
                            zip.write(bytes); zip.closeEntry()
                            hashes[entry] = hash
                            manifest.appendLine("OK $path entry=$entry bytes=${bytes.size} sha256=$hash")
                        }
                        manifest.appendLine("完整文件 ${hashes.size}/${paths.size}；失败项未伪装为成功，未匹配文件不代表不存在。")
                        zip.putNextEntry(java.util.zip.ZipEntry("manifest.txt"))
                        zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                    java.util.zip.ZipFile(temp).use { zip ->
                        for ((entry, hash) in hashes) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            zip.getInputStream(zip.getEntry(entry)).use { input ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    digest.update(buffer, 0, n)
                                }
                            }
                            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { container.context.getString(R.string.cpu_probe_zip_verify_failed, entry) }
                        }
                        check(zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().use { it.readText() } == manifest.toString())
                    }
                    check(temp.renameTo(java.io.File(probeDirectory, name))) { container.context.getString(R.string.cpu_probe_zip_save_failed) }
                }
                _state.value = _state.value.copy(configArchive = name, probeSaveNote = container.context.getString(R.string.cpu_probe_impl_saved, hashes.size, paths.size))
            } catch (e: CancellationException) {
                manifest.appendLine("已取消，未生成完整包")
                _state.value = _state.value.copy(probeSaveNote = container.context.getString(R.string.cpu_probe_impl_cancelled))
                throw e
            }
            catch (e: Exception) {
                manifest.appendLine("导出失败：${e.message}")
                _state.value = _state.value.copy(probeSaveNote = container.context.getString(R.string.cpu_probe_save_failed, e.message))
            }
            finally {
                withContext(Dispatchers.IO + NonCancellable) { temp.delete() }
                _state.value = _state.value.copy(busy = false, probing = false, probeStage = "", probeReport = manifest.toString())
            }
        }
    }

    fun probe(index: Int, mode: CpuProbeMode) = runProbe(index, listOf(mode))

    private fun runProbe(index: Int, modes: List<CpuProbeMode>) {
        if (_state.value.busy) return
        val edit = _state.value.edits.getOrNull(index) ?: return
        val clusters = _state.value.edits.map { it.cluster }
        if (modes.size > 1 && edit.minFreqKhz == edit.maxFreqKhz) {
            _state.value = _state.value.copy(probeSaveNote = container.context.getString(R.string.cpu_probe_need_two_endpoints))
            return
        }
        val reportName = "cpu-probe-${System.currentTimeMillis()}.txt"
        _state.value = _state.value.copy(busy = true, probing = true, probeReport = "", probeSaveNote = container.context.getString(R.string.cpu_probe_recording_note))
        probeJob = viewModelScope.launch {
            fun record(line: String) {
                val text = "${magicau.mtkcontroller.data.diag.DiagReport.timestamp(System.currentTimeMillis())} $line"
                _state.value = _state.value.copy(probeReport = _state.value.probeReport + text + "\n")
                AppLog.i("CpuProbe", line)
            }
            try {
                record("MTK God ${magicau.mtkcontroller.BuildConfig.VERSION_NAME} ROM=${android.os.Build.DISPLAY}")
                record("权限=${PrivilegeManager.state.value}")
                if (modes == listOf(CpuProbeMode.ROM)) {
                    _state.value = _state.value.copy(probeStage = container.context.getString(R.string.cpu_probe_stage_rom))
                    val result = withContext(NonCancellable) { PrivilegeManager.romControlProbe() }
                    record(result)
                    record(if (result.contains("ROM_PROBE_COMPLETE")) "快照结束；请保留权限拒绝、空输出和截断记录。" else "快照失败，原始错误已保存。")
                    return@launch
                }
                if (modes.size > 1 &&
                    (container.cpuControl.lastApplied().isNotEmpty() || container.cpuControl.status.value.handler > 0)) {
                    record("请先点击释放，再开始对照；避免原请求污染静置基线。")
                    return@launch
                }
                CpuReapplyService.stop(container.context)
                val power = container.context.getSystemService(android.os.PowerManager::class.java)
                for ((step, mode) in modes.withIndex()) {
                    val stage = container.context.getString(R.string.cpu_probe_stage_phase, step + 1, modes.size, container.context.getString(mode.labelRes))
                    _state.value = _state.value.copy(probeStage = stage)
                    record(stage)
                    val outcome = container.cpuControl.probe(clusters, edit.cluster.policy,
                        edit.minFreqKhz, edit.maxFreqKhz, mode) { line ->
                        record("$line thermal=${power?.currentThermalStatus} powerSave=${power?.isPowerSaveMode}")
                        // 这两个前缀匹配的是 CpuControl 写进报告的原文行（"测试 …"/"普通负载预热后 …"），
                        // 是报告数据而非界面文案；不作资源，搬走会与产出方脱钩。
                        // ui-check:ignore 匹配产出方的报告原文，不是界面文案
                        if (mode.loaded && (line.startsWith("测试 ") || line.startsWith("普通负载预热后")) &&
                            (power?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_SEVERE)
                            error(container.context.getString(R.string.cpu_probe_thermal_severe))
                    }
                    record(outcome.message.resolve(container.context))
                    if (!outcome.success) { record("后续阶段中止"); break }
                    if (mode.loaded && step < modes.lastIndex) {
                        _state.value = _state.value.copy(probeStage = container.context.getString(R.string.cpu_probe_stage_cooldown))
                        record("间隔静置开始，15 秒后继续；此阶段没有计算负载")
                        kotlinx.coroutines.delay(15_000)
                    }
                }
                record("本次记录结束")
            } catch (e: CancellationException) {
                record("已停止（请核对上方恢复记录）")
                throw e
            } catch (e: Exception) {
                record("探测失败：${e.message}")
            } finally {
                withContext(NonCancellable) {
                    runCatching {
                        if (container.cpuControl.lastApplied().isNotEmpty()) container.syncCpuReapply()
                    }.onFailure { record("轮询恢复失败：${it.message}") }
                    val report = _state.value.probeReport
                    val saved = runCatching { withContext(Dispatchers.IO) {
                        check(probeDirectory.isDirectory || probeDirectory.mkdirs()) { container.context.getString(R.string.cpu_probe_mkdir_failed) }
                        java.io.File(probeDirectory, reportName).writeText(report)
                        java.io.File(probeDirectory, "latest.txt").writeText(report)
                    } }
                    _state.value = _state.value.copy(probeSaveNote = if (saved.isSuccess)
                        container.context.getString(R.string.cpu_probe_autosave_ok, reportName)
                    else container.context.getString(R.string.cpu_probe_autosave_failed, saved.exceptionOrNull()?.message))
                }
                _state.value = _state.value.copy(busy = false, probing = false, probeStage = "")
            }
        }
    }

    init {
        // 当前生效的温控档位**收**共享状态，不在四个动作点各读一次快照。
        //
        // 温控档位有三个来源：用户、按应用规则、实验室。另两个动了这一页必须跟着变；
        // 快照只在"本页自己刚操作过"那一刻是对的，别处改了就会显示一个过期值 ——
        // 用户看到的是"实验室明明切了档，CPU 页还说没有"。三处界面各读各的，
        // 正是当初把它们收敛到一个控制器的理由（见 ThermalPolicyController 的说明）。
        //
        // 采集器和四个动作点写过的是同一个字段，后写的那个赢；动作点自己不再写，
        // 就只剩这一个来源。
        viewModelScope.launch {
            ThermalPolicyController.state.collect { thermal ->
                _state.value = _state.value.copy(
                    thermalPolicyActive = thermal.appliedIndex != null,
                    thermalPolicyIndex = thermal.appliedIndex,
                )
            }
        }
        // 方案列表用流收集，而不是只在 refresh 时读一次：存了/删了/改了名
        // 之后界面要立刻对上，否则用户会以为没生效而再点一次。
        viewModelScope.launch {
            container.profileRepository.profiles.collect { list ->
                _state.value = _state.value.copy(profiles = list)
            }
        }
        viewModelScope.launch {
            container.profileRepository.activeProfileId.collect { id ->
                val name = id?.let { target ->
                    _state.value.profiles.firstOrNull { it.id == target }?.name
                }
                _state.value = _state.value.copy(
                    activeProfileId = id,
                    activeProfileName = name,
                    // 用户已经在这一页上手点过标签之后，就不再替他改高亮 ——
                    // 否则别处（首页套用方案）一变动，编辑区的高亮会被悄悄拽走。
                    editorProfileId = if (editorTouched) _state.value.editorProfileId else id,
                )
            }
        }
        viewModelScope.launch {
            val archive = withContext(Dispatchers.IO) {
                probeDirectory.listFiles()?.filter { it.name.matches(Regex("rom-configs-[0-9]+\\.zip")) }
                    ?.maxByOrNull { it.name }?.name
            }
            if (archive != null && !_state.value.busy && _state.value.configArchive.isEmpty())
                _state.value = _state.value.copy(configArchive = archive)
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { java.io.File(probeDirectory, "latest.txt").readText() }.getOrNull()
            }
            if (saved != null && !_state.value.probing && _state.value.probeReport.isEmpty())
                _state.value = _state.value.copy(probeReport = saved, probeSaveNote = container.context.getString(R.string.cpu_probe_loaded_last))
        }
        viewModelScope.launch {
            PrivilegeManager.state.collect { current ->
                refreshPowerHalStatus(current.mode, current.permissionGranted)
                // 刚拿到提权、而档位还是空的：补读一次。没有这一段，初始化时抢在绑定
                // 之前完成的那次读失败会一直留着，界面停在空白。
                if (current.mode.canElevate &&
                    _state.value.thermalIndices.isEmpty() &&
                    _state.value.thermalReadError != null
                ) {
                    // 补读要走和 refresh() 同一条派生：档位、结构化策略、摘要三份
                    // 必须一起到位。只填 indices 会让芯片出现而梯子表还是「—」。
                    val (indices, failure) = readThermal()
                    if (indices.isNotEmpty()) {
                        val policies = readThermalPolicies(indices)
                        _state.value = _state.value.copy(
                            thermalIndices = indices,
                            thermalReadError = failure,
                            thermalPolicies = policies,
                            thermalBriefs = briefsOf(policies),
                        )
                    }
                }
            }
        }
        // 布局用流收集而不是读一次：在设置里改完回到这一页要立刻是新布局。
        viewModelScope.launch {
            container.settingsRepository.cpuLayoutMode.collect { name ->
                _state.value = _state.value.copy(layoutMode = CpuLayoutMode.fromName(name))
            }
        }
        viewModelScope.launch {
            container.settingsRepository.rangeControlMode.collect { name ->
                _state.value = _state.value.copy(rangeControlMode = RangeControlMode.fromName(name))
            }
        }
        refresh()
    }

    /**
     * Resolve PowerHAL availability off the main thread. Resolving the hidden
     * service is a blocking transaction into Shizuku, so it cannot run inline.
     */
    private fun refreshPowerHalStatus(mode: PrivilegeMode, permissionGranted: Boolean) {
        viewModelScope.launch {
            val available = mode.canElevate && PowerHal.isAvailableNow()
            val reason = when {
                available -> null
                !permissionGranted -> container.context.getString(R.string.cpu_no_permission)
                !mode.canElevate -> container.context.getString(R.string.cpu_shizuku_down)
                else -> container.context.getString(R.string.cpu_unsupported)
            }
            _state.value = _state.value.copy(
                powerHal = PowerHalStatus(checked = true, available = available, reason = reason),
            )
        }
    }

    /**
     * 读温控档位列表。
     *
     * **为什么单独抽出来**：这个读只在 `refresh()` 里做过一次，而 `refresh()` 跑在
     * ViewModel 初始化时 —— 那一刻 UserService 往往还没绑上（绑定是异步的，发生在
     * `MainActivity.onResume`）。于是 `exec` 返回 null、列表为空，而且**没有任何东西
     * 会重读**：用户看到的就是一片空白，重启 App 才好。表现就是"以前好的，忽然不行了"。
     *
     * 现在提权状态一变、且手上还没有档位时就重读一次。
     *
     * 返回值是「列表 + 失败原因」。null 列表表示列不出目录，空列表表示列过了但没有
     * 配置文件 —— 两件事在界面上必须分开说。
     */
    /** 读每个档位的结构化策略。摘要必须从同一批结果派生，否则会出现"表格有而摘要没有"。 */
    private suspend fun readThermalPolicies(indices: List<Int>): Map<Int, ThermalPolicy> =
        withContext(Dispatchers.IO) {
            indices.associateWith { index ->
                runCatching { ThermalPolicyReader.read(index) }.getOrElse {
                    ThermalPolicy(
                        index,
                        decodeError = container.context.getString(R.string.value_read_failed),
                    )
                }
            }
        }

    /** 每个档位的一句话摘要，从 [policies] 派生。 */
    private fun briefsOf(policies: Map<Int, ThermalPolicy>): Map<Int, String> =
        policies.mapValues { (_, policy) ->
            runCatching { ThermalPolicySummary.brief(policy) }.getOrDefault("")
        }

    private suspend fun readThermal(): Pair<List<Int>, Failure?> {
        // null = 列不出目录（提权不可用、服务端抛异常）；空列表 = 列过了但没有配置文件。
        // **两者都必须分开。** 曾经在这里写成 `?: emptyList()`，于是前者被报成
        // "未找到配置文件" —— 把"不知道"说成了"确定没有"，而重试条件又指着
        // `thermalReadError != null`，导致提权到位后永远不会补读。那个重试是死代码。
        var listed: List<Int>? = null
        var thrown: Failure? = null
        try {
            listed = withContext(Dispatchers.IO) { ThermalPolicyReader.availableIndicesOrNull() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 异常路径：类名和消息进 detail，由 FailureNote 收起展示、可复制。
            thrown = Failure.from("Cpu", Message(R.string.thermal_read_error), e)
        }
        if (thrown != null) return emptyList<Int>() to thrown

        if (listed == null) {
            // 列不出目录也是失败。detail 说明卡在哪一步，不说原因 ——
            // 原因是服务没连还是别的问题，只有日志知道，而摘要不该猜。
            AppLog.w("Cpu", "thermal policy directory could not be listed")
            return emptyList<Int>() to Failure(
                summary = Message(R.string.thermal_read_error),
                detail = "could not list thermal policy directory",
            )
        }

        AppLog.i("Cpu", container.context.getString(R.string.thermal_log_count, listed.size))
        return listed to null
    }

    /**
     * Re-poll PowerHAL availability without rescanning the clusters, for when
     * the screen comes back to the foreground — the user may have just granted
     * Shizuku in another app.
     */
    fun recheckPowerHal() {
        PrivilegeManager.refresh()
        val current = PrivilegeManager.state.value
        refreshPowerHalStatus(current.mode, current.permissionGranted)
    }

    /**
     * 读每簇的下限、上限、当前频率。
     *
     * **`scaling_min_freq` / `scaling_max_freq` 读不到是常态**，不是异常：
     * 这两个节点在本机是 `system:system 0660`，我们不在 `system` 组
     * （见 DEV-003）。历史上它一度是 0664，所以"以前显示得好好的"。
     * 读到就报，读不到就如实显示 —— 不猜，也不拿别的量顶替。
     *
     * 具体地，**不要**用这两个替代品：
     *  - `cpuinfo_max_freq` 是硬件上限，不是当前被限制到的值；
     *  - `stats/time_in_state` 里"到过的最高频率"是历史最大值，跟"现在能跑多高"
     *    没有关系。曾经用它当退路，是错的 —— 那等于对用户断言一件我们不知道的事。
     */
    suspend fun readCaps() {
        val policies = _state.value.edits.map { it.cluster.policy }
        if (policies.isEmpty()) return
        val read = withContext(Dispatchers.IO) {
            policies.map { policy ->
                // 只按 App 身份直读，**不批量也不提权补读** —— 这是故意的：
                // 这一页要如实反映 App 自己能看到什么，读不到就显示未知。
                // 控制验证那条路是提权补读的，两边可能不一致，那是事实。
                // 路径与解析和验证那条路共用 CpuFreqNodes，不会各改一半。
                val (minKhz, maxKhz, curKhz) = CpuFreqNodes.readDirect(policy)
                ClusterCaps(
                    policy = policy,
                    minKhz = minKhz?.toInt(),
                    maxKhz = maxKhz?.toInt(),
                    curKhz = curKhz?.toInt(),
                )
            }
        }
        _state.value = _state.value.copy(caps = read)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val clusters = withContext(Dispatchers.IO) { container.cpuScanner.scan() }
            val edits = clusters.map { cluster ->
                val lo = cluster.availableFreqs.indexOfFirst { it >= cluster.minFreqKhz }
                    .takeIf { it >= 0 } ?: 0
                val hi = cluster.availableFreqs.indexOfLast { it <= cluster.maxFreqKhz }
                    .takeIf { it >= 0 } ?: (cluster.availableFreqs.lastIndex.coerceAtLeast(0))
                ClusterEdit(cluster, lo, hi.coerceAtLeast(lo), cluster.currentGovernor)
            }
            // 档位列表现读现解：不同机器、不同 ROM 上可选项不同，不能写死。
            val (indices, thermalFailure) = readThermal()
            // 摘要和结构化两份都从同一批策略派生 —— 读两遍会在两次读之间出现
            // "表格有而摘要没有"这种自相矛盾的中间态。
            val policies = readThermalPolicies(indices)
            val briefs = briefsOf(policies)
            val allProfiles = container.profileRepository.profiles.first()
            val activeId = container.profileRepository.activeProfileId.first()
            val activeName = activeId?.let { id -> allProfiles.firstOrNull { it.id == id }?.name }
            // Record the untouched limits while nothing of ours is applied, so
            // a later release can be judged against them.
            container.cpuControl.captureBaselineIfClean(clusters)
            // 皮肤阈值：当前实际生效的那一档。读不到就留空，不用历史值顶替。
            val skin = runCatching { ThermalPolicyCatalog.skinThresholdText() }.getOrNull()
            _state.value = _state.value.copy(
                loading = false,
                edits = edits,
                activeProfileName = activeName,
                thermalIndices = indices,
                thermalReadError = thermalFailure,
                thermalBriefs = briefs,
                thermalSkinThreshold = skin,
                thermalPolicies = policies,
                profiles = allProfiles,
                activeProfileId = activeId,
                // 编辑区的档位默认就是当前生效的那个；用户没选过之前不要凭空给一个值，
                // 否则「应用」会把一个用户没选过的档位发下去。
                thermalIndexEdit = _state.value.thermalIndexEdit
                    ?: ThermalPolicyController.state.value.appliedIndex,
            )
            refreshPowerHalStatus(PrivilegeManager.state.value.mode, PrivilegeManager.state.value.permissionGranted)
        }
    }

    fun setGovernor(index: Int, governor: String) = update(index) { it.copy(governor = governor) }

    /** Set an explicit [min, max] pair; order does not matter. */
    fun setRange(index: Int, minIndex: Int, maxIndex: Int) = update(index) { edit ->
        val last = edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0)
        val lo = minIndex.coerceIn(0, last)
        val hi = maxIndex.coerceIn(0, last)
        edit.copy(minIndex = minOf(lo, hi), maxIndex = maxOf(lo, hi))
    }

    private fun update(index: Int, transform: (ClusterEdit) -> ClusterEdit) {
        _state.value = _state.value.copy(
            edits = _state.value.edits.mapIndexed { i, edit -> if (i == index) transform(edit) else edit },
        )
    }

    fun apply() {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val settings = edits.map {
                ClusterSetting(
                    policy = it.cluster.policy,
                    minFreqKhz = it.minFreqKhz,
                    maxFreqKhz = it.maxFreqKhz,
                    governor = it.governor,
                )
            }
            // 温控档位先落地，再下发频率。
            //
            // **顺序不能反。** 切温控策略会让平台重推自己的一套限制；如果频率请求先发，
            // 随后的切档可能把它顶掉（2026-10-22 真机：CPU 请求 17:48:30 下发，
            // 17:48:33 切到 01 后读回只剩 1.2/0.6GHz）。把频率放在最后，
            // 至少我们发出的是切换之后的请求。
            // 温控失败不阻断频率：两者独立，用户最需要的是频率控制。
            val manual = _state.value.thermalIndexEdit
            val companion = if (manual == null) {
                runCatching { container.settingsRepository.freqThermalCompanion.first() }
                    .getOrDefault(true)
            } else false
            when {
                // 用户手动选过档位：以手动为准。
                manual != null -> {
                    val thermal = ThermalPolicyController.applyUserIndex(manual)
                    if (!thermal.success) AppLog.w("Cpu", "温控档位切换失败，继续下发频率")
                }
                // 没选过 + 设置里开着：一并切到最宽松的那一档。
                companion -> {
                    val loose = magicau.mtkcontroller.data.lab.ThermalPolicyCatalog.looseIndex()
                    if (loose == null) {
                        AppLog.w("Cpu", "没有读到可用的温控档位，未附带放开")
                    } else {
                        val thermal = ThermalPolicyController.applyUserIndex(loose)
                        if (!thermal.success) AppLog.w("Cpu", "附带放开温控失败，继续下发频率")
                    }
                }
            }

            // CpuControl owns the whole release-then-acquire sequence, the
            // handle bookkeeping and the governor side of it.
            val outcome = container.cpuControl.apply(edits.map { it.cluster }, settings)
            if (outcome.success) container.syncCpuReapply()
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                messageKind = when {
                    !outcome.success -> OutcomeKind.ERROR
                    container.cpuControl.status.value.verification == CpuVerification.VERIFIED ->
                        OutcomeKind.SUCCESS
                    else -> OutcomeKind.WARNING
                },
            )
        }
    }

    /**
     * Bypass PowerHAL and write the hardware range straight to the kernel.
     * The only escape from a cluster pinned by a request whose handle was lost.
     */
    /**
     * 启停满载负载，并每秒刷新一次各簇实时频率。
     *
     * 负载必须在 App 进程里跑 —— adb 起的 busy loop 落在根 cpuset，
     * 有效利用率被压到极低，cpu7 会停在最低频，测出来的结论是错的。
     * 详见 [magicau.mtkcontroller.data.cpu.CpuLoadRunner]。
     */
    private var loadJob: Job? = null

    fun toggleLoad(cores: List<Int>? = null) {
        if (_state.value.loadActive) {
            loadJob?.cancel(); loadJob = null
            CpuLoadRunner.stop()
            _state.value = _state.value.copy(loadActive = false, loadSeconds = 0, loadFreqs = emptyMap())
            return
        }
        CpuLoadRunner.start(cores ?: CpuLoadRunner.allCores())
        _state.value = _state.value.copy(loadActive = true, loadSeconds = 0)
        loadJob = viewModelScope.launch {
            while (true) {
                ensureActive()
                CpuLoadRunner.tick()
                val freqs = withContext(Dispatchers.IO) {
                    _state.value.edits.associate { edit ->
                        // 只读当前频率一个节点 —— 这是 1 Hz 的负载展示，
                        // 别为"统一"把下限上限也读一遍。
                        edit.cluster.policy to (
                            CpuFreqNodes.parseKhz(Sysfs.read(CpuFreqNodes.cur(edit.cluster.policy)))
                                ?.let { it / 1000 }?.toInt() ?: 0)
                    }
                }
                _state.value = _state.value.copy(
                    loadSeconds = CpuLoadRunner.state.value.seconds,
                    loadFreqs = freqs,
                )
                delay(1000)
            }
        }
    }

    fun emergencyRestore() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
            CpuReapplyService.stop(container.context)
            val outcome = container.cpuControl.emergencyRestore(clusters)
            _state.value = _state.value.copy(busy = false, message = Failure(outcome.message, outcome.detail))
        }
    }

    fun release() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
            // 覆盖面与顺序都在 AppContainer.releaseAllRequests 一处说清，
            // 不在两个界面各写一遍 —— 原来就是这么漂的（首页那份漏了三样）。
            val outcome = container.releaseAllRequests(clusters)
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                messageKind = if (outcome.success) OutcomeKind.SUCCESS else OutcomeKind.ERROR,
            )
        }
    }

    /**
     * 保存当前编辑状态为方案。
     *
     * @param overwriteId 传入已有方案的 id 表示覆盖（编辑），null 表示新建。
     */
    fun saveAsProfile(name: String, overwriteId: String? = null) {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            val existing = overwriteId?.let { id -> _state.value.profiles.firstOrNull { it.id == id } }
            val profile = Profile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.ifBlank {
                    existing?.name ?: container.context.getString(R.string.cpu_profile_unnamed)
                },
                clusters = edits.map {
                    ClusterSetting(it.cluster.policy, it.minFreqKhz, it.maxFreqKhz, it.governor)
                },
                // 档位跟着方案走：只存频率的方案换到别的机器会跑出不同结果。
                // 存**编辑区选的那个**，与频率区间同源 —— 用户选了 02 还没点应用就保存，
                // 存下来的应该是 02，不是上一个已生效的档位。
                thermalIndex = _state.value.thermalIndexEdit,
                createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
            )
            container.profileRepository.upsert(profile)
            _state.value = _state.value.copy(
                message = Failure(
                    Message(
                        if (existing != null) R.string.cpu_profile_overwritten
                        else R.string.cpu_profile_saved,
                        listOf(profile.name),
                    ),
                ),
            )
        }
    }

    /**
     * 把方案的内容装进编辑区，**不应用** —— 用户还要看清再决定。
     *
     * 存的是频率值，编辑区用的是档位下标，所以要按当前簇的可用频率表换算一次。
     * 换算后还要归一化，否则方案里的上下限在别的机器上可能落成 min > max。
     */
    fun loadProfileIntoEditor(profile: Profile) {
        editorTouched = true
        val current = _state.value.edits
        viewModelScope.launch {
            val next = current.map { edit ->
                val saved = profile.clusters.firstOrNull { it.policy == edit.cluster.policy }
                    // 方案里没有这一簇 → 保持现状，而不是清零。换机的方案可能少几簇。
                    ?: return@map edit
                val freqs = edit.cluster.availableFreqs
                if (freqs.isEmpty()) return@map edit
                val lo = freqs.indexOfFirst { it >= saved.minFreqKhz }.takeIf { it >= 0 } ?: 0
                val hi = freqs.indexOfLast { it <= saved.maxFreqKhz }.takeIf { it >= 0 }
                    ?: edit.maxIndex
                edit.copy(minIndex = lo, maxIndex = hi.coerceAtLeast(lo))
            }
            _state.value = _state.value.copy(
                edits = next,
                editorProfileId = profile.id,
                // 档位也是方案的一部分，一起装进编辑区，等「应用」。
                thermalIndexEdit = profile.thermalIndex ?: _state.value.thermalIndexEdit,
            )
        }
    }

    /**
     * 选中"默认"：撤掉 App 施加的全部限制，回到系统自己的状态。
     *
     * 做三件事，缺一不可 —— 频率范围、温控档位、方案标记。只做前两件的话，
     * 标签行还会高亮着上一个方案，用户看到的是"已套用某方案"，与事实不符。
     *
     * 编辑区同时回到全量程，否则界面上的滑块还停在旧方案的位置。
     */
    fun selectDefaultProfile() {
        editorTouched = true
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val full = _state.value.edits.map { edit ->
                val last = edit.cluster.availableFreqs.lastIndex.coerceAtLeast(0)
                edit.copy(minIndex = 0, maxIndex = last)
            }
            _state.value = _state.value.copy(edits = full)
            val clusters = full.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan(deep = false) } }
            // 用户选了“默认”：不是锁屏自动释放，而是明确不再需要控制。
            // 用 disable，避免亮屏联动把刚才撤掉的范围又放回来。
            val outcome = container.cpuControl.disable(clusters)
            CpuReapplyService.stop(container.context)
            ThermalPolicyController.releaseAll()
            container.profileRepository.setActiveProfile(null)
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                activeProfileName = null,
                editorProfileId = null,
                thermalIndexEdit = null,
            )
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            container.profileRepository.delete(id)
            // 删的是当前激活的那个就把激活标记一并清掉，否则界面会显示一个不存在的方案名。
            if (_state.value.activeProfileId == id) container.profileRepository.setActiveProfile(null)
        }
    }

    fun renameProfile(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { container.profileRepository.rename(id, newName.trim()) }
    }

    fun applyProfile(profile: Profile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val clusters = _state.value.edits.map { it.cluster }
                .ifEmpty { withContext(Dispatchers.IO) { container.cpuScanner.scan() } }
            // 先切档位、再下发频率：切档会重推平台限制，频率请求必须最后发。
            // 档位失败不阻断频率；null = 这个方案不管档位，保持现场不动。
            // applyIndex 而不是 enable：enable 在已有句柄时是 alreadyOn，换方案时切不过去。
            profile.thermalIndex?.let { index ->
                val thermal = ThermalPolicyController.applyUserIndex(index)
                if (!thermal.success) AppLog.w("Cpu", "方案温控档位切换失败，继续下发频率")
            }
            val outcome = container.cpuControl.apply(clusters, profile.clusters)
            if (outcome.success) {
                container.profileRepository.setActiveProfile(profile.id)
                container.syncCpuReapply()
            }
            if (outcome.success) editorTouched = true
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                activeProfileName = if (outcome.success) profile.name else _state.value.activeProfileName,
                editorProfileId = if (outcome.success) profile.id else _state.value.editorProfileId,
                thermalIndexEdit = if (outcome.success) profile.thermalIndex else _state.value.thermalIndexEdit,
            )
        }
    }

    /**
     * 建两个示例方案，让用户看懂"方案"能干什么。
     *
     * 取值按**本机实际频率表**算，不写死数字 —— 每台机器的档位不同，
     * 写死会在别的机型上落到范围外，被硬件钳掉，用户看到的结果与名字不符。
     */
    fun createExampleProfiles() {
        val edits = _state.value.edits
        if (edits.isEmpty()) return
        viewModelScope.launch {
            fun settings(fraction: Double?): List<ClusterSetting> = edits.mapNotNull { edit ->
                val freqs = edit.cluster.availableFreqs
                if (freqs.isEmpty()) return@mapNotNull null
                val hwMax = freqs.last()
                val ceiling = fraction?.let { (hwMax * it).toLong() } ?: hwMax
                val maxIdx = freqs.indexOfLast { it <= ceiling }.coerceAtLeast(0)
                ClusterSetting(
                    policy = edit.cluster.policy,
                    minFreqKhz = freqs.first(),
                    maxFreqKhz = freqs[maxIdx],
                )
            }

            // 省电：上限压到硬件上限的一半左右，温控不动。
            container.profileRepository.upsert(
                Profile(
                    id = UUID.randomUUID().toString(),
                    name = container.context.getString(R.string.cpu_example_power_save),
                    clusters = settings(0.55),
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            // 狂暴：锁死最高频（下限=上限=硬件上限），并切到解锁档位。
            // 两者要一起给：只锁频不改档位，温控阶梯仍会把上限压回去。
            val top = settings(null).map { it.copy(minFreqKhz = it.maxFreqKhz) }
            container.profileRepository.upsert(
                Profile(
                    id = UUID.randomUUID().toString(),
                    name = container.context.getString(R.string.cpu_example_beast),
                    clusters = top,
                    thermalIndex = 1,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            _state.value = _state.value.copy(
                message = Failure(
                    Message(
                        R.string.cpu_examples_created,
                        listOf(
                            Message(R.string.cpu_example_power_save),
                            Message(R.string.cpu_example_beast),
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * 直接在性能页切温控档位。
     *
     * 以前这一步在设置 → 实验室 → 只读面板里，而档位与频率区间共同决定实际能跑多高，
     * 用户调完频率还要跨两级菜单去改档位，是错的。档位选择属于这一页。
     */
    fun selectThermalIndex(index: Int) {
        // **只记录，不下发。** 与频率区间一样，等用户点「应用」才落地 ——
        // 这样没授权时点档位也有反馈（选中了），不会像坏按钮。
        _state.value = _state.value.copy(thermalIndexEdit = index)
    }

    /**
     * 切换温控参数原文的显示。
     *
     * 打开时才去读全量参数 —— 它要遍历每份配置的所有字段，而绝大多数用户
     * 只看温度与频率那一行。
     */
    fun toggleThermalDetail() {
        val next = !_state.value.showThermalDetail
        _state.value = _state.value.copy(showThermalDetail = next)
        if (!next || _state.value.thermalFull.isNotEmpty()) return
        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) {
                _state.value.thermalIndices.associateWith { index ->
                    runCatching {
                        ThermalPolicySummary.full(ThermalPolicyReader.read(index))
                    }.getOrDefault("")
                }
            }
            _state.value = _state.value.copy(thermalFull = full)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Exposed for the diagnostics screen. */
}
