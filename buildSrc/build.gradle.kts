import java.util.Properties

plugins {
    `kotlin-dsl`
}

val androidSdkAvailable: Boolean = run {
    if (System.getenv("SUITE_SKIP_ANDROID") == "1") return@run false
    val env = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    if (env.any { File(it).isDirectory }) return@run true
    val localProps = File(rootDir.parentFile, "local.properties")
    if (!localProps.isFile) return@run false
    val props = Properties().apply { localProps.inputStream().use { load(it) } }
    props.getProperty("sdk.dir")?.let { File(it).isDirectory } == true
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    if (androidSdkAvailable) {
        implementation(libs.android.gradle.plugin)
        implementation(libs.roborazzi.gradle.plugin)
    }
}
