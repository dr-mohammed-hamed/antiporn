plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.drmohammed.antiporn"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.drmohammed.antiporn"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ar", "en")
    }

    signingConfigs {
        // Local/dev key only. MUST NOT sign public releases — AOSP test keys are public.
        create("aospTestKey") {
            storeFile = file("keystore/testkey.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // CRITICAL(C2): production key comes exclusively from CI secrets (never committed).
        // Set KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD to sign releases.
        val prodKeystorePath = System.getenv("KEYSTORE_PATH")
        if (!prodKeystorePath.isNullOrBlank()) {
            create("prod") {
                storeFile = file(prodKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("aospTestKey")
        }
        release {
            // Uses "prod" when KEYSTORE_PATH is set (CI); otherwise falls back to
            // debug signing so local assembleRelease still works but is NOT shippable.
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("prod")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}