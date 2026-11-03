plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arenascanner.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.arenascanner.app"
        minSdk = 31 // LiteRT-LM (on-device Gemma) needs Android 12+
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionCode = run
        versionName = "1.$run"
        // Same reason as SmartCam: only ship 64-bit ARM ML Kit libraries to keep the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        // Reuses SmartCam's keystore so both apps install the same way.
        create("smartcam") {
            storeFile = file("../app/smartcam.jks")
            storePassword = "smartcam123"
            keyAlias = "smartcam"
            keyPassword = "smartcam123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("smartcam") }
    }
    testOptions { unitTests.all { it.testLogging { events("passed", "failed") } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT-LM may be built with a newer Kotlin than this project; its API is plain Kotlin/Java.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    // On-device text recognition (Latin script), bundled so it works offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // On-device LLM runtime for the move coach (Gemma 4 E2B).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
    testImplementation("junit:junit:4.13.2")
}
