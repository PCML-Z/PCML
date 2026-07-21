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
    kotlinOptions.jvmTarget = "21"
}

// Plugin API must not depend on core or ui at runtime.
// Plugins that need core access should add core as compileOnly themselves.
dependencies {
    // Compose runtime annotations (@Composable) — compileOnly so plugin-api
    // doesn't pull in full Compose at runtime; PMCL provides it.
    compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")
}
