plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()
val keystorePath: String? = System.getenv("MURMUR_KEYSTORE")

android {
    namespace = "com.morty649.murmur"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.morty649.murmur"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Practically every Android phone from the last ~7 years is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("murmur") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("MURMUR_KEYSTORE_PASSWORD")
                keyAlias = "murmur"
                keyPassword = System.getenv("MURMUR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A fixed key means new builds install over old ones (and keep the downloaded model).
            signingConfig = if (keystorePath != null) signingConfigs.getByName("murmur")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // sherpa-onnx (offline ASR runtime + Kotlin API). Fetched by scripts/fetch-sherpa.sh.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
