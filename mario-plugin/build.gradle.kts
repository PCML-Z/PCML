plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

dependencies {
    // 插件 API：由 PMCL 在运行时提供（隔离类加载器桥接 com.pmcl.plugin.*）
    compileOnly(project(":plugin-api"))

    // Compose：由宿主提供（compileOnly），用于把 Swing 游戏面板包进 SwingPanel 嵌入主窗口。
    // SwingPanel 定义在 ui-desktop 的 desktopMain，plain `ui` artifact 没有它。
    val composeVer = libs.versions.compose.multiplatform.get()
    compileOnly("org.jetbrains.compose.runtime:runtime:$composeVer")
    compileOnly("org.jetbrains.compose.foundation:foundation:$composeVer")
    compileOnly("org.jetbrains.compose.ui:ui:$composeVer")
    compileOnly("org.jetbrains.compose.ui:ui-desktop:$composeVer")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("mario")
    archiveVersion.set("1.0.0")
}
