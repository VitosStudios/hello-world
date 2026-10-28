package com.vitosstudios.suite.feature.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.opc.OfficeDocument
import com.vitosstudios.suite.opc.OpcException
import com.vitosstudios.suite.opc.PartNotEditableException
import com.vitosstudios.suite.ui.components.SaveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared editor logic for DOCX, XLSX and PPTX.
 *
 * The model is only touched on the main thread. Saving takes a snapshot on the main thread and
 * writes it on a background thread: the working copy is replaced atomically after it has been
 * re-opened with the format's own reader, then the verified copy is written back to the user's
 * file. A failure leaves both the user's file and the previous working copy untouched.
 */
abstract class OfficeEditorViewModel<D : OfficeDocument>(
    protected val container: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {
    val fileId: String = checkNotNull(handle["id"]) { "Missing file id" }

    var entry by mutableStateOf<FileEntry?>(null)
        private set
    var document by mutableStateOf<D?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /** Increments on every model change (edit, undo, redo); composables read it to refresh. */
    var version by mutableLongStateOf(0L)
        private set
    var saveState by mutableStateOf(SaveState.SAVED)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** One-off messages for a snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var autosaveJob: Job? = null
    private var saving = false

    init {
        viewModelScope.launch { load() }
    }

    protected abstract fun openDocument(file: File): D

    /** Re-opens a freshly written file with the format reader; throws if it cannot be read. */
    protected abstract fun verifyWritten(file: File)

    /** Called once after the document is open (on the main thread). */
    protected open fun onOpened(doc: D) {}

    /** Content captured on the main thread and written on a background thread. */
    interface SaveSnapshot {
        val undoMarker: Long
        fun write(target: File)
    }

    /**
     * Captures what to save. The default writes the OPC package (unchanged parts copied
     * byte-identical) and verifies it with [verifyWritten]. CSV files override this.
     */
    protected open fun snapshotForSave(doc: D): SaveSnapshot {
        val snap = doc.pkg.snapshot()
        return object : SaveSnapshot {
            override val undoMarker: Long = snap.undoMarker
            override fun write(target: File) = snap.save(target) { verifyWritten(it) }
        }
    }

    private suspend fun load() {
        val e = container.files.get(fileId)
        if (e == null) {
            loadError = "This file is no longer in the list."
            return
        }
        entry = e
        container.files.touch(fileId)
        try {
            val d = withContext(Dispatchers.IO) { openDocument(File(e.workingPath)) }
            val undo = d.pkg.undo
            undo.addListener {
                version++
                canUndo = undo.canUndo
                canRedo = undo.canRedo
                if (undo.isDirty) scheduleAutosave()
            }
            document = d
            onOpened(d)
            if (e.pendingWriteBack) {
                saveState = SaveState.UNSAVED
                viewModelScope.launch { writeBackOnly() }
            }
        } catch (ex: Exception) {
            loadError = describe(ex)
        }
    }

    /**
     * Runs one user action as one undo step. Failures roll back and are reported, never
     * half-applied. Actions with the same [coalesceKey] in quick succession (typing) merge.
     */
    fun edit(label: String, coalesceKey: Any? = null, block: (D) -> Unit) {
        val d = document ?: return
        try {
            d.pkg.undo.transaction(label, coalesceKey) { block(d) }
        } catch (ex: Exception) {
            messages.tryEmit(ex.message ?: "Could not complete “$label”")
        }
    }

    fun undo() { document?.pkg?.undo?.undo() }
    fun redo() { document?.pkg?.undo?.redo() }

    fun notify(message: String) { messages.tryEmit(message) }

    private fun scheduleAutosave(delayMs: Long = 1500) {
        saveState = SaveState.UNSAVED
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(delayMs)
            saveNow()
        }
    }

    /** Saves immediately (used when the screen stops and by "retry"). */
    fun flush() {
        autosaveJob?.cancel()
        viewModelScope.launch { saveNow() }
    }

    private suspend fun saveNow() {
        val d = document ?: return
        val e = entry ?: return
        if (saving) {
            scheduleAutosave(500)
            return
        }
        val dirty = d.pkg.undo.isDirty
        if (!dirty && saveState != SaveState.FAILED) return
        saving = true
        saveState = SaveState.SAVING
        try {
            if (dirty) {
                val snapshot = snapshotForSave(d)
                val err = withContext(Dispatchers.IO) {
                    try {
                        snapshot.write(File(e.workingPath))
                        container.files.markPending(fileId)
                        null
                    } catch (ex: Exception) {
                        describe(ex)
                    }
                }
                if (err != null) {
                    fail(err)
                    return
                }
                d.pkg.undo.markSaved(snapshot.undoMarker)
            }
            writeBackOnly()
        } finally {
            saving = false
        }
    }

    private suspend fun writeBackOnly() {
        val err = container.files.writeBack(fileId)
        entry = container.files.get(fileId)
        if (err != null) fail(err) else {
            saveError = null
            saveState = if (document?.pkg?.undo?.isDirty == true) SaveState.UNSAVED else SaveState.SAVED
        }
    }

    private fun fail(message: String) {
        saveState = SaveState.FAILED
        saveError = message
    }

    override fun onCleared() {
        val d = document ?: return
        autosaveJob?.cancel()
        val e = entry
        if (e != null && d.pkg.undo.isDirty) {
            // Last chance: write the pending edits from the app scope, then close the package.
            val snapshot = snapshotForSave(d)
            container.appScope.launch(Dispatchers.IO) {
                try {
                    snapshot.write(File(e.workingPath))
                    container.files.markPending(fileId)
                    container.files.writeBack(fileId)
                } catch (_: Exception) {
                } finally {
                    d.close()
                }
            }
        } else {
            d.close()
        }
    }

    protected fun describe(ex: Throwable): String = when (ex) {
        is PartNotEditableException -> "Part of this file cannot be edited safely: ${ex.partName}"
        is OpcException -> ex.message ?: "The file is not a valid package"
        is java.util.zip.ZipException -> "The file is damaged or not a supported format"
        is OutOfMemoryError -> "The file is too large to open on this device"
        else -> ex.message ?: ex.javaClass.simpleName
    }
}
