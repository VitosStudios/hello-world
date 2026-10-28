package com.vitosstudios.suite.screenshots

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vitosstudios.suite.MainActivity
import com.vitosstudios.suite.testing.Corpus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens real corpus files through the same path as "Open with" from another app (VIEW intent →
 * repository → working copy → editor) and checks that each editor shows the content. The images
 * double as screenshots of the editors.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Waits for [matcher]; on timeout the failure message contains the whole semantics tree. */
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long) {
        try {
            compose.waitUntilAtLeastOneExists(matcher, timeoutMs)
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString() }
                .getOrElse { "(no semantics tree: $it)" }
            throw AssertionError("Timed out waiting for ${matcher.description}. Screen:\n$tree", e)
        }
    }

    private fun open(file: File): ActivityScenario<MainActivity> {
        val copy = File(context.cacheDir, "in-" + file.name)
        file.copyTo(copy, overwrite = true)
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(copy)).setClass(context, MainActivity::class.java)
        return ActivityScenario.launch(intent)
    }

    @Test
    fun homeScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasText("Create new"), 15_000)
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun documentOpensAndAcceptsTyping() {
        open(Corpus.file("docx/pylib-features.docx")).use {
            waitFor(hasText("Heading level 1", substring = true), 20_000)
            compose.onRoot().captureRoboImage()
            compose.onNodeWithText("Heading level 2", substring = true).performClick()
            waitFor(hasContentDescription("Paragraph editor"), 5_000)
            compose.onNodeWithContentDescription("Paragraph editor").performTextInput("Typed ")
            waitFor(hasText("Typed", substring = true), 5_000)
            compose.onNodeWithContentDescription("Undo").assertExists()
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun wordDocumentWithLockedContentOpens() {
        open(Corpus.file("docx/word-poi-drawing.docx")).use {
            waitFor(hasText("DOCX"), 20_000)
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun spreadsheetOpens() {
        open(Corpus.file("xlsx/pylib-features.xlsx")).use {
            waitFor(hasContentDescription("Sheet Data", substring = true), 20_000)
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun presentationOpens() {
        open(Corpus.file("pptx/pylib-features.pptx")).use {
            waitFor(hasContentDescription("Slide 1", substring = true), 20_000)
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun powerPointDeckOpens() {
        open(Corpus.file("pptx/powerpoint-poi-SampleShow.pptx")).use {
            waitFor(hasContentDescription("Slide 1", substring = true), 20_000)
            compose.onRoot().captureRoboImage()
        }
    }

    @Test
    fun photoOpens() {
        val png = File(context.cacheDir, "sample.png")
        val bmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(40, 110, 200))
            drawCircle(320f, 200f, 120f, Paint().apply { color = Color.rgb(250, 200, 60) })
        }
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        open(png).use {
            waitFor(hasContentDescription("Edited photo"), 20_000)
            compose.onRoot().captureRoboImage()
        }
    }
}
