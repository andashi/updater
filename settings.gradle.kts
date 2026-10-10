// Modules, by what they may import (each lands with its own change):
//   probe  a throwaway app that answers one hardware question (design §16)
//   core   pure Kotlin, no Android - every decision the updater makes, unit-tested
//   app    the updater (org.andashi.updater)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "updater"
include(":probe", ":core")
