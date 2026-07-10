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
    // Core (compileOnly — only needed because the plugin accesses LauncherCore)
    compileOnly(project(":core"))
}

/**
 * Build a .ppk plugin package (ZIP archive) containing:
 * - plugin.xml                     (XML manifest with info, sources, versions)
 * - META-INF/pmcl-plugin.properties (properties descriptor)
 * - src/kt/**/*.kt                 (Kotlin source files)
 * - src/java/**/*.java             (Java source files)
 * - classes/**/*.class             (compiled bytecode)
 * - resources/                     (resource files)
 *
 * Output: build/libs/demo-plugin-1.0.0.ppk
 */
tasks.register<Zip>("ppk") {
    group = "build"
    description = "Assembles the .ppk plugin package"

    archiveBaseName.set("demo-plugin")
    archiveVersion.set("1.0.0")
    archiveExtension.set("ppk")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Compiled class files -> classes/
    from(sourceSets.main.get().output.classesDirs) {
        into("classes")
    }

    // Kotlin source files -> src/kt/
    from("src/main/kotlin") {
        into("src/kt")
    }

    // Java source files -> src/java/
    from("src/main/java") {
        into("src/java")
    }

    // Resources -> resources/
    from("src/main/resources/resources") {
        into("resources")
    }

    // plugin.xml at root
    from("src/main/resources/plugin.xml")

    // META-INF/pmcl-plugin.properties
    from("src/main/resources/META-INF/pmcl-plugin.properties") {
        into("META-INF")
    }
}

tasks.named("build") {
    dependsOn("ppk")
}
