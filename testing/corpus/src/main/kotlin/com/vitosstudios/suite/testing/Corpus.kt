package com.vitosstudios.suite.testing

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.fail

/** Locates the shared round-trip corpus in test/corpus. */
object Corpus {
    val repoRoot: File by lazy {
        System.getProperty("suite.repoRoot")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    val dir: File get() = File(repoRoot, "test/corpus")

    /** Every corpus file with [extension], including the large-file set. */
    fun files(extension: String, includeLarge: Boolean = true): List<File> {
        val dirs = listOfNotNull(File(dir, extension), if (includeLarge) File(dir, "large") else null)
        val found = dirs.flatMap { d -> d.listFiles()?.filter { it.isFile && it.extension.equals(extension, true) }.orEmpty() }
            .sortedBy { it.name }
        if (found.isEmpty()) fail("No .$extension files in $dir")
        return found
    }

    fun file(relative: String): File = File(dir, relative).also { if (!it.isFile) fail("Missing corpus file $it") }

    fun tempDir(name: String): File {
        val d = File(repoRoot, "build/test-output/$name")
        d.mkdirs()
        return d
    }
}

/** ZIP-level comparisons between an original and a saved package. */
object PackageCompare {
    /** Entry name -> content for every non-directory entry, in archive order. */
    fun entries(file: File): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { z ->
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                map[e.name] = z.getInputStream(e).use { it.readBytes() }
            }
        }
        return map
    }

    /** Names of entries whose content differs, plus added and removed entries. */
    fun differences(original: File, saved: File): List<String> {
        val a = entries(original)
        val b = entries(saved)
        val out = ArrayList<String>()
        for ((k, v) in a) {
            val w = b[k]
            if (w == null) out += "removed $k" else if (!v.contentEquals(w)) out += "changed $k"
        }
        for (k in b.keys) if (k !in a) out += "added $k"
        return out
    }

    fun assertIdentical(original: File, saved: File) {
        val diff = differences(original, saved)
        if (diff.isNotEmpty()) fail("${original.name}: parts differ after round trip: $diff")
        assertEquals(entries(original).keys.toList(), entries(saved).keys.toList(), "${original.name}: entry order changed")
    }

    /** Asserts that only [allowed] entries (by exact name) differ. */
    fun assertOnlyChanged(original: File, saved: File, allowed: Set<String>, allowAdded: (String) -> Boolean = { false }) {
        val unexpected = differences(original, saved).filter { d ->
            val name = d.substringAfter(' ')
            when {
                d.startsWith("changed ") -> name !in allowed
                d.startsWith("added ") -> !allowAdded(name) && name !in allowed
                else -> name !in allowed
            }
        }
        if (unexpected.isNotEmpty()) fail("${original.name}: unexpected part changes: $unexpected")
    }
}
