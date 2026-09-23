package com.example.audioconverter.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.audioconverter.audio.AudioConverter
import com.example.audioconverter.audio.ConversionEvent
import com.example.audioconverter.audio.EditOptions
import com.example.audioconverter.audio.OutputFormat
import com.example.audioconverter.server.ServerClient
import com.example.audioconverter.server.ServerFormat
import com.example.audioconverter.server.ServerJob
import com.example.audioconverter.server.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private sealed interface UiState {
    data object Idle : UiState
    /** [percent] < 0 shows an indeterminate bar. */
    data class Running(val label: String, val percent: Int) : UiState
    data class Success(val outputUri: Uri, val mime: String) : UiState
    data class Error(val message: String) : UiState
}

private enum class Source(val title: String) { FILE("Datei"), LINK("Link") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(sharedLink: String?, onSharedLinkConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { ServerSettings(ctx) }

    var source by remember { mutableStateOf(Source.FILE) }
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputName by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    var localFormat by remember { mutableStateOf(OutputFormat.MP3) }
    var serverFormats by remember { mutableStateOf<List<ServerFormat>>(emptyList()) }
    var serverFormatId by remember { mutableStateOf("mp3") }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var formatsReload by remember { mutableStateOf(0) }

    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(1f) }
    var normalize by remember { mutableStateOf(false) }

    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    var pendingJob by remember { mutableStateOf<ServerJob?>(null) }
    var showSettings by remember { mutableStateOf(!settings.isConfigured && sharedLink != null) }

    LaunchedEffect(sharedLink) {
        if (sharedLink != null) {
            source = Source.LINK
            link = sharedLink
            state = UiState.Idle
            onSharedLinkConsumed()
        }
    }

    LaunchedEffect(source, formatsReload) {
        if (source != Source.LINK) return@LaunchedEffect
        serverMessage = null
        try {
            serverFormats = ServerClient.formats(settings)
            if (serverFormats.none { it.id == serverFormatId }) {
                serverFormatId = serverFormats.firstOrNull()?.id ?: serverFormatId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverMessage = e.message ?: "Server nicht erreichbar."
        }
    }

    fun currentEdit(): EditOptions? = try {
        EditOptions(
            startSec = EditOptions.parseTime(startText),
            endSec = EditOptions.parseTime(endText),
            speed = speed.toDouble(),
            normalize = normalize,
        ).also { opts -> opts.validate()?.let { throw IllegalArgumentException(it) } }
    } catch (e: IllegalArgumentException) {
        state = UiState.Error(e.message ?: "Ungültige Eingabe")
        null
    }

    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            inputUri = uri
            inputName = DocumentFile.fromSingleUri(ctx, uri)?.name
            state = UiState.Idle
        }
    }

    val saveLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val src = inputUri ?: return@rememberLauncherForActivityResult
        val edit = currentEdit() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val fmt = localFormat
        state = UiState.Running("Konvertiere…", -1)
        scope.launch {
            AudioConverter.convert(ctx, src, uri, fmt, edit).collect { ev ->
                state = when (ev) {
                    is ConversionEvent.Progress -> UiState.Running("Konvertiere…", ev.percent)
                    is ConversionEvent.Done -> UiState.Success(ev.outputUri, fmt.mimeType)
                    is ConversionEvent.Failed -> UiState.Error(ev.reason)
                    is ConversionEvent.Log -> state
                }
            }
        }
    }

    val saveServerResult = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val job = pendingJob ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            state = UiState.Error("Speichern abgebrochen. Das Ergebnis liegt noch einige Stunden auf dem Server.")
            return@rememberLauncherForActivityResult
        }
        val video = serverFormats.firstOrNull { it.id == serverFormatId }?.video == true
        state = UiState.Running("Lade Ergebnis herunter…", -1)
        scope.launch {
            state = try {
                ServerClient.download(ctx, settings, job.id, uri)
                pendingJob = null
                UiState.Success(uri, if (video) "video/*" else "audio/*")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Download fehlgeschlagen.")
            }
        }
    }

    fun startLink() {
        val edit = currentEdit() ?: return
        val url = link.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            state = UiState.Error("Bitte einen gültigen Link (https://…) eingeben.")
            return
        }
        state = UiState.Running("Sende Auftrag…", -1)
        scope.launch {
            try {
                var job = ServerClient.createJob(settings, url, serverFormatId, edit)
                while (!job.finished) {
                    val label = if (job.status == "downloading") "Server lädt herunter…" else "Server konvertiert…"
                    state = UiState.Running(label, (job.progress * 100).toInt())
                    delay(1000)
                    job = ServerClient.job(settings, job.id)
                }
                if (job.status == "error") {
                    state = UiState.Error(job.error ?: "Unbekannter Fehler auf dem Server.")
                } else {
                    pendingJob = job
                    state = UiState.Running("Speicherort wählen…", 100)
                    saveServerResult.launch(job.filename ?: "media")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UiState.Error(e.message ?: "Server nicht erreichbar.")
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onDismiss = {
                showSettings = false
                formatsReload++
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Studio") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                },
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
                .verticalScroll(rememberScrollState()),
        ) {
            TabRow(selectedTabIndex = source.ordinal) {
                Source.entries.forEach { s ->
                    Tab(
                        selected = source == s,
                        onClick = { source = s; state = UiState.Idle },
                        text = { Text(s.title) },
                    )
                }
            }

            Column(Modifier.padding(16.dp)) {
                when (source) {
                    Source.FILE -> FilePickerCard(
                        inputName = inputName,
                        onPick = { pickInput.launch(arrayOf("audio/*", "video/*")) },
                    )
                    Source.LINK -> LinkCard(
                        link = link,
                        onLinkChange = { link = it },
                        serverMessage = serverMessage,
                        onOpenSettings = { showSettings = true },
                    )
                }

                Spacer(Modifier.height(20.dp))
                SectionTitle("Zielformat")
                when (source) {
                    Source.FILE -> {
                        ChipGrid(
                            items = OutputFormat.entries,
                            label = { it.displayName },
                            isSelected = { it == localFormat },
                            onSelect = { localFormat = it },
                        )
                        FormatHint(localFormat.description)
                    }
                    Source.LINK -> if (serverFormats.isNotEmpty()) {
                        ChipGrid(
                            items = serverFormats,
                            label = { it.label },
                            isSelected = { it.id == serverFormatId },
                            onSelect = { serverFormatId = it.id },
                        )
                        serverFormats.firstOrNull { it.id == serverFormatId }
                            ?.let { FormatHint(it.description) }
                    } else {
                        Text(
                            "Formate werden vom Server geladen…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                SectionTitle("Bearbeiten")
                EditSection(
                    startText = startText,
                    onStartChange = { startText = it },
                    endText = endText,
                    onEndChange = { endText = it },
                    speed = speed,
                    onSpeedChange = { speed = it },
                    normalize = normalize,
                    onNormalizeChange = { normalize = it },
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                val running = state is UiState.Running
                ActionArea(
                    state = state,
                    canStart = !running && when (source) {
                        Source.FILE -> inputUri != null
                        Source.LINK -> link.isNotBlank() && serverFormats.isNotEmpty()
                    },
                    onStart = {
                        when (source) {
                            Source.FILE -> {
                                if (currentEdit() == null) return@ActionArea
                                val base = inputName?.substringBeforeLast('.') ?: "audio"
                                saveLocal.launch("$base.${localFormat.extension}")
                            }
                            Source.LINK -> startLink()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FormatHint(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilePickerCard(inputName: String?, onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(inputName ?: "Keine Datei ausgewählt", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPick) {
                Text(if (inputName == null) "Datei auswählen" else "Andere Datei")
            }
        }
    }
}

@Composable
private fun LinkCard(
    link: String,
    onLinkChange: (String) -> Unit,
    serverMessage: String?,
    onOpenSettings: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = link,
                onValueChange = onLinkChange,
                label = { Text("YouTube- oder anderer Link") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let(onLinkChange) }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Einfügen")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tipp: In YouTube auf „Teilen“ tippen und Media Studio wählen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (serverMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(serverMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text("Server einstellen") }
            }
        }
    }
}

@Composable
private fun <T> ChipGrid(
    items: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    val selected = isSelected(item)
                    FilterChip(
                        selected = selected,
                        onClick = { onSelect(item) },
                        label = { Text(label(item)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditSection(
    startText: String,
    onStartChange: (String) -> Unit,
    endText: String,
    onEndChange: (String) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    normalize: Boolean,
    onNormalizeChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = onStartChange,
                    label = { Text("Start") },
                    placeholder = { Text("0:00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = onEndChange,
                    label = { Text("Ende") },
                    placeholder = { Text("bis Schluss") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Zeit als Sekunden oder m:ss, z. B. 1:30",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", modifier = Modifier.weight(1f))
                Text(String.format(Locale.GERMANY, "%.2f×", speed), fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = speed,
                onValueChange = { onSpeedChange((it * 20).toInt() / 20f) },
                valueRange = 0.25f..4f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.5f, 2f).forEach { preset ->
                    OutlinedButton(onClick = { onSpeedChange(preset) }) {
                        Text(String.format(Locale.GERMANY, "%.1f×", preset))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lautstärke normalisieren")
                    Text(
                        "Gleicht auf −14 LUFS an (Streaming-Standard)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = normalize, onCheckedChange = onNormalizeChange)
            }
        }
    }
}

@Composable
private fun ActionArea(state: UiState, canStart: Boolean, onStart: () -> Unit) {
    val ctx = LocalContext.current
    when (state) {
        is UiState.Running -> {
            Text(if (state.percent >= 0) "${state.label} ${state.percent} %" else state.label)
            Spacer(Modifier.height(8.dp))
            if (state.percent >= 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is UiState.Success -> {
            Text("Fertig! Gespeichert.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(state.outputUri, state.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(intent, "Öffnen mit"))
            }) { Text("Datei öffnen") }
        }
        is UiState.Error -> Text("Fehler: ${state.message.take(600)}", color = MaterialTheme.colorScheme.error)
        UiState.Idle -> Unit
    }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Starten")
    }
}

@Composable
private fun SettingsDialog(settings: ServerSettings, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(settings.baseUrl) }
    var token by remember { mutableStateOf(settings.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Für Links (YouTube usw.) wird dein eigener Media-Studio-Server genutzt. " +
                        "Lokale Dateien werden direkt auf dem Handy konvertiert.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server-Adresse") },
                    placeholder = { Text("https://media.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.baseUrl = url
                settings.token = token
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
