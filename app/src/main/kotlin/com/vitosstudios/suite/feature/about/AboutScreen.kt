package com.vitosstudios.suite.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.theme.Spacing

private val licenses = listOf(
    "AndroidX (Core, Activity, Lifecycle, Navigation, Room, ExifInterface, Compose, Material 3)" to "Apache License 2.0, The Android Open Source Project",
    "AndroidX Media3 (ExoPlayer, Transformer, Effect)" to "Apache License 2.0, The Android Open Source Project",
    "Kotlin standard library and kotlinx.coroutines" to "Apache License 2.0, JetBrains s.r.o. and contributors",
    "Guava (via Media3)" to "Apache License 2.0, The Guava Authors",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { SuiteTopBar(title = "About", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l)) {
            Text("ArtCraft Mobile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Version 0.1.0 (working name)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Edit DOCX, XLSX and PPTX files, photos and videos on your device. Files are saved in the format they were opened in; " +
                    "content this app does not edit is kept unchanged.",
                modifier = Modifier.padding(top = Spacing.m),
            )
            Section("Privacy")
            Text("No account, no analytics and no network access: the app does not request the internet permission. Files stay on your device and in the storage providers you choose.")
            Section("Open-source licences")
            for ((name, license) in licenses) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.s))
                Text(license, style = MaterialTheme.typography.bodySmall)
            }
            Section("Reference projects")
            Text(
                "The open-source ArtCraft desktop app (github.com/storytold/artcraft, MIT OR Apache-2.0) was reviewed for ideas only. " +
                    "No code, assets, names or visuals were copied. The ArtCraft name and logo are trademarks of their owners and are not licensed to this app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Section("Formats")
            Text("Formats are described by their technical names (DOCX, XLSX, PPTX, PDF, JPG, PNG, WebP, MP4). This app is not affiliated with the vendors of other office or photo software.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = Spacing.m))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
