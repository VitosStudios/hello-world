// Root build file. The Android Gradle Plugin is only put on the build classpath
// when settings.gradle.kts found an Android SDK (and therefore included :app);
// it has to live in the same class loader as the Kotlin plugin, which is why it
// is added here and not in app/build.gradle.kts.
buildscript {
    val hasAndroidSdk = gradle.extra.has("tippli.hasAndroidSdk") &&
        gradle.extra["tippli.hasAndroidSdk"] == true
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (hasAndroidSdk) {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
