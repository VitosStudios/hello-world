package com.vitosstudios.suite.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vitosstudios.suite.ui.components.ColorPalette
import com.vitosstudios.suite.ui.components.ContextToolbar
import com.vitosstudios.suite.ui.components.EditorBottomBar
import com.vitosstudios.suite.ui.components.EmptyState
import com.vitosstudios.suite.ui.components.LabeledSlider
import com.vitosstudios.suite.ui.components.LockedPlaceholder
import com.vitosstudios.suite.ui.components.SaveState
import com.vitosstudios.suite.ui.components.SaveStatus
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.theme.SuiteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the component library (light and dark). Run `./gradlew :app:recordRoborazziDebug`
 * to write the images to app/build/outputs/roborazzi; CI uploads them as an artifact.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ComponentScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            SuiteTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun topBarAndToolbars() = shoot(false) {
        Column {
            SuiteTopBar(title = "Quarterly report.docx", subtitle = "DOCX", onBack = {}, actions = { SaveStatus(SaveState.SAVED, null) {} })
            ContextToolbar(true, listOf(
                ToolbarAction("Bold", Icons.Default.FormatBold, selected = true) {},
                ToolbarAction("Italic", Icons.Default.FormatItalic, selected = false) {},
                ToolbarAction("Link", Icons.Default.Link) {},
                ToolbarAction("Insert", Icons.Default.Add) {},
            ))
            EditorBottomBar(listOf(ToolbarAction("Insert", Icons.Default.Add) {}, ToolbarAction("Link", Icons.Default.Link) {}))
        }
    }

    @Test
    fun lockedPlaceholderAndPalette() = shoot(false) {
        Column(Modifier.padding(16.dp)) {
            LockedPlaceholder("Chart", "Charts are kept unchanged.")
            Text("Text colour", Modifier.padding(top = 16.dp))
            ColorPalette(selected = null, onSelect = {})
            LabeledSlider("Exposure", 0.4f, -2f..2f, onChange = {})
        }
    }

    @Test
    fun emptyStateDark() = shoot(true) {
        EmptyState(Icons.Outlined.Description, "No files yet", "Open a DOCX, XLSX, PPTX, PDF, image or MP4 file, or create a new one from a template.")
    }
}
