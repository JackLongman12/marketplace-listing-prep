plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.listingprep"
    compileSdk = 35

    // CI passes -PversionCode=<run number> so each GitHub build installs over the last one.
    val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.listingprep"
        minSdk = 33 // Built for a Galaxy S26 Ultra; no need to support old phones.
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Release signing key comes from GitHub secrets (see .github/workflows/build.yml).
    val signingStore = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    androidResources { noCompress += "tflite" }
    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

// Small (~4 MB) on-device image embedding model used to group photos by item.
// Fetched once from Google's MediaPipe model bucket and bundled into the app.
val embedderModel = file("src/main/assets/mobilenet_v3_small.tflite")
val downloadEmbedderModel by tasks.registering {
    outputs.file(embedderModel)
    doLast {
        if (!embedderModel.exists()) {
            embedderModel.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
            java.net.URI(url).toURL().openStream().use { input ->
                embedderModel.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadEmbedderModel) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device ML: photo grouping, text reading, and the Gemma 3n language model.
    implementation("com.google.mediapipe:tasks-vision:0.10.24")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
