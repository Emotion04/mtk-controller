package magicau.mtkcontroller.feature.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.thermal.ThermalPolicyController

/**
 * 实验室二级页：一个入口下的全部控件。
 *
 * ## 为什么要有这一层
 *
 * 一级列表原来直接铺 [FeatureCard]，一张占接近一屏，「本机探测」有 17 项，
 * 滚 17 屏才能看完一遍，而用户第一件想看的"生效了吗"反而埋在卡里。
 * 现在一级只放紧凑行（名称 + 判定 + 风险 + 实时读数），控件和说明都挪到这里。
 *
 * ## 合并入口
 *
 * `mergeKey` 相同的多项会一起进来（前台/可见 uclamp 就是两个），
 * 此时顶部多一段话说清它们差在哪儿 —— 合并的目的就是让人一眼看出
 * "这是同一个机制的两个作用对象"，而不是两个不相干的功能。
 *
 * @param items 该入口下的项，按技术分类排序。通常一个。
 */
@Composable
fun LabDetailScreen(
    items: List<LabItemState>,
    clusters: List<String>,
    liveThermal: Pair<Int, ThermalPolicyController.Source>?,
    physicalMaxByCluster: Map<Int, Int> = emptyMap(),
    onValueChange: (LabFeatureEntry, String, Int) -> Unit,
    onHoldChange: (LabFeatureEntry, Boolean) -> Unit,
    onTest: (LabFeatureEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (items.size > 1) {
            item(key = "merge-note") {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 不说"作用对象"这种词 —— top-app / foreground 对用的人来说
                        // 是基础知识，解释了反而显啰嗦。标题带上数量就够。
                        Text(
                            stringResource(R.string.lab_merge_note, items.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }

        items(items, key = { it.feature.key }) { item ->
            FeatureCard(
                item = item,
                clusters = clusters,
                liveThermal = if (item.feature.key == "thermal_policy") liveThermal else null,
                physicalMaxByCluster = physicalMaxByCluster,
                onValueChange = { policy, value -> onValueChange(item.feature, policy, value) },
                onHoldChange = { onHoldChange(item.feature, it) },
                onTest = { onTest(item.feature) },
            )
        }
    }
}

/** 二级页回调的参数。就是 [magicau.mtkcontroller.data.lab.LabFeature]，起个别名少打几遍。 */
typealias LabFeatureEntry = magicau.mtkcontroller.data.lab.LabFeature
