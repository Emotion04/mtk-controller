package magicau.mtkcontroller.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExpiryTest {

    private val day = 24L * 60 * 60 * 1000
    private val build = 1_700_000_000_000L

    @Test
    fun `未到期可用`() {
        assertFalse(AppExpiry.isExpired(now = build + 29 * day, buildTime = build))
    }

    @Test
    fun `正好三十天算到期`() {
        assertTrue(AppExpiry.isExpired(now = build + 30 * day, buildTime = build))
    }

    @Test
    fun `过期后不可用`() {
        assertTrue(AppExpiry.isExpired(now = build + 31 * day, buildTime = build))
    }

    @Test
    fun `构建时间缺失时不拦截`() {
        assertFalse(AppExpiry.isExpired(now = build, buildTime = 0L))
    }

    @Test
    fun `时间被拨回构建之前算异常`() {
        assertTrue(AppExpiry.isExpired(now = build - 2 * day, buildTime = build))
    }

    @Test
    fun `强制标记用于测试过期页`() {
        assertTrue(AppExpiry.isExpired(now = build, buildTime = build, force = true))
    }

    @Test
    fun `一天以内的时钟差不算异常`() {
        assertFalse(AppExpiry.isExpired(now = build - day / 2, buildTime = build))
    }
}
