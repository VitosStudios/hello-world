import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Tippli"

include(":core")

// The Android app module needs an Android SDK. Without one (e.g. a plain JVM
// machine) only the pure-Kotlin :core module is built, so `gradle :core:test`
// works everywhere. CI runners have ANDROID_HOME set and build :app too.
val hasAndroidSdk: Boolean = run {
    val env = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it) }
    if (env.any { it.isNotBlank() && file(it).isDirectory }) return@run true
    val localProperties = file("local.properties")
    if (!localProperties.isFile) return@run false
    val props = Properties().apply { localProperties.inputStream().use { load(it) } }
    props.getProperty("sdk.dir")?.let { file(it).isDirectory } ?: false
}
gradle.extra["tippli.hasAndroidSdk"] = hasAndroidSdk

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Tippli: no Android SDK found (ANDROID_HOME / local.properties) – building :core only.")
}
