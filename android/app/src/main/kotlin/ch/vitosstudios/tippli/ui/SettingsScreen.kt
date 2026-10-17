@file:OptIn(ExperimentalMaterial3Api::class)

package ch.vitosstudios.tippli.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.vitosstudios.tippli.BuildConfig
import ch.vitosstudios.tippli.R
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.settings.Trigger
import java.util.Locale

@Composable
fun TippliRoot(model: SettingsModel) {
    if (model.onboardingDone) SettingsScreen(model) else OnboardingScreen(model)
}

/** Settings in the order of SPEC §7.3. */
@Composable
fun SettingsScreen(model: SettingsModel) {
    val strings = model.strings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(strings["app.name"] + " · " + strings["settings.title"]) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = padding,
        ) {
            if (!model.imeEnabled || !model.imeSelected) {
                item { KeyboardSetupCard(model) }
            }
            item {
                Column {
                    SectionHeader(strings["settings.sectionProvider"])
                    ProviderSection(model)
                }
            }
            item { Column { CorrectionSection(model) } }
            item { Column { LanguagesSection(model) } }
            item { Column { KeyboardSection(model) } }
            item { Column { TipsSection(model) } }
            item { Column { PrivacySection(model) } }
            item { Column { AboutSection(model) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun msLabel(ms: Int) = "$ms ms"
private fun secondsLabel(ms: Int) = String.format(Locale.forLanguageTag("de-CH"), "%.1f s", ms / 1000f)

@Composable
private fun CorrectionSection(model: SettingsModel) {
    val strings = model.strings
    val s = model.settings
    val defaults = model.shared.settingsDefaults
    SectionHeader(strings["settings.sectionCorrection"])
    SwitchRow(strings["settings.aiEnabled"], s.aiEnabled, { model.setBoolean(SettingKeys.AI_ENABLED, it) })
    val triggers = defaults.allowed(SettingKeys.TRIGGER).ifEmpty { Trigger.entries.map { it.id } }
    ChoiceRow(
        strings["settings.trigger"],
        triggers.map { it to strings["settings.trigger" + it.replaceFirstChar { c -> c.uppercase() }] },
        s.trigger.id,
    ) { model.setString(SettingKeys.TRIGGER, it) }
    if (s.trigger == Trigger.LIVE || s.trigger == Trigger.PAUSE) {
        SliderRow(
            strings["settings.pauseMs"], s.pauseMs, defaults.range(SettingKeys.PAUSE_MS) ?: 300..2000, 100, ::msLabel,
        ) { model.setInt(SettingKeys.PAUSE_MS, it) }
    }
    val strengths = defaults.allowed(SettingKeys.STRENGTH).ifEmpty { listOf("light", "normal", "strong") }
    ChoiceRow(
        strings["settings.strength"],
        strengths.map { it to strings["settings.strength" + it.replaceFirstChar { c -> c.uppercase() }] },
        s.strength,
    ) { model.setString(SettingKeys.STRENGTH, it) }
    SliderRow(
        strings["settings.waitOnSend"], s.waitOnSendMs, defaults.range(SettingKeys.WAIT_ON_SEND_MS) ?: 0..4000, 250, ::secondsLabel,
    ) { model.setInt(SettingKeys.WAIT_ON_SEND_MS, it) }
    SwitchRow(strings["settings.moodWarning"], s.moodWarning, { model.setBoolean(SettingKeys.MOOD_WARNING, it) })
}

@Composable
private fun LanguagesSection(model: SettingsModel) {
    val strings = model.strings
    val s = model.settings
    val shared = model.shared
    var layoutDialogFor by remember { mutableStateOf<String?>(null) }
    SectionHeader(strings["settings.sectionLanguages"])
    InfoText(strings["settings.languagesHint"])
    val ordered = s.languages.mapNotNull { shared.language(it) } + shared.languages.filter { it.id !in s.languages }
    val keyboards = shared.layouts.keyboards.map { (id, keyboard) -> id to keyboard.name }
    ordered.forEach { language ->
        val enabled = language.id in s.languages
        val index = s.languages.indexOf(language.id)
        ListItem(
            headlineContent = { Text(language.name) },
            supportingContent = if (enabled) {
                {
                    val layoutName = shared.keyboard(s.keyboardFor(language))?.name ?: s.keyboardFor(language)
                    Text(strings["settings.layoutFor"] + ": " + layoutName)
                }
            } else {
                null
            },
            leadingContent = {
                Checkbox(checked = enabled, onCheckedChange = { model.setLanguageEnabled(language.id, it) })
            },
            trailingContent = if (enabled && s.languages.size > 1) {
                {
                    Row {
                        IconButton(enabled = index > 0, onClick = { model.moveLanguage(language.id, -1) }) {
                            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = null)
                        }
                        IconButton(enabled = index < s.languages.size - 1, onClick = { model.moveLanguage(language.id, 1) }) {
                            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null)
                        }
                    }
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                if (enabled) layoutDialogFor = language.id else model.setLanguageEnabled(language.id, true)
            },
        )
    }
    layoutDialogFor?.let { id ->
        val language = shared.language(id)
        if (language != null) {
            ChoiceDialog(
                strings["settings.layoutFor"] + " · " + language.name,
                keyboards,
                s.keyboardFor(language),
                onDismiss = { layoutDialogFor = null },
            ) { keyboardId ->
                layoutDialogFor = null
                if (keyboardId == language.keyboard) model.remove(SettingKeys.keyboard(id)) else model.setString(SettingKeys.keyboard(id), keyboardId)
            }
        }
    }
}

@Composable
private fun KeyboardSection(model: SettingsModel) {
    val strings = model.strings
    val s = model.settings
    val defaults = model.shared.settingsDefaults
    SectionHeader(strings["settings.sectionKeyboard"])
    ChoiceRow(
        strings["settings.keyboardMode"],
        listOf("hex" to strings["settings.modeHex"], "normal" to strings["settings.modeNormal"]),
        s.keyboardMode.id,
    ) { model.setString(SettingKeys.KEYBOARD_MODE, it) }
    SwitchRow(strings["settings.longPressAccents"], s.longPressAccents, { model.setBoolean(SettingKeys.LONG_PRESS_ACCENTS, it) })
    SwitchRow(strings["settings.longPressDigits"], s.longPressDigits, { model.setBoolean(SettingKeys.LONG_PRESS_DIGITS, it) })
    SliderRow(
        strings["settings.longPressMs"], s.longPressMs, defaults.range(SettingKeys.LONG_PRESS_MS) ?: 200..800, 50, ::msLabel,
    ) { model.setInt(SettingKeys.LONG_PRESS_MS, it) }
    ChoiceRow(
        strings["settings.keyHeight"],
        listOf(
            "small" to strings["settings.keyHeightSmall"],
            "medium" to strings["settings.keyHeightMedium"],
            "large" to strings["settings.keyHeightLarge"],
        ),
        s.keyHeight,
    ) { model.setString(SettingKeys.KEY_HEIGHT, it) }
    SwitchRow(strings["settings.haptics"], s.haptics, { model.setBoolean(SettingKeys.HAPTICS, it) })
    SwitchRow(strings["settings.sound"], s.sound, { model.setBoolean(SettingKeys.SOUND, it) })
    SwitchRow(strings["settings.autoCapitalize"], s.autoCapitalize, { model.setBoolean(SettingKeys.AUTO_CAPITALIZE, it) })
    SwitchRow(strings["settings.doubleSpacePeriod"], s.doubleSpacePeriod, { model.setBoolean(SettingKeys.DOUBLE_SPACE_PERIOD, it) })
    SwitchRow(strings["settings.numberRow"], s.numberRow, { model.setBoolean(SettingKeys.NUMBER_ROW, it) })
    ChoiceRow(
        strings["settings.theme"],
        listOf(
            "system" to strings["settings.themeSystem"],
            "light" to strings["settings.themeLight"],
            "dark" to strings["settings.themeDark"],
        ),
        s.theme,
    ) { model.setString(SettingKeys.THEME, it) }
}

@Composable
private fun TipsSection(model: SettingsModel) {
    val strings = model.strings
    val s = model.settings
    SectionHeader(strings["settings.sectionTips"])
    InfoText(strings["settings.tipsSections"])
    SwitchRow(strings["tips.rewrites"], s.tipsRewrites, { model.setBoolean(SettingKeys.TIPS_REWRITES, it) })
    SwitchRow(strings["tips.explanations"], s.tipsExplanations, { model.setBoolean(SettingKeys.TIPS_EXPLANATIONS, it) })
    SwitchRow(strings["tips.writing"], s.tipsWriting, { model.setBoolean(SettingKeys.TIPS_WRITING, it) })
    SwitchRow(strings["tips.emotion"], s.tipsEmotion, { model.setBoolean(SettingKeys.TIPS_EMOTION, it) })
    SwitchRow(strings["tips.translate"], s.tipsTranslate, { model.setBoolean(SettingKeys.TIPS_TRANSLATE, it) })
    if (s.tipsRewrites) {
        ChipSelector(
            strings["settings.rewriteVariants"],
            model.shared.prompts.parts.rewriteVariants.map { it.id to it.label },
            s.rewriteVariants,
        ) { model.setList(SettingKeys.REWRITE_VARIANTS, it) }
    }
    if (s.tipsTranslate) {
        ChipSelector(
            strings["settings.translateTargets"],
            model.shared.translateTargets.map { it.id to it.name },
            s.translateTargets,
        ) { model.setList(SettingKeys.TRANSLATE_TARGETS, it) }
    }
}

@Composable
private fun PrivacySection(model: SettingsModel) {
    val strings = model.strings
    SectionHeader(strings["settings.sectionPrivacy"])
    SwitchRow(strings["settings.incognitoNoAI"], model.settings.incognitoNoAI, { model.setBoolean(SettingKeys.INCOGNITO_NO_AI, it) })
    InfoText(strings["settings.privacyText"])
}

@Composable
private fun AboutSection(model: SettingsModel) {
    val strings = model.strings
    SectionHeader(strings["settings.sectionAbout"])
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(strings["app.name"] + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(strings["app.tagline"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    GestureHelp(model)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    TextButton(onClick = { model.restartOnboarding() }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(strings["onboarding.welcomeTitle"])
    }
}

/** Gesture help (SPEC §7.3, strings.gestures). */
@Composable
fun GestureHelp(model: SettingsModel) {
    val strings = model.strings
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(strings["gestures.title"], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        listOf("swipeUp", "swipeLeft", "swipeRight", "longPress", "spaceDrag", "backspaceHold", "shiftDouble").forEach {
            Text(
                "•  " + strings["gestures.$it"],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
