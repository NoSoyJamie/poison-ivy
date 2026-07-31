plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.poisonivy.printer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poisonivy.printer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed, checked-in debug keystore (app/debug.keystore) instead of
            // letting the Android Gradle Plugin auto-generate a random one per
            // machine. Without this, every fresh environment -- including a
            // brand new GitHub Actions runner on every single workflow run --
            // signs debug builds with a different random key, and Android
            // refuses to install a new APK over an old one with a different
            // signature (INSTALL_FAILED_UPDATE_INCOMPATIBLE). This keystore is
            // debug-only, not sensitive, and is meant to be shared/committed --
            // same convention the Android SDK itself uses for its own
            // auto-generated ~/.android/debug.keystore.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
