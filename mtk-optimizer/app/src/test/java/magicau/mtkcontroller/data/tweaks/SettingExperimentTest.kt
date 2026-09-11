package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 桌面假 Settings 后端：验证手机脚本的实际控制流，不模拟厂商硬件效果。 */
class SettingExperimentTest {
    private fun runCase(failWrite: Boolean) {
        val shell = System.getenv("TWEAK_TEST_SHELL") ?: "/bin/sh"
        assumeTrue("需要 sh 或设置 TWEAK_TEST_SHELL", File(shell).isFile)
        val dir = Files.createTempDirectory("setting-experiment").toFile()
        try {
            File(dir, "pem_htp_level").writeText("1")
            val before = mapOf("system pem_htp_level" to "1", "system pem_htp_thred" to "null")
            val mock = """
                export PATH="/usr/bin:/bin:${'$'}PATH"
                timeout() { shift; "${'$'}@"; }
                sleep() { :; }
                settings() {
                    op="${'$'}1"; key="${'$'}3"; value="${'$'}4"
                    case "${'$'}op" in
                        get) if [ -f "${'$'}key" ]; then cat "${'$'}key"; else printf null; fi ;;
                        put) if [ "${if (failWrite) "yes" else "no"}" = yes ] && [ "${'$'}key" = pem_htp_thred ]; then return 9; fi
                             printf '%s' "${'$'}value" > "${'$'}key" ;;
                        delete) rm -f "${'$'}key" ;;
                    esac
                }
            """.trimIndent()
            val script = File(dir, "run.sh")
            script.writeText(mock + "\n" + SettingExperiment.script(TweakValues.htp("high")!!, before))
            val process = ProcessBuilder(shell, script.absolutePath.replace('\\', '/')).directory(dir).redirectErrorStream(true).start()
            assertTrue("脚本不得无限等待", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, if (failWrite) 3 else 0, process.exitValue())
            assertEquals("1", File(dir, "pem_htp_level").readText())
            assertFalse("原本缺失的键必须删除", File(dir, "pem_htp_thred").exists())
            assertEquals(!failWrite, output.contains("[experiment-applied]"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun successfulExperimentRestoresExistingAndMissingKeys() = runCase(false)
    @Test fun partialWriteFailureStillRestoresBothKeys() = runCase(true)
}
