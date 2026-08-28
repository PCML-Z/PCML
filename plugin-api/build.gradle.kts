plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        // So Java host facades can inherit Kotlin interface default methods
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
    }
}

// Plugin API must not depend on core or ui at runtime.
// Plugins that need core access should add core as compileOnly themselves.
dependencies {
    // Compose runtime annotations (@Composable) — compileOnly so plugin-api
    // doesn't pull in full Compose at runtime; PMCL provides it.
    compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")

    // JavaFX API (JavaFxContent.createRoot returns javafx.scene.Parent) —
    // compileOnly: the host provides the JavaFX runtime at runtime via the
    // plugin classloader bridge (javafx.* is host-exported; plugins must NOT
    // bundle their own JavaFX, it would be shadowed and risk version skew).
    // openjfx requires an explicit OS+arch classifier (unclassified = empty pom).
    val fxOs = System.getProperty("os.name").lowercase()
    val fxArch = System.getProperty("os.arch").lowercase()
    val fxClassifier = when {
        fxOs.contains("mac") && fxArch.contains("aarch64") -> "mac-aarch64"
        fxOs.contains("mac") -> "mac"
        fxOs.contains("windows") -> "win"
        fxOs.contains("linux") && fxArch.contains("aarch64") -> "linux-aarch64"
        else -> "linux"
    }
    compileOnly("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$fxClassifier")

    // 测试：JUnit5 + Compose runtime + JavaFX API。
    // - Compose runtime：compose-compiler 插件编译 test 源集时要求 runtime 在
    //   classpath（main 源集是 compileOnly，不传递到 test）
    // - JavaFX：JavaFxContent 测试 lambda 的签名引用 javafx.scene.Parent；加载/链接
    //   Parent 及其父类链（javafx-base 的 javafx.event.*）不需要初始化 toolkit，
    //   不会拉起渲染线程
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")
    testImplementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$fxClassifier")
    testImplementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$fxClassifier")
}

tasks.test {
    useJUnitPlatform()
}
