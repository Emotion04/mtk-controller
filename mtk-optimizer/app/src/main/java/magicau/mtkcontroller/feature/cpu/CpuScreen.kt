package magicau.mtkcontroller.feature.cpu

import magicau.mtkcontroller.ui.components.OutcomeNote
import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import magicau.mtkcontroller.R
import magicau.mtkcontroller.domain.model.CpuLayoutMode
import magicau.mtkcontroller.domain.model.RangeControlMode
import magicau.mtkcontroller.ui.components.FrequencyGauge
import magicau.mtkcontroller.ui.components.ProfileTagRow
import magicau.mtkcontroller.ui.displayLabel
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * CPU 调频页。
 *
 * ## 页面顺序 = 决策顺序
 *
 * 标签 → 仪表 → 频率区间 →（左下角）高级设置 → 操作。
 * 先看现在被压到哪儿（仪表），再定区间（最常调的）；温控档位和调速器是选一次就
 * 放着的低频控件，收进二级页，不占首屏。
 *
 * ## 不写页眉
 *
 * 版本号、簇数量那些是开发时确认环境用的，对用户是噪声。
 */
@Composable
fun CpuScreen(
    state: CpuUiState,
    onRange: (Int, Int, Int) -> Unit,
    onApply: () -> Unit,
    onRelease: () -> Unit,
    onEmergencyRestore: () -> Unit,
    onToggleLoad: () -> Unit = {},
    onSaveProfile: (String) -> Unit,
    /** 点标签：把方案装进编辑区（不应用）。-1 表示"默认"。 */
    onSelectProfile: (Int) -> Unit = {},
    /** 一个方案都没有时，一键建两个示例。 */
    onCreateExampleProfiles: () -> Unit = {},
    onDeleteProfile: (Int) -> Unit = {},
    onRenameProfile: (Int, String) -> Unit = { _, _ -> },
    /** 打开「高级设置」二级页（温控档位 + 调速器）。 */
    onOpenAdvanced: () -> Unit = {},
    /** 这一页是否正显示。为假时不跑周期读取。 */
    samplingActive: Boolean = true,
    onReadCaps: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
    onResume: () -> Unit = {},
) {
    // 从后台回到前台时重查一次 PowerHAL：用户可能在别的页面刚授权 Shizuku。
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }

    if (state.loading) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.edits.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.cpu_no_clusters_title),
            body = stringResource(R.string.cpu_no_clusters_body),
            modifier = modifier,
        )
        return
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    // 长按标签后的重命名/删除入口。放在这里而不是父级，是因为它只服务于
    // 这一行标签，父级没必要知道有这么一个菜单。
    var profileMenuFor by remember { mutableStateOf<Int?>(null) }
    var renamingProfile by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalNavBarClearance.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 方案标签永远在第一位：它决定下面所有控件的取值，属于"先选方案再微调"。
        item {
            ProfileTagRow(
                names = state.profiles.map { it.name },
                // 高亮跟**编辑区**，不是跟"已应用" —— 点标签只装载，不应用。
                // 跟已应用的话，点任何标签都会是「默认」亮着。
                activeIndex = state.profiles.indexOfFirst { it.id == state.editorProfileId },
                onSelect = onSelectProfile,
                onLongPress = { index -> profileMenuFor = index },
                onAdd = { showSaveDialog = true },
            )
        }

        // 一个方案都没有时给一句话和一个按钮。用户不知道"方案"是什么之前，
        // 不会去点末尾那个孤零零的 +。
        if (state.profiles.isEmpty()) {
            item {
                Column {
                    Text(
                        stringResource(R.string.profile_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.profile_empty_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onCreateExampleProfiles,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.profile_create_examples)) }
                }
            }
        }

        // 仪表提到最上面：它是这一页唯一"一眼看懂现在什么状况"的东西。
        item { CapsPanel(state, onReadCaps, samplingActive) }

        when (state.layoutMode) {
            // 分卡片：每个核簇一张，高级入口挂在最后一格的左下角。
            CpuLayoutMode.PER_CLUSTER -> {
                itemsIndexed(
                    items = state.edits,
                    key = { _, edit -> edit.cluster.policy },
                ) { index, edit ->
                    ClusterCard(
                        edit = edit,
                        enabled = !state.busy,
                        mode = state.rangeControlMode,
                        onRange = { lo, hi -> onRange(index, lo, hi) },
                    )
                }
                item { AdvancedEntry(state, onOpenAdvanced) }
            }

            // 合并卡片：频率区间和高级入口在同一张卡里，入口在卡片左下角。
            CpuLayoutMode.UNIFIED -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.cpu_range_title), style = MaterialTheme.typography.titleSmall)
                        state.edits.forEachIndexed { index, edit ->
                            RangeRow(edit, !state.busy, state.rangeControlMode) { lo, hi -> onRange(index, lo, hi) }
                        }
                        AdvancedEntry(state, onOpenAdvanced)
                    }
                }
            }
        }

        item {
            ActionsSection(
                state = state,
                onApply = onApply,
                onRelease = onRelease,
                onToggleLoad = onToggleLoad,
                onEmergencyRestore = onEmergencyRestore,
                onSaveProfile = { showSaveDialog = true },
            )
        }
    }

    if (showSaveDialog) {
        SaveProfileDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = {
                onSaveProfile(it)
                showSaveDialog = false
            },
        )
    }

    // 长按标签：先给一个只有两个动作的菜单，而不是直接弹重命名输入框 ——
    // 长按的意图可能是删除，不该先逼用户面对一个文本框。
    profileMenuFor?.let { index ->
        val profile = state.profiles.getOrNull(index)
        if (profile == null) {
            profileMenuFor = null
        } else {
            AlertDialog(
                onDismissRequest = { profileMenuFor = null },
                title = { Text(profile.name) },
                text = {
                    Column {
                        TextButton(
                            onClick = { profileMenuFor = null; renamingProfile = index },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_rename)) }
                        TextButton(
                            onClick = { profileMenuFor = null; onDeleteProfile(index) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_delete)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { profileMenuFor = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }

    renamingProfile?.let { index ->
        val profile = state.profiles.getOrNull(index)
        if (profile == null) {
            renamingProfile = null
        } else {
            SaveProfileDialog(
                initialName = profile.name,
                title = stringResource(R.string.cpu_rename_profile),
                onDismiss = { renamingProfile = null },
                onConfirm = { newName ->
                    onRenameProfile(index, newName)
                    renamingProfile = null
                },
            )
        }
    }

}

/**
 * 「高级设置」入口：**小字 + 小按钮**，左对齐贴在频率区间的左下角。
 *
 * 摘要一直显示（当前档位、当前调速器），所以收起来不等于看不见 ——
 * 入口本身就把状态说清楚了，不需要点进去确认。
 */
@Composable
private fun AdvancedEntry(state: CpuUiState, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                stringResource(R.string.cpu_advanced),
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.height(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 文案先在 composable 上下文里取好 —— `buildString` 的 lambda 不是
        // composable 上下文，里面调不了 `stringResource`。
        //
        // 显示**编辑区选的**档位，后面括号标出生效状态 ——
        // 只显示已生效的话，用户选完还看不出自己选了什么。
        val editing = state.thermalIndexEdit
        val thermalBadge = editing
            ?.let { stringResource(R.string.cpu_thermal_badge, it) }
            ?: stringResource(R.string.cpu_thermal_badge_none)
        val pendingSuffix = if (editing != null && editing != state.thermalPolicyIndex) {
            stringResource(R.string.cpu_pending_suffix)
        } else {
            ""
        }
        val governors = state.edits.map { it.governor ?: "—" }.distinct().joinToString("/")
        Text(
            "$thermalBadge$pendingSuffix · $governors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== 频率区间 ====================

@Composable
private fun ClusterCard(
    edit: ClusterEdit,
    enabled: Boolean,
    mode: RangeControlMode,
    onRange: (Int, Int) -> Unit,
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
                Text(edit.cluster.displayLabel(), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.cpu_core_count, edit.cluster.cpus.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RangeRow(edit, enabled, mode, onRange)
        }
    }
}

/** 一个核簇的频率区间控件：标题 + 当前范围 + 调节条。 */
@Composable
private fun RangeRow(
    edit: ClusterEdit,
    enabled: Boolean,
    mode: RangeControlMode,
    onRange: (Int, Int) -> Unit,
) {
    val freqs = edit.cluster.availableFreqs
    if (freqs.isEmpty()) {
        Text(
            stringResource(R.string.cpu_no_freq_table, edit.cluster.displayLabel()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(edit.cluster.displayLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${edit.minFreqKhz / 1000} – ${edit.maxFreqKhz / 1000} MHz",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (mode) {
            RangeControlMode.SEGMENT -> SegmentBar(
                count = freqs.size,
                minIndex = edit.minIndex,
                maxIndex = edit.maxIndex,
                enabled = enabled,
                onRange = onRange,
            )
            RangeControlMode.SLIDER -> RangeHandleBar(
                count = freqs.size,
                minIndex = edit.minIndex,
                maxIndex = edit.maxIndex,
                enabled = enabled,
                onRange = onRange,
            )
        }
    }
}

/**
 * Segment bar: one segment per available frequency, tap or drag to pick.
 *
 * The single control covers all three intents:
 *  - one tap      → lock the cluster to that frequency
 *  - a second tap → widen to the range between the two taps
 *  - a drag       → select the span the finger crossed
 *
 * The span is always normalised to low..high, so it does not matter which end
 * the gesture started from — the earlier version could hand the high value to
 * the "min" slot when the user tapped right-to-left.
 */
@Composable
private fun SegmentBar(
    count: Int,
    minIndex: Int,
    maxIndex: Int,
    enabled: Boolean,
    onRange: (Int, Int) -> Unit,
) {
    var tapAnchor by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0) }

    fun indexAt(x: Float): Int {
        if (widthPx <= 0 || count <= 0) return 0
        // Each segment occupies an equal slice including its share of the gaps.
        val slot = (x / widthPx * count).toInt()
        return slot.coerceIn(0, count - 1)
    }

    /** Highlight during a drag follows the finger; otherwise the committed range. */
    val shownFrom = dragFrom ?: minIndex
    val shownTo = dragTo ?: maxIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(count, widthPx) {
                        detectTapGestures { offset ->
                            val i = indexAt(offset.x)
                            val anchor = tapAnchor
                            if (anchor == null) {
                                // First tap locks immediately…
                                onRange(i, i)
                                tapAnchor = i
                            } else {
                                // …the second one opens it into a range.
                                onRange(minOf(anchor, i), maxOf(anchor, i))
                                tapAnchor = null
                            }
                        }
                    }
                    .pointerInput(count, widthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val i = indexAt(offset.x)
                                dragFrom = i
                                dragTo = i
                                tapAnchor = null
                            },
                            onDragEnd = {
                                val from = dragFrom
                                val to = dragTo
                                if (from != null && to != null) {
                                    onRange(minOf(from, to), maxOf(from, to))
                                }
                                dragFrom = null
                                dragTo = null
                            },
                            onDragCancel = {
                                dragFrom = null
                                dragTo = null
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragTo = indexAt(change.position.x)
                            },
                        )
                    }
            ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(count) { i ->
            val inRange = i in minOf(shownFrom, shownTo)..maxOf(shownFrom, shownTo)
            val isEdge = i == shownFrom || i == shownTo
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(LocalShapeStyle.current.chip)
                    .background(
                        when {
                            isEdge && shownFrom != shownTo -> MaterialTheme.colorScheme.tertiary
                            inRange -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
            )
        }
    }
}

/**
 * 内核频率约束总览：每簇的下限、上限、当前频率。
 *
 * 三个数是这套机制的全部真相 —— **上限**是温控/限幅层写的，我们发的频率请求改不动它；
 * **下限**是我们能设的；**当前**是 Governor 按利用率选的。
 * 只看"当前频率"会分不清是上限被压住还是负载没上去，所以三个一起摆出来。
 *
 * 轮询用 repeatOnLifecycle(STARTED)：退到后台立刻停，不持续耗电。
 */
@Composable
private fun CapsPanel(state: CpuUiState, onReadCaps: suspend () -> Unit, active: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // active 为假时不起轮询：相邻标签页现在会预组合，不门控的话
    // 两页会同时每秒读几次内核。
    // 回调经 rememberUpdatedState 取：下面这个循环**永不停**， LaunchedEffect 的 key
    // 只有 lifecycleOwner 和 active，重组不重启它。协程里攥着第一次组合那个 lambda 的话，
    // 它会以两秒一次的频率一直呼叫旧的那份，而外面早就换了新的 —— 这种错没有任何症状，
    // 只是读数不更新。调用方目前给的是 vm::readCaps（绑定在稳定 ViewModel 上的方法引用），
    // 所以还没出事；换成读普通 val 的 lambda 就会静默失效。
    val readCapsNow by rememberUpdatedState(onReadCaps)
    LaunchedEffect(lifecycleOwner, active) {
        if (!active) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                readCapsNow()
                delay(2000)
            }
        }
    }
    if (state.caps.isEmpty()) return

    // 并排一行：几簇放一起才能横向比较"哪一簇被压得更狠"。
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
        // 右上角的温控档位徽章：倒梯形，左边斜、右边竖，顶部略宽。
        // 显示**当前生效**的档位，不是编辑区里选中未应用的那个。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ThermalBadge(state.thermalPolicyIndex)
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.caps.forEach { cap ->
                val cluster = state.edits.firstOrNull { it.cluster.policy == cap.policy }?.cluster
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (cluster != null) cluster.displayLabel() else cap.policy,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FrequencyGauge(
                        currentKhz = cap.curKhz?.toLong(),
                        maxLimitKhz = cap.maxKhz?.toLong(),
                        minLimitKhz = cap.minKhz?.toLong(),
                        // 量程取硬件上限：固定不变，红弧的长度才可比。
                        hardwareMaxKhz = cluster?.maxFreqKhz?.takeIf { it > 0 }
                            ?: cap.maxKhz?.toLong() ?: 0L,
                        compact = true,
                    )
                }
            }
        }
        }
    }
}

/** 倒梯形：上宽下窄，左边斜、右边竖直，四角带小圆角。 */
private class KeystoneShape(
    private val slant: androidx.compose.ui.unit.Dp = 7.dp,
    private val corner: androidx.compose.ui.unit.Dp = 3.dp,
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val s = with(density) { slant.toPx() }
        val r = with(density) { corner.toPx() }
        val w = size.width
        val h = size.height
        val len = kotlin.math.sqrt(s * s + h * h)
        // **右侧是真正的直角**：上边与右边、下边与右边都直接交于 (w,0)/(w,h)；
        // 只把左侧斜边的两个角做成小圆角。四角都圆会让它看起来像普通圆角矩形，
        // 直角梯形的形状就没了。
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w, 0f)
            lineTo(w, h)
            lineTo(s + r, h)
            quadraticBezierTo(s, h, s - r * s / len, h - r * h / len)
            lineTo(r * s / len, r * h / len)
            quadraticBezierTo(0f, 0f, r, 0f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/** 环形图卡片右上角的温控档位徽章。 */
@Composable
private fun ThermalBadge(index: Int?) {
    val shape = androidx.compose.runtime.remember { KeystoneShape() }
    Row(
        modifier = Modifier
            .height(19.dp)
            .shadow(2.dp, shape, clip = false)
            .background(MaterialTheme.colorScheme.secondaryContainer, shape)
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (index == null) stringResource(R.string.cpu_thermal_badge_empty)
            else stringResource(R.string.cpu_thermal_badge, index),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ==================== 操作区 ====================

/** 页面底部的动作：应用 / 释放、满载负载、强制恢复、保存方案。 */
@Composable
private fun ActionsSection(
    state: CpuUiState,
    onApply: () -> Unit,
    onRelease: () -> Unit,
    onToggleLoad: () -> Unit,
    onEmergencyRestore: () -> Unit,
    onSaveProfile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApply,
                enabled = !state.busy && state.powerHal.available,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_apply)) }

            FilledTonalButton(
                onClick = onRelease,
                enabled = !state.busy && state.powerHal.available,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_release)) }
        }

        // 功能区：小按钮 + 图标，横排。不用整行大按钮 —— 这几项都不是主流程，
        // 占满宽度会把「应用/释放」压下去，而那才是这一页的主动作。
        // 做成 FlowRow 是为了后面加东西时不用重新排版。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 满载负载。**必须在 App 进程里跑**：adb 起的 busy loop 落在根 cpuset，
            // 有效利用率被压到极低，超大核会停在最低频，用它验证频率只会得到错误结论。
            SmallAction(
                icon = if (state.loadActive) Icons.Filled.Close else Icons.Filled.PlayArrow,
                label = if (state.loadActive) {
                    stringResource(R.string.cpu_load_stop, state.loadSeconds)
                } else {
                    stringResource(R.string.cpu_load_start)
                },
                enabled = !state.busy,
                onClick = onToggleLoad,
            )
            // Deliberately not styled as a normal action: it is the way out
            // of a cluster pinned by a lost request, which no other control can undo.
            SmallAction(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.cpu_emergency_restore),
                enabled = !state.busy,
                onClick = onEmergencyRestore,
            )
            SmallAction(
                icon = Icons.Filled.Create,
                label = stringResource(R.string.cpu_save_profile),
                enabled = !state.busy && state.edits.any { it.cluster.availableFreqs.isNotEmpty() },
                onClick = onSaveProfile,
            )
        }

        if (state.loadActive) {
            state.loadFreqs.forEach { (policy, mhz) ->
                Text(
                    if (mhz > 0) {
                        stringResource(R.string.cpu_load_freq, policy, mhz)
                    } else {
                        stringResource(R.string.cpu_policy_unread, policy)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!state.powerHal.available && state.powerHal.checked) {
            Text(
                text = state.powerHal.reason ?: stringResource(R.string.cpu_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.message?.let { OutcomeNote(message = it, kind = state.messageKind) }
    }
}

@Composable
private fun SaveProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialName: String = "",
    title: String? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    // onClick 的 lambda 不是 composable 上下文，文案要先取出来。
    val unnamed = stringResource(R.string.cpu_profile_unnamed)

    AlertDialog(
        onDismissRequest = onDismiss,
        // 默认标题从资源取 —— 参数默认值里不能调 stringResource。
        title = { Text(title ?: stringResource(R.string.cpu_save_profile)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cpu_profile_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { unnamed }) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 功能区里的一个小按钮：图标 + 短标签。
 *
 * 用 tonal 而不是实底：这一区都是"偶尔用一次"的动作，实底会把
 * 「应用 / 释放」的主次关系压反。
 */
@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 双向指针条：**和分段条同一套视觉**，两端各加一个三角指针。
 *
 * ## 为什么不用 Material 的 `RangeSlider`
 *
 * 两个问题。一是**吃不住拖动** —— 它内部用 `draggable`，和外面标签页的
 * `HorizontalPager` 抢手势时经常抢输，于是调范围的同时页面也翻过去了。
 * 分段条用 `detectHorizontalDragGestures` + `change.consume()`，一直是稳的，
 * 所以这里用同一套手势管线。
 * 二是外观：这一页的两种调节方式如果长得完全不一样，用户会以为它们不是
 * 同一件事的两个做法。
 *
 * ## 手势：就近抓一根
 *
 * 按下时看离哪个指针近，那一次拖动就只动它。这样不需要"精确按住指针"，
 * 也不会出现"想拖上限却把下限带跑了"。
 */
@Composable
private fun RangeHandleBar(
    count: Int,
    minIndex: Int,
    maxIndex: Int,
    enabled: Boolean,
    onRange: (Int, Int) -> Unit,
) {
    if (count <= 1) {
        Text(
            stringResource(R.string.cpu_single_freq),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var widthPx by remember { mutableStateOf(0) }
    /** 这一次拖动动的是哪一根：true = 下限。 */
    var draggingMin by remember { mutableStateOf<Boolean?>(null) }
    // 颜色必须在**组合期**取出来。Canvas 的绘制 lambda 不是 @Composable 上下文，
    // 里面读 MaterialTheme 会编译不过 —— 这项目里踩过一次了。
    val handleColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    fun indexAt(x: Float): Int =
        if (widthPx <= 0) 0 else ((x / widthPx) * count).toInt().coerceIn(0, count - 1)

    /** 第 i 格的中心横坐标，指针画在这里。 */
    fun centerOf(i: Int): Float = (i + 0.5f) / count * widthPx

    Column(modifier = Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .then(
                    if (!enabled) Modifier else Modifier
                        .pointerInput(count, widthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    draggingMin =
                                        kotlin.math.abs(offset.x - centerOf(minIndex)) <=
                                            kotlin.math.abs(offset.x - centerOf(maxIndex))
                                    val i = indexAt(offset.x)
                                    if (draggingMin == true) {
                                        onRange(minOf(i, maxIndex), maxIndex)
                                    } else {
                                        onRange(minIndex, maxOf(i, minIndex))
                                    }
                                },
                                onDragEnd = { draggingMin = null },
                                onDragCancel = { draggingMin = null },
                                onHorizontalDrag = { change, _ ->
                                    // **必须吃掉。** 不吃的话这一次横向拖动会同时
                                    // 被标签页的 Pager 拿去翻页。
                                    change.consume()
                                    val i = indexAt(change.position.x)
                                    if (draggingMin == true) {
                                        onRange(minOf(i, maxIndex), maxIndex)
                                    } else {
                                        onRange(minIndex, maxOf(i, minIndex))
                                    }
                                },
                            )
                        }
                ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(count) { i ->
                val inRange = i in minIndex..maxIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(LocalShapeStyle.current.chip)
                        .background(
                            if (inRange) handleColor else trackColor
                        ),
                )
            }
        }

        // 三角指针。画在条**下面**，尖端朝上指着它标记的那一格中心。
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp),
        ) {
            if (widthPx <= 0) return@Canvas
            listOf(minIndex, maxIndex).forEach { index ->
                val cx = (index + 0.5f) / count * size.width
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, 0f)
                    lineTo(cx - 5.dp.toPx(), size.height)
                    lineTo(cx + 5.dp.toPx(), size.height)
                    close()
                }
                drawPath(path, handleColor)
            }
        }
    }
}
