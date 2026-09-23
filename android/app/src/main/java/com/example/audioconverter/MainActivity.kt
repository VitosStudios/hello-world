package com.example.audioconverter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.example.audioconverter.ui.AppTheme
import com.example.audioconverter.ui.ConverterScreen

class MainActivity : ComponentActivity() {

    /** Link shared into the app (e.g. "Teilen" in the YouTube app). */
    private val sharedLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) sharedLink.value = extractLink(intent)
        setContent {
            AppTheme {
                ConverterScreen(
                    sharedLink = sharedLink.value,
                    onSharedLinkConsumed = { sharedLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractLink(intent)?.let { sharedLink.value = it }
    }

    private fun extractLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }
}
