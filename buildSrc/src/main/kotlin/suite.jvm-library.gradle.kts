// Convention for the pure Kotlin/JVM modules (round-trip engine and format layers).
// They have no Android dependency so they run on any JVM and are unit-tested without an emulator.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(kotlin("test-junit"))
    "testImplementation"(catalog.findLibrary("junit").get())
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("suite.repoRoot", rootDir.absolutePath)
    systemProperty("suite.secondReader", System.getProperty("suite.secondReader") ?: System.getenv("SUITE_SECOND_READER") ?: "auto")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
