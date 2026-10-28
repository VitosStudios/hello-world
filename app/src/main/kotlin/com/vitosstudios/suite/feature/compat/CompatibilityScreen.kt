package com.vitosstudios.suite.feature.compat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.docx.DocxDocument
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.SupportLevel
import com.vitosstudios.suite.pptx.PptxPresentation
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.theme.Spacing
import com.vitosstudios.suite.xlsx.XlsxWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Per-file compatibility: which content is editable, read-only (shown, locked) or preserved
 * (kept unchanged, not shown). Built from the saved working copy.
 */
@Composable
fun CompatibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val id = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.arguments?.getString("id") ?: return
    var report by remember { mutableStateOf<CompatibilityReport?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showAbsent by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val e = context.appContainer.files.get(id) ?: run { error = "This file is no longer in the list."; return@LaunchedEffect }
        name = e.displayName
        report = try {
            withContext(Dispatchers.IO) {
                val f = File(e.workingPath)
                when (e.kind) {
                    FileKind.DOCX -> DocxDocument.open(f).use { it.compatibility() }
                    FileKind.XLSX -> XlsxWorkbook.open(f).use { it.compatibility() }
                    FileKind.PPTX -> PptxPresentation.open(f).use { it.compatibility() }
                    else -> null
                }
            }
        } catch (ex: Exception) {
            error = ex.message ?: "The report could not be built"
            null
        }
        if (report == null && error == null) error = "Compatibility details are available for DOCX, XLSX and PPTX files."
    }
    Scaffold(topBar = { SuiteTopBar(title = "Compatibility", subtitle = name, onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val r = report
            if (LoadingOrError(r == null, error) && r != null) {
                LazyColumn(contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    item {
                        Text(
                            "This ${r.format} file is saved in its own format. Content the app does not edit is never removed: " +
                                "it is shown with a lock or kept unchanged in the file.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
                            FilterChip(selected = showAbsent, onClick = { showAbsent = !showAbsent }, label = { Text("Show features not in this file") })
                        }
                    }
                    for (level in SupportLevel.entries) {
                        val items = r.items.filter { it.level == level && (showAbsent || it.count > 0) }
                        if (items.isEmpty()) continue
                        item {
                            val (title, icon) = when (level) {
                                SupportLevel.EDITABLE -> "Editable" to Icons.Default.Edit
                                SupportLevel.READ_ONLY -> "Shown, read-only" to Icons.Default.Lock
                                SupportLevel.PRESERVED -> "Preserved unchanged" to Icons.Default.Inventory2
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.m)) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(Spacing.s))
                                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            }
                        }
                        items(items, key = { level.name + it.feature }) { item ->
                            Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                contentDescription = "${item.feature}: ${item.count} in this file. ${item.detail}"
                            }) {
                                Column(Modifier.padding(Spacing.m)) {
                                    Row {
                                        Text(item.feature, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Text(if (item.count > 0) "${item.count}" else "none", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (r.unparsedParts.isNotEmpty()) {
                        item {
                            Text("Parts kept byte-identical because they could not be read safely", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.m))
                        }
                        items(r.unparsedParts) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
