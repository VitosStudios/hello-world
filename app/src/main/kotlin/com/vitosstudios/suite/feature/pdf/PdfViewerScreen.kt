package com.vitosstudios.suite.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Read-only PDF viewer (rendering with the platform PdfRenderer). PDFs are never modified. */
@Composable
fun PdfViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current as? NavBackStackEntry
    val id = owner?.arguments?.getString("id") ?: return
    var entry by remember { mutableStateOf<FileEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    val lock = remember { Mutex() }
    LaunchedEffect(id) {
        val e = context.appContainer.files.get(id) ?: run { error = "This file is no longer in the list."; return@LaunchedEffect }
        entry = e
        context.appContainer.files.touch(id)
        try {
            renderer = withContext(Dispatchers.IO) {
                PdfRenderer(ParcelFileDescriptor.open(File(e.workingPath), ParcelFileDescriptor.MODE_READ_ONLY))
            }
        } catch (ex: Exception) {
            error = if (ex is SecurityException) "This PDF is password protected and cannot be shown." else (ex.message ?: "This PDF cannot be opened")
        }
    }
    DisposableEffect(Unit) { onDispose { renderer?.close() } }
    Scaffold(topBar = { SuiteTopBar(title = entry?.displayName ?: "PDF", subtitle = "PDF • view only", onBack = onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            val r = renderer
            if (LoadingOrError(r == null, error) && r != null) {
                val pages = (0 until r.pageCount).toList()
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val widthPx = with(LocalDensity.current) { minOf(maxWidth, 900.dp).toPx() }.toInt()
                    LazyColumn(contentPadding = PaddingValues(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.widthIn(max = 900.dp)) {
                        items(pages) { i ->
                            val bmp by produceState<Bitmap?>(null, r, i, widthPx) {
                                value = withContext(Dispatchers.IO) {
                                    lock.withLock {
                                        r.openPage(i).use { page ->
                                            val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                            Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { b ->
                                                b.eraseColor(AColor.WHITE)
                                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            }
                                        }
                                    }
                                }
                            }
                            Surface(shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Page ${i + 1} of ${pages.size}" }) {
                                val b = bmp
                                if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                                else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(androidx.compose.ui.graphics.Color.White))
                            }
                        }
                    }
                }
            }
        }
    }
}
