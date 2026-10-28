// buildSrc hosts every Gradle plugin on one class loader (Kotlin, Compose compiler, KSP and,
// when an Android SDK is present, the Android Gradle plugin). See docs/DECISIONS.md (D-002).
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (androidSdkAvailable()) google()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "buildSrc"

fun androidSdkAvailable(): Boolean {
    if (System.getenv("SUITE_SKIP_ANDROID") == "1") return false
    val env = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    if (env.any { File(it).isDirectory }) return true
    val localProps = File(settingsDir.parentFile, "local.properties")
    if (!localProps.isFile) return false
    val props = java.util.Properties().apply { localProps.inputStream().use { load(it) } }
    return props.getProperty("sdk.dir")?.let { File(it).isDirectory } == true
}
