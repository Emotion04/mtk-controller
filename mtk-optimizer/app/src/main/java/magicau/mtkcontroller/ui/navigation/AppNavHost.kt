package magicau.mtkcontroller.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.di.containerViewModelFactory
import magicau.mtkcontroller.feature.cpu.CpuScreen
import magicau.mtkcontroller.feature.cpu.CpuViewModel
import magicau.mtkcontroller.feature.diag.DiagScreen
import magicau.mtkcontroller.feature.diag.DiagViewModel
import magicau.mtkcontroller.feature.gpu.GpuScreen
import magicau.mtkcontroller.feature.gpu.GpuViewModel
import magicau.mtkcontroller.feature.home.HomeScreen
import magicau.mtkcontroller.feature.home.HomeViewModel
import magicau.mtkcontroller.feature.lab.LabPanelScreen
import magicau.mtkcontroller.feature.lab.LabPanelViewModel
import magicau.mtkcontroller.feature.lab.LabScreen
import magicau.mtkcontroller.feature.lab.LabViewModel
import magicau.mtkcontroller.feature.notifications.NotificationScreen
import magicau.mtkcontroller.feature.profile.ProfileScreen
import magicau.mtkcontroller.feature.profile.ProfileViewModel
import magicau.mtkcontroller.feature.settings.LogScreen
import magicau.mtkcontroller.feature.settings.SettingsScreen
import magicau.mtkcontroller.feature.settings.SettingsViewModel
import magicau.mtkcontroller.feature.tweaks.TweaksScreen
import magicau.mtkcontroller.feature.tweaks.TweaksViewModel
import magicau.mtkcontroller.ui.components.FloatingCapsuleNavBar
import magicau.mtkcontroller.ui.components.SubScreenScaffold
import magicau.mtkcontroller.ui.theme.AppPalette

@Composable
fun AppNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    // The launch tab is a user preference, so the graph waits for it.
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val configured = container.settingsRepository.defaultTabRoute.first()
        startRoute = configured ?: Destination.HOME.route
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

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The diagnostics screen lives under Settings, so the bar keeps Settings lit.
    val selected = when {
        currentRoute?.startsWith(Destination.SETTINGS.route) == true -> Destination.SETTINGS
        else -> Destination.fromRoute(currentRoute)
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = route,
            // Edge-to-edge is enabled, so keep content clear of the status bar.
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            composable(Destination.HOME.route) {
                val vm: HomeViewModel = viewModel(
                    factory = containerViewModelFactory { HomeViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                // The dashboard's sampler reads sysfs once a second; run it only
                // while the tab is actually on screen.
                LifecycleResumeEffect(Unit) {
                    vm.setSamplingActive(true)
                    onPauseOrDispose { vm.setSamplingActive(false) }
                }
                HomeScreen(
                    state = state,
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

            composable(Destination.CPU.route) {
                val vm: CpuViewModel = viewModel(
                    factory = containerViewModelFactory { CpuViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                CpuScreen(
                    state = state,
                    onGovernorChange = vm::setGovernor,
                    onRange = vm::setRange,
                    onApply = vm::apply,
                    onRelease = vm::release,
                    onSaveProfile = vm::saveAsProfile,
                    onResume = vm::recheckPowerHal,
                )
            }

            composable(Destination.GPU.route) {
                val vm: GpuViewModel = viewModel(
                    factory = containerViewModelFactory { GpuViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                GpuScreen(
                    state = state,
                    onMinChange = vm::setMin,
                    onMaxChange = vm::setMax,
                    onApply = vm::apply,
                    onRelease = vm::release,
                )
            }

            composable(Destination.PROFILES.route) {
                val vm: ProfileViewModel = viewModel(
                    factory = containerViewModelFactory { ProfileViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                ProfileScreen(
                    state = state,
                    onApply = vm::apply,
                    onDelete = vm::delete,
                    onRename = vm::rename,
                    onRelease = vm::release,
                )
            }

            composable(Destination.SETTINGS.route) {
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onSetDefaultTab = vm::setDefaultTab,
                    onSetThemeMode = vm::setThemeMode,
                    onSetPalette = vm::setPalette,
                    onSetNavBarStyle = vm::setNavBarStyle,
                    onSetNavBarCustomPalette = vm::setNavBarCustomPalette,
                    onSetApplyMode = vm::setApplyMode,
                    onSetReapplyInterval = vm::setReapplyIntervalMs,
                    onSetLogLevel = vm::setLogLevel,
                    onOpenTweaks = { navController.navigate(SubRoutes.TWEAKS) },
                    onOpenDiagnostics = { navController.navigate(SubRoutes.DIAGNOSTICS) },
                    onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
                    onOpenLogs = { navController.navigate(SubRoutes.LOGS) },
                    onOpenLab = { navController.navigate(SubRoutes.LAB) },
                    onExportBackup = vm::exportBackup,
                    onPrepareImport = vm::prepareImport,
                    onConfirmImport = vm::confirmImport,
                    onCancelImport = vm::cancelImport,
                )
            }

            // The sub-screens are pure content; the nav host owns their top bar
            // so the centred title is identical across all three.
            composable(SubRoutes.TWEAKS) {
                val vm: TweaksViewModel = viewModel(
                    factory = containerViewModelFactory { TweaksViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold("实用工具", onBack = navController::popBackStack) {
                    TweaksScreen(
                        state = state,
                        onTouch = vm::setTouch,
                        onTouchRate = vm::setTouchRate,
                        onFrameInterpolation = vm::setFrameInterpolation,
                        onFrameInterpolationSr = vm::setFrameInterpolationSr,
                        onRotationSuggestion = vm::setRotationSuggestion,
                        onThermalBrightness = vm::setThermalBrightness,
                        onRequestPermission = vm::requestPermission,
                        onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
                    )
                }
            }

            composable(SubRoutes.NOTIFICATIONS) {
                SubScreenScaffold("通知管理", onBack = navController::popBackStack) {
                    NotificationScreen()
                }
            }

            composable(SubRoutes.DIAGNOSTICS) {
                val vm: DiagViewModel = viewModel(
                    factory = containerViewModelFactory { DiagViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold("诊断信息", onBack = navController::popBackStack) {
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
                SubScreenScaffold("实验室", onBack = navController::popBackStack) {
                    LabScreen(
                        state = state,
                        onOpenPanel = { navController.navigate(SubRoutes.LAB_PANEL) },
                        onValueChange = vm::setValue,
                        onHoldChange = vm::setHold,
                        onTest = vm::test,
                        onResetAll = vm::resetAll,
                        onClearObservations = vm::clearObservations,
                    )
                }
            }

            composable(SubRoutes.LAB_PANEL) {
                val vm: LabPanelViewModel = viewModel(
                    factory = containerViewModelFactory { LabPanelViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold("只读面板", onBack = navController::popBackStack) {
                    LabPanelScreen(state = state, onRefresh = vm::refresh)
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
                SubScreenScaffold("运行日志", onBack = navController::popBackStack) {
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
            destinations = Destination.entries,
            selectedRoute = selected.route,
            style = NavBarStyle.fromName(navStyleName),
            customColor = AppPalette.fromName(navCustomName).accent,
            onSelect = { destination ->
                if (selected != destination) {
                    navController.navigate(destination.route) {
                        popUpTo(Destination.HOME.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        )
    }
}
