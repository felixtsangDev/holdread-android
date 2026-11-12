// Plugins go on the root classpath so the Kotlin and Android plugins share one classloader.
// With -PengineOnly=true the Android plugin is left out, so the engine builds without Google's Maven repo.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        if (providers.gradleProperty("engineOnly").orNull != "true") {
            classpath(libs.android.gradle.plugin)
            classpath(libs.compose.compiler.gradle.plugin)
        }
    }
}
