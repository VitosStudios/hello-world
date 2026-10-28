package com.vitosstudios.suite.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vitosstudios.suite.ui.components.EmptyState
import com.vitosstudios.suite.ui.components.SaveStatus
import kotlinx.coroutines.flow.Flow

/** Undo, redo and save status, shared by the office editors' top bars. */
@Composable
fun EditorTopActions(vm: OfficeEditorViewModel<*>) {
    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
    SaveStatus(vm.saveState, vm.saveError, onRetry = vm::flush)
}

/** Saves when the app goes to the background and shows one-off messages. */
@Composable
fun EditorLifecycle(vm: OfficeEditorViewModel<*>, snackbar: SnackbarHostState) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    CollectMessages(vm.messages, snackbar)
}

@Composable
fun CollectMessages(messages: Flow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(it) }
    }
}

/** Loading spinner or error while the document opens. Returns true when content can be shown. */
@Composable
fun LoadingOrError(loading: Boolean, error: String?): Boolean {
    if (error != null) {
        EmptyState(Icons.Default.ErrorOutline, "This file cannot be opened", error)
        return false
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return false
    }
    return true
}
