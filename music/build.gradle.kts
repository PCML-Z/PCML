plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // 依赖 core 模块（传递 Gson）
    api(project(":core"))

    // HTTP 客户端（core 的 okhttp 是 implementation 不传递，music 模块独立依赖）
    implementation(libs.okhttp)

    // JavaCV：FFmpeg 编解码（自带原生库）
    api(libs.javacv)
    // macOS ARM64 ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-arm64")
    // macOS x86_64 ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-x86_64")
    // Windows ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:windows-x86_64")
    // Linux ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:linux-x86_64")

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
