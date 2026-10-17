@file:OptIn(ExperimentalMaterial3Api::class)

package ch.vitosstudios.tippli.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.vitosstudios.tippli.R

/** Three-step onboarding (SPEC §7.3): enable keyboard → connect AI → try it. */
@Composable
fun OnboardingScreen(model: SettingsModel) {
    val strings = model.strings
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(strings["onboarding.step1"], strings["onboarding.step2"], strings["onboarding.step3"])
    BackHandler(enabled = step > 0) { step-- }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings["onboarding.welcomeTitle"]) },
                navigationIcon = {
                    if (step > 0) {
                        IconButton(onClick = { step-- }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
            ) {
                Button(onClick = { if (step < 2) step++ else model.finishOnboarding() }) {
                    Text(if (step < 2) strings["keys.next"] else strings["keys.done"])
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            StepIndicator(titles, step)
            when (step) {
                0 -> {
                    Text(
                        strings["onboarding.welcomeText"],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    KeyboardSetupCard(model)
                }
                1 -> ProviderSection(model)
                else -> {
                    var text by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(strings["onboarding.tryHere"]) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                    GestureHelp(model)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepIndicator(titles: List<String>, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == current
            Surface(
                shape = CircleShape,
                color = if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(26.dp),
            ) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index <= current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        }
    }
}
