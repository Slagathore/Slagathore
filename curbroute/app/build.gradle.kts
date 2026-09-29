import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // for the saved-session file (survives Android killing the app)
}

android {
    namespace = "dev.slagathore.curbroute"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.slagathore.curbroute"
        minSdk = 26 // Android 8.0+, which covers basically every phone still in use
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // ML Kit's OCR engine ships a ~11 MB native library per CPU type. Build one APK per type plus a
    // "universal" one: install app-arm64-v8a-*.apk on your phone (~25 MB instead of ~56 MB); the
    // universal APK is for PC emulators (x86_64).
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core")) // all the routing brains live there

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.mlkit.text.recognition) // on-device OCR: free, offline, no API key
    implementation(libs.play.services.location) // "start from where I'm parked"
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services) // lets us `await()` Google Play Tasks
}
