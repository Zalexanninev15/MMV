plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing material comes from the environment in CI and from gradle.properties (or -P)
// locally, so no keystore or password is ever committed. Resolved at configuration time
// because buildTypes below has to know whether a real config exists.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
    ?: project.findProperty("MMV_KEYSTORE_FILE") as String?
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: project.findProperty("MMV_KEYSTORE_PASSWORD") as String?
val keyAliasName: String? = System.getenv("KEY_ALIAS")
    ?: project.findProperty("MMV_KEY_ALIAS") as String?
val keyPasswordValue: String? = System.getenv("KEY_PASSWORD")
    ?: project.findProperty("MMV_KEY_PASSWORD") as String?

val canSign = keystoreFile != null && keystorePassword != null &&
    keyAliasName != null && keyPasswordValue != null && file(keystoreFile).exists()

android {
    namespace = "io.github.zalexanninev15.magicmusicv"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zalexanninev15.magicmusicv"
        // minSdk 31: Vibrator.arePrimitivesSupported() / getPrimitiveDurations() and
        // VibratorManager are API 31. The whole app is built on haptic primitives,
        // so anything below 31 would silently degrade to a buzz — not worth shipping.
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.6"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    /**
     * Per-ABI APKs.
     *
     * Worth being honest about the size win: this app is almost entirely Kotlin, and the
     * only native code in it comes from Compose (libandroidx.graphics.path.so), so the
     * split saves a few hundred KB rather than megabytes. The universal APK is kept as
     * well, and is the one to hand to anyone who does not know their device's ABI.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // R8 stays off for now. It has never run in CI on this project, and turning it
            // on in the same change as release signing would make any failure ambiguous.
            // proguard-rules.pro already carries the keep rules, so this is a one-flag
            // change once a signed build is confirmed working.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug key so the release variant still builds without
            // secrets — on a fork, or a pull request, where secrets are not available.
            signingConfig = if (canSign) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Local-library folder scanning (SAF tree walking) and offline track analysis.
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
