plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.userexec.soneme.mimic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.userexec.soneme.mimic"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("SONEME_KEYSTORE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SONEME_STORE_PASSWORD")
                keyAlias = System.getenv("SONEME_KEY_ALIAS") ?: "soneme"
                keyPassword = System.getenv("SONEME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("SONEME_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
}
