package magicau.mtkcontroller.feature.cpu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import magicau.mtkcontroller.di.*
import magicau.mtkcontroller.feature.gpu.*

@Composable
fun PerformanceScreen(
    container: AppContainer,
    initialPage: Int = 0,
    /** 这一页是否正显示。为假时不跑周期读取。 */
    samplingActive: Boolean = true,
    /** 打开「高级设置」二级页（温控档位 + 调速器）。 */
    onOpenAdvanced: () -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = initialPage) { 2 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = pager.currentPage) {
            listOf("CPU", "GPU").forEachIndexed { index, label ->
                Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(label) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            // 切换只影响展示，不下发或释放频率。
            if (page == pager.currentPage) {
                if (page == 0) {
                    val vm: CpuViewModel = viewModel(factory = containerViewModelFactory { CpuViewModel(container) })
                    val state by vm.state.collectAsStateWithLifecycle()
                    CpuScreen(state = state, onRange = vm::setRange,
                        onApply = vm::apply, onRelease = vm::release, onEmergencyRestore = vm::emergencyRestore,
                        onToggleLoad = vm::toggleLoad,
                        onSaveProfile = vm::saveAsProfile, onReadCaps = vm::readCaps,
                        onOpenAdvanced = onOpenAdvanced,
                        // 方案标签：选中只装载不应用，应用由下面的按钮触发。
                        // 例外是 -1（"默认"）：它代表"撤掉 App 的限制"，
                        // 那本身就是一个撤除动作，不装载也不等待应用。
                        onSelectProfile = { index ->
                            if (index < 0) vm.selectDefaultProfile()
                            else state.profiles.getOrNull(index)?.let(vm::loadProfileIntoEditor)
                        },
                        onDeleteProfile = { index ->
                            state.profiles.getOrNull(index)?.let { vm.deleteProfile(it.id) }
                        },
                        onCreateExampleProfiles = vm::createExampleProfiles,
                        onRenameProfile = { index, newName ->
                            state.profiles.getOrNull(index)?.let { vm.renameProfile(it.id, newName) }
                        },
                        onResume = vm::recheckPowerHal,
                        samplingActive = samplingActive)
                } else {
                    val vm: GpuViewModel = viewModel(factory = containerViewModelFactory { GpuViewModel(container) })
                    val state by vm.state.collectAsStateWithLifecycle()
                    GpuScreen(state, vm::setMin, vm::setMax, vm::apply, vm::release)
                }
            }
        }
    }
}
