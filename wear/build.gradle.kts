import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AnkiDroid for Wear OS (Galaxy Watch): study existing decks, with the phone doing the scheduling.
// Sideloaded with adb; not published.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ichi2.anki.wear"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // Must equal the phone app's application ID, and both must be signed with the same key:
        // the Wear OS Data Layer only connects an app to its own counterpart.
        applicationId = "com.ichi2.anki"
        minSdk = 30 // Wear OS 3
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Same rules as the phone app (AnkiDroid/build.gradle), so the keys match when built the same way
        create("release") {
            val keystorePath = System.getenv("KEYSTOREPATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTOREPWD") ?: System.getenv("KSTOREPWD")
                keyAlias = System.getenv("KEYALIAS")
                keyPassword = System.getenv("KEYPWD")
            } else {
                storeFile = rootProject.file("tools/fallback-release-keystore.jks")
                storePassword = "Test@123"
                keyAlias = "my-key"
                keyPassword = "Test@123"
            }
        }
    }

    buildTypes {
        debug {
            // pairs with the phone's debug build (com.ichi2.anki.debug)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("color", "anki_foreground_icon_color_0", "#FFFF0000")
            resValue("color", "anki_foreground_icon_color_1", "#FFFF0000")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            // same option as the phone app: assembleRelease -PcustomSuffix="suffix"
            if (project.hasProperty("customSuffix")) {
                applicationIdSuffix = "." + project.property("customSuffix").toString().trimStart('.')
            }
            resValue("color", "anki_foreground_icon_color_0", "#FF29B6F6")
            resValue("color", "anki_foreground_icon_color_1", "#FF0288D1")
        }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    implementation(project(":wear-protocol"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)
    // system text input (keyboard, handwriting, voice) for adding words
    implementation(libs.androidx.wear.input)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
