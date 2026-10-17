package ch.vitosstudios.tippli.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ch.vitosstudios.tippli.R

/** Step 1 of the onboarding: enable and select the keyboard (SPEC §7.3, §9.1). */
@Composable
fun KeyboardSetupCard(model: SettingsModel, modifier: Modifier = Modifier) {
    val strings = model.strings
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings["onboarding.androidEnable"], style = MaterialTheme.typography.bodyMedium)
            StatusLine(strings["onboarding.step1"], model.imeEnabled)
            StatusLine(strings["onboarding.androidSelect"], model.imeSelected)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!model.imeEnabled) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(strings["onboarding.openSettings"]) }
                } else if (!model.imeSelected) {
                    Button(onClick = {
                        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }) { Text(strings["onboarding.androidSelect"]) }
                } else {
                    FilledTonalButton(onClick = {
                        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    }) { Text(strings["onboarding.androidSelect"]) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(
                painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
