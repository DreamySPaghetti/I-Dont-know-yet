plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.phonectrl"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.phonectrl"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
    }
    signingConfigs {
        create("rel") {
            storeFile = file("release.jks")
            storePassword = "phonectrl"
            keyAlias = "key"
            keyPassword = "phonectrl"
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("rel") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
