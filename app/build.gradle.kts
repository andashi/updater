// The updater itself: org.andashi.updater. Permanent application id - signer
// continuity and the installer-of-record role are bound to it (design §17).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.andashi.updater"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.andashi.updater"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config here on purpose: the release is signed offline
            // by the owner (design §12). An unsigned release APK is the
            // artifact CI produces and the owner compares against.
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Deterministic output: no dependency metadata block (it embeds the
    // Gradle version and is encrypted, so it defeats comparison), no
    // version-control info.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        buildConfig = false
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}


dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
}
