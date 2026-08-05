plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val pmclVersion = providers.gradleProperty("pmcl.version").orElse("1.3.0")

kotlin {
    jvm("desktop") {
        // 强制编译为 Java 21 字节码（版本 65），确保 Windows 上 Java 21 可运行
        // 否则用 JDK 24 编译时 Kotlin 会 fallback 到 JVM_22，Java 21 运行时报 UnsupportedClassVersionError
        compilations.all {
            kotlinOptions.jvmTarget = "21"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlin.coroutines.core)
        }

        val desktopMain by getting {

            dependencies {
                implementation(compose.desktop.currentOs)
                // 引入 Windows / Linux 平台 native 库，使 fat jar 可跨平台运行 Compose UI
                implementation(compose.desktop.windows_x64)
                implementation(compose.desktop.linux_x64)
                implementation(compose.desktop.linux_arm64)

                implementation(project(":core"))
                implementation(project(":cli"))
                implementation(project(":video"))
                implementation(project(":music"))

                // WebSocket 服务宿主（伴随模式）
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.gson)

                // 条码/二维码生成（配对码展示）
                implementation(libs.zxing.core)
                implementation(libs.zxing.javase)

                // 自定义背景视频解码（FFmpeg 原生库由 :video 模块的 runtime 依赖提供）
                implementation(libs.javacv)

                // JavaFX WebView（Wiki 内嵌浏览器：JFXPanel 嵌入 SwingPanel）
                // openjfx 必须显式指定 OS+架构 classifier，否则：
                // 1. 无 classifier 只拉到空壳 pom，编译报 unresolved javafx.*
                // 2. mac classifier 默认 x86_64，Apple Silicon 会加载失败
                //    (libprism_es2.dylib: incompatible architecture have x86_64 need arm64)
                //    导致 QuantumRenderer 初始化失败 → 整个窗口卡死
                // 3. 每个模块的 classifier jar 独立，传递依赖不带 classifier，需全部显式声明
                // 注意：不可把多平台 classifier 同时打进 fat jar（类重复）；Wiki 仅当前构建平台可用
                val fxVer = libs.versions.javafx.get()
                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                val fxClassifier = when {
                    osName.contains("mac") && osArch.contains("aarch64") -> "mac-aarch64"
                    osName.contains("mac") -> "mac"
                    osName.contains("windows") -> "win"
                    osName.contains("linux") && osArch.contains("aarch64") -> "linux-aarch64"
                    osName.contains("linux") -> "linux"
                    else -> "linux"
                }
                listOf("javafx-base", "javafx-graphics", "javafx-controls", "javafx-web", "javafx-swing", "javafx-media").forEach {
                    implementation("org.openjfx:$it:$fxVer:$fxClassifier")
                }
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.pmcl.ui.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Pkg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "pmcl"
            packageVersion = pmclVersion.get()

            // jlink 默认仅按显式 module 依赖打包，反射/运行时加载的模块需手动声明
            // 缺失会导致 NoClassDefFoundError: java/lang/management/ManagementFactory 等
            modules(
                "java.management",      // ManagementFactory（Ktor/JavaFX 运行时检测）
                "java.net.http",        // Ktor CIO engine / HTTP 客户端
                "java.naming",          // JNDI（部分库初始化引用）
                "java.sql",             // JDBC（如未来扩展）
                "java.transaction.xa",  // 事务（部分库引用）
                "java.scripting",       // Nashorn/ScriptEngine
                "java.desktop",         // AWT/Swing（FileDialog、JFXPanel 宿主）
                "java.instrument",      // agent 支持（部分启动器场景）
                "jdk.crypto.cryptoki",  // 加密 provider（HTTPS 握手需要）
                "jdk.crypto.ec",        // EC 曲线（TLS 1.3 / Xbox 认证）
                "jdk.management",       // jdk.management.* 子包
                "jdk.unsupported",      // sun.misc.Unsafe（部分 native / Netty 直接使用）
                "jdk.security.auth"     // JAAS（部分认证流程）
            )

            windows {
                menuGroup = "PMCL"
                upgradeUuid = "7f5e9c8a-3b2d-4e1f-9a8c-1b6d5e4f7a2c"
            }
            macOS {
                bundleID = "com.pmcl.ui"
                iconFile.set(project.file("src/commonMain/resources/pmcl.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>PMCL 需要访问本地网络，以检测 Minecraft 局域网世界并使用陶瓦联机创建或加入房间。</string>
                    """.trimIndent()
                }
            }
            linux {
                packageName = "pmcl"
            }
        }
    }
}

// 跨平台 fat jar：包含所有依赖（含 Windows native 库），Windows 上 java -jar 即可运行
tasks.register<Jar>("fatJar") {
    dependsOn("desktopJar")
    archiveBaseName.set("pmcl")
    archiveClassifier.set("all")
    archiveVersion.set(pmclVersion.get())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "com.pmcl.ui.MainKt",
            "Implementation-Version" to pmclVersion.get()
        )
    }

    // 合入 desktop 主类输出
    from(tasks.named<Jar>("desktopJar").get().archiveFile.map { zipTree(it) })

    // 合入运行时所有依赖 jar
    from({
        configurations.named("desktopRuntimeClasspath").get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    // 排除签名文件冲突
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // 排除所有 module-info.class（fat jar 作为 unnamed module 运行，否则 Java 9+ 启动时报 JNI 错误）
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    // M3 合规修复：保留开源协议元数据，满足 Apache 2.0 / MIT / BSD 等协议的归属要求
    // - META-INF/maven/** 包含 groupId/artifactId/version 的 POM 元数据（各依赖路径不同，不冲突，自动合并）
    // - META-INF/LICENSE* / META-INF/NOTICE* 文件由 duplicatesStrategy = EXCLUDE 自动保留第一份副本
    // - 不排除 LICENSE / NOTICE / pom.properties / pom.xml，确保协议合规
}
