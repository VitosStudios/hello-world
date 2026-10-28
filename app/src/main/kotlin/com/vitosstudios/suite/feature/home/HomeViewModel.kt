package com.vitosstudios.suite.feature.home

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.data.FileEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val files: StateFlow<List<FileEntry>> = container.files.observeFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Emits the id of a file to open in its editor. */
    val openEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    var busy by mutableStateOf(false)
        private set

    fun open(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val e = container.files.open(uri)
                openEvents.emit(e.id)
            } catch (ex: Exception) {
                messages.emit(ex.message ?: "The file could not be opened")
            } finally {
                busy = false
            }
        }
    }

    fun create(template: NewFileTemplate) {
        viewModelScope.launch {
            busy = true
            try {
                val e = container.files.createLocal(template.baseName, template.kind, template.create)
                openEvents.emit(e.id)
            } catch (ex: Exception) {
                messages.emit(ex.message ?: "The file could not be created")
            } finally {
                busy = false
            }
        }
    }

    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { container.files.setPinned(id, pinned) }

    fun forget(id: String) = viewModelScope.launch { container.files.forget(id) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                HomeViewModel(app.container)
            }
        }
    }
}
