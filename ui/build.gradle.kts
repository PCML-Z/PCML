plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

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
                // 引入 Windows 平台 native 库，使 fat jar 可在 Windows 上运行
                implementation(compose.desktop.windows_x64)

                implementation(project(":core"))
                implementation(project(":cli"))
                implementation(project(":video"))

                // WebSocket 服务宿主（伴随模式）
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.gson)

                // 条码/二维码生成（配对码展示）
                implementation(libs.zxing.core)
                implementation(libs.zxing.javase)

                // 内嵌 PTY 终端（运行 OpenCode TUI）
                implementation(libs.pty4j)
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
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "pmcl"
            packageVersion = "1.0.0"

            windows {
                menuGroup = "PMCL"
                upgradeUuid = "7f5e9c8a-3b2d-4e1f-9a8c-1b6d5e4f7a2c"
            }
            macOS {
                bundleID = "com.pmcl.ui"
                iconFile.set(project.file("src/commonMain/resources/pmcl.icns"))
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
    archiveVersion.set("1.0.0")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to "com.pmcl.ui.MainKt")
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
}
