plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // Plugin API (compileOnly — provided by PMCL at runtime)
    compileOnly(project(":plugin-api"))
    // Core (compileOnly — only needed if the plugin accesses LauncherCore)
    compileOnly(project(":core"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
