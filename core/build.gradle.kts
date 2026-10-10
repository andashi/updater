// Pure Kotlin. No Android dependency may enter this module: every decision
// the updater makes lives here so that its refusal cases run as plain JUnit
// tests, without an emulator (design §5.2, §16).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode level 21 whatever JDK runs the build: the host has JDK 26, CI
// pins 21, and a reproducible build (design §12) must not depend on which.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The one library: JSON for the lock and the config. Kotlin's own,
    // pure Kotlin, no reflection, no platform JSON (Android's org.json is
    // not the JVM's, and the decisions must run on both).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
