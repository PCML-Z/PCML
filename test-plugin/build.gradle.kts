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
    // Plugin API (compileOnly — provided by PMCL at runtime)
    compileOnly(project(":plugin-api"))
    // Compose for plugin pages / settings sections (provided by host)
    compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")
    compileOnly("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.multiplatform.get()}")
    compileOnly("org.jetbrains.compose.material3:material3:${libs.versions.compose.multiplatform.get()}")
    compileOnly("org.jetbrains.compose.ui:ui:${libs.versions.compose.multiplatform.get()}")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
