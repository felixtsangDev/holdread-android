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

rootProject.name = "holdread"

// The reading engine is plain Kotlin and builds anywhere a JDK is available.
include(":engine")

// The Android app needs the Android SDK and Google's Maven repo.
// Pass -PengineOnly=true to work on the engine where those aren't reachable.
if (providers.gradleProperty("engineOnly").orNull != "true") {
    include(":app")
}
