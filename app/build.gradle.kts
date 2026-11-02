plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartcam.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.smartcam.app"
        minSdk = 30
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionCode = run
        versionName = "1.$run"
    }
    signingConfigs {
        create("smartcam") {
            storeFile = file("smartcam.jks")
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:object-detection:17.0.2")
    testImplementation("junit:junit:4.13.2")
}
