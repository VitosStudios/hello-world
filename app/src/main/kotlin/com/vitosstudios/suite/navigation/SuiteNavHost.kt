package com.vitosstudios.suite.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.feature.about.AboutScreen
import com.vitosstudios.suite.feature.compat.CompatibilityScreen
import com.vitosstudios.suite.feature.docs.DocsEditorScreen
import com.vitosstudios.suite.feature.home.HomeScreen
import com.vitosstudios.suite.feature.pdf.PdfViewerScreen
import com.vitosstudios.suite.feature.photo.PhotoEditorScreen
import com.vitosstudios.suite.feature.sheets.SheetsEditorScreen
import com.vitosstudios.suite.feature.slides.SlidesEditorScreen
import com.vitosstudios.suite.feature.video.VideoEditorScreen
import com.vitosstudios.suite.ui.components.EmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{id}"
    const val COMPAT = "compat/{id}"
    const val ABOUT = "about"

    fun editor(id: String) = "editor/$id"
    fun compat(id: String) = "compat/$id"
}

/** Single-activity navigation: home, one editor route that dispatches by file type, and helpers. */
@Composable
fun SuiteNavHost() {
    val nav = rememberNavController()
    val container = LocalContext.current.appContainer

    LaunchedEffect(Unit) {
        container.incomingUris.collect { uri ->
            container.incomingUris.resetReplayCache()
            try {
                val e = container.files.open(uri)
                nav.navigate(Routes.editor(e.id))
            } catch (_: Exception) {
                // The home screen shows the file list; an unsupported file is simply not opened.
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenFile = { nav.navigate(Routes.editor(it)) },
                onCompatibility = { nav.navigate(Routes.compat(it)) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.StringType })) { back ->
            val id = back.arguments?.getString("id") ?: return@composable
            EditorRoute(
                id = id,
                onBack = { nav.popBackStack() },
                onCompatibility = { nav.navigate(Routes.compat(id)) },
            )
        }
        composable(Routes.COMPAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            CompatibilityScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun EditorRoute(id: String, onBack: () -> Unit, onCompatibility: () -> Unit) {
    val container = LocalContext.current.appContainer
    var entry by remember(id) { mutableStateOf<FileEntry?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        entry = container.files.get(id)
        missing = entry == null
    }
    val e = entry
    when {
        missing -> EmptyState(Icons.Default.ErrorOutline, "File not found", "This file is no longer in the list.")
        e == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> when (e.kind.editor) {
            FileKind.Editor.DOCUMENTS -> DocsEditorScreen(onBack = onBack, onCompatibility = onCompatibility)
            FileKind.Editor.SHEETS -> SheetsEditorScreen(onBack = onBack, onCompatibility = onCompatibility)
            FileKind.Editor.SLIDES -> SlidesEditorScreen(onBack = onBack, onCompatibility = onCompatibility)
            FileKind.Editor.PHOTO -> PhotoEditorScreen(onBack = onBack)
            FileKind.Editor.VIDEO -> VideoEditorScreen(onBack = onBack)
            FileKind.Editor.PDF -> PdfViewerScreen(onBack = onBack)
        }
    }
}
