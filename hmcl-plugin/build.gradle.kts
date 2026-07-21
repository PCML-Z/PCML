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
    compileOnly(project(":plugin-api"))
    compileOnly(project(":core"))
    // M2/M8 安全修复：使用 libs.versions.toml 统一 Compose / Kotlin 版本
    val composeVer = libs.versions.compose.multiplatform.get()
    compileOnly("org.jetbrains.compose.runtime:runtime:$composeVer")
    compileOnly("org.jetbrains.compose.material3:material3:$composeVer")
    compileOnly("org.jetbrains.compose.foundation:foundation:$composeVer")
    compileOnly("org.jetbrains.compose.ui:ui:$composeVer")
    // Desktop-only UI variant — defines androidx.compose.ui.viewinterop.SwingPanel
    // (SwingPanel is in desktopMain, not commonMain; the plain `ui` artifact lacks it)
    compileOnly("org.jetbrains.compose.ui:ui-desktop:$composeVer")
    compileOnly("org.jetbrains.compose.material:material-icons-extended:$composeVer")
    compileOnly(libs.kotlin.coroutines.core)

    // JavaFX + HMCL — compileOnly (bundled in lib/ at runtime)
    compileOnly(fileTree("lib") { include("*.jar") })
}

tasks.register<Zip>("ppk") {
    group = "build"
    description = "Assembles the .ppk HMCL embed plugin package"

    archiveBaseName.set("hmcl-embed")
    archiveVersion.set("1.0.0")
    archiveExtension.set("ppk")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output.classesDirs) {
        into("classes")
    }
    from("src/main/kotlin") {
        into("src/kt")
    }
    from("src/main/java") {
        into("src/java")
    }
    from("src/main/resources/resources") {
        into("resources")
    }
    from("src/main/resources/plugin.xml")
    from("src/main/resources/META-INF/pmcl-plugin.properties") {
        into("META-INF")
    }
    // Bundle all runtime dependency jars
    from("lib") {
        into("lib")
    }
}

tasks.named("build") {
    dependsOn("ppk")
}
