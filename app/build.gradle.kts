plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}
import java.util.Properties
import java.io.FileInputStream



android {
    namespace = "com.drmohammed.antiporn"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.drmohammed.antiporn"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ar", "en")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Local/dev key: load rotated key from keystore/key.properties if present, fallback to AOSP testkey.
        val keyPropsFile = file("keystore/key.properties")
        if (keyPropsFile.exists()) {
            val keyProps = Properties().apply {
                FileInputStream(keyPropsFile).use { load(it) }
            }
            create("rotatedDevKey") {
                storeFile = file("keystore/${keyProps.getProperty("storeFile", "antiporn-release.jks")}")
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
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
            signingConfig = signingConfigs.findByName("rotatedDevKey") ?: signingConfigs.getByName("aospTestKey")
        }
        release {
            // Uses "prod" when KEYSTORE_PATH is set (CI); otherwise falls back to
            // active dev signing so local assembleRelease still works but is NOT shippable.
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.findByName("rotatedDevKey") ?: signingConfigs.getByName("aospTestKey")
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
    lint {
        disable += "MissingTranslation"
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.tensorflow.lite)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Firebase BoM & Cloud Infrastructure
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Coroutines & Play Services integration
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Local Data Layer
    implementation(libs.androidx.room.runtime)

    // Wireless ADB & Device Owner Provisioning
    implementation(libs.adb.kt)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
