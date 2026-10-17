package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.data.FileResourceLoader
import ch.vitosstudios.tippli.core.data.SharedData
import java.io.File

/** Access to the real `shared/` directory of the repository. */
object TestShared {
    val dir: File by lazy {
        System.getProperty("tippli.sharedDir")?.let { File(it) }?.takeIf { File(it, "SPEC.md").isFile }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "shared") }
                .firstOrNull { File(it, "SPEC.md").isFile }
            ?: error("shared/ directory not found – set -Dtippli.sharedDir")
    }

    val data: SharedData by lazy { SharedData(FileResourceLoader(dir)) }

    fun read(path: String): String = File(dir, path).readText(Charsets.UTF_8)
}
