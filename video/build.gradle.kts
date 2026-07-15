plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm) apply false
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 依赖 core 模块的好友系统（FriendManager、FriendProtocol、信令）
    api(project(":core"))

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")

    // ice4j：ICE/STUN/TURN NAT 穿透
    implementation(libs.ice4j)

    // JavaCV：摄像头采集 + FFmpeg 编解码（自带原生库）
    implementation(libs.javacv)
    // macOS ARM64 ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-arm64")
    // macOS x86_64 ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:macosx-x86_64")
    // Windows ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:windows-x86_64")
    // Linux ffmpeg 原生库
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:linux-x86_64")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
