plugins {
    alias(libs.plugins.kotlin.jvm)
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
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
