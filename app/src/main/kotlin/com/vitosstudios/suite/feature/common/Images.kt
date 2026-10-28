package com.vitosstudios.suite.feature.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Image decoding with downsampling, shared by the editors. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    data class Info(val width: Int, val height: Int, val mime: String?)

    fun bounds(bytes: ByteArray): Info {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return Info(o.outWidth, o.outHeight, o.outMimeType)
    }

    /** Decodes [bytes] so that the longer edge is at most about [maxEdge] pixels. */
    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val info = bounds(bytes)
        if (info.width <= 0 || info.height <= 0) return null
        var sample = 1
        while (maxOf(info.width, info.height) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun cached(key: String, maxEdge: Int, load: () -> ByteArray?): Bitmap? {
        val k = "$key@$maxEdge"
        cache.get(k)?.let { return it }
        val bytes = load() ?: return null
        val bmp = decode(bytes, maxEdge) ?: return null
        cache.put(k, bmp)
        return bmp
    }

    fun readUri(context: Context, uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    /** File extension for an image MIME type ("png", "jpg", ...). */
    fun extensionFor(mime: String?): String = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }
}

/** Loads a bitmap off the main thread and caches it. */
@Composable
fun rememberBitmap(key: String, maxEdge: Int, load: () -> ByteArray?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, key, maxEdge) {
        value = withContext(Dispatchers.IO) { ImageLoader.cached(key, maxEdge, load)?.asImageBitmap() }
    }
    return state
}
