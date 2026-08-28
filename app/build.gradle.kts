plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Signing: pakai keystore rilis bila tersedia (lokal atau CI lewat secret),
 * selain itu jatuh ke debug key supaya build tetap jalan tanpa rahasia apa pun.
 */
val keystoreFile = rootProject.file("keystore/release.jks")
val hasReleaseKeystore = keystoreFile.exists() &&
    System.getenv("KEYSTORE_PASSWORD") != null

android {
    namespace = "id.autoair.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.autoair.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.7"

        // Buang resource bahasa yang tidak dipakai (hemat ~1 MB dari Material).
        resourceConfigurations += listOf("in", "en")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "autoair"
                keyPassword = System.getenv("KEY_PASSWORD")
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
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // APK per-ABI: perangkat hanya mengunduh kode natif yang dibutuhkan.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "**/*.proto"
            )
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
        buildConfig = false
        resValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
