import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties, which stays out of git;
// without it, release builds are signed with the debug key.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.kadaliao.kaixinguo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kadaliao.kaixinguo"
        // Android 9: the first that decodes the CDN's HEIC covers.
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK for each kind of processor, and one for all. Phones, Huawei's
    // included, take arm64-v8a; the emulator on an Intel Mac takes x86_64.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Media3 marks much of what an app needs (its data sources, the
        // player view's settings) as unstable API; an app, unlike a library,
        // may use it.
        disable += "UnsafeOptInUsageError"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 临时用 fork 的 Release（见 settings.gradle.kts），那里只有 aar 文件、没有 pom，
    // 所以要写明类型；它依赖的 kotlinx-coroutines-core 由下面的 coroutines-android 带进来。
    implementation(libs.openhongguo) { artifact { type = "aar" } }
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
