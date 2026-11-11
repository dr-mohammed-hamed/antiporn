plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}
import java.util.Properties
import java.io.FileInputStream

val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { load(it) }
    }
}
val otaBaseUrl = localProps.getProperty("ota.base.url")
    ?: System.getenv("OTA_BASE_URL")
    ?: "https://example-releases.r2.dev"

val googleServicesFile = file("google-services.json")
if (!googleServicesFile.exists()) {
    googleServicesFile.writeText("""
        {
          "project_info": {
            "project_number": "1234567890",
            "project_id": "antiporn-mock",
            "storage_bucket": "antiporn-mock.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:1234567890:android:mockappiddrmohammed",
                "android_client_info": {
                  "package_name": "com.drmohammed.antiporn"
                }
              },
              "oauth_client": [],
              "api_key": [
                {
                  "current_key": "fake_api_key_for_debug_builds"
                }
              ],
              "services": {}
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent())
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
        versionCode = 19
        versionName = "1.80"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ar", "en")
        buildConfigField("String", "OTA_BASE_URL", "\"$otaBaseUrl\"")
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        // Local/dev key: load rotated key from keystore/key.properties if present, fallback to AOSP testkey or debug key.
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
        val devTestKeyFile = file("keystore/testkey.p12")
        if (devTestKeyFile.exists()) {
            create("aospTestKey") {
                storeFile = devTestKeyFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Production key comes exclusively from CI secrets (never committed).
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
            signingConfig = signingConfigs.findByName("rotatedDevKey")
                ?: signingConfigs.findByName("aospTestKey")
                ?: signingConfigs.getByName("debug")
        }
        release {
            // Uses "prod" when KEYSTORE_PATH is set (CI); otherwise falls back to
            // active dev signing so local assembleRelease still works but is NOT shippable.
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.findByName("rotatedDevKey")
                    ?: signingConfigs.findByName("aospTestKey")
                    ?: signingConfigs.getByName("debug")
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
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "onnx"
        noCompress += "bin"
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.tensorflow.lite)
    implementation(libs.onnxruntime.android)

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

    // Wireless ADB & Device Owner Provisioning
    implementation(libs.adb.kt)

    // QR Code Generation & Scanning (CameraX + ML Kit) & Printing
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.print)

    // Dependency Injection (Koin)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    testImplementation(libs.koin.test)

    // AndroidX WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.androidx.work.testing)

    implementation(libs.timber)

    // Jetpack Room Persistence & Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

apply(from = "filter_generator.gradle.kts")

