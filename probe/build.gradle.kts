// A throwaway app that answers the one question the emulator cannot
// (design §16): does a dataSync foreground service, started from
// WorkManager, get network in a zone whose VPN is in lockdown? It fetches
// one URL and writes what happened where the shell can read it. Nothing
// here ships to a phone for good; the application id says so.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.andashi.updater.probe"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.andashi.updater.probe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
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
    buildFeatures { buildConfig = false }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
