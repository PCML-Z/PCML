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
    // Compose runtime — for @Composable annotations in plugin GUI pages
    // M2/M8 安全修复：使用 libs.versions.toml 统一版本，避免硬编码导致 UI 升级时签名不匹配
    val composeVer = libs.versions.compose.multiplatform.get()
    compileOnly("org.jetbrains.compose.runtime:runtime:$composeVer")
    // Material3 — for UI components (provided by PMCL at runtime)
    compileOnly("org.jetbrains.compose.material3:material3:$composeVer")
    compileOnly("org.jetbrains.compose.foundation:foundation:$composeVer")
    compileOnly("org.jetbrains.compose.ui:ui:$composeVer")
    // Material icons extended — for Icons.Filled.Download, Save, etc.
    compileOnly("org.jetbrains.compose.material:material-icons-extended:$composeVer")
    // Coroutines — for launch/withContext in download operations
    compileOnly(libs.kotlin.coroutines.core)
}

tasks.register<Zip>("ppk") {
    group = "build"
    description = "Assembles the .ppk plugin package"

    archiveBaseName.set("custom-downloader")
    archiveVersion.set("1.1.0")
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
}

tasks.named("build") {
    dependsOn("ppk")
}
