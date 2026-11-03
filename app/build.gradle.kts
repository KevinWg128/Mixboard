plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hk.mixboard.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "hk.mixboard.ime"
        minSdk = 28        // Android 9+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // One fixed key for every build, so a new APK installs over the old one
    // (no uninstall, no lost learning data). Keep the repo private.
    signingConfigs {
        create("personal") {
            storeFile = file("personal.keystore")
            storePassword = "mixboard"
            keyAlias = "mixboard"
            keyPassword = "mixboard"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
