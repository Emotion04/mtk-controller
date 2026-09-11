package magicau.mtkcontroller.data.sysfs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Sysfs.classifyCatFailure] 的判定测试。
 *
 * 钉住的铁律：**读不到不等于不存在。** 这台机器上 `/proc/perfmgr/` 整个目录被
 * SELinux 挡着，实验室据此把「温控策略索引」滑杆判成"本机没有这个节点"并置灰，
 * 而它背后的 PowerHAL opcode `0x03000000` 本来就是通的。
 * 只有 shell 明确说"没有这个文件"才允许禁用控件。
 */
class SysfsStateTest {

    @Test
    fun `真的不存在才判 ABSENT`() {
        assertEquals(
            Sysfs.NodeState.ABSENT,
            Sysfs.classifyCatFailure("cat: /proc/perfmgr/thermal_policy: No such file or directory"),
        )
        assertEquals(
            Sysfs.NodeState.ABSENT,
            Sysfs.classifyCatFailure("cat: /x/y: Not a directory"),
        )
    }

    @Test
    fun `权限拒绝判 UNREADABLE 而不是 ABSENT`() {
        assertEquals(
            Sysfs.NodeState.UNREADABLE,
            Sysfs.classifyCatFailure("cat: /proc/perfmgr/thermal_policy: Permission denied"),
        )
        assertEquals(
            Sysfs.NodeState.UNREADABLE,
            Sysfs.classifyCatFailure(
                "cat: /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq: Permission denied",
            ),
        )
    }

    @Test
    fun `不认识的错误一律 UNREADABLE 不猜 ABSENT`() {
        // 陌生错误永远不许禁用控件：猜错的代价是藏起一个本来能用的功能。
        listOf(
            "cat: /x: Too many levels of symbolic links",
            "cat: /x: Input/output error",
            "sh: something unexpected",
            "",
            "随便一段不像错误的内容",
        ).forEach { message ->
            assertEquals(
                "不该对陌生错误判 ABSENT: $message",
                Sysfs.NodeState.UNREADABLE,
                Sysfs.classifyCatFailure(message),
            )
        }
    }

    @Test
    fun `多行输出按整段内容判定`() {
        // 两种错误同时出现时，先匹配"不存在"—— 宁可放行，不可错杀。
        val both = "cat: /proc/perfmgr/thermal_policy: Permission denied\n" +
            "cat: /proc/perfmgr/thermal_policy: No such file or directory"
        assertEquals(Sysfs.NodeState.ABSENT, Sysfs.classifyCatFailure(both))

        // 只有权限那一行，另一行是无关内容，仍然是读不到。
        val onlyPerm = "Permission denied\nsome other line"
        assertEquals(Sysfs.NodeState.UNREADABLE, Sysfs.classifyCatFailure(onlyPerm))
    }

    @Test
    fun `能打开但是目录 判 PRESENT 而不是 UNREADABLE`() {
        // 目录要判"存在且搜得动"，只能靠打开它的结果：
        // 权限位全开（dr-xr-xr-x）而 SELinux 拒搜索时，stat 照样成功。
        assertEquals(
            Sysfs.NodeState.PRESENT,
            Sysfs.classifyCatFailure("cat: /proc/perfmgr: Is a directory"),
        )
    }

    @Test
    fun `父目录读不到时 ENOENT 不能证明节点不存在`() {
        // 真机实测：/proc/perfmgr 是 Permission denied，里面的 thermal_policy
        // 却是 No such file or directory。SELinux 拒掉目录搜索后，里层文件报
        // ENOENT 而不是 EACCES，内核故意如此以免泄露存在性。
        assertEquals(false, Sysfs.absenceIsProven(Sysfs.NodeState.UNREADABLE))
        assertEquals(false, Sysfs.absenceIsProven(Sysfs.NodeState.ABSENT))
    }

    @Test
    fun `父目录可读时 ENOENT 才作数`() {
        assertEquals(true, Sysfs.absenceIsProven(Sysfs.NodeState.PRESENT))
    }
}
