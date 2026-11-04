plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaohei.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaohei.tv"
        // 降到 21 (Android 5.0)，覆盖绝大多数老电视/盒子（海信等国产固件多为 Android 5~8）
        minSdk = 21
        // 33 是兼顾老电视安装器的稳妥值，避免过高 targetSdk 被旧 PackageManager 拒解析
        targetSdk = 33
        versionCode = 16
        versionName = "1.8.3"
    }

    signingConfigs {
        // 复用本机 debug 钥匙（与历史版本同签名，支持覆盖更新）；
        // 同时显式开启 v1+v2+v3。老电视（Android < 7）必须 v1 才能安装，
        // 否则会报「解析软件包时出现问题」。正式发布时可换成专用 release keystore。
        create("release") {
            val debugStore = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storeFile = debugStore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
