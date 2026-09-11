package magicau.mtkcontroller.data.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import magicau.mtkcontroller.data.exec.DaemonExec

/**
 * [DaemonExec] 输出解析的单元测试。
 *
 * 这套包装是"把命令送进 Shizuku 用户服务"的唯一入口，解析错一次会让所有只读指标
 * 静默变成读不到，所以格式必须钉死。
 */
class DaemonExecTest {

    @Test
    fun `正常输出取 stdout 和退出码`() {
        val out = DaemonExec.parse("1\n2\n3\n[exit] 0")!!
        assertEquals("1\n2\n3", out.stdout)
        assertEquals("", out.stderr)
        assertEquals(0, out.exit)
    }

    @Test
    fun `stderr 单独取出来`() {
        val out = DaemonExec.parse("partial\n[stderr] something failed\n[exit] 1")!!
        assertEquals("partial", out.stdout)
        assertEquals("something failed", out.stderr)
        assertEquals(1, out.exit)
    }

    @Test
    fun `没有结束标记说明走的是异常分支`() {
        // 用户服务把异常包成 "ERROR: ..."，没有 [exit]；不能当成一次成功的执行。
        assertNull(DaemonExec.parse("ERROR: RuntimeException: boom"))
        assertNull(DaemonExec.parse(null))
    }

    @Test
    fun `stderr 里带换行也不会吃掉退出码`() {
        val out = DaemonExec.parse("out\n[stderr] line1\nline2\nline3\n[exit] 2")!!
        assertEquals("out", out.stdout)
        assertEquals("line1\nline2\nline3", out.stderr)
        assertEquals(2, out.exit)
    }

    @Test
    fun `stdout 为空但退出码为零不等于失败`() {
        // getprop 读未设置的属性就是这种：空串，退出码 0。要和"读不到"分开。
        val out = DaemonExec.parse("\n[exit] 0")!!
        assertEquals("", out.stdout)
        assertFalse(out.stderr.isNotEmpty())
        assertEquals(0, out.exit)
        assertTrue(out.exit == 0)
    }

    @Test
    fun `参数加引号后特殊字符不被 shell 解释`() {
        assertEquals("'simple'", DaemonExec.quote("simple"))
        assertEquals("''", DaemonExec.quote(""))
        // 单引号要拆成 '\'' 三段，否则路径带撇号会截断命令。
        assertEquals("'a'\\''b'", DaemonExec.quote("a'b"))
    }
}
