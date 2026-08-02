import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

// Load signing credentials from a gitignored keystore.properties at the project root.
// If the file is missing, release builds remain unsigned (debug builds still work).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { load(keystorePropsFile.inputStream()) }
} else null

android {
    namespace = "com.mobildroid.cloudshelf.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mobildroid.cloudshelf.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // Two build flavors per the plan:
    //   playStore  -> AAB for Google Play
    //   internal   -> universal APK for sideload / GitHub releases
    flavorDimensions += "distribution"
    productFlavors {
        create("playStore") {
            dimension = "distribution"
            versionNameSuffix = "-play"
        }
        create("internal") {
            dimension = "distribution"
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Apply the release signing config only if it was successfully loaded.
            // Otherwise the release build stays unsigned (`assembleRelease` still works;
            // `bundleRelease` will print a warning).
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BoM-managed versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // AWS SDK for Kotlin
    implementation(libs.aws.s3)
    implementation(libs.aws.sts)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Media3 (ExoPlayer) for video preview
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Coil (image loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Room (transfer queue, cache index)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (settings prefs that aren't secret)
    implementation(libs.datastore.preferences)

    // WorkManager (background transfers)
    implementation(libs.androidx.work.runtime.ktx)

    // Security Crypto (EncryptedSharedPreferences for IAM key storage)
    implementation(libs.androidx.security.crypto)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
