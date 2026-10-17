import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions come from the root build (AGP is only on the classpath when an SDK exists).
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Shared data (SPEC §2): bundled from ../shared at build time, never copied by
// hand. Only the runtime files go into the APK (no SPEC, tools or tests).
// ---------------------------------------------------------------------------
val sharedDir: File = rootProject.file("../shared")
val sharedAssetsDir: File = layout.buildDirectory.dir("generated/sharedAssets").get().asFile

val syncSharedAssets = tasks.register<Sync>("syncSharedAssets") {
    description = "Copies the shared Tippli data into the generated assets folder."
    from(sharedDir) {
        include(
            "layouts/layouts.json",
            "layouts/accents.json",
            "languages.json",
            "providers.json",
            "prompts/*.txt",
            "prompts/prompt-parts.json",
            "emoji.json",
            "theme.json",
            "settings-defaults.json",
            "strings-de.json",
        )
        into("shared")
    }
    into(sharedAssetsDir)
}

android {
    namespace = "ch.vitosstudios.tippli"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.vitosstudios.tippli"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // A committed debug keystore: every CI build has the same signature, so
        // users can install updates without uninstalling first.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(sharedAssetsDir)
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Every task that reads the asset folders must run after the sync.
tasks.configureEach {
    if (name == "preBuild" ||
        (name != "syncSharedAssets" && (name.contains("Assets") || name.contains("lint", ignoreCase = true)))
    ) {
        dependsOn(syncSharedAssets)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
