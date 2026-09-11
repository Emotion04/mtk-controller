package magicau.mtkcontroller.data.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 编译状态判定的单元测试。
 *
 * 这些判定错了会让用户误判"编译过没有"，所以纯逻辑部分要钉住。
 * 真正读文件的部分依赖设备，不在这里测。
 *
 * 用 Robolectric 是为了拿到真 Context 去解析 `Message` —— `note` 已经是
 * `Message` 而不是拼好的 `String`（数据层不该依赖资源系统，构造 `Message`
 * 也不需要 Context）。断言仍然按**人能读懂的那句话**写，不是按资源 id。
 */
@RunWith(RobolectricTestRunner::class)
class CompileStatusTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun state(
        odexKb: Long? = null,
        vdexKb: Long? = null,
        apkMtimeMs: Long? = null,
        odexMtimeMs: Long? = null,
        profileBytes: Long? = null,
    ) = CompileStatus.State(
        apkPath = "/data/app/x/base.apk",
        odexKb = odexKb,
        vdexKb = vdexKb,
        apkMtimeMs = apkMtimeMs,
        odexMtimeMs = odexMtimeMs,
        profileBytes = profileBytes,
        note = CompileStatus.note(odexKb, vdexKb, apkMtimeMs, odexMtimeMs, profileBytes),
    )

    @Test
    fun `没有任何产物时算未编译`() {
        val s = state(odexKb = null, vdexKb = null, profileBytes = 1_024)
        assertFalse(s.hasCompiledArtifact)
        assertFalse(s.compiledAfterUpdate)
        assertTrue(s.note.resolve(context).contains("没有编译产物"))
    }

    @Test
    fun `只有验证产物时报未编译而不是读不到时间`() {
        // vdex 只是验证信息；没有 odex 时编译时间读不到是正常的，
        // 但不能因此把它说成"读不到"，那会让用户以为本机不支持。
        val s = state(odexKb = null, vdexKb = 4_096, profileBytes = 1_024)
        assertFalse(s.hasCompiledArtifact)
        assertTrue(s.note.resolve(context).contains("只有验证产物，未编译"))
        assertFalse(s.note.resolve(context).contains("读不到编译时间"))
    }

    @Test
    fun `odex 晚于应用更新算已编译`() {
        val s = state(
            odexKb = 8_192, vdexKb = 4_096,
            apkMtimeMs = 1_000, odexMtimeMs = 5_000, profileBytes = 2_048,
        )
        assertTrue(s.hasCompiledArtifact)
        assertTrue(s.compiledAfterUpdate)
        assertFalse(s.stale)
        assertTrue(s.note.resolve(context).contains("编译晚于上次应用更新"))
    }

    @Test
    fun `odex 早于应用更新算过期`() {
        val s = state(odexKb = 8_192, apkMtimeMs = 9_000, odexMtimeMs = 2_000)
        assertTrue(s.stale)
        assertFalse(s.compiledAfterUpdate)
        assertTrue(s.note.resolve(context).contains("编译早于上次应用更新"))
    }

    @Test
    fun `读不到应用更新时间不判过期`() {
        val s = state(odexKb = 8_192, apkMtimeMs = null, odexMtimeMs = 2_000)
        assertFalse(s.stale)
        assertTrue(s.compiledAfterUpdate)
    }

    @Test
    fun `使用记录读不到要说出来`() {
        val s = state(odexKb = 8_192, profileBytes = null)
        assertTrue(s.note.resolve(context).contains("使用记录读不到"))
    }

    @Test
    fun `使用记录为空要说出来`() {
        val s = state(odexKb = 8_192, profileBytes = 0L)
        // speed-profile 依赖使用记录；空文件会让它静默退化，这是最该提醒的一种。
        assertTrue(s.note.resolve(context).contains("使用记录为空"))
    }

    @Test
    fun `vdex 为 0 时不写进结论`() {
        val s = state(odexKb = 8_192, vdexKb = 0L, profileBytes = 512)
        assertTrue(s.note.resolve(context).contains("odex"))
        assertFalse(s.note.resolve(context).contains("vdex"))
    }

    @Test
    fun `note 不会声称编译是否值得`() {
        val s = state(odexKb = 8_192, vdexKb = 4_096, profileBytes = 512)
        // 判断留给用户，状态只报事实。
        listOf("建议", "不值得", "应该", "无需").forEach { word ->
            assertFalse("note 里不该出现「$word」: ${s.note.resolve(context)}", s.note.resolve(context).contains(word))
        }
    }

    @Test
    fun `结论用 MiB 报体积`() {
        val s = state(odexKb = 20_480, vdexKb = 10_240, profileBytes = 2_048)
        assertTrue(s.note.resolve(context).contains("odex 20 MiB"))
        assertTrue(s.note.resolve(context).contains("vdex 10 MiB"))
        assertTrue(s.note.resolve(context).contains("使用记录 2 KiB"))
    }
}
