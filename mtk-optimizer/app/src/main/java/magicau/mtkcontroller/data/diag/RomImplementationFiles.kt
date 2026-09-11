package magicau.mtkcontroller.data.diag

import java.io.File
import magicau.mtkcontroller.data.probe.ProbeProcess

/** 只枚举系统分区的实现文件，不执行库、加载类或调用厂商控制事务。 */
object RomImplementationFiles {
    private val keywords = Regex("power|perf|thermal|vboost|symphony", RegexOption.IGNORE_CASE)
    fun relevant(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return if ("/framework/" in path) {
            name.substringAfterLast('.') in listOf("jar", "odex", "vdex") && (keywords.containsMatchIn(name) ||
                name.contains("vivo", true) || name.substringBeforeLast('.') in listOf("services", "framework"))
        } else keywords.containsMatchIn(name) &&
            (name.endsWith(".so") || name.endsWith(".rc") || name.endsWith(".xml") || "/bin/" in path)
    }

    fun discover(): Pair<List<String>, String> {
        val paths = sortedSetOf<String>()
        val report = StringBuilder("ROM_IMPLEMENTATION_V1 只读枚举；未匹配文件不代表不存在\n")
        for (partition in listOf("/vendor", "/odm", "/system", "/system_ext", "/product")) {
            for (sub in listOf("lib64", "lib", "bin", "framework", "etc/init", "etc/vintf")) {
                val root = File("$partition/$sub")
                fun visit(dir: File, depth: Int) {
                    val children = dir.listFiles()
                    if (children == null) {
                        report.appendLine("UNREADABLE_OR_ABSENT ${dir.path}")
                        return
                    }
                    report.appendLine("DIRECTORY ${dir.path} entries=${children.size}")
                    for (file in children.sortedBy { it.name }) {
                        if (file.isDirectory && depth < 2 && file.canonicalPath == file.absolutePath) visit(file, depth + 1)
                        else if (file.isFile && relevant(file.path)) paths.add(file.path)
                    }
                }
                try { visit(root, 0) }
                catch (e: Exception) { report.appendLine("FAILED ${root.path} ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        report.appendLine("DISCOVERED ${paths.size}；目录最多向下两层；不包含其他进程内存或私有数据")
        report.appendLine("BOOTCLASSPATH=${System.getenv("BOOTCLASSPATH")}")
        report.appendLine("SYSTEMSERVERCLASSPATH=${System.getenv("SYSTEMSERVERCLASSPATH")}")
        report.appendLine("SERVICE_IDENTITIES")
        report.appendLine(ProbeProcess.run(listOf("/system/bin/service", "list"), 2500, 48000).render())
        return paths.toList() to report.toString()
    }
}
