import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名只从 git 之外的文件读。keystore 与密码放在仓库根 signing/，该目录已被
// .gitignore 忽略；把密码写进这里等于提交进版本历史，而泄密的签名等于白建 ——
// 换签名会让所有用户必须卸载重装，丢失本地方案、设置和报告。
val keystorePropsFile = rootProject.file("signing/keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    val props = Properties()
    keystorePropsFile.inputStream().use { stream -> props.load(stream) }
    props
} else null

android {
    namespace = "magicau.mtkcontroller"
    compileSdk = 37

    defaultConfig {
        applicationId = "magicau.mtkcontroller"
        minSdk = 33
        targetSdk = 37
        // Bump hand-off APKs so reports identify the exact installed revision.
        versionCode = 204
        versionName = "0.5.1"
        // 证据等级低于这个值的功能在设置页不列出。
        //
        // **按构建类型定，不按“正式/非正式”定**：canary（debug）是给先锋用户试的，
        // 不该把“预计无效”的东西摆在面前；release 是交付包，列出来的都要能用 ——
        // 但发布前没法逐台真机复验，所以 release 不设门槛，靠 Evidence 标签说明。
        // 默认值跟着构建类型走；`-PminEvidence=` 可以单独覆盖（例如发一个纯验证包）。
        // 仅用于测试过期页：`-PforceExpiry=true` 的 debug 包也会进入阻断页。
        // debug 也受 30 天限制；重新构建会刷新编译时间。
        // 更新日志的公开地址（静态 JSON）。留空时用 APK 内置的那份。
        buildConfigField(
            "String",
            "CHANGELOG_URL",
            "\"" + providers.gradleProperty("changelogUrl").getOrElse(
                "https://changelog.a0v0a.cn/MTK-God/changelog.json",
            ) + "\"",
        )
        // 备用地址：主地址在国内被限速/不可达时再试一次。
        buildConfigField(
            "String",
            "CHANGELOG_URL_BACKUP",
            "\"" + providers.gradleProperty("changelogUrlBackup").getOrElse("") + "\"",
        )
        // 更新清单；公开只读地址。App 只读，不放任何密钥。
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"" + providers.gradleProperty("updateUrl")
                .getOrElse("https://changelog.a0v0a.cn/MTK-God/update.json") + "\"",
        )
        buildConfigField(
            "String",
            "UPDATE_URL_BACKUP",
            "\"" + providers.gradleProperty("updateUrlBackup").getOrElse("") + "\"",
        )
        buildConfigField(
            "String",
            "REMOTE_CONFIG_URL",
            "\"" + providers.gradleProperty("remoteConfigUrl")
                .getOrElse("https://changelog.a0v0a.cn/MTK-God/remote-config.json") + "\"",
        )
        buildConfigField(
            "Boolean",
            "FORCE_EXPIRY",
            providers.gradleProperty("forceExpiry").getOrElse("false"),
        )
        buildConfigField(
            "String",
            "MIN_EVIDENCE",
            "\"" + (providers.gradleProperty("minEvidence").getOrElse("LIKELY_USELESS")) + "\"",
        )
    }

    testOptions {
        unitTests {
            // Robolectric 要读 res/ 才能让 Context.getString 真的返回 strings.xml 里的文案。
            // 不开这个，所有资源 id 都解析不出来，测试里看到的只会是占位符。
            isIncludeAndroidResources = true
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        // 没有 keystore 时用 debug 签名顶位：只是让配置阶段能找到名字，
        // 产物仍装不到官方包上（签名不同），见 README。
        create("unsignedLike") {
            val debugSign = signingConfigs.getByName("debug")
            storeFile = debugSign.storeFile
            storePassword = debugSign.storePassword
            keyAlias = debugSign.keyAlias
            keyPassword = debugSign.keyPassword
        }
        // keystore.properties 缺失时不创建 release 签名：构建会因未签名而失败，
        // 这比静默退回 debug 签名好 —— 后者会让人在不知情的情况下发出一个
        // 任何人都能生成的签名，之后无法换成正式签名而不丢掉所有用户。
    }

    buildTypes {
        release {
            // release 开混淆与资源压缩：data/ 层的平台知识来自数月真机实测，
            // 明文打包等于把结论直接送人。
            // 规则见 proguard-rules.pro，只 keep 跨进程契约与序列化这些必需项。
            //
            // 混淆只在 release 生效：debug 保持原样，方便日常调试与抓栈。
            //
            // **只在改动 proguard-rules.pro 之后**才需要在真机上重跑序列化
            // （方案导入导出、备份还原）与 Shizuku 提权链路。没改规则就不必重跑。
            isMinifyEnabled = true
            isShrinkResources = true
            // 没有 keystore（公开源码的干净克隆）时不绑定签名：debug 仍能构建，
            // release 包会签不出正式签名、无法覆盖安装 —— 这是有意的，见上面 signingConfigs。
            // 没有 release 时必须有一个存在的名字，否则 AGP 在配置期报 "SigningConfig not found"。
            signingConfig = signingConfigs.getByName(if (keystoreProps != null) "release" else "unsignedLike")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // canary 包：把证据不足的项藏起来，见 defaultConfig 里 MIN_EVIDENCE 的说明。
            buildConfigField(
                "String",
                "MIN_EVIDENCE",
                "\"" + (providers.gradleProperty("minEvidence").getOrElse("WRITTEN")) + "\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 的字节码版本必须和上面那一行一致。
    //
    // 不设的话 Kotlin 跟着 Gradle 守护进程的 JDK 走 —— 这台机器是 JDK 25，
    // 于是 Kotlin 出 Java 25 字节码（class file major version 69），而 Java 源码
    // 是 17。两边不一致平时看不出问题（DEX 编译器都收），但 Robolectric 的
    // 插桩 classloader 只认到 Java 21，一跑测试就炸
    // "Unsupported class file major version 69"。
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

// Robolectric 4.14.1 自带 ASM 9.7.1，只认到 Java 24 字节码。
// 而 Gradle 守护进程跑在 JDK 25 上，插桩时要读 JDK 自己的类（v69），
// 于是每个测试都在 teardown 里炸
// "Unsupported class file major version 69" —— 断言其实都跑过了，挂在收尾。
// ASM 9.9 起支持 Java 25。ASM 只在测试类路径上，不进 APK。
configurations.configureEach {
    resolutionStrategy {
        force("org.ow2.asm:asm:9.9.1")
        force("org.ow2.asm:asm-tree:9.9.1")
        force("org.ow2.asm:asm-analysis:9.9.1")
        force("org.ow2.asm:asm-commons:9.9.1")
        force("org.ow2.asm:asm-util:9.9.1")
    }
}

// ---------------------------------------------------------------------------
// 编译时间
//
// 版本有效期从**这次构建**算，不是从安装时间算：时间戳编译进 APK，卸载重装同一个包
// 不会重置。用生成源码而不是配置期的时间：配置缓存会把配置期的值缓存下来，
// 重新构建可能拿到几个月前的旧时间。
//
// 生成 Java 常量，走 Variant API 的 generated source，依赖关系由 AGP 管理。
// 任务每次构建都会跑（输出目录由任务自己生成）。
// ---------------------------------------------------------------------------
val buildTimeDir = layout.buildDirectory.dir("generated/buildtime")

abstract class GenerateBuildTime : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("magicau/mtkcontroller")
        dir.mkdirs()
        dir.resolve("AppBuildTime.java").writeText(
            """
            package magicau.mtkcontroller;

            /** 由构建生成，勿手改。 */
            public final class AppBuildTime {
                private AppBuildTime() {}
                public static final long MS = ${System.currentTimeMillis()}L;
            }
            """.trimIndent() + System.lineSeparator(),
        )
    }
}

val generateBuildTime = tasks.register<GenerateBuildTime>("generateBuildTime") {
    outputDir.set(buildTimeDir)
}
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateBuildTime,
            GenerateBuildTime::outputDir,
        )
    }
}

// 规范只有真的跑到才算规范 —— 靠人记得手动执行等于没有。挂在 `preBuild` 上，
// 让每次构建都过一遍；检查内容见 docs/dev/wording.md 与 docs/CONTRIBUTING.md。
//
// 用 Gradle 自带的 `Exec` 而不是 `doLast {}`：后者会捕获构建脚本对象，
// 使配置缓存失效（本仓库开着配置缓存）。
//
// 路径在配置期算好 —— 执行期再读 `rootProject` 同样会破坏配置缓存。
// 检查脚本自己按脚本位置解析仓库根，所以工作目录不影响结果。
val repoRoot = rootProject.projectDir.parentFile

val checkDocs by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查文档链接、锚点、总索引可达性与代码标识符"
    val script = repoRoot.resolve("tools/check_docs.py")
    commandLine("python", script.absolutePath)
    // 只发布源码的仓库里没有这个脚本：Closure 只比较路径，配置缓存可以序列化。
    inputs.files(script)
    onlyIf("检查脚本存在") { script.isFile }
    // 只发布源码的仓库里没有 docs/，没有可核对的文案基线，检查器以 2 退出不算错。
    isIgnoreExitValue = true
}

val checkCopy by tasks.registering(Exec::class) {
    group = "verification"
    description = "检查资源引用、分层文案审计与基线回归"
    val script = repoRoot.resolve("tools/check_strings.py")
    commandLine("python", script.absolutePath)
    // 同上。
    inputs.files(script)
    onlyIf("检查脚本存在") { script.isFile }
    // 同上：没有 docs/ 时检查器以 2 退出。
    isIgnoreExitValue = true
}

// 更新日志以根目录 CHANGELOG.md 为唯一来源：构建前生成内置 JSON。
// 不手工维护 assets/changelog.json，也不再依赖 remote 才会有内容。
val changelogFile = repoRoot.resolve("CHANGELOG.md")
// 有源文件才生成；没有（只发布源码的仓库）时 preBuild 不依赖这个任务。
val hasChangelogSource = changelogFile.isFile
val changelogOutDir = layout.buildDirectory.dir("generated/changelog")
val generateChangelog by tasks.registering(org.gradle.api.tasks.Exec::class) {
    // 源码快照里没有 CHANGELOG.md / 生成脚本：跳过，内置日志为空，远端日志照常可用。
    val generator = repoRoot.resolve("tools/make_changelog.py")
    // 有源文件才生成；没有（只发布源码的仓库）时由下面的 dependsOn 条件跳过整个任务。
    inputs.files(changelogFile, generator)
    outputs.file(changelogOutDir.map { it.file("changelog.json") })
    commandLine(
        "python",
        repoRoot.resolve("tools/make_changelog.py").absolutePath,
        "--from-changelog", changelogFile.absolutePath,
        "--out", changelogOutDir.get().file("changelog.json").asFile.absolutePath,
    )
}
android.sourceSets.getByName("main").assets.srcDir(changelogOutDir.get().asFile)
if (hasChangelogSource) tasks.named("preBuild") { dependsOn(generateChangelog) }

// 发布的远程地址以 release/config.jsonc 为准；这里核对 buildConfigField 的默认值没有跑偏，
// 免得改了配置地址、App 却还读旧位置。`-PchangelogUrl=` 等覆盖时跳过对应项。
val checkReleaseConfig by tasks.registering(org.gradle.api.tasks.Exec::class) {
    group = "verification"
    description = "检查 buildConfigField 的远程地址默认值与 release/config.jsonc 一致"
    val script = repoRoot.resolve("tools/check_release_config.py")
    commandLine("python", script.absolutePath)
    inputs.files(script, repoRoot.resolve("release/config.jsonc"))
    outputs.upToDateWhen { false }
    // 它比对的是发布配置；源码快照里没有 release/config.jsonc，脚本以 2 退出不是错误。
    isIgnoreExitValue = true
    for ((key, _) in listOf("changelogUrl", "updateUrl", "remoteConfigUrl").map { it to "" }) {
        providers.gradleProperty(key).orNull?.let { environment("GRADLE_PROP_$key", it) }
    }
}
tasks.named("preBuild") { dependsOn(checkReleaseConfig) }

// 没装 Python 的机器可以用 `-PskipChecks` 跳过；正常开发不要用。
val skipChecks = providers.gradleProperty("skipChecks").isPresent
if (!skipChecks) {
    tasks.named("preBuild") { dependsOn(checkDocs, checkCopy) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 单元测试里的 Context 只能靠它。`CpuControl` 和 `SmartPlatform.report`
    // 都拿 Context 去 getString，而 Context 有 103 个抽象方法，纯 JUnit 伪造不出来。
    // 不改成注入解析器是因为那要动 CPU 控制路径 —— 那条路径不许为测试便利而改。
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
