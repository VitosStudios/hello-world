pluginManagement {
    // This block is compiled on its own and cannot call functions declared below.
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (System.getenv("SUITE_SKIP_ANDROID") != "1" &&
            (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || File(settingsDir, "local.properties").isFile)
        ) {
            google()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (androidSdkAvailable()) google()
    }
}

rootProject.name = "ArtCraftMobile"

// Round-trip engine and format layers: pure Kotlin/JVM, always built.
include(":core:xml")
include(":core:opc")
include(":core:common")
include(":format:docx")
include(":format:xlsx")
include(":format:pptx")
include(":format:photo")
include(":format:video")
include(":testing:corpus")

// The Android app needs an Android SDK. Without one (for example in a sandbox that cannot reach
// Google's servers) the JVM modules still build and test. See docs/DECISIONS.md (D-002).
if (androidSdkAvailable()) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found: building JVM modules only (set ANDROID_HOME to include :app).")
}

fun androidSdkAvailable(): Boolean {
    if (System.getenv("SUITE_SKIP_ANDROID") == "1") return false
    val env = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    if (env.any { File(it).isDirectory }) return true
    val localProps = File(settingsDir, "local.properties")
    if (!localProps.isFile) return false
    val props = java.util.Properties().apply { localProps.inputStream().use { load(it) } }
    return props.getProperty("sdk.dir")?.let { File(it).isDirectory } == true
}
