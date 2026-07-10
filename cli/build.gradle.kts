plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.slf4j.simple)
}

// 允许 application 插件运行
tasks.jar {
    dependsOn(":core:jar")
    manifest {
        attributes(
            "Main-Class" to "com.pmcl.cli.PmclCli"
        )
    }
    // 将依赖打包进 fat jar
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
