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

    // libjitsi：音视频采集/编码/解码/渲染
    implementation(libs.libjitsi)
    // libjitsi 的 native 库（jnopus/jnvpx/jnportaudio/jnawtrenderer/jnscreencapture）
    implementation(libs.jitsi.lgpl.dependencies)
    // ice4j：ICE/STUN/TURN NAT 穿透
    implementation(libs.ice4j)
}
