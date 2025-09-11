plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.webbridgesdk.webbridgekit"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Android 官方提供的 WebView 增强库 - 移除重复依赖
    implementation(libs.androidx.webkit)

    // 用于二维码扫描的 ZXing
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 移除未使用的 CameraX 依赖项（项目使用 ZXing 进行二维码扫描）
    // implementation("androidx.camera:camera-core:1.3.1")
    // implementation("androidx.camera:camera-camera2:1.3.1")
    // implementation("androidx.camera:camera-lifecycle:1.3.1")
    // implementation("androidx.camera:camera-view:1.3.1")

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}