package ch.vitosstudios.tippli.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ch.vitosstudios.tippli.R
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.tips.TipsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val CUSTOM_MODEL = "\u0000custom"

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val output: String) : TestState
    data class Failed(val message: String) : TestState
}

/** "KI-Anbieter" (SPEC §6.1, §7.3): provider, API key, model, base URL, model list and connection test. */
@Composable
fun ProviderSection(model: SettingsModel) {
    val strings = model.strings
    val settings = model.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = model.shared.providers
    val preset = model.shared.provider(settings.provider) ?: providers.first()

    var key by remember(preset.id) { mutableStateOf(model.apiKey(preset.id)) }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember(preset.id) { mutableStateOf(settings.baseUrlFor(preset)) }
    var loadedModels by remember(preset.id) { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember(preset.id) { mutableStateOf(false) }
    var modelsError by remember(preset.id) { mutableStateOf<String?>(null) }
    var modelDialog by remember { mutableStateOf(false) }
    var testState by remember(preset.id) { mutableStateOf<TestState>(TestState.Idle) }

    val currentModel = settings.modelFor(preset)
    val presetIds = preset.models.map { it.id }
    var customMode by remember(preset.id) { mutableStateOf(currentModel !in presetIds) }
    var customText by remember(preset.id) { mutableStateOf(currentModel) }

    fun errorText(e: AiException) = strings[e.kind.stringPath] + (e.detail?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")

    ChoiceRow(strings["settings.provider"], providers.map { it.id to it.name }, preset.id) {
        model.setString(SettingKeys.PROVIDER, it)
    }

    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it
            model.setApiKey(preset.id, it)
            testState = TestState.Idle
        },
        label = { Text(strings["settings.apiKey"]) },
        placeholder = { Text(preset.keyHint) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    painterResource(if (showKey) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = null,
                )
            }
        },
        supportingText = if (key.isNotBlank()) {
            { Text(strings["settings.apiKeySaved"]) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (preset.keyUrl.isNotBlank()) {
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(strings["settings.getKey"] + " ↗") }
    }

    if (preset.baseUrlEditable) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                model.setString(SettingKeys.CUSTOM_BASE_URL, it.trim())
            },
            label = { Text(strings["settings.baseUrl"]) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    // Model: presets, loaded models and "Eigenes Modell".
    val options = preset.models.map { it.id to it.label } +
        loadedModels.filter { it !in presetIds }.map { it to it } +
        (CUSTOM_MODEL to strings["settings.customModel"])
    val selectedOption = if (customMode) CUSTOM_MODEL else currentModel
    ListItem(
        headlineContent = { Text(strings["settings.model"]) },
        supportingContent = {
            Text(if (customMode) currentModel else options.firstOrNull { it.first == currentModel }?.second ?: currentModel)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { modelDialog = true },
    )
    if (modelDialog) {
        ChoiceDialog(strings["settings.model"], options, selectedOption, onDismiss = { modelDialog = false }) { id ->
            modelDialog = false
            testState = TestState.Idle
            if (id == CUSTOM_MODEL) {
                customMode = true
                customText = currentModel
            } else {
                customMode = id !in presetIds && id !in loadedModels
                model.setString(SettingKeys.model(preset.id), id)
            }
        }
    }
    if (customMode) {
        OutlinedTextField(
            value = customText,
            onValueChange = {
                customText = it
                if (it.isBlank()) model.remove(SettingKeys.model(preset.id)) else model.setString(SettingKeys.model(preset.id), it.trim())
            },
            label = { Text(strings["settings.customModel"]) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedButton(
            enabled = !loadingModels,
            onClick = {
                val endpoint = model.app.endpointFor(model.settings) ?: return@OutlinedButton
                loadingModels = true
                modelsError = null
                scope.launch {
                    try {
                        loadedModels = model.app.clientFor(endpoint).listModels()
                        if (loadedModels.isNotEmpty()) modelDialog = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AiException) {
                        modelsError = errorText(e)
                    } catch (e: Exception) {
                        modelsError = strings[AiErrorKind.BAD_RESPONSE.stringPath]
                    } finally {
                        loadingModels = false
                    }
                }
            },
        ) {
            if (loadingModels) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(strings["settings.loadModels"])
        }
        Button(
            enabled = testState != TestState.Running,
            onClick = {
                val current = model.settings
                val endpoint = model.app.endpointFor(current) ?: return@Button
                testState = TestState.Running
                scope.launch {
                    testState = try {
                        if (endpoint.preset.keyRequired && endpoint.apiKey.isBlank()) throw AiException(AiErrorKind.NO_KEY)
                        val language = model.shared.language(current.activeLanguage) ?: model.shared.languages.first()
                        val prompt = model.app.prompts.connectionTest(language, current.strength, current.keyboardFor(language))
                        val output = model.app.clientFor(endpoint).complete(AiTask.CORRECTION, prompt.system, prompt.user)
                        TestState.Ok(TipsService.cleanFreeText(output))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AiException) {
                        TestState.Failed(errorText(e))
                    } catch (e: Exception) {
                        TestState.Failed(strings[AiErrorKind.NETWORK.stringPath])
                    }
                }
            },
        ) {
            if (testState == TestState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(strings["settings.testRunning"])
            } else {
                Text(strings["settings.test"])
            }
        }
    }
    modelsError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
    when (val state = testState) {
        is TestState.Ok -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(strings["settings.testOk"], color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "„${model.app.prompts.testSegment}“ → „${state.output}“",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is TestState.Failed -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        else -> Unit
    }
}
