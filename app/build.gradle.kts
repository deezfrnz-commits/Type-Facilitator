plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.typefacilitator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typefacilitator"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 keeps the APK small; signed with the debug key so the CI artifact is installable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { buildConfig = false }
}

dependencies {
    // The only dependency: the app's own typing engine. No AndroidX, no third-party libraries.
    implementation(project(":core"))
}
