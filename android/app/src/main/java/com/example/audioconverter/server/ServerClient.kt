package com.example.audioconverter.server

import android.content.Context
import android.net.Uri
import com.example.audioconverter.audio.EditOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Server address and token, stored in the app's private preferences. */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(value) = prefs.edit().putString("url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()
}

data class ServerFormat(
    val id: String,
    val label: String,
    val extension: String,
    val video: Boolean,
    val description: String,
)

data class ServerJob(
    val id: String,
    val status: String,
    val progress: Double,
    val error: String?,
    val filename: String?,
) {
    val finished: Boolean get() = status == "done" || status == "error"
}

class ServerException(message: String) : IOException(message)

/** Minimal client for the Media Studio server API (see server/app/main.py). */
object ServerClient {

    suspend fun formats(settings: ServerSettings): List<ServerFormat> {
        val arr = JSONArray(request(settings, "GET", "/api/formats").decodeToString())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ServerFormat(
                id = o.getString("id"),
                label = o.getString("label"),
                extension = o.getString("extension"),
                video = o.getBoolean("video"),
                description = o.getString("description"),
            )
        }
    }

    suspend fun createJob(
        settings: ServerSettings,
        link: String,
        formatId: String,
        edit: EditOptions,
    ): ServerJob {
        val form = buildMap {
            put("url", link)
            put("format", formatId)
            edit.startSec?.let { put("start", EditOptions.num(it)) }
            edit.endSec?.let { put("end", EditOptions.num(it)) }
            put("speed", EditOptions.num(edit.speed))
            put("normalize", edit.normalize.toString())
        }.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return parseJob(request(settings, "POST", "/api/jobs", form))
    }

    suspend fun job(settings: ServerSettings, id: String): ServerJob =
        parseJob(request(settings, "GET", "/api/jobs/$id"))

    /** Streams the finished result into [target] (a SAF document). */
    suspend fun download(context: Context, settings: ServerSettings, id: String, target: Uri) =
        withContext(Dispatchers.IO) {
            val conn = open(settings, "GET", "/api/jobs/$id/download")
            try {
                check(conn)
                val sink = context.contentResolver.openOutputStream(target, "wt")
                    ?: throw ServerException("Ziel-Datei nicht beschreibbar.")
                sink.use { out -> conn.inputStream.use { it.copyTo(out) } }
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun request(
        settings: ServerSettings,
        method: String,
        path: String,
        form: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(settings, method, path)
        try {
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
            }
            check(conn)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(settings: ServerSettings, method: String, path: String): HttpURLConnection {
        if (!settings.isConfigured) {
            throw ServerException("Server-Adresse und Token in den Einstellungen eintragen.")
        }
        return (URL(settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${settings.token}")
        }
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val detail = runCatching {
            JSONObject(conn.errorStream.bufferedReader().readText()).getString("detail")
        }.getOrNull()
        throw ServerException(
            when (code) {
                401 -> "Token falsch, bitte in den Einstellungen prüfen."
                else -> detail ?: "Server-Fehler $code"
            }
        )
    }

    private fun parseJob(body: ByteArray): ServerJob {
        val o = JSONObject(body.decodeToString())
        return ServerJob(
            id = o.getString("id"),
            status = o.getString("status"),
            progress = o.optDouble("progress", 0.0),
            error = o.optString("error").takeIf { !o.isNull("error") && it.isNotEmpty() },
            filename = o.optString("filename").takeIf { !o.isNull("filename") && it.isNotEmpty() },
        )
    }
}
