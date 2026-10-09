// Three modules, by what they may import:
//   core   pure Kotlin, no Android - every decision the updater makes, unit-tested
//   app    the updater (org.andashi.updater)
//   probe  a throwaway app that answers one hardware question (design §16)
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
include(":core", ":app", ":probe")
