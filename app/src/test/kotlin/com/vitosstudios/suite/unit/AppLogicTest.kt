package com.vitosstudios.suite.unit

import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.feature.docs.DocsViewModel
import com.vitosstudios.suite.feature.photo.PhotoRecipe
import com.vitosstudios.suite.photo.Adjustments
import com.vitosstudios.suite.photo.CropRect
import com.vitosstudios.suite.photo.Geometry
import com.vitosstudios.suite.photo.PhotoEditState
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.CommandMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLogicTest {
    @Test
    fun textDiffIsMinimal() {
        assertEquals(Triple(5, 5, " big"), DocsViewModel.diff("Hello world", "Hello big world"))
        assertEquals(Triple(4, 5, ""), DocsViewModel.diff("abcde", "abcd"))
        assertEquals(Triple(0, 3, "xyz"), DocsViewModel.diff("abc", "xyz"))
        assertEquals(Triple(2, 2, "\n"), DocsViewModel.diff("ab", "ab\n"))
    }

    @Test
    fun urlsAreNormalised() {
        assertEquals("https://example.org", DocsViewModel.normalizeUrl("example.org"))
        assertEquals("mailto:a@b.c", DocsViewModel.normalizeUrl("a@b.c"))
        assertEquals("http://x.y/z", DocsViewModel.normalizeUrl("http://x.y/z"))
        assertEquals("#Bookmark", DocsViewModel.normalizeUrl("#Bookmark"))
    }

    @Test
    fun fileKindsByNameAndMime() {
        assertEquals(FileKind.DOCX, FileKind.from("Report.DOCX", null))
        assertEquals(FileKind.XLSX, FileKind.from("x", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(FileKind.JPEG, FileKind.from("photo.jpeg", "image/jpeg"))
        assertEquals(FileKind.CSV, FileKind.from("data.csv", null))
        assertNull(FileKind.from("notes.txt", "text/plain"))
    }

    @Test
    fun commandSearchRanksPrefixesFirst() {
        val cmds = listOf("Insert table", "Bold", "Table rows and columns", "Export as PDF").map { Command(it, it, "g") {} }
        assertEquals(listOf("Table rows and columns", "Insert table"), CommandMatcher.filter(cmds, "tab").map { it.title })
        assertEquals("Export as PDF", CommandMatcher.filter(cmds, "pdf").single().title)
    }

    @Test
    fun photoRecipeRoundTrips() {
        val s = PhotoEditState(
            Geometry(CropRect(0.1f, 0.2f, 0.9f, 0.8f), quarterTurns = 1, flipH = true, flipV = false, straightenDegrees = -3.5f),
            Adjustments(exposure = 0.5f, contrast = 10f, highlights = -20f, shadows = 15f, saturation = 5f, warmth = -8f, sharpness = 30f),
            presetId = "vivid",
        )
        assertEquals(s, PhotoRecipe.decode(PhotoRecipe.encode(s)))
        assertNull(PhotoRecipe.decode("garbage"))
    }
}
