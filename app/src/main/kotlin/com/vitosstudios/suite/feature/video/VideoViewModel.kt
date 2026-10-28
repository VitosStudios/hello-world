package com.vitosstudios.suite.feature.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.video.ExportPreset
import com.vitosstudios.suite.video.Timeline
import com.vitosstudios.suite.video.TimelineSession
import com.vitosstudios.suite.video.TimelineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video editing works on a timeline project (autosaved, restored after process death). Source
 * clips are never modified; the result is exported to a new MP4.
 */
class VideoViewModel(private val app: SuiteApplication, handle: SavedStateHandle) : ViewModel() {
    private val container: AppContainer = app.container
    val fileId: String = checkNotNull(handle["id"])

    var entry by mutableStateOf<FileEntry?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    var timeline by mutableStateOf(Timeline()); private set
    var selectedClip by mutableStateOf<String?>(null)
    var positionMs by mutableLongStateOf(0L)
    var exporting by mutableStateOf(false); private set
    var exportProgress by mutableFloatStateOf(0f)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val session = TimelineSession()
    private var saveJob: Job? = null
    private val projectFile: File? get() = entry?.let { File(it.workingPath) }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val e = container.files.get(fileId) ?: run { loadError = "This file is no longer in the list."; return }
        entry = e
        container.files.touch(fileId)
        try {
            val restored = withContext(Dispatchers.IO) { projectFile?.let { TimelineStore.load(it) } }
            val t = restored ?: run {
                val uri = e.uri ?: throw IllegalStateException("The video has no source")
                val d = withContext(Dispatchers.IO) { durationOf(app, Uri.parse(uri)) }
                Timeline().addClip(uri, d)
            }
            session.reset(t)
            publish()
        } catch (ex: Exception) {
            loadError = ex.message ?: "The video cannot be opened"
        }
    }

    private fun publish() {
        timeline = session.timeline
        canUndo = session.canUndo
        canRedo = session.canRedo
        if (selectedClip != null && timeline.clipIndex(selectedClip!!) < 0) selectedClip = null
        positionMs = positionMs.coerceIn(0, timeline.durationMs)
        scheduleSave()
    }

    fun apply(op: (Timeline) -> Timeline) { session.apply(op); publish() }
    fun drag(op: (Timeline) -> Timeline) { session.drag(op); publish() }
    fun endDrag() { session.endDrag(); publish() }
    fun undo() { session.undo(); publish() }
    fun redo() { session.redo(); publish() }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val f = projectFile ?: return@launch
            val t = timeline
            try {
                withContext(Dispatchers.IO) { TimelineStore.save(f, t) }
            } catch (ex: Exception) {
                messages.emit("Autosave failed: ${ex.message}")
            }
        }
    }

    fun selectedIndex(): Int = selectedClip?.let { timeline.clipIndex(it) } ?: -1

    fun addClip(uri: Uri) {
        viewModelScope.launch {
            try {
                takePermission(uri)
                val d = withContext(Dispatchers.IO) { durationOf(app, uri) }
                apply { it.addClip(uri.toString(), d) }
            } catch (ex: Exception) {
                messages.emit("This clip cannot be added: ${ex.message}")
            }
        }
    }

    fun setMusic(uri: Uri) {
        viewModelScope.launch {
            try {
                takePermission(uri)
                val d = withContext(Dispatchers.IO) { durationOf(app, uri) }
                apply { it.setMusic(uri.toString(), d) }
            } catch (ex: Exception) {
                messages.emit("This audio file cannot be used: ${ex.message}")
            }
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
    }

    fun split() = apply { it.splitAt(positionMs) }

    /** Start of a segment to delete, marked at the playhead. */
    var segmentStartMs by mutableStateOf<Long?>(null)

    fun markSegmentStart() { segmentStartMs = positionMs }

    /** Deletes the timeline range between the mark and the playhead (across clips). */
    fun deleteSegment() {
        val start = segmentStartMs ?: return notify("Mark the start of the segment first")
        if (kotlin.math.abs(positionMs - start) < 100) return notify("Move the playhead to the end of the segment")
        if (minOf(start, positionMs) <= 0 && maxOf(start, positionMs) >= timeline.durationMs) return notify("The whole video cannot be deleted")
        apply { it.deleteRange(start, positionMs) }
        positionMs = minOf(start, positionMs)
        segmentStartMs = null
    }

    fun deleteSelected() {
        val i = selectedIndex()
        if (i < 0) return notify("Select a clip first")
        if (timeline.clips.size == 1) return notify("The timeline needs at least one clip")
        apply { it.deleteClip(i) }
    }

    fun moveSelected(delta: Int) {
        val i = selectedIndex()
        if (i < 0) return notify("Select a clip first")
        val to = i + delta
        if (to !in timeline.clips.indices) return
        apply { it.moveClip(i, to) }
    }

    fun notify(m: String) { messages.tryEmit(m) }

    fun startExport(context: Context, preset: ExportPreset, onDone: (File) -> Unit) {
        if (exporting) return
        exporting = true
        exportProgress = 0f
        val name = com.vitosstudios.suite.video.ExportPlanner.fileName(entry?.displayName ?: "video.mp4", preset)
        val out = File(container.files.exportDir(), name)
        VideoExporter.export(context, timeline, preset, out,
            onProgress = { exportProgress = it },
            onDone = { err ->
                exporting = false
                if (err == null) onDone(out) else notify("Export failed: $err")
            },
        )
    }

    fun writeExport(file: File, target: Uri) {
        viewModelScope.launch { messages.emit(container.files.writeTo(target, file) ?: "Video exported") }
    }

    companion object {
        fun durationOf(context: Context, uri: Uri): Long {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalArgumentException("Unknown duration")
            } finally {
                r.release()
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                VideoViewModel(app, createSavedStateHandle())
            }
        }
    }
}
