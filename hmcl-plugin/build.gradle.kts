plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    compileOnly(project(":plugin-api"))
    compileOnly(project(":core"))
    // Compose runtime — for @Composable annotations in plugin GUI pages
    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.0")
    compileOnly("org.jetbrains.compose.material3:material3:1.7.0")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.7.0")
    compileOnly("org.jetbrains.compose.ui:ui:1.7.0")
    // Desktop-only UI variant — defines androidx.compose.ui.viewinterop.SwingPanel
    // (SwingPanel is in desktopMain, not commonMain; the plain `ui` artifact lacks it)
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.7.0")
    compileOnly("org.jetbrains.compose.material:material-icons-extended:1.7.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

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
