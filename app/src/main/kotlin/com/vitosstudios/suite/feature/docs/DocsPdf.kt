package com.vitosstudios.suite.feature.docs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.vitosstudios.suite.appContainer
import kotlinx.coroutines.launch
import java.io.File

/** "Export as PDF" and "Share as PDF" for the document editor. */
class PdfExportHandle(val export: () -> Unit, val share: () -> Unit)

@Composable
fun rememberPdfExport(vm: DocsViewModel): PdfExportHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<File>(1) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pending[0]
        if (uri != null && file != null) scope.launch {
            val err = context.appContainer.files.writeTo(uri, file)
            vm.notify(err ?: "PDF exported")
        }
    }
    fun render(): File? {
        val doc = vm.document ?: return null
        val name = (vm.entry?.displayName ?: "Document").substringBeforeLast('.') + ".pdf"
        val out = File(context.appContainer.files.exportDir(), name)
        return try {
            // Layout reads the model, so it runs on the main thread like every other model access.
            DocxPdfExporter(doc).export(out)
            out
        } catch (e: Exception) {
            vm.notify("PDF export failed: ${e.message}")
            null
        }
    }
    return remember(vm) {
        PdfExportHandle(
            export = { render()?.let { f -> pending[0] = f; create.launch(f.name) } },
            share = { render()?.let { f -> shareFile(context, f, "application/pdf") } },
        )
    }
}
