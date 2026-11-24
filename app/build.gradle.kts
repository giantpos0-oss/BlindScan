plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.willwalsh.blindscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.willwalsh.blindscan"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.5.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release builds are signed with BlindScan's own key, kept as the GitHub secret BLINDSCAN_SIGNING,
    // so every new version installs over the old one. Without the secret, the debug key is used.
    val keystorePath: String? = System.getenv("BLINDSCAN_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("blindscan") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BLINDSCAN_KEY_PASSWORD")
                keyAlias = "blindscan"
                keyPassword = System.getenv("BLINDSCAN_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("blindscan") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
    // The user guide and release notes (docs/*.md) are bundled so the Help tab works offline.
    // They are the same files the Help tab's browser buttons open, so there is only one copy to update.
    sourceSets { getByName("main") { assets.srcDir(rootProject.file("docs")) } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Ask a question: the phone's built-in AI (Gemini Nano through Android's AICore).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")

}
