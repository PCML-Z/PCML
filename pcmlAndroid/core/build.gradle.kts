plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lash.pmcl.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    // 与 PMCL 桌面版保持一致的核心依赖
    api(libs.gson)
    api(libs.okhttp)
    implementation(libs.kotlin.coroutines.core)

    testImplementation(libs.junit)
}
