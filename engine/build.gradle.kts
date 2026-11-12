plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    // Same ICU word breaking that Android ships as android.icu, so JVM tests segment text like the phone does.
    testImplementation(libs.icu4j)
}

tasks.test {
    // JSON engine cases are shared with iOS and live at the repo root.
    systemProperty("holdread.cases", rootProject.file("spec/cases").absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
