package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.data.ProviderPreset
import ch.vitosstudios.tippli.core.data.TippliJson
import ch.vitosstudios.tippli.core.data.parseHexColor
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import ch.vitosstudios.tippli.core.settings.MapSettingsStore
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.settings.SettingsReader
import ch.vitosstudios.tippli.core.settings.Trigger
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedDataTest {
    private val shared = TestShared.data

    @Test
    fun allBundledFilesExistAndParse() {
        for (path in ch.vitosstudios.tippli.core.data.SharedData.BUNDLED_FILES) {
            assertTrue("missing $path", File(TestShared.dir, path).isFile)
        }
        assertTrue(shared.layouts.keyboards.isNotEmpty())
        assertTrue(shared.accents.isNotEmpty())
        assertTrue(shared.languages.isNotEmpty())
        assertTrue(shared.providers.isNotEmpty())
        assertTrue(shared.emoji.isNotEmpty())
        assertTrue(shared.theme.light.isNotEmpty())
        assertTrue(shared.prompts.correctionSystem.contains("{{segment}}").not())
        assertTrue(shared.prompts.correctionUser.contains("{{segment}}"))
    }

    @Test
    fun everyLanguageKeyboardExists() {
        for (language in shared.languages) {
            assertNotNull("keyboard ${language.keyboard} of ${language.id}", shared.keyboard(language.keyboard))
        }
        assertTrue(shared.languages.any { it.pinyin })
    }

    @Test
    fun keySpecsParseStringsAndObjects() {
        val qwertz = shared.keyboard("qwertz")!!
        val third = qwertz.normal.rows[2]
        assertEquals("\$shift", third.keys.first().key)
        assertEquals(1.5, third.keys.first().width, 0.0)
        assertEquals("y", third.keys[1].key)
        assertEquals(1.0, third.keys[1].width, 0.0)
        assertEquals(10, shared.layouts.numberRow.size)
        assertEquals(mapOf("," to "，", "." to "。"), shared.keyboard("pinyin")!!.bottomPunctuation)
    }

    @Test
    fun providerDefaultsComeFromProvidersJson() {
        val raw = TippliJson.parseToJsonElement(TestShared.read("providers.json")).jsonObject
        val rawProviders = raw.getValue("providers").jsonArray.map { it.jsonObject }
        assertEquals(rawProviders.size, shared.providers.size)
        for ((parsed, json) in shared.providers.zip(rawProviders)) {
            assertEquals(json.getValue("defaultModel").jsonPrimitive.content, parsed.defaultModel)
            assertTrue("default model of ${parsed.id} must be listed or custom",
                parsed.models.isEmpty() || parsed.models.any { it.id == parsed.defaultModel })
        }
        val anthropic = shared.provider("anthropic")!!
        assertEquals(ProviderPreset.PROTOCOL_ANTHROPIC, anthropic.protocol)
        assertEquals("Claude", anthropic.shortName)
        val openai = shared.provider("openai")!!
        assertEquals("max_completion_tokens", openai.maxTokensField)
        assertEquals("low", openai.reasoningEffort)
        assertEquals("2022-11-28", shared.provider("github")!!.extraHeaders["X-GitHub-Api-Version"])
        assertTrue(shared.provider("custom")!!.baseUrlEditable)
        assertFalse(shared.provider("custom")!!.keyRequired)
    }

    @Test
    fun settingsDefaults() {
        val settings = SettingsReader(shared).read(MapSettingsStore())
        val d = shared.settingsDefaults
        assertEquals(Trigger.from(d.string("trigger")), settings.trigger)
        assertEquals(d.string("provider"), settings.provider)
        assertEquals(d.int("pauseMs"), settings.pauseMs)
        assertEquals(KeyboardMode.from(d.string("keyboardMode")), settings.keyboardMode)
        assertEquals(d.stringList("languages"), settings.languages)
        assertEquals(d.stringList("translateTargets"), settings.translateTargets)
        // Allowed values are the Trigger enum, in the documented order.
        assertEquals(d.allowed("trigger"), Trigger.entries.map { it.id })
        // Model default per provider comes from providers.json.
        val anthropic = shared.provider("anthropic")!!
        assertEquals(anthropic.defaultModel, settings.modelFor(anthropic))
    }

    @Test
    fun settingsValidation() {
        val store = MapSettingsStore(
            mapOf(
                SettingKeys.TRIGGER to "bogus",
                SettingKeys.PAUSE_MS to 99_999,
                SettingKeys.LONG_PRESS_MS to 1,
                SettingKeys.LANGUAGES to listOf("en", "xx", "zh-Hans", "en"),
                SettingKeys.ACTIVE_LANGUAGE to "de-CH",
                SettingKeys.KEYBOARD_MODE to "normal",
                SettingKeys.model("anthropic") to "claude-sonnet-5-5",
                SettingKeys.keyboard("en") to "qwertz",
                SettingKeys.keyboard("it") to "does-not-exist",
            ),
        )
        val s = SettingsReader(shared).read(store)
        assertEquals(Trigger.from(shared.settingsDefaults.string("trigger")), s.trigger)
        assertEquals(2000, s.pauseMs)
        assertEquals(200, s.longPressMs)
        assertEquals(listOf("en", "zh-Hans"), s.languages)
        assertEquals("en", s.activeLanguage) // not enabled → first enabled language
        assertEquals(KeyboardMode.NORMAL, s.keyboardMode)
        assertEquals("claude-sonnet-5-5", s.modelFor(shared.provider("anthropic")!!))
        assertEquals("qwertz", s.keyboardFor(shared.language("en")!!))
        assertEquals("qwerty", s.keyboardFor(shared.language("it")!!))
    }

    @Test
    fun stringsAndTheme() {
        val strings = shared.strings
        assertEquals("Original", strings["toolbar.undo"])
        assertTrue(strings.has("settings.triggerLive"))
        assertEquals(4, strings.list("tips.emotionLevels").size)
        assertEquals("missing.key", strings["missing.key"])
        for (trigger in Trigger.entries) {
            assertTrue("strings for trigger ${trigger.id}",
                strings.has("settings.trigger" + trigger.id.replaceFirstChar { it.uppercase() }))
        }
        assertEquals(0xFF4F46E5.toInt(), parseHexColor("#4F46E5"))
        assertEquals(0x2E000000, parseHexColor("#0000002E"))
        assertEquals(null, parseHexColor("#12"))
        val light = ch.vitosstudios.tippli.core.data.ThemePalette.from(shared.theme.light)
        assertEquals(0xFFFFFFFF.toInt(), light.key)
        assertEquals(52f, shared.theme.metrics.rowHeightFor("medium"))
    }
}
