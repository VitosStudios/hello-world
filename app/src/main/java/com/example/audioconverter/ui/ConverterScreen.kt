package com.example.audioconverter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.audioconverter.audio.AudioConverter
import com.example.audioconverter.audio.ConversionEvent
import com.example.audioconverter.audio.OutputFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Idle : UiState
    data class Running(val percent: Int) : UiState
    data class Success(val outputUri: Uri) : UiState
    data class Error(val message: String) : UiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputName by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf(OutputFormat.MP3) }
    val state = remember { MutableStateFlow<UiState>(UiState.Idle) }
    val uiState by state.asStateFlow().collectAsState()

    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            inputUri = uri
            inputName = DocumentFile.fromSingleUri(ctx, uri)?.name
            state.value = UiState.Idle
        }
    }

    val pickOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val src = inputUri ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val chosenFormat = format
        scope.launch {
            AudioConverter.convert(ctx, src, uri, chosenFormat).collect { ev ->
                when (ev) {
                    is ConversionEvent.Progress ->
                        state.value = UiState.Running(ev.percent.coerceAtLeast(0))
                    is ConversionEvent.Done -> state.value = UiState.Success(ev.outputUri)
                    is ConversionEvent.Failed -> state.value = UiState.Error(ev.reason)
                    is ConversionEvent.Log -> Unit
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Converter") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            FilePickerCard(
                inputName = inputName,
                onPick = { pickInput.launch(arrayOf("audio/*", "video/*")) },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Zielformat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            FormatGrid(selected = format, onSelect = { format = it })

            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(format.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        format.description,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            ActionArea(
                state = uiState,
                canStart = inputUri != null && uiState !is UiState.Running,
                onStart = {
                    val base = inputName?.substringBeforeLast('.') ?: "audio"
                    pickOutput.launch("$base.${format.extension}")
                },
            )
        }
    }
}

@Composable
private fun FilePickerCard(inputName: String?, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.AudioFile,
                contentDescription = null,
                modifier = Modifier.height(48.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                inputName ?: "Keine Datei ausgewählt",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPick) {
                Text(if (inputName == null) "Datei auswählen" else "Andere Datei")
            }
        }
    }
}

@Composable
private fun FormatGrid(selected: OutputFormat, onSelect: (OutputFormat) -> Unit) {
    val all = OutputFormat.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { fmt ->
                    FilterChip(
                        selected = fmt == selected,
                        onClick = { onSelect(fmt) },
                        label = { Text(fmt.displayName) },
                        leadingIcon = if (fmt == selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionArea(state: UiState, canStart: Boolean, onStart: () -> Unit) {
    when (state) {
        is UiState.Running -> {
            Text("Konvertiere… ${state.percent}%")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is UiState.Success -> {
            val ctx = LocalContext.current
            Text(
                "Fertig! Gespeichert.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(state.outputUri, "audio/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, "Öffnen mit"))
            }) { Text("Datei öffnen") }
        }
        is UiState.Error -> {
            Text(
                "Fehler: ${state.message.take(400)}",
                color = MaterialTheme.colorScheme.error,
            )
        }
        UiState.Idle -> Unit
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onStart,
        enabled = canStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.height(0.dp))
        Text(" Konvertierung starten")
    }
}
