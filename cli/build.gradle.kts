plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val pmclVersion = providers.gradleProperty("pmcl.version").orElse("2.2a")

dependencies {
    implementation(project(":core"))
    // 显式声明：CLI 直接引用 com.pmcl.plugin.*（PluginInfo、PluginPackageParser 等），
    // 此前仅靠 core 的 api(project(":plugin-api")) 传递可用，脆弱。
    implementation(project(":plugin-api"))
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.slf4j.simple)
}

// 给桌面端依赖的是这个薄 jar。把 core 再打进这里会让启动器 classpath 上出现两份核心类，
// 先加载的那份没有 Implementation-Version，更新检查就会把新版本看成 2.2a。
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.pmcl.cli.PmclCli",
            "Implementation-Version" to pmclVersion.get()
        )
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Standalone CLI jar. pmcl.sh cli uses this."
    dependsOn(tasks.jar, configurations.runtimeClasspath)
    archiveBaseName.set("cli")
    archiveVersion.set("")
    archiveClassifier.set("all")
    archiveFileName.set("cli-all.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    manifest {
        attributes(
            "Main-Class" to "com.pmcl.cli.PmclCli",
            "Implementation-Version" to pmclVersion.get()
        )
    }
    from(tasks.jar.get().archiveFile.map { zipTree(it) })
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    exclude("META-INF/MANIFEST.MF")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}
