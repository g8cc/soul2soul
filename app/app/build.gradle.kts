plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.soul2soul.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.soul2soul.app"
        // 23 = Android 6.0：全兼容下限（覆盖小米5的 Android 7 乃至更老设备）
        // <26 适配点：通知渠道/VibrationEffect/PiP/前台服务守卫 + LE 根证书代码级信任链(TlsTrust)
        minSdk = 23
        targetSdk = 34
        versionCode = 13
        versionName = "0.2.13"
        // 正式入口：wss 加密信令（nginx 反代 443 → 127.0.0.1:8080，证书 Let's Encrypt 自动续期）
        buildConfigField("String", "SIGNALING_URL", "\"wss://soul.lumi666.cloud\"")
    }

    // prod: 真机用，连云服务器。emu: 双模拟器联调用，连宿主机本地信令（10.0.2.2）
    // 注意：不要用 -P 属性切换 URL —— BuildConfig 任务对属性变化不敏感，会拿到旧值
    flavorDimensions += "env"
    productFlavors {
        create("prod") { dimension = "env" }
        create("emu") {
            dimension = "env"
            applicationIdSuffix = ".emu"
            buildConfigField("String", "SIGNALING_URL", "\"ws://10.0.2.2:8080\"")
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/soul2soul-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "soul2soul2026"
                keyAlias = "soul2soul"
                keyPassword = "soul2soul2026"
                // Android 7.0 的 MIUI 安装器对 v2-only 包有静默失败 bug，强制 v1+v2 双签名
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // v0.2 稳定性优先；后续开 R8 需补混淆规则
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
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 预编译 libwebrtc（官方 org.webrtc API）
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    testImplementation("junit:junit:4.13.2")
    // 本地单测用真实 org.json 实现（android.jar 里是 stub）
    testImplementation("org.json:json:20240303")
}
