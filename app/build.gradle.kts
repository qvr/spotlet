plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fi.qvr.spotlet"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.qvr.spotlet"
        // 26 is a hard floor: the native core's cpal/AAudio backend links libaaudio.so,
        // which the NDK only ships for API >= 26.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The native core is built for exactly these ABIs (see .github/workflows/build.yml).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Stable release signing. When the keystore env vars are absent (local builds) the
    // release build is left unsigned instead of failing.
    signingConfigs {
        create("release") {
            System.getenv("SIGNING_KEYSTORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SIGNING_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    testImplementation(libs.junit)
}
