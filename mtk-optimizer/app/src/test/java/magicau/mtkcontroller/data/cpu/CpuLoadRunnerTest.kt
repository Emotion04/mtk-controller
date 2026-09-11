package magicau.mtkcontroller.data.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载器的启停与"必须在 App 进程里跑"这条约束。
 */
class CpuLoadRunnerTest {

    @Test
    fun `启动后进入运行态并可再次启停`() {
        CpuLoadRunner.stop() // 先清干净，防止别的用例留下状态
        CpuLoadRunner.start(cores = listOf(0))
        assertTrue("启动后应为运行态", CpuLoadRunner.state.value.active)
        assertTrue("应记录线程数", CpuLoadRunner.state.value.threads >= 1)
        assertTrue("应记下占用的核", CpuLoadRunner.state.value.cores.isNotEmpty())
        CpuLoadRunner.tick()
        assertTrue("tick 后秒数应增加", CpuLoadRunner.state.value.seconds >= 1)
        CpuLoadRunner.stop()
        assertFalse("停止后不应仍是运行态", CpuLoadRunner.state.value.active)
    }

    @Test
    fun `重复启动不叠加线程`() {
        CpuLoadRunner.stop()
        CpuLoadRunner.start(cores = listOf(0))
        val first = CpuLoadRunner.state.value.threads
        CpuLoadRunner.start(cores = listOf(0))
        assertEquals("已在跑时再启动不应叠加", first, CpuLoadRunner.state.value.threads)
        CpuLoadRunner.stop()
    }


    @Test fun `可以只压指定的核`() {
        CpuLoadRunner.stop()
        CpuLoadRunner.start(cores = listOf(7, 7, 4))
        val st = CpuLoadRunner.state.value
        assertEquals("重复核要去重", listOf(4, 7), st.cores)
        assertEquals("线程数按去重后算", 2, st.threads)
        CpuLoadRunner.stop()
    }
}
