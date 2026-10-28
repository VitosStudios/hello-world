package com.vitosstudios.suite.video

import com.vitosstudios.suite.common.AtomicFile
import java.io.File

class TimelineFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Text form of a [Timeline] for autosave and process-death recovery. Plain JSON written and read
 * by hand (no extra dependency). Unknown keys are ignored so newer versions stay readable.
 */
object TimelineCodec {
    const val FORMAT = "suite-video-timeline"
    const val VERSION = 1

    fun encode(t: Timeline): String {
        val w = JsonWriter()
        w.obj {
            field("format", FORMAT)
            field("version", VERSION)
            field("preset", t.preset.name)
            key("clips"); array(t.clips) { c ->
                obj {
                    field("id", c.id); field("uri", c.sourceUri); field("sourceDurationMs", c.sourceDurationMs)
                    field("inMs", c.inMs); field("outMs", c.outMs); field("volume", c.volume); field("muted", c.muted)
                }
            }
            key("music")
            val m = t.music
            if (m == null) nullValue() else obj {
                field("uri", m.uri); field("durationMs", m.durationMs); field("volume", m.volume)
                field("loop", m.loop); field("offsetMs", m.offsetMs)
            }
            key("overlays"); array(t.overlays) { o ->
                obj {
                    field("id", o.id); field("text", o.text); field("startMs", o.startMs); field("endMs", o.endMs)
                    field("fontSizeSp", o.fontSizeSp); field("color", "#%08X".format(o.colorArgb))
                    field("x", o.x); field("y", o.y); field("anchor", o.anchor.name); field("bold", o.bold)
                }
            }
        }
        return w.toString()
    }

    fun decode(text: String): Timeline {
        val root = try {
            JsonReader(text).readDocument()
        } catch (e: TimelineFormatException) {
            throw e
        } catch (e: Exception) {
            throw TimelineFormatException("Not a timeline: ${e.message}", e)
        }
        val o = root as? Map<*, *> ?: throw TimelineFormatException("Not a timeline object")
        if (o["format"] != FORMAT) throw TimelineFormatException("Unknown format ${o["format"]}")
        val version = (o["version"] as? Number)?.toInt() ?: throw TimelineFormatException("Missing version")
        if (version > VERSION) throw TimelineFormatException("Timeline version $version is newer than this app")
        try {
            val clips = (o["clips"] as? List<*>).orEmpty().map { item ->
                val c = item as Map<*, *>
                Clip(
                    id = c.str("id"), sourceUri = c.str("uri"), sourceDurationMs = c.long("sourceDurationMs"),
                    inMs = c.long("inMs"), outMs = c.long("outMs"),
                    volume = c.float("volume", 1f), muted = c["muted"] as? Boolean ?: false,
                )
            }
            val music = (o["music"] as? Map<*, *>)?.let { m ->
                BackgroundMusic(
                    uri = m.str("uri"), durationMs = m.long("durationMs"), volume = m.float("volume", 0.6f),
                    loop = m["loop"] as? Boolean ?: true, offsetMs = (m["offsetMs"] as? Number)?.toLong() ?: 0,
                )
            }
            val overlays = (o["overlays"] as? List<*>).orEmpty().map { item ->
                val v = item as Map<*, *>
                TextOverlay(
                    id = v.str("id"), text = v.str("text"), startMs = v.long("startMs"), endMs = v.long("endMs"),
                    fontSizeSp = v.float("fontSizeSp", 32f),
                    colorArgb = (v["color"] as? String)?.removePrefix("#")?.toLong(16)?.toInt() ?: -0x1,
                    x = v.float("x", 0.5f), y = v.float("y", 0.9f),
                    anchor = (v["anchor"] as? String)?.let { a -> OverlayAnchor.values().firstOrNull { it.name == a } } ?: OverlayAnchor.BOTTOM_CENTER,
                    bold = v["bold"] as? Boolean ?: false,
                )
            }
            val preset = (o["preset"] as? String)?.let { p -> ExportPreset.values().firstOrNull { it.name == p } } ?: ExportPreset.P1080
            return Timeline(clips, music, overlays, preset)
        } catch (e: TimelineFormatException) {
            throw e
        } catch (e: Exception) {
            throw TimelineFormatException("Corrupt timeline: ${e.message}", e)
        }
    }

    private fun Map<*, *>.str(k: String): String = this[k] as? String ?: throw TimelineFormatException("Missing '$k'")
    private fun Map<*, *>.long(k: String): Long = (this[k] as? Number)?.toLong() ?: throw TimelineFormatException("Missing '$k'")
    private fun Map<*, *>.float(k: String, default: Float): Float = (this[k] as? Number)?.toFloat() ?: default
}

/** Crash-safe autosave of a timeline project file. */
object TimelineStore {
    /** Writes atomically; the temporary file is decoded and compared before it replaces [file]. */
    fun save(file: File, timeline: Timeline) {
        val text = TimelineCodec.encode(timeline)
        AtomicFile.write(file, verify = { tmp ->
            if (TimelineCodec.decode(tmp.readText()) != timeline) throw TimelineFormatException("Autosave verification failed")
        }) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** The saved timeline, or null if there is none. Throws [TimelineFormatException] if it is unreadable. */
    fun load(file: File): Timeline? = if (!file.isFile) null else TimelineCodec.decode(file.readText())
}

// ----------------------------------------------------------------------------------------------
// Minimal JSON

internal class JsonWriter {
    private val sb = StringBuilder()
    /** Per open container: true until its first member is written. */
    private val first = ArrayList<Boolean>()
    private var afterKey = false

    private fun beforeValue() {
        if (afterKey) {
            afterKey = false
            return
        }
        if (first.isNotEmpty()) {
            if (!first.last()) sb.append(',')
            first[first.lastIndex] = false
        }
    }

    fun obj(body: JsonWriter.() -> Unit) {
        beforeValue()
        sb.append('{'); first += true
        body()
        first.removeAt(first.lastIndex); sb.append('}')
    }

    fun <T> array(items: List<T>, each: JsonWriter.(T) -> Unit) {
        beforeValue()
        sb.append('['); first += true
        for (item in items) each(item)
        first.removeAt(first.lastIndex); sb.append(']')
    }

    fun key(k: String) {
        if (!first.last()) sb.append(',')
        first[first.lastIndex] = false
        string(k)
        sb.append(':')
        afterKey = true
    }

    fun field(k: String, v: String) { key(k); beforeValue(); string(v) }
    fun field(k: String, v: Long) { key(k); beforeValue(); sb.append(v) }
    fun field(k: String, v: Int) { key(k); beforeValue(); sb.append(v) }
    fun field(k: String, v: Float) { key(k); beforeValue(); sb.append(v.toString()) }
    fun field(k: String, v: Boolean) { key(k); beforeValue(); sb.append(v) }
    fun nullValue() { beforeValue(); sb.append("null") }

    private fun string(s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    override fun toString(): String = sb.toString()
}

internal class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Any? {
        val v = value()
        ws()
        if (i != s.length) fail("Trailing content")
        return v
    }

    private fun fail(msg: String): Nothing = throw TimelineFormatException("$msg at $i")

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        if (i >= s.length) fail("Unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected '$c'")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("Expected $word")
        i += word.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("Expected key")
            val k = str()
            ws()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            m[k] = value()
            ws()
            if (i >= s.length) fail("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun arr(): List<Any?> {
        i++
        val l = ArrayList<Any?>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            l += value()
            ws()
            if (i >= s.length) fail("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return l }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("Bad escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) fail("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> fail("Bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Number {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: fail("Bad number $t")
    }
}
