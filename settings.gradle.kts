rootProject.name = "pmcl"

// M4 修复：通过 -Ppmcl.useAliyunMirror=false 或 ~/.gradle/gradle.properties 切换镜像顺序
// 默认 true（中国网络环境友好），国际用户设置为 false 切换为官方源优先
pluginManagement {
    val useAliyun = gradle.startParameter.projectProperties["pmcl.useAliyunMirror"]?.toBoolean() ?: true
    repositories {
        if (useAliyun) {
            // 中国网络环境：Aliyun 镜像优先，加速插件下载
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 国际用户：官方源优先
            google()
            mavenCentral()
            gradlePluginPortal()
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
}

dependencyResolutionManagement {
    val useAliyun = gradle.startParameter.projectProperties["pmcl.useAliyunMirror"]?.toBoolean() ?: true
    repositories {
        if (useAliyun) {
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
            maven("https://jitpack.io")
        } else {
            google()
            mavenCentral()
            maven("https://jitpack.io")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

include(":core")
include(":ui")
include(":cli")
include(":plugin-api")
include(":test-plugin")
include(":test-plugin-package")
include(":custom-downloader-plugin")
include(":hmcl-plugin")
include(":video")
include(":music")
