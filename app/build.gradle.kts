plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.inkwell.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.inkwell.app"
        minSdk = 26
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionCode = run
        versionName = "1.$run"
    }
    signingConfigs {
        create("inkwell") {
            storeFile = file("inkwell.jks")
            storePassword = "12b2e82bab05c11877f9cea4"
            keyAlias = "inkwell"
            keyPassword = "12b2e82bab05c11877f9cea4"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("inkwell") }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
}
