package com.vitosstudios.suite.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.ui.theme.AppAccent
import com.vitosstudios.suite.ui.theme.LocalAccents

fun FileKind.Editor.icon(): ImageVector = when (this) {
    FileKind.Editor.DOCUMENTS -> Icons.AutoMirrored.Filled.Article
    FileKind.Editor.SHEETS -> Icons.Default.GridOn
    FileKind.Editor.SLIDES -> Icons.Default.Slideshow
    FileKind.Editor.PHOTO -> Icons.Default.Image
    FileKind.Editor.VIDEO -> Icons.Default.Movie
    FileKind.Editor.PDF -> Icons.Default.PictureAsPdf
}

fun FileKind.Editor.title(): String = when (this) {
    FileKind.Editor.DOCUMENTS -> "Documents"
    FileKind.Editor.SHEETS -> "Sheets"
    FileKind.Editor.SLIDES -> "Slides"
    FileKind.Editor.PHOTO -> "Photo"
    FileKind.Editor.VIDEO -> "Video"
    FileKind.Editor.PDF -> "PDF viewer"
}

@Composable
fun FileKind.Editor.accent(): AppAccent {
    val a = LocalAccents.current
    return when (this) {
        FileKind.Editor.DOCUMENTS -> a.documents
        FileKind.Editor.SHEETS -> a.sheets
        FileKind.Editor.SLIDES -> a.slides
        FileKind.Editor.PHOTO -> a.photo
        FileKind.Editor.VIDEO -> a.video
        FileKind.Editor.PDF -> a.pdf
    }
}
