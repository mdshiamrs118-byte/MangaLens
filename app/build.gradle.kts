plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mangalens"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mangalens"
        minSdk = 24
        targetSdk = 29 // Android 10
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Phones only; keeps the APK smaller (ML Kit + ONNX Runtime ship native libs).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // OCR (Korean model is bundled in the APK, no download needed)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Offline translation (language model downloaded on demand by ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")
    // Runs the OPUS-MT and NLLB models
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    implementation("com.google.code.gson:gson:2.11.0")
}
