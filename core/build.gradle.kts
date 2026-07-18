plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
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

    // 测试
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
