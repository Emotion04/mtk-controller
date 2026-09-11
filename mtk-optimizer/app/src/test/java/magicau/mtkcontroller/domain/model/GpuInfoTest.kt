package magicau.mtkcontroller.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GpuInfo.controllable] 的判定测试。
 *
 * 钉住的铁律：**探到接口不等于能控制。** 本机 `/sys/class/devfreq/mtk-mali` 过得去
 * `test -e`，但目录里每个文件对 shell 都是 SELinux 拒绝，扫出来的结果是
 * 「有通道、没有频率表」。把它当可控，界面上就会多一个只会回答"映射失败"的滑条 ——
 * 比直接说这台机器没有 GPU 控制更糟。
 */
class GpuInfoTest {

    @Test
    fun `探到通道但读不到频率表 不可控`() {
        val info = GpuInfo(
            channel = GpuChannel.DEVFREQ,
            availableFreqs = emptyList(),
            currentFreqKhz = null,
        )
        assertFalse(info.controllable)
    }

    @Test
    fun `探到通道且读得到频率表 才可控`() {
        val info = GpuInfo(
            channel = GpuChannel.DEVFREQ,
            availableFreqs = listOf(300_000_000, 600_000_000, 900_000_000),
            minFreqKhz = 300_000_000,
            maxFreqKhz = 900_000_000,
        )
        assertTrue(info.controllable)
    }

    @Test
    fun `没有通道一律不可控`() {
        assertFalse(GpuInfo(channel = GpuChannel.NONE, availableFreqs = listOf(1)).controllable)
    }
}
