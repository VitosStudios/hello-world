package com.vitosstudios.suite.feature.home

import com.vitosstudios.suite.data.FileKind
import java.io.File

/** A "new file" choice on the home screen. [create] writes the new file. */
data class NewFileTemplate(
    val id: String,
    val title: String,
    val description: String,
    val kind: FileKind,
    val baseName: String,
    val create: (File) -> Unit,
)

object NewFileTemplates {
    val all: List<NewFileTemplate> by lazy { FormatTemplates.list() }
}
