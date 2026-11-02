plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.all { it.maxHeapSize = "3g"; it.testLogging { events("passed", "failed"); showStandardStreams = true } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
