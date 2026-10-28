package com.vitosstudios.suite.testing

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume

/**
 * An independent reader for edited files: LibreOffice in headless mode. A file "opens in the
 * second reader" if LibreOffice converts it to another format without error; text checks use the
 * flat ODF or CSV output.
 *
 * Mode is taken from the system property `suite.secondReader`:
 *  - `auto` (default): run if `soffice` is installed, otherwise skip the check,
 *  - `required`: fail if `soffice` is missing (used in CI),
 *  - `off`: never run.
 */
object SecondReader {
    private val mode: String = System.getProperty("suite.secondReader") ?: "auto"

    private val executable: String? by lazy {
        val candidates = listOf("soffice", "libreoffice", "/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice")
        candidates.firstOrNull { c ->
            if (c.startsWith("/")) File(c).canExecute()
            else System.getenv("PATH")?.split(File.pathSeparator)?.any { File(it, c).canExecute() } == true
        }
    }

    val isAvailable: Boolean get() = mode != "off" && executable != null

    /** Skips the calling test (or fails in `required` mode) when LibreOffice is not usable. */
    fun assume() {
        if (mode == "required" && executable == null) throw AssertionError("LibreOffice (soffice) is required but not installed")
        Assume.assumeTrue("LibreOffice not available; second-reader check skipped", isAvailable)
    }

    private val profile: File by lazy { Corpus.tempDir("lo-profile-${ProcessHandle.current().pid()}") }

    /**
     * Converts [file] with LibreOffice to [filter] (e.g. "pdf", "fodt", "fods", "fodp", "csv")
     * and returns the output, or throws if LibreOffice could not read the file.
     */
    @Synchronized
    fun convert(file: File, filter: String): File {
        assume()
        val outDir = Corpus.tempDir("second-reader/${file.nameWithoutExtension}-${System.nanoTime()}")
        val ext = filter.substringBefore(':')
        val cmd = listOf(executable!!, "-env:UserInstallation=${profile.toURI()}", "--headless", "--norestore",
            "--convert-to", filter, "--outdir", outDir.absolutePath, file.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(180, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw AssertionError("LibreOffice timed out on ${file.name}")
        }
        val out = File(outDir, file.nameWithoutExtension + "." + ext)
        if (proc.exitValue() != 0 || !out.isFile || out.length() == 0L) {
            throw AssertionError("LibreOffice could not read ${file.name} (exit ${proc.exitValue()}): $output")
        }
        return out
    }

    /** Text content as LibreOffice sees it (flat ODF XML for documents and decks, CSV for sheets). */
    fun text(file: File): String = when (file.extension.lowercase()) {
        "docx" -> convert(file, "fodt").readText()
        "pptx" -> convert(file, "fodp").readText()
        "xlsx" -> convert(file, "fods").readText()
        else -> error("Unsupported ${file.extension}")
    }

    /** First sheet as CSV. */
    fun csv(file: File): String = convert(file, "csv").readText()
}
