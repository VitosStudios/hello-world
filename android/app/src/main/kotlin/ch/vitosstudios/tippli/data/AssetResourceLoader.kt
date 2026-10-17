package ch.vitosstudios.tippli.data

import android.content.res.AssetManager
import ch.vitosstudios.tippli.core.data.ResourceLoader

/** Reads the shared files that the `syncSharedAssets` Gradle task bundled under `assets/shared/`. */
class AssetResourceLoader(private val assets: AssetManager) : ResourceLoader {
    override fun readText(path: String): String =
        assets.open("shared/$path").use { it.readBytes().toString(Charsets.UTF_8) }
}
