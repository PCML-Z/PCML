plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val pmclVersion = providers.gradleProperty("pmcl.version").orElse("1.3.0")
tasks.withType<Jar>().configureEach {
    manifest {
        attributes("Implementation-Version" to pmclVersion.get())
    }
}

val glfwAgent: SourceSet by sourceSets.creating {
    java.srcDir("src/glfwAgent/java")
}

dependencies {
    // Plugin API (core implements PluginContext, manages plugins)
    api(project(":plugin-api"))
    // JSON 解析（DataCache 的公共 API 暴露 TypeToken，故用 api 使其传递可用）
    api(libs.gson)
    // HTTP 客户端
    implementation(libs.okhttp)
    // 系统硬件信息（内存、CPU、操作系统）
    implementation(libs.oshi)
    // 日志
    implementation(libs.slf4j.simple)
    // QR code generation
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    // GLFW macOS icon-fix agent（独立 source set，勿继承 implementation 以免拉到 Java 21 工程）
    add(glfwAgent.implementationConfigurationName, libs.asm)
    add(glfwAgent.implementationConfigurationName, libs.asm.commons)

    // 测试
    testImplementation(libs.junit.jupiter)
}

tasks.named<JavaCompile>("compileGlfwAgentJava") {
    // 游戏 JVM 可能是 Java 8（Rosetta jre-legacy）
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.release.set(8)
}

val glfwAgentJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Fat jar: GLFW macOS icon-fix javaagent (Java 8)"
    archiveBaseName.set("pmcl-glfw-icon-agent")
    archiveVersion.set("")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("generated/glfwAgent"))
    from(glfwAgent.output)
    dependsOn(tasks.named("compileGlfwAgentJava"))
    // 嵌入 ASM，供 agent 在隔离 ClassLoader 中自给自足
    from(configurations.named(glfwAgent.runtimeClasspathConfigurationName).map { cfg ->
        cfg.filter { it.name.startsWith("asm") }.map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("**/module-info.class")
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/versions/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "com.pmcl.core.glfw.GlfwIconFixAgent",
            "Agent-Class" to "com.pmcl.core.glfw.GlfwIconFixAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

// 将 agent jar 拷入 resources，运行时由 MacOsGlfwFix 解出
val syncGlfwAgentResource by tasks.registering(Copy::class) {
    dependsOn(glfwAgentJar)
    from(glfwAgentJar.map { it.archiveFile })
    into(layout.buildDirectory.dir("generated/glfwAgentResource/com/pmcl/core/glfw"))
    rename { "pmcl-glfw-icon-agent.jar" }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/glfwAgentResource"))
}

tasks.named("processResources") {
    dependsOn(syncGlfwAgentResource)
}

tasks.named<Jar>("jar") {
    dependsOn(syncGlfwAgentResource)
}

tasks.test {
    useJUnitPlatform()
}
