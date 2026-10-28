package com.vitosstudios.suite.feature.photo

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.common.AtomicFile
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.photo.AdjustmentKind
import com.vitosstudios.suite.photo.AspectPreset
import com.vitosstudios.suite.photo.CropRect
import com.vitosstudios.suite.photo.ExifPolicy
import com.vitosstudios.suite.photo.FilterPreset
import com.vitosstudios.suite.photo.Geometry
import com.vitosstudios.suite.photo.PhotoEditSession
import com.vitosstudios.suite.photo.PhotoEditState
import com.vitosstudios.suite.photo.PhotoExportOptions
import com.vitosstudios.suite.photo.PhotoFormat
import com.vitosstudios.suite.photo.PhotoGeometry
import com.vitosstudios.suite.photo.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PhotoTool { CROP, ADJUST, FILTERS }

/**
 * Photo editing is non-destructive: the edit recipe is autosaved continuously, the image is only
 * re-encoded when the user saves over the original (same format) or exports a new file.
 */
class PhotoViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {
    val fileId: String = checkNotNull(handle["id"])

    var entry by mutableStateOf<FileEntry?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    var state by mutableStateOf(PhotoEditState()); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    /** Upright preview source with geometry but no crop (for the crop tool). */
    var frame by mutableStateOf<Bitmap?>(null); private set
    var original by mutableStateOf<Bitmap?>(null); private set
    var tool by mutableStateOf(PhotoTool.ADJUST)
    var showBefore by mutableStateOf(false)
    var aspect by mutableStateOf(AspectPreset.FREE)
    var busy by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val session = PhotoEditSession()
    private var source: Bitmap? = null
    private var renderJob: Job? = null
    private var saveJob: Job? = null
    private val recipeFile: File? get() = entry?.let { File(File(it.workingPath).parentFile, "photo-recipe.txt") }

    val sourceSize: Size? get() = source?.let { Size(it.width, it.height) }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val e = container.files.get(fileId) ?: run { loadError = "This file is no longer in the list."; return }
        entry = e
        container.files.touch(fileId)
        try {
            val bmp = withContext(Dispatchers.IO) { PhotoRender.decodeUpright(File(e.workingPath), PREVIEW_EDGE) }
            source = bmp
            original = bmp
            val restored = withContext(Dispatchers.IO) { recipeFile?.takeIf { it.isFile }?.readText()?.let(PhotoRecipe::decode) }
            if (restored != null) session.reset(restored)
            state = session.state
            refresh()
        } catch (ex: Exception) {
            loadError = ex.message ?: "This image cannot be opened"
        } catch (oom: OutOfMemoryError) {
            loadError = "This image is too large for this device"
        }
    }

    private fun publish(newState: PhotoEditState) {
        state = newState
        canUndo = session.canUndo
        canRedo = session.canRedo
        refresh()
        scheduleRecipeSave()
    }

    fun apply(change: (PhotoEditState) -> PhotoEditState) = publish(session.apply(change))
    fun drag(change: (PhotoEditState) -> PhotoEditState) = publish(session.drag(change))
    fun endDrag() { session.endDrag(); publish(session.state) }
    fun undo() = publish(session.undo())
    fun redo() = publish(session.redo())

    fun setAdjustment(kind: AdjustmentKind, value: Float) = drag { it.withAdjustment(kind, value) }
    fun applyPreset(p: FilterPreset) = apply { it.withPreset(p) }
    fun rotate(clockwise: Boolean) = apply { it.withGeometry(if (clockwise) it.geometry.rotateClockwise() else it.geometry.rotateCounterClockwise()) }
    fun flip(horizontal: Boolean) = apply { it.withGeometry(if (horizontal) it.geometry.flipHorizontal() else it.geometry.flipVertical()) }
    fun straighten(degrees: Float) = drag { it.withGeometry(it.geometry.withStraighten(degrees)) }
    fun setCrop(rect: CropRect, live: Boolean) = if (live) drag { it.withGeometry(it.geometry.withCrop(rect)) } else apply { it.withGeometry(it.geometry.withCrop(rect)) }
    fun resetAll() = apply { it.reset() }

    fun chooseAspect(p: AspectPreset) {
        aspect = p
        val src = sourceSize ?: return
        val frameSize = PhotoGeometry.frameSizePx(src, state.geometry)
        apply { it.withGeometry(it.geometry.withCrop(p.centeredCrop(frameSize, src))) }
    }

    /** Re-renders the preview (and the crop frame) off the main thread, newest request wins. */
    private fun refresh() {
        val src = source ?: return
        val s = state
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val (p, f) = withContext(Dispatchers.Default) {
                val frameGeometry = s.geometry.copy(crop = CropRect.FULL)
                val frameBmp = PhotoRender.applyGeometry(src, frameGeometry)
                PhotoRender.render(src, s) to frameBmp
            }
            preview = p
            frame = f
        }
    }

    private fun scheduleRecipeSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            val f = recipeFile ?: return@launch
            val text = PhotoRecipe.encode(state)
            withContext(Dispatchers.IO) { AtomicFile.write(f) { it.write(text.toByteArray()) } }
        }
    }

    fun presetThumbnail(p: FilterPreset, thumb: Bitmap): Bitmap = PhotoRender.applyColor(thumb, p.adjustments)

    /** Full-resolution render to a temporary file in [options]' format, with EXIF per policy. */
    private suspend fun renderFull(options: PhotoExportOptions, name: String): File = withContext(Dispatchers.Default) {
        val e = entry ?: error("No file")
        val working = File(e.workingPath)
        val full = PhotoRender.decodeUpright(working, null)
        val out = PhotoGeometry.outputSize(Size(full.width, full.height), state.geometry)
        val scale = options.scale(out)
        val rendered = PhotoRender.render(full, state, scale)
        val target = File(container.files.exportDir(), name)
        val format = when (options.format) {
            PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
            PhotoFormat.PNG -> Bitmap.CompressFormat.PNG
            PhotoFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        AtomicFile.write(target, verify = { tmp ->
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, o)
            if (o.outWidth != rendered.width || o.outHeight != rendered.height) throw IllegalStateException("The encoded image could not be read back")
        }) { os ->
            val bg = if (!options.format.supportsAlpha) flatten(rendered) else rendered
            if (!bg.compress(format, options.validated().quality, os)) throw IllegalStateException("Encoding failed")
        }
        if (options.format != PhotoFormat.PNG) copyExif(working, target, options, Size(rendered.width, rendered.height))
        target
    }

    private fun flatten(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return out
    }

    private fun copyExif(from: File, to: File, options: PhotoExportOptions, size: Size) {
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (tag in ExifPolicy.tagsToCopy(options)) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            for ((tag, v) in ExifPolicy.tagsToWrite(size)) dst.setAttribute(tag, v)
            dst.saveAttributes()
        } catch (_: Exception) {
            // Metadata is optional; the exported pixels are already verified.
        }
    }

    /** Export to a new file chosen by the user. */
    fun export(options: PhotoExportOptions, target: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val name = options.fileName(entry?.displayName ?: "photo.jpg")
                val f = renderFull(options, name)
                messages.emit(container.files.writeTo(target, f) ?: "Exported ${f.name}")
            } catch (ex: Throwable) {
                messages.emit("Export failed: ${ex.message ?: ex.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    /** Saves over the original in its own format (after the user confirmed). */
    fun saveOverOriginal() {
        val e = entry ?: return
        val fmt = PhotoFormat.forMimeType(e.kind.primaryMime) ?: PhotoFormat.JPEG
        viewModelScope.launch {
            busy = true
            try {
                val f = renderFull(PhotoExportOptions(format = fmt, quality = 95, keepMetadata = true, keepLocation = true), "save.${fmt.extension}")
                withContext(Dispatchers.IO) {
                    AtomicFile.write(File(e.workingPath)) { os -> f.inputStream().use { it.copyTo(os) } }
                    container.files.markPending(fileId)
                }
                val err = container.files.writeBack(fileId)
                if (err == null) {
                    // The saved image is the new original; start a fresh edit on top of it.
                    val bmp = withContext(Dispatchers.IO) { PhotoRender.decodeUpright(File(e.workingPath), PREVIEW_EDGE) }
                    source = bmp
                    original = bmp
                    session.reset(PhotoEditState())
                    withContext(Dispatchers.IO) { recipeFile?.delete() }
                    publish(session.state)
                }
                messages.emit(err ?: "Saved")
            } catch (ex: Throwable) {
                messages.emit("Save failed: ${ex.message ?: ex.javaClass.simpleName}. The original was not changed.")
            } finally {
                busy = false
            }
        }
    }

    companion object {
        const val PREVIEW_EDGE = 1600

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                PhotoViewModel(app.container, createSavedStateHandle())
            }
        }
    }
}

@Suppress("unused")
private val keepGeometryImport: Geometry? = null
