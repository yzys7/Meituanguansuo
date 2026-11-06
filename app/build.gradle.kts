plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bikeguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bikeguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
    }

    buildTypes {
        release {
            // 自用工具，用 debug 签名即可；这样直接 assembleRelease 也能装
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildToolsVersion = "36.1.0"

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 零第三方依赖：只用 Android Framework，构建快、体积小、不引入 AndroidX
dependencies {
}
