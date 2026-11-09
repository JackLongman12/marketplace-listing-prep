import java.net.URI
import java.security.MessageDigest

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

        // The S26 Ultra is 64-bit ARM; skip native libs for other CPUs to roughly halve the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing key comes from GitHub secrets (see .github/workflows/build.yml).
    val signingStore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
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
            // R8 shrinks and optimizes the code: smaller APK and noticeably smoother Compose.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeCompiler {
        // Lets Compose skip redrawing list rows whose item didn't change.
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    }
    androidResources { noCompress += "tflite" }
    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

// Small (~4 MB) on-device image embedding model used to group photos by item.
// Fetched once from Google's MediaPipe model bucket and bundled into the app.
// Pinned SHA-256 so a tampered or swapped file fails the build instead of shipping.
val embedderModel = file("src/main/assets/mobilenet_v3_small.tflite")
val embedderSha256 = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40"
fun sha256Of(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
val downloadEmbedderModel by tasks.registering {
    outputs.file(embedderModel)
    doLast {
        if (!embedderModel.exists()) {
            embedderModel.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
            URI(url).toURL().openStream().use { input ->
                embedderModel.outputStream().use { input.copyTo(it) }
            }
        }
        val actual = sha256Of(embedderModel)
        if (actual != embedderSha256) {
            embedderModel.delete()
            throw GradleException("mobilenet_v3_small.tflite checksum mismatch: $actual")
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

    // Built-in camera.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device ML: photo grouping, text reading, and the Gemma 3n language model.
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    implementation("com.google.mediapipe:tasks-genai:0.10.29")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
