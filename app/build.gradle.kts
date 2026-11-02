plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pixelporters.game"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pixelporters.game"
        minSdk = 26
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionCode = run
        versionName = "1.$run"
    }
    signingConfigs {
        create("porters") {
            storeFile = file("porters.jks")
            storePassword = "09a2cb65e93dbc30fc96873d"
            keyAlias = "porters"
            keyPassword = "09a2cb65e93dbc30fc96873d"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("porters") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {}
