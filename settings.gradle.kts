rootProject.name = "pmcl"

pluginManagement {
    repositories {
        // 国内镜像，加速插件下载
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 国内镜像，加速依赖下载
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven("https://jitpack.io")
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
include(":live2d-plugin")
