package com.vitosstudios.suite

import android.app.Application
import android.content.Context
import com.vitosstudios.suite.common.AtomicFile
import com.vitosstudios.suite.data.AppDatabase
import com.vitosstudios.suite.data.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val database = AppDatabase.create(context)
    val files = FileRepository(context, database.files())
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Files handed to the app by other apps (VIEW/EDIT intents), consumed by the navigation host. */
    val incomingUris = MutableSharedFlow<android.net.Uri>(replay = 1, extraBufferCapacity = 4)
}

class SuiteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch(Dispatchers.IO) {
            // Temporary files of a save interrupted by a crash are never used; remove them.
            File(filesDir, "work").walkTopDown().filter { it.isDirectory }.forEach { AtomicFile.cleanupStale(it) }
            AtomicFile.cleanupStale(File(filesDir, "documents"))
            // Retry write-backs that did not finish before the process died.
            for (e in container.files.pendingWriteBacks()) container.files.writeBack(e.id)
        }
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as SuiteApplication).container
