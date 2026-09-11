package magicau.mtkcontroller.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.di.containerViewModelFactory
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.NavBarVisibility
import magicau.mtkcontroller.domain.model.TabRestoreMode
import magicau.mtkcontroller.feature.cpu.CpuViewModel
import magicau.mtkcontroller.feature.diag.DiagScreen
import magicau.mtkcontroller.feature.diag.DiagViewModel
import magicau.mtkcontroller.feature.home.HomeScreen
import magicau.mtkcontroller.feature.home.HomeViewModel
import magicau.mtkcontroller.feature.changelog.ChangelogScreen
import magicau.mtkcontroller.feature.changelog.ChangelogViewModel
import magicau.mtkcontroller.feature.lab.LabDetailScreen
import magicau.mtkcontroller.feature.lab.LabPanelScreen
import magicau.mtkcontroller.feature.lab.LabPanelViewModel
import magicau.mtkcontroller.feature.lab.LabScreen
import magicau.mtkcontroller.feature.lab.LabViewModel
import magicau.mtkcontroller.feature.lab.RawTestScreen
import magicau.mtkcontroller.feature.lab.RawTestViewModel
import magicau.mtkcontroller.feature.notifications.NotificationScreen
import magicau.mtkcontroller.feature.settings.ArchiveScreen
import magicau.mtkcontroller.feature.settings.LogScreen
import magicau.mtkcontroller.feature.settings.SettingsScreen
import magicau.mtkcontroller.feature.settings.SettingsViewModel
import magicau.mtkcontroller.feature.thermal.AppThermalScreen
import magicau.mtkcontroller.feature.thermal.AppThermalViewModel
import magicau.mtkcontroller.feature.tweaks.StatusToolsScreen
import magicau.mtkcontroller.feature.tweaks.TweaksScreen
import magicau.mtkcontroller.feature.tweaks.TweaksViewModel
import magicau.mtkcontroller.ui.components.FloatingCapsuleNavBar
import magicau.mtkcontroller.ui.components.SubScreenScaffold
import magicau.mtkcontroller.ui.theme.AppPalette
import magicau.mtkcontroller.ui.theme.LocalMotion

@Composable
fun AppNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    // The launch tab is a user preference, so the graph waits for it.
    var startRoute by remember { mutableStateOf<String?>(null) }
    // 当前标签。Pager 和底栏共用这一个来源，避免两处各自记一份而分叉。
    //
    // **必须可保存。** 进程重建时导航栈由系统还原（人还停在二级页上），
    // 而这一份若被清空，底栏会亮回默认标签 —— 高亮和用户眼前的页面分家。
    var selectedTab by rememberSaveable { mutableStateOf(Destination.HOME) }
    // 冷启动才用偏好里的启动标签。进程重建时 selectedTab 已还原，再覆盖就把对的冲掉了。
    var tabInitialised by rememberSaveable { mutableStateOf(false) }
    var swipeMode by remember { mutableStateOf(TabSwipeMode.NEAREST) }
    // Pager 状态提在这里：底栏拖动要直接改它的滚动位置。
    val tabPagerState = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { tabs.size },
    )
    // 一页的宽度 = Pager 的宽度（每页占满）。`PagerState.pageSize` 是 internal，
    // 所以自己量 —— 底栏拖动要按它把"页面单位"换算成像素。
    var pageWidthPx by remember { mutableIntStateOf(0) }
    // 拖动起点。HOLD 模式下没停够要弹回这里，而不是弹到当前位置。
    var draggedFromPage by remember { mutableIntStateOf(0) }
    /** 贯穿一次拖动的滚动会话；null 表示当前没在拖。 */
    var dragSession by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<Float>?>(null) }
    /** 拖动中的绝对页位置（0.0 到 tabs.lastIndex）。拖动期间它才是真源，不是 Pager 的 currentPage。 */
    var dragPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val configured = container.settingsRepository.defaultTabRoute.first()
        startRoute = configured ?: FeatureFlags.defaultTab().route
        // 上次停留的标签就是启动标签 —— 用户从哪离开就从哪回来。
        // 但只在冷启动这么做：tabInitialised 为真说明这份状态是还原来的，
        // 那时 selectedTab 已经是用户真正停留的标签，覆盖它就和眼前看到的分家了。
        if (!tabInitialised) {
            selectedTab = Destination.fromRoute(configured)
            tabInitialised = true
        }
        // 不在这里读 swipeMode —— 见下面的 collect
    }

    val route = startRoute
    if (route == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val navStyleName by container.settingsRepository.navBarStyle
        .collectAsStateWithLifecycle(initialValue = NavBarStyle.COLORFUL.name)
    val navCustomName by container.settingsRepository.navBarCustomPalette
        .collectAsStateWithLifecycle(initialValue = AppPalette.TEAL.name)
    // 底栏的滚动行为。它是**全应用**的一个选择：决定了所有页面的底部留白，
    // 所以在这里读一次、往下传，而不是让每个页面各读一遍。
    val navBarVisibilityName by container.settingsRepository.navBarVisibility
        .collectAsStateWithLifecycle(initialValue = NavBarVisibility.FIXED.name)
    val navBarVisibility = NavBarVisibility.fromName(navBarVisibilityName)
    val navBarClearance = magicau.mtkcontroller.ui.components.clearanceFor(navBarVisibility)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    /** 切回标签时保留原二级页面，还是回到标签主页。 */
    // 只收名字，不在这里推导成枚举：switchTab 每次都现推（见那里的注释），
    // 这里再存一份就成了同一个设置的第二个真相。
    val tabRestoreName by container.settingsRepository.tabRestoreMode
        .collectAsStateWithLifecycle(initialValue = TabRestoreMode.KEEP.name)

    /**
     * 每个标签最后停住的**整条**子页链（`tabs` 之下的每一层，含参数）。
     *
     * ## 为什么存链而不是存一页
     *
     * `settings/lab` → `settings/lab/panel` 是两层。只记最上面那一层的话，
     * 还原后按返回会从 panel 直接跳回 `tabs`，中间那一层被吞掉 —— 用户会发现自己
     * "少退了一步"，而且说不清为什么。
     *
     * ## 数据从哪儿来
     *
     * `navController.currentBackStack`。它就是权威，所以不自己攒：自己攒要和
     * **系统还原、返回键、屏幕内部发起的 navigate** 三方保持一致，漏一个方向
     * 就会和真实栈分叉，而那种分叉在桌面上看不出来。
     *
     * ## 为什么键是"离开时的那个标签"
     *
     * 子页是从某个标签钻进去的，就记在哪个标签名下。**不能按路由前缀判归属** ——
     * `settings/notifications` 是从工具页钻进去的，前缀却属于设置；按前缀分会把
     * 它记到设置名下，于是从工具页离开再回来，位置丢了。
     * 底栏高亮也用同一个来源（见下面的 `selected`），两边因此不可能不一致。
     *
     * 只在 KEEP 模式下写。手动按返回退回标签主页时清空对应项 ——
     * "退回主页"和"去隔壁看一眼"是两种意图，前者不该被还原。
     */
    // 用 rememberSaveable：旋屏或进程重建后导航栈由系统还原（人还停在二级页），
    // 记忆若被清空，之后切标签就不还原了，与用户所见不一致。
    // Map + List + 枚举都是 Serializable，默认的 saver 存得下。
    var tabStacks by rememberSaveable { mutableStateOf(emptyMap<Destination, List<String>>()) }

    /**
     * 当前返回栈里 `tabs` 之上的那条子页链；空表示停在标签主页。
     *
     * 读 `.value` 而不是 collect：只在切标签那一刻需要它，收进来只会让每次
     * 导航都重组整个 NavHost。
     */
    fun chainAboveTabs(): List<String> {
        val stack = navController.currentBackStack.value
        val start = stack.indexOfFirst { it.destination.route == TABS_ROUTE }
        if (start < 0) return emptyList()
        return stack.drop(start + 1).mapNotNull { it.concreteRoute() }
    }

    /**
     * 栈顶路由，**现取**。
     *
     * 不能用组合时那个 `currentRoute`：本函数会被 `TabsPager` 的
     * `LaunchedEffect(pagerState)` 长期持有（它的 key 只有 pagerState，
     * 重组不会重启它），捕获进来的普通 `val` 会一直是第一次组合时的旧值 ——
     * 于是"人在不在子页上"这个判断永远停在过去。
     * 状态委托（`by`）捕获的是 State 对象、读的时候取新值，所以 selectedTab、
     * tabStacks、tabRestoreName 都能直接用；只有这种算出来的 val 必须现取。
     */
    fun currentRouteNow(): String? =
        navController.currentBackStack.value.lastOrNull()?.destination?.route

    /**
     * 切到某个标签。**底栏点击、左右滑动、拖着底栏滑，三条路都走这里**，
     * 所以两种手势之间不可能有行为差。
     *
     * 语义完全由 [TabRestoreMode] 决定，与手势无关：
     *
     * | 模式 | 切到某个标签 |
     * |---|---|
     * | 保留 | 还原那个标签上次停住的整条子页链；没有就落主页 |
     * | 重置 | 一律回标签主页 |
     */
    fun switchTab(destination: Destination) {
        val keep = TabRestoreMode.fromName(tabRestoreName) == TabRestoreMode.KEEP
        // 保留模式下，点当前标签是**空操作** —— 你要的"上次那一页"就是眼前这一页。
        //
        // 曾经这里是"点当前标签就回主页"，于是同一个设置下点一下和滑一下结果不同：
        // 滑走再滑回来停在原地，点一下却被弹回主页。想回这个标签的主页用系统返回键，
        // 那同时也表示"我退出了这一层"，记忆按同一个规则作废（见下面的 LaunchedEffect）。
        //
        // 重置模式不走这个分支：那一档的语义就是"切就回主页"，点当前标签也该如此，
        // 否则从深层子页出来只剩系统返回键一条路。
        if (keep && destination == selectedTab) return
        val here = currentRouteNow()

        // 先把当前标签的子页链收起来。
        if (keep && here != TABS_ROUTE) {
            val chain = chainAboveTabs()
            if (chain.isNotEmpty()) tabStacks = tabStacks + (selectedTab to chain)
        }

        selectedTab = destination

        // 保存和读取必须用同一个条件 —— 曾经只在保存处判 KEEP，于是用户先在 KEEP 下
        // 设了记忆、再切成 RESET，旧条目还躺着，点标签会把旧二级页还原出来，
        // 与 RESET 的承诺相反。
        val remembered = if (keep) tabStacks[destination] else null
        if (!remembered.isNullOrEmpty()) {
            // 一次 navigate 同时完成"清掉当前子页"和"推入目标页"：分两步做会在中间
            // 多出一帧的栈状态。链上后面的每一层再逐个推，返回键才一层层走得回去。
            //
            // 失败不能静默：concreteRoute() 可能留下占位符、路由也可能改名，
            // 吞掉异常的话用户点标签什么都没发生，连反馈都没有。退回主页并记日志。
            val restored = runCatching {
                navController.navigate(remembered.first()) {
                    popUpTo(TABS_ROUTE) { inclusive = false }
                }
                remembered.drop(1).forEach { navController.navigate(it) }
            }
            if (restored.isFailure) {
                AppLog.w("Nav", "还原二级页失败：$remembered")
                if (here != TABS_ROUTE) {
                    navController.popBackStack(TABS_ROUTE, inclusive = false)
                }
            }
        } else if (here != TABS_ROUTE) {
            navController.popBackStack(TABS_ROUTE, inclusive = false)
        }
    }

    val scope = rememberCoroutineScope()
    // 用 collect 而不是 .first()：设置里改完要立刻生效。
    // 之前读一次就完，用户改了模式却要重启 App 才生效 —— 看起来就像"设置没用"。
    LaunchedEffect(Unit) {
        container.settingsRepository.tabSwipeMode.collect { name ->
            swipeMode = TabSwipeMode.fromName(name)
        }
    }

    /**
     * 标签层的 ViewModelStoreOwner，就是 `TABS_ROUTE` 那个返回栈条目。
     *
     * **标签层挪到 NavHost 外面之后才需要它。** 放在 NavHost 里时
     * `viewModel()` 自动拿到所在目的地的条目；挪到外面就只剩 Activity 了，
     * 而「高级设置」二级页要复用的正是标签那一份 `CpuViewModel`
     * （它读的选中档位、要写的调速器都在那份状态里）。所以显式把条目取出来，
     * 用 `CompositionLocalProvider` 指回 `LocalViewModelStoreOwner` ——
     * 标签层拿到的和以前完全是同一个 store。
     *
     * 图是 `NavHost` 第一次组合时才建好的，所以要先等它上栈。
     */
    var tabsOwner by remember { mutableStateOf<androidx.lifecycle.ViewModelStoreOwner?>(null) }
    LaunchedEffect(navController) {
        navController.currentBackStack.first { stack -> stack.any { it.destination.route == TABS_ROUTE } }
        tabsOwner = runCatching { navController.getBackStackEntry(TABS_ROUTE) }.getOrNull()
    }

    // 停在标签主页 = 用户手动退出了一层。这时那个标签记住的位置作废。
    LaunchedEffect(currentRoute) {
        if (currentRoute == TABS_ROUTE) tabStacks = tabStacks - selectedTab
    }

    // 进子页时底栏仍高亮它所属的标签；回到 tabs 时用 selectedTab。
    //
    // **子页永远不改变高亮：它是压在**当前标签**之上的，不是另一个标签。**
    // 曾经这里按路由前缀判归属，于是从工具页钻进去的 `settings/notifications`
    // 把底栏亮成了设置 —— 用户明明在工具页的流程里，抬头一看标签换了。
    // 而记忆键用的是 selectedTab，两边一套前缀、一套状态，必然分叉。
    // 现在只有一个来源，分叉这一类错从此不可能发生。
    val selected = selectedTab

    // 底栏是否被滚走。只在 AUTO_HIDE 下会变。
    var barHidden by remember { mutableStateOf(false) }
    // 累计滚动量。**不能一见位移就切** —— 手指的每一次微小抖动都会翻转状态，
    // 底栏会抖个不停。攒够一段距离再判定，方向明确。
    var scrollAccumPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(navBarVisibility) {
        // 切回固定模式时把状态复位，否则底栏会停在被藏起来的位置。
        if (navBarVisibility != NavBarVisibility.AUTO_HIDE) barHidden = false
    }
    val navBarScrollConnection = remember(navBarVisibility) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 只观察，不消费 —— 消费掉的话页面就滚不动了。
                if (navBarVisibility != NavBarVisibility.AUTO_HIDE) return Offset.Zero
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                scrollAccumPx += dy
                // dy < 0 = 内容往上走（人在往深处看）→ 收起底栏把地方让出来。
                if (scrollAccumPx < -NAV_BAR_HIDE_AFTER_PX) {
                    barHidden = true; scrollAccumPx = 0f
                } else if (scrollAccumPx > NAV_BAR_HIDE_AFTER_PX) {
                    // 往回滚 → 底栏回来。用户想切标签时就是先往回带一下。
                    barHidden = false; scrollAccumPx = 0f
                }
                return Offset.Zero
            }
        }
    }

    val barOffsetY by animateDpAsState(
        targetValue = if (barHidden) 120.dp else 0.dp,
        animationSpec = LocalMotion.current.baseSpec(),
        label = "navBarOffset",
    )

    CompositionLocalProvider(
        magicau.mtkcontroller.ui.components.LocalNavBarClearance provides navBarClearance,
    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(navBarScrollConnection),
    ) {
        // 转场 lambda 不是 Composable 上下文，读不了 CompositionLocal，
        // 所以在这里取一次再捕获进去。
        val motion = LocalMotion.current

        // 四个顶层标签装在**导航之外**的一层里：左右滑动即可切换，底栏跟着走。
        // 子页面是 NavHost 里的独立目的地，压在它之上 —— "平移到隔壁"和
        // "进入下一层"是两种关系，返回语义也不同。
        //
        // **标签层必须在 NavHost 外面。** 曾经它是 `composable(TABS_ROUTE)` 的内容，
        // 于是一进二级页它就离开组合、返回时整套重新组合加重新布局。实测返回一帧
        // 要 233 ms，其中 228 ms 全花在 measure/layout/draw 上（gfxinfo framestats
        // 逐帧量的），用户看到的是"按了返回要停半秒动画才开始"。
        // 挪到外面之后标签层永不销毁，进出子页只剩子页自己那一层过渡。
        //
        // NavHost 覆盖在它上面，但停在标签主页时那个目的地是**空的**，
        // 而 NavHost 全栈没有任何 pointerInput 节点 —— 触摸会穿过空层落到标签上。
        // 子页压着时子页自己有滚动手势，触摸自然归它，标签不再响应，正是想要的。
        val owner = tabsOwner
        if (owner != null) {
            CompositionLocalProvider(
                androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().statusBarsPadding()
                        .onSizeChanged { pageWidthPx = it.width },
                ) {
                    TabsPager(
                        tabs = tabs,
                        selected = selected,
                        // 滑动和点击同一条路。曾经滑动只改 selectedTab、不收记忆也不还原，
                        // 于是"滑走再滑回来"丢位置，而点一下却不会 —— 同一个动作两种结果。
                        onTabChange = { switchTab(it) },
                        pagerState = tabPagerState,
                    ) { tab, isSelected ->
                    when (tab) {
                        Destination.HOME -> {
                            val vm: HomeViewModel = viewModel(
                                factory = containerViewModelFactory { HomeViewModel(container) },
                            )
                            val state by vm.state.collectAsStateWithLifecycle()
                            // 采样器只在首页**真正显示**时跑。
                            // 相邻页现在会预组合（为的是滑动不卡），
                            // 所以"被组合"不等于"看得见" —— 必须看 isSelected。
                            LifecycleResumeEffect(isSelected) {
                                vm.setSamplingActive(isSelected)
                                onPauseOrDispose { vm.setSamplingActive(false) }
                            }
                            HomeScreen(
                                state = state,
                                onOpenTests = { navController.navigate(SubRoutes.TESTS) },
                                onMoveCard = vm::moveCard,
                                onRemoveCard = vm::removeCard,
                                onAddCard = vm::addCard,
                                onResetCards = vm::resetCards,
                                onApplyProfile = vm::applyProfile,
                                onRelease = vm::release,
                                onTouchEnabled = vm::setTouchOptimization,
                                onTouchRate = vm::setTouchRate,
                                onRotationSuggestion = vm::setRotationSuggestion,
                                onThermalBrightness = vm::setThermalBrightness,
                            )
                        }
                        Destination.CPU -> magicau.mtkcontroller.feature.cpu.PerformanceScreen(
                            container,
                            samplingActive = isSelected,
                            onOpenAdvanced = { navController.navigate(SubRoutes.CPU_ADVANCED) },
                        )
                        Destination.TOOLS -> magicau.mtkcontroller.feature.tweaks.TuningScreen { navController.navigate(it) }
                        else -> SettingsTab(container, navController)
                    }
                    }
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = TABS_ROUTE,
            // Edge-to-edge is enabled, so keep content clear of the status bar.
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            // 这里只剩子页进出一种转场（见 NavMotion）。顶层标签在 NavHost 外面
            // 那一层，切标签由 Pager 自己滚，不经过这些 transition。
            enterTransition = { NavMotion.enter(this, motion) },
            exitTransition = { NavMotion.exit(this, motion) },
            popEnterTransition = { NavMotion.popEnter(this, motion) },
            popExitTransition = { NavMotion.popExit(this, motion) },
        ) {

            // **标签主页本身是空的。** 四个标签在 NavHost 外面那一层（见上文），
            // 这里只是图的起点，也是 `popUpTo(TABS_ROUTE)` 的落点。
            //
            // 空是有意的：不空的话这一层会盖住标签、把触摸吃掉。
            // NavHost 自己没有 pointerInput 节点，所以空目的地上触摸会穿过去。
            composable(TABS_ROUTE) { }

            composable("gpu") {
                magicau.mtkcontroller.feature.cpu.PerformanceScreen(container, initialPage = 1)
            }

            // 「高级设置」二级页。**复用 CPU 标签那一个 ViewModel 实例**：
            // 它要读的选中档位、要写的调速器就在那份状态里，另开一个实例两边会对不上。
            composable(SubRoutes.CPU_ADVANCED) { entry ->
                val owner = remember(entry) {
                    runCatching { navController.getBackStackEntry(TABS_ROUTE) }.getOrDefault(entry)
                }
                val vm: CpuViewModel = viewModel(
                    owner,
                    factory = containerViewModelFactory { CpuViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(R.string.cpu_advanced),
                    onBack = navController::popBackStack,
                ) {
                    magicau.mtkcontroller.feature.cpu.CpuAdvancedScreen(
                        state = state,
                        onGovernorChange = vm::setGovernor,
                        onSelectThermalIndex = vm::selectThermalIndex,
                        onToggleThermalDetail = vm::toggleThermalDetail,
                    )
                }
            }

            // 方案不再是独立标签，内容变成性能页顶部的标签。
            // 旧路由保留并重定向，避免深链和"上次停留页"记录指向空页面。
            composable("profiles") {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    // 方案已并入性能页，落到标签容器即可；标签页内容由 Pager 决定。
                    navController.navigate(TABS_ROUTE) {
                        popUpTo(TABS_ROUTE) { inclusive = true }
                    }
                }
            }

            // 设置分类的二级页。一条路由装全部分类，分类名走路径参数 ——
            // 每加一个分类只改 SettingsCategory，不动这里。
            composable("${SubRoutes.SETTINGS_CATEGORY}/{${SubRoutes.SETTINGS_CATEGORY_ARG}}") { entry ->
                val category = magicau.mtkcontroller.feature.settings.SettingsCategory.fromName(
                    entry.arguments?.getString(SubRoutes.SETTINGS_CATEGORY_ARG),
                )
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(category.labelRes), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.settings.SettingsCategoryScreen(
                        category = category,
                        state = state,
                        actions = settingsActions(vm, navController, androidx.compose.ui.platform.LocalContext.current),
                    )
                }
            }

            // The sub-screens are pure content; the nav host owns their top bar
            // so the centred title is identical across all three.
            composable(SubRoutes.TESTS) {
                val vm: CpuViewModel = viewModel(factory = containerViewModelFactory { CpuViewModel(container) })
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_tests), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.cpu.CpuTestsScreen(
                        state, vm::recheckPowerHal, vm::probe, vm::stopProbe, vm::probeAll,
                        vm::exportProbe, vm::collectRomConfigs, vm::setRange, vm::release,
                        onOpenFeatureTrial = { navController.navigate(SubRoutes.FEATURE_TRIAL) },
                    )
                }
            }

            composable(SubRoutes.FEATURE_TRIAL) {
                SubScreenScaffold(stringResource(R.string.sub_feature_trial), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.tweaks.FeatureTrialScreen(container)
                }
            }

        composable(SubRoutes.STATUS) {
            SubScreenScaffold(stringResource(R.string.sub_status_tools), onBack = navController::popBackStack) {
                StatusToolsScreen()
            }
        }

            listOf(SubRoutes.TWEAKS, SubRoutes.CONFIG, SubRoutes.MAINTENANCE, SubRoutes.GAME).forEach { toolsRoute -> composable(toolsRoute) {
                val vm: TweaksViewModel = viewModel(
                    factory = containerViewModelFactory { TweaksViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val (title, group) = when (toolsRoute) {
                    SubRoutes.CONFIG -> R.string.sub_config to "config"
                    SubRoutes.MAINTENANCE -> R.string.sub_maintenance to "maintenance"
                    SubRoutes.GAME -> R.string.sub_game to "game"
                    else -> R.string.sub_display to "display"
                }
                SubScreenScaffold(stringResource(title), onBack = navController::popBackStack) {
                    TweaksScreen(
                        state = state,
                        onTouch = vm::setTouch,
                        onVivo = { feature, restore, option, force -> vm.setVivo(feature, restore, option, force) },
                        onCollectReport = vm::collectTweakReport,
                        onExportReport = vm::exportTweakReport,
                        onDisplay = vm::setDisplay,
                        onMaintenance = vm::runMaintenance,
                        onRefreshVivo = vm::refreshVivoCapabilities,
                        onSetPhaseOffset = vm::setPhaseOffset,
                        onRestorePhaseOffsets = vm::restorePhaseOffsets,
                        onFakeBattery = vm::fakeBattery,
                        onApplyGameBoost = vm::applyGameBoost,
                        onRestoreGameBoost = vm::restoreGameBoost,
                        onSelectGameBoostApp = vm::selectGameBoostApp,
                        onForegroundApp = vm::refreshForegroundApp,
                        group = group,
                        // 进入"游戏与画面实验"时先读一次相位偏移的当前值 ——
                        // 之后无论写什么，恢复都写回这里读到的那些。
                        onEnterGame = if (group == "game") ({ vm.snapshotPhaseOffsets(); vm.refreshGameBoostStatus() }) else null,
                        onTouchRate = vm::setTouchRate,
                        onTouchItem = vm::setTouchItem,
                        onEnterDisplay = vm::probeTouchItems,
                        onTouchNumber = vm::setTouchNumber,
                        onVivoUnsupported = vm::reportPerfUnsupported,
                        onFrameInterpolation = vm::setFrameInterpolation,
                        onFrameInterpolationSr = vm::setFrameInterpolationSr,
                        onRotationSuggestion = vm::setRotationSuggestion,
                        onAutoRotate = vm::setAutoRotate,
                        onThermalBrightness = vm::setThermalBrightness,
                        onRequestPermission = vm::requestPermission,
                        onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
                    )
                }
            } }

            listOf(SubRoutes.SMART, SubRoutes.SMART_BACKUP).forEach { smartRoute -> composable(smartRoute) {
                val vm: TweaksViewModel = viewModel(factory = containerViewModelFactory { TweaksViewModel(container) })
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(
                        if (smartRoute == SubRoutes.SMART) R.string.sub_smart else R.string.sub_smart_backup,
                    ),
                    onBack = navController::popBackStack,
                ) {
                    if (smartRoute == SubRoutes.SMART) magicau.mtkcontroller.feature.tweaks.SmartPlatformScreen(state, vm) { navController.navigate(SubRoutes.SMART_BACKUP) }
                    else magicau.mtkcontroller.feature.tweaks.SmartBackupScreen(state, vm)
                }
            } }

            composable(SubRoutes.CHANGELOG) {
                val vm: ChangelogViewModel = viewModel(
                    factory = containerViewModelFactory { ChangelogViewModel(container) },
                )
                SubScreenScaffold(stringResource(R.string.changelog_title), onBack = navController::popBackStack) {
                    ChangelogScreen(vm)
                }
            }

            composable(SubRoutes.NOTIFICATIONS) {
                SubScreenScaffold(stringResource(R.string.sub_notifications), onBack = navController::popBackStack) {
                    NotificationScreen()
                }
            }

            composable(SubRoutes.DIAGNOSTICS) {
                val vm: DiagViewModel = viewModel(
                    factory = containerViewModelFactory { DiagViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_diagnostics), onBack = navController::popBackStack) {
                    DiagScreen(
                        state = state,
                        onRefresh = vm::refresh,
                        onRequestPermission = vm::requestPermission,
                        onVerify = vm::verifyApplied,
                    )
                }
            }

            composable(SubRoutes.LAB) {
                val vm: LabViewModel = viewModel(
                    factory = containerViewModelFactory { LabViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                // 编辑中按返回/×：先问一句。草稿只在内存里，退出就没了，
                // 而用户点了十几下才标完，不该因为一次误触全丢。
                var confirmExit by remember { mutableStateOf(false) }
                SubScreenScaffold(
                    title = stringResource(R.string.sub_lab),
                    onBack = { if (state.editing) confirmExit = true else navController.popBackStack() },
                    // 编辑态：左边 ×（放弃），右边 √（保存）。
                    // 两个动作分列两端，**不能都堆在右边** —— 挨着放很容易点错，
                    // 而这两个的结果正好相反（一个丢、一个存）。
                    leadingIcon = if (state.editing) Icons.Filled.Close else null,
                    leadingDescription = if (state.editing) {
                        stringResource(R.string.lab_edit_discard)
                    } else {
                        null
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (state.editing) vm.commitEditing() else vm.setEditing(true)
                            },
                        ) {
                            Icon(
                                imageVector = if (state.editing) Icons.Filled.Check else Icons.Filled.Edit,
                                contentDescription = stringResource(
                                    if (state.editing) R.string.lab_edit_done else R.string.lab_edit,
                                ),
                            )
                        }
                    },
                ) {
                    LabScreen(
                        state = state,
                        onOpenFeature = { entry -> navController.navigate(SubRoutes.labDetail(entry)) },
                        onOpenPanel = { navController.navigate(SubRoutes.LAB_PANEL) },
                        onOpenRawTest = { navController.navigate(SubRoutes.LAB_RAW) },
                        onValueChange = vm::setValue,
                        onHoldChange = vm::setHold,
                        onTest = vm::test,
                        onResetAll = vm::resetAll,
                        onClearObservations = vm::clearObservations,
                        onEditModeChange = vm::setEditing,
                        onMark = vm::mark,
                    )
                }

                if (confirmExit) {
                    AlertDialog(
                        onDismissRequest = { confirmExit = false },
                        title = { Text(stringResource(R.string.lab_edit_exit_title)) },
                        text = { Text(stringResource(R.string.lab_edit_exit_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmExit = false
                                vm.commitEditing()
                            }) { Text(stringResource(R.string.lab_edit_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                confirmExit = false
                                vm.discardEditing()
                            }) { Text(stringResource(R.string.lab_edit_discard)) }
                        },
                    )
                }
            }

            composable(
                route = SubRoutes.LAB_DETAIL,
                arguments = listOf(navArgument("entry") { type = NavType.StringType }),
            ) { backStackEntry ->
                // **必须复用一级页的 ViewModel**：`viewModel()` 默认按当前
                // NavBackStackEntry 建实例，详情页会拿到另一个 LabViewModel ——
                // 它的 onCleared 在返回列表时把所有请求释放掉，而用户只是点开看了一眼。
                // 这里显式取 LAB 那一层的 owner，两个页面共享同一份状态和生命周期。
                val labEntry = backStackEntry.arguments?.getString("entry").orEmpty()
                // 正常栈是 LAB → LAB_DETAIL；深链、标签还原或异常返回可能只剩详情页。
                // 取不到父条目时退回详情页自己的 ViewModel，至少不崩。
                val labOwner = runCatching { navController.getBackStackEntry(SubRoutes.LAB) }.getOrNull()
                val vm: LabViewModel = if (labOwner != null) {
                    viewModel(
                        viewModelStoreOwner = labOwner,
                        factory = containerViewModelFactory { LabViewModel(container) },
                    )
                } else {
                    viewModel(factory = containerViewModelFactory { LabViewModel(container) })
                }
                val state by vm.state.collectAsStateWithLifecycle()
                // 入口可能对应多项（mergeKey），所以按一级列表用的同一个键反查。
                // 先落到 val 再取下标：`链式调用` 换行后紧跟 `[key]` 会被 Kotlin
                // 当成集合字面量，报一个和真实原因无关的错。
                val byEntry = state.groups
                    .flatMap { it.second }
                    .groupBy { it.feature.mergeKey ?: it.feature.key }
                val items = byEntry[labEntry].orEmpty()
                SubScreenScaffold(
                    title = items.firstOrNull()?.feature?.labelRes
                        ?.let { stringResource(it) } ?: stringResource(R.string.sub_lab),
                    onBack = navController::popBackStack,
                ) {
                    LabDetailScreen(
                        items = items,
                        clusters = state.clusters.map { it.policy },
                        physicalMaxByCluster = state.clusters.mapNotNull { cluster ->
                            cluster.availableFreqs.maxOrNull()?.let { cluster.index to (it / 1000).toInt() }
                        }.toMap(),
                        liveThermal = state.thermalAppliedIndex
                            ?.takeIf { state.thermalAppliedSource != null }
                            ?.let { it to state.thermalAppliedSource!! },
                        onValueChange = { feature, policy, value ->
                            vm.setValue(feature, policy, value)
                        },
                        onHoldChange = { feature, hold -> vm.setHold(feature, hold) },
                        onTest = { feature -> vm.test(feature) },
                    )
                }
            }

            composable(SubRoutes.LAB_RAW) {
                val vm: RawTestViewModel = viewModel(
                    factory = containerViewModelFactory { RawTestViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_lab_raw), onBack = navController::popBackStack) {
                    RawTestScreen(
                        state = state,
                        onPolicy = vm::setPolicy,
                        onRow = vm::setRow,
                        onHold = vm::setHold,
                        onSend = vm::send,
                        onReset = vm::reset,
                    )
                }
            }

            composable(SubRoutes.APP_THERMAL) {
                val vm: AppThermalViewModel = viewModel(
                    factory = containerViewModelFactory { AppThermalViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_app_thermal), onBack = navController::popBackStack) {
                    AppThermalScreen(
                        state = state,
                        onSetRunning = vm::setRunning,
                        onShowAdd = vm::showAdd,
                        onHideAdd = vm::hideAdd,
                        onDraftPkg = vm::setDraftPkg,
                        onDraftIndex = vm::setDraftIndex,
                        onAdd = vm::addRule,
                        onRemove = vm::removeRule,
                    )
                }
            }

            composable(SubRoutes.LAB_PANEL) {
                val vm: LabPanelViewModel = viewModel(
                    factory = containerViewModelFactory { LabPanelViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_lab_panel), onBack = navController::popBackStack) {
                    LabPanelScreen(state = state, onRefresh = vm::refresh)
                }
            }

            composable(SubRoutes.ARCHIVE) {
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(R.string.archive_title),
                    onBack = navController::popBackStack,
                ) {
                    ArchiveScreen(
                        freqCompanionLabel = state.freqCompanionLabel,
                        onSetFreqCompanion = vm::setFreqCompanion,
                        onOpenTests = { navController.navigate(SubRoutes.TESTS) },
                        onOpenFeatureTrial = { navController.navigate(SubRoutes.FEATURE_TRIAL) },
                    )
                }
            }
            composable(SubRoutes.EXPORT) {
                val vm: magicau.mtkcontroller.feature.diag.ExportViewModel = viewModel(
                    factory = containerViewModelFactory {
                        magicau.mtkcontroller.feature.diag.ExportViewModel(container)
                    },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                val saveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri -> uri?.let(vm::saveTo) }
                SubScreenScaffold(stringResource(R.string.sub_export), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.diag.ExportScreen(
                        state = state,
                        onSetInclude = vm::setInclude,
                        onToggleRecording = vm::toggleRecording,
                        onSetRecordingLevel = vm::setRecordingLevel,
                        onDiscardRecovered = vm::discardRecovered,
                        onExport = vm::export,
                        onStartAdaptation = vm::startAdaptation,
                        onStopAdaptation = vm::stopAdaptation,
                        onSetBlindWrites = vm::setBlindWrites,
                        onShare = {
                            // 分享可能因为系统没有可接收的应用而失败，
                            // 所以界面上同时给了"保存到文件"和"复制路径"两条退路。
                            vm.fileForShare()?.let { file ->
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.reports", file,
                                    )
                                    val send = android.content.Intent(
                                        android.content.Intent.ACTION_SEND,
                                    ).apply {
                                        type = "application/zip"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, null),
                                    )
                                }
                            }
                        },
                        onSave = { saveLauncher.launch("diagnostic.zip") },
                        onClearLogs = vm::clearLogs,
                        onDismissMessage = vm::dismissMessage,
                    )
                }
            }
            composable(SubRoutes.LOGS) {
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val reportExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri -> uri?.let(vm::exportReport) }
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_logs), onBack = navController::popBackStack) {
                    // Probe once on entry so "复制报告" carries the device data
                    // rather than only the log lines.
                    LaunchedEffect(Unit) { vm.refreshReport() }
                    LogScreen(
                        state = state,
                        onClear = vm::clearLogEntries,
                        onCopyReport = vm::copyReport,
                        onExportReport = { reportExportLauncher.launch(vm.reportFileName()) },
                    )
                }
            }
        }

        FloatingCapsuleNavBar(
            // 和 Pager 用**同一个** [tabs]，不是各排一次。两处各列一遍的话，
            // 将来加一个标签时漏改一处，底栏和页面的顺序就错开 —— 而这种错
            // 在桌面上看不出来，只会觉得"滑动方向反了"。
            destinations = tabs,
            selectedRoute = selected.route,
            style = NavBarStyle.fromName(navStyleName),
            customColor = AppPalette.fromName(navCustomName).accent,
            // 点击和滑动同一条路，行为只由 TabRestoreMode 决定。
            onSelect = { switchTab(it) },
            // 胶囊位置从 Pager 推导，不再是底栏自己的一份状态。
            // 拖动期间读自己记的位置：Pager 的 currentPage 要到会话结束才更新，
            // 直接读它会让胶囊在整段拖动里纹丝不动。
            // **必须是 lambda，不能在这儿求值。** 这个表达式读的是 Pager 的
            // snapshot 状态，每帧都变；在 AppNavHost 的组合作用域里读它，
            // 一帧就让整个 NavHost（含四个标签页的全部内容）失效重组。
            // 传进去、让它在底栏内部读，失效范围才缩得下来。
            pageOffset = {
                if (dragSession != null) {
                    dragPosition
                } else {
                    tabPagerState.currentPage + tabPagerState.currentPageOffsetFraction
                }
            },
            onDragStart = {
                draggedFromPage = tabPagerState.currentPage
                // 自己累计"拖到第几页"，而不是从 Pager 反推。
                //
                // 为什么必须自己存：`scrollBy` 只移动像素，**逻辑页码要等会话
                // 结束才更新** —— 拖动全程 currentPage 都停在原页，松手就弹回去。
                // 官方为此提供了 updateCurrentPage(page, offset)，在会话内立即
                // 更新页码，由我们喂绝对位置而不是增量。
                dragPosition = tabPagerState.currentPage.toFloat()
                val channel = kotlinx.coroutines.channels.Channel<Float>(
                    kotlinx.coroutines.channels.Channel.CONFLATED,
                )
                dragSession = channel
                scope.launch {
                    tabPagerState.scroll {
                        // 这版 Compose 的 ScrollScope 只有 scrollBy，没有
                        // updateCurrentPage —— 所以 Pager 的逻辑页码要到会话结束
                        // 才更新。没关系：拖动期间**我们自己记的 dragPosition 才是真源**，
                        // 胶囊和松手都读它，不读 Pager 的 currentPage。
                        for (delta in channel) scrollBy(delta)
                    }
                }
            },
            onDragBy = { pageDelta ->
                if (pageWidthPx > 0) {
                    dragPosition = (dragPosition + pageDelta)
                        .coerceIn(0f, tabs.lastIndex.toFloat())
                    // 会话里要的是**像素增量**，不是绝对页位置 —— 位置由我们累计，
                    // Pager 只负责把画面移过去。
                    dragSession?.trySend(pageDelta * pageWidthPx)
                }
            },
            onSettle = { nearest, heldStill ->
                dragSession?.close()
                dragSession = null
                // nearest 是底栏按手指最后的位置算出来的"离哪一页近"，已经夹过范围。
                // 这里不再按 dragPosition 重算一遍 —— 两处各夹一次，将来改夹取规则
                // 时只改一处，另一处就悄悄给出不同的页。
                val page = when (swipeMode) {
                    TabSwipeMode.NEAREST -> nearest
                    // 停留后切换：没停够就弹回**拖动起点**，不是当前位置 ——
                    // 否则拖一半松手会停在中间那页。
                    TabSwipeMode.HOLD -> if (heldStill) nearest else draggedFromPage
                }
                val destination = tabs.getOrNull(page) ?: selectedTab
                // 三条手势（点底栏、滑页面、拖底栏）汇到同一个入口。
                //
                // 曾经滑动这条路只调 `animateScrollToPage`，指望 Pager 停住后由
                // `settledPage` 回调 `onTabChange` 再来切 —— 二级页压在标签层之上时
                // 那个收集器没在跑，`selectedTab` 和导航栈都不更新：胶囊动了，页面没动。
                switchTab(destination)
                // 只有"标签没变"时才需要自己归位。
                //
                // 标签变了的话 `selectedTab` 一变，`TabsPager` 里那个
                // `LaunchedEffect(selectedIndex)` 就会滑过去 —— 这里再滑一次是重复劳动。
                //
                // 而标签没变时那个 effect 的 key 不变、不会重跑，拖动却可能把 Pager
                // 丢在两页中间，所以这一种必须自己归位。
                //
                // **"变了他会滑"这件事依赖那个 effect 的判到位条件是对的**：
                // 它必须同时看页码和偏移（见 TabsPager）。只判 currentPage 会漏 ——
                // 拖过中线松手时 Pager 的 currentPage 已经跳到目标页，判等成立，
                // 归位动画被整段跳过，页面停在两页中间，要用户再碰一下才好。
                if (destination == selectedTab) {
                    scope.launch { tabPagerState.animateScrollToPage(tabs.indexOf(destination)) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 收起时整条滑到屏幕外。位移量给足，别留一条边露在底下。
                .offset(y = barOffsetY)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        )
    }
    }
}

/**
 * 攒够多少纵向位移才翻转底栏的显隐。
 *
 * 太小会在手指的抖动里来回翻；太大则要划很久才有反应。
 * 取 ~56dp：一次有意的滚动轻松超过，噪声远达不到。
 */
private const val NAV_BAR_HIDE_AFTER_PX = 56f

/** 四个顶层标签共用的容器路由。标签本身不再是导航目的地。 */
private const val TABS_ROUTE = "tabs"

/** 标签顺序。底栏与 Pager 都用它，避免两处各排一次而错位。 */
private val tabs: List<Destination> = FeatureFlags.tabs

/**
 * 把 ViewModel 和导航控制器接成设置页要用的那一包回调。
 *
 * **只在这里接线。** 一级页和二级页都收这个对象，所以加一项设置时
 * 需要改的只有：`SettingsActions` 加一个字段 + 这里加一行 + 真正用它那个二级页面。
 * 原来三十多个具名参数要在三个调用点各抄一遍，加一项要改三处，还容易漏。
 */
private fun settingsActions(
    vm: SettingsViewModel,
    navController: androidx.navigation.NavHostController,
    context: android.content.Context,
) = magicau.mtkcontroller.feature.settings.SettingsActions(
    onSetDefaultTab = vm::setDefaultTab,
    onSetApplyMode = vm::setApplyMode,
    onSetReapplyInterval = vm::setReapplyIntervalMs,
    onSetScreenOffPolicy = vm::setScreenOffPolicy,
    onSetHideFromRecents = vm::setHideFromRecents,
    onSetApplyOnBoot = vm::setApplyOnBoot,
    onSetFreqRangeMode = vm::setFreqRangeMode,
    onSetFreqHardLimits = vm::setFreqHardLimits,
    onSetFreqThermalCompanion = vm::setFreqThermalCompanion,
    onSetTouchBackend = vm::setTouchBackend,
    onSetBrightnessBackend = vm::setBrightnessBackend,
    onSetThemeMode = vm::setThemeMode,
    onSetMotionSpeed = vm::setMotionSpeed,
    onSetShapeStyle = vm::setShapeStyle,
    onSetPalette = vm::setPalette,
    onSetNavBarStyle = vm::setNavBarStyle,
    onSetNavBarCustomPalette = vm::setNavBarCustomPalette,
    onSetTabSwipeMode = vm::setTabSwipeMode,
    onSetCpuLayoutMode = vm::setCpuLayoutMode,
    onSetRangeControlMode = vm::setRangeControlMode,
    onSetNavBarVisibility = vm::setNavBarVisibility,
    onSetTabRestoreMode = vm::setTabRestoreMode,
    onSetCustomHue = vm::setCustomHue,
    onSetCustomConcentration = vm::setCustomConcentration,
    onSetBatteryWhitelist = vm::setBatteryWhitelist,
    onRefreshBatteryWhitelist = vm::refreshBatteryWhitelist,
    // 打开系统界面要 Context，而设置页只拿得到 navController ——
    // 导航层有 Context，所以这一步在导航层做，ViewModel 仍然不碰 Android 的界面类。
    onOpenBatterySettings = {
        vm.clearBatteryMessage()
        val opened = magicau.mtkcontroller.data.power.BatteryWhitelist.openSystemSettings(context)
        if (!opened) {
            AppLog.w("Nav", "打不开系统电池设置页")
            vm.reportBatterySettingsFailure()
        }
    },
    onOpenDisplayTweaks = { navController.navigate(SubRoutes.TWEAKS) },
    onOpenPerformanceMode = { navController.navigate(SubRoutes.CONFIG) },
    onOpenLab = { navController.navigate(SubRoutes.LAB) },
    onOpenAppThermal = { navController.navigate(SubRoutes.APP_THERMAL) },
    onOpenTests = { navController.navigate(SubRoutes.TESTS) },
    onOpenArchive = { navController.navigate(SubRoutes.ARCHIVE) },
    onOpenExport = { navController.navigate(SubRoutes.EXPORT) },
    onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
    onOpenChangelog = { navController.navigate(SubRoutes.CHANGELOG) },
    onCheckUpdate = { magicau.mtkcontroller.data.update.UpdateCenter.checkNow(vm.container) },
    onSetKeepAlive = vm::setKeepAlive,
    onSetNotifShowProfile = vm::setNotifShowProfile,
    onSetNotifShowCpu = vm::setNotifShowCpu,
    onSetNotifShowTemp = vm::setNotifShowTemp,
    onSetNotifQuickSwitch = vm::setNotifQuickSwitch,
    onOpenDiagnostics = { navController.navigate(SubRoutes.DIAGNOSTICS) },
    onOpenLogs = { navController.navigate(SubRoutes.LOGS) },
    onResetAllToDefaults = vm::resetAllToDefaults,
    onSetLogLevel = vm::setLogLevel,
    onOpenSmartBackup = { navController.navigate(SubRoutes.SMART_BACKUP) },
    onExportBackup = vm::exportBackup,
    onPrepareImport = vm::prepareImport,
    onConfirmImport = vm::confirmImport,
    onCancelImport = vm::cancelImport,
    onToggleExportCategory = vm::toggleExportCategory,
    onToggleImportCategory = vm::toggleImportCategory,
    onRequestAuth = vm::requestAuth,
)

/** 设置标签的内容：一级分类列表。 */
@Composable
private fun SettingsTab(
    container: AppContainer,
    navController: androidx.navigation.NavHostController,
) {
    val vm: SettingsViewModel = viewModel(
        factory = containerViewModelFactory { SettingsViewModel(container) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = settingsActions(vm, navController, androidx.compose.ui.platform.LocalContext.current),
        onOpenCategory = { category ->
            navController.navigate(SubRoutes.settingsCategory(category))
        },
    )
}

/**
 * 当前条目的**具体**路由：`settings/cat/GENERAL`，不是 `settings/cat/{category}`。
 *
 * `destination.route` 给的是模式，`navigate()` 不认模式 —— 传进去会当成一个字面路径，
 * 匹配不上任何 composable。所以要拿参数把占位符填回去。
 *
 * 参数取不到的（理论上不该发生）就原样留着占位符，调用方靠 `runCatching` 兜住。
 */
private fun androidx.navigation.NavBackStackEntry.concreteRoute(): String? {
    val pattern = destination.route ?: return null
    val args = arguments ?: return pattern
    var out = pattern
    for (key in args.keySet()) {
        val value = args[key] ?: continue
        out = out.replace("{$key}", value.toString())
    }
    return out
}
