plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.thorcorners"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.thorcorners"
        minSdk = 30          // createWindowContext() needs API 30; the Thor runs Android 13 (API 33)
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No libraries on purpose: plain Android Views keep the app tiny and fast to rebuild.
