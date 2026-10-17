package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.engine.CorrectionEngine
import ch.vitosstudios.tippli.core.engine.EngineConfig
import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.Page
import ch.vitosstudios.tippli.core.geometry.PixelMetrics
import ch.vitosstudios.tippli.core.input.ManualScheduler
import ch.vitosstudios.tippli.core.keyboard.EnterAction
import ch.vitosstudios.tippli.core.keyboard.FieldInfo
import ch.vitosstudios.tippli.core.keyboard.FieldKind
import ch.vitosstudios.tippli.core.keyboard.KeyboardController
import ch.vitosstudios.tippli.core.keyboard.KeyboardControllerListener
import ch.vitosstudios.tippli.core.keyboard.ShiftState
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import ch.vitosstudios.tippli.core.settings.MapSettingsStore
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.settings.SettingsReader
import ch.vitosstudios.tippli.core.settings.TippliSettings
import ch.vitosstudios.tippli.core.settings.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardControllerTest {
    private val shared = TestShared.data
    private val metrics = PixelMetrics.from(shared.theme.metrics, "medium", 1f)

    private class Env(
        val controller: KeyboardController,
        val editor: FakeEditor,
        val ai: FakeAi,
        val scheduler: ManualScheduler,
        val events: MutableList<String>,
    ) {
        fun key(code: String): Key = controller.currentLayout()!!.keys.first { it.code == code }
        fun key(type: KeyType): Key = controller.currentLayout()!!.keys.first { it.type == type }

        fun tap(k: Key) {
            controller.interpreter.onDown(0, k.centerX, k.centerY)
            controller.interpreter.onUp(0, k.centerX, k.centerY)
            scheduler.advanceBy(120) // slow enough to never be a double tap
        }

        fun type(s: String) {
            for (ch in s) {
                when (ch) {
                    ' ' -> tap(key(KeyType.SPACE))
                    else -> {
                        val k = controller.currentLayout()!!.keys.firstOrNull { it.code == ch.lowercase() }
                            ?: error("no key for $ch")
                        if (ch.isUpperCase() != controller.isShifted) tap(key(KeyType.SHIFT))
                        tap(k)
                    }
                }
            }
        }

        fun swipe(k: Key, dx: Float, dy: Float = 0f) {
            controller.interpreter.onDown(0, k.centerX, k.centerY)
            controller.interpreter.onMove(0, k.centerX + dx, k.centerY + dy)
            controller.interpreter.onUp(0, k.centerX + dx, k.centerY + dy)
            scheduler.advanceBy(120)
        }
    }

    private fun settings(vararg values: Pair<String, Any>): TippliSettings =
        SettingsReader(shared).read(MapSettingsStore(mapOf(*values)))

    private fun TestScope.env(
        settings: TippliSettings = settings(SettingKeys.KEYBOARD_MODE to "normal"),
        field: FieldInfo = FieldInfo(),
        initial: String = "",
        aiActive: Boolean = false,
    ): Env {
        val editor = FakeEditor(initial)
        val ai = FakeAi(AiEndpoint(shared.provider("anthropic")!!, "sk", "claude-haiku-5-5"))
        val engine = CorrectionEngine(editor, PromptBuilder(shared.prompts, shared.layouts), backgroundScope) {
            testScheduler.currentTime
        }
        engine.configure(
            EngineConfig(
                aiEnabled = aiActive, trigger = Trigger.PAUSE, waitOnSendMs = settings.waitOnSendMs.toLong(),
                language = shared.language(settings.activeLanguage)!!,
            ),
            ai,
        )
        engine.onFieldStart(field.aiAllowed(settings.incognitoNoAI))
        val scheduler = ManualScheduler(startTime = 10_000)
        val controller = KeyboardController(shared, editor, engine, scheduler, scheduler, backgroundScope)
        val events = mutableListOf<String>()
        controller.listener = object : KeyboardControllerListener {
            override fun onNoOpFeedback() { events += "noop" }
            override fun onEmojiPanelRequested() { events += "emoji" }
            override fun persistKeyboardMode(mode: KeyboardMode) { events += "mode:${mode.id}" }
            override fun persistActiveLanguage(languageId: String) { events += "lang:$languageId" }
            override fun persistEmojiRecents(recents: List<String>) { events += "recents:${recents.size}" }
        }
        controller.startInput(settings, field)
        controller.layout(400f, metrics)
        controller.updateGestureConfig(1f)
        return Env(controller, editor, ai, scheduler, events)
    }

    @Test
    fun typingWithAutoCapitalisation() = runTest {
        val e = env()
        assertEquals(ShiftState.ONE_SHOT, e.controller.shift) // empty field
        e.tap(e.key("h"))
        e.tap(e.key("a"))
        assertEquals("Ha", e.editor.value)
        assertEquals(ShiftState.OFF, e.controller.shift)
        e.tap(e.key("."))
        e.tap(e.key(KeyType.SPACE))
        assertEquals(ShiftState.ONE_SHOT, e.controller.shift)
        e.tap(e.key("x"))
        assertEquals("Ha. X", e.editor.value)
    }

    @Test
    fun autoShiftNeverOverridesUserShiftAndIsOffForPinyinAndAddresses() = runTest {
        val e = env(initial = "abc ")
        assertEquals(ShiftState.OFF, e.controller.shift)
        e.tap(e.key(KeyType.SHIFT))
        assertEquals(ShiftState.ONE_SHOT, e.controller.shift)
        e.tap(e.key(KeyType.SPACE)) // auto-cap evaluation keeps the user's shift
        assertEquals(ShiftState.ONE_SHOT, e.controller.shift)

        val pinyin = env(settings(SettingKeys.LANGUAGES to listOf("zh-Hans"), SettingKeys.ACTIVE_LANGUAGE to "zh-Hans"))
        assertEquals(ShiftState.OFF, pinyin.controller.shift)
        val email = env(field = FieldInfo(kind = FieldKind.ADDRESS))
        assertEquals(ShiftState.OFF, email.controller.shift)
        val noAutoCap = env(settings(SettingKeys.AUTO_CAPITALIZE to false))
        assertEquals(ShiftState.OFF, noAutoCap.controller.shift)
    }

    @Test
    fun shiftDoubleTapIsCapsLock() = runTest {
        val e = env(initial = "x ")
        val shift = e.key(KeyType.SHIFT)
        e.controller.interpreter.onDown(0, shift.centerX, shift.centerY)
        e.controller.interpreter.onUp(0, shift.centerX, shift.centerY)
        e.scheduler.advanceBy(150)
        e.controller.interpreter.onDown(0, shift.centerX, shift.centerY)
        e.controller.interpreter.onUp(0, shift.centerX, shift.centerY)
        assertEquals(ShiftState.CAPS_LOCK, e.controller.shift)
        e.scheduler.advanceBy(500)
        e.tap(e.key("a"))
        e.tap(e.key("b"))
        assertEquals("x AB", e.editor.value)
        e.tap(shift)
        assertEquals(ShiftState.OFF, e.controller.shift)
        // Slow second tap is not a double tap: one-shot on, then off.
        e.tap(shift)
        e.scheduler.advanceBy(400)
        e.tap(shift)
        assertEquals(ShiftState.OFF, e.controller.shift)
    }

    @Test
    fun shiftLongPressIsCapsLock() = runTest {
        val e = env(initial = "x ")
        val shift = e.key(KeyType.SHIFT)
        e.controller.interpreter.onDown(0, shift.centerX, shift.centerY)
        e.scheduler.advanceBy(400)
        e.controller.interpreter.onUp(0, shift.centerX, shift.centerY)
        assertEquals(ShiftState.CAPS_LOCK, e.controller.shift)
    }

    @Test
    fun swipeUpUppercaseKeepsShiftState() = runTest {
        val e = env(initial = "ab")
        e.swipe(e.key("c"), dx = 0f, dy = -40f)
        assertEquals("abC", e.editor.value)
        assertEquals(ShiftState.OFF, e.controller.shift)
    }

    @Test
    fun doubleSpacePeriod() = runTest {
        val e = env(initial = "hallo")
        e.tap(e.key(KeyType.SPACE))
        e.controller.interpreter.onDown(0, e.key(KeyType.SPACE).centerX, e.key(KeyType.SPACE).centerY)
        e.controller.interpreter.onUp(0, e.key(KeyType.SPACE).centerX, e.key(KeyType.SPACE).centerY)
        assertEquals("hallo. ", e.editor.value)
        assertEquals(ShiftState.ONE_SHOT, e.controller.shift)
        // Slow double space is just two spaces.
        val slow = env(initial = "ok")
        slow.tap(slow.key(KeyType.SPACE))
        slow.scheduler.advanceBy(500)
        slow.tap(slow.key(KeyType.SPACE))
        assertEquals("ok  ", slow.editor.value)
        // Disabled by setting.
        val off = env(settings(SettingKeys.DOUBLE_SPACE_PERIOD to false), initial = "ok")
        off.tap(off.key(KeyType.SPACE))
        off.controller.interpreter.onDown(0, off.key(KeyType.SPACE).centerX, off.key(KeyType.SPACE).centerY)
        off.controller.interpreter.onUp(0, off.key(KeyType.SPACE).centerX, off.key(KeyType.SPACE).centerY)
        assertEquals("ok  ", off.editor.value)
    }

    @Test
    fun swipeLeftDeletesAndSwipeRightRestores() = runTest {
        val e = env(initial = "eins zwei drei")
        val k = e.key("g")
        e.swipe(k, dx = -45f)
        assertEquals("eins zwei ", e.editor.value)
        e.swipe(k, dx = -90f) // two more words in one gesture
        assertEquals("", e.editor.value)
        assertEquals(3, e.controller.restoreStackSize)
        e.swipe(k, dx = 45f)
        assertEquals("eins ", e.editor.value)
        e.swipe(k, dx = 45f)
        assertEquals("eins zwei ", e.editor.value)
        // Typing clears the stack; swipe right then has nothing to do.
        e.tap(e.key("x"))
        assertEquals(0, e.controller.restoreStackSize)
        e.swipe(k, dx = 45f)
        assertEquals("eins zwei x", e.editor.value)
        assertEquals("noop", e.events.last())
    }

    @Test
    fun swipeRightUndoesAiCorrection() = runTest {
        val e = env(aiActive = true)
        e.type("Ich bin hir ")
        advanceTimeBy(701)
        runCurrent()
        e.ai.corrections.single().reply.complete("Ich bin hier ")
        runCurrent()
        assertEquals("Ich bin hier ", e.editor.value)
        e.swipe(e.key("g"), dx = 45f)
        assertEquals("Ich bin hir ", e.editor.value)
    }

    @Test
    fun backspaceDeletesGraphemesAndSelections() = runTest {
        val e = env(initial = "ok 👍🏽")
        e.tap(e.key(KeyType.BACK))
        assertEquals("ok ", e.editor.value)
        e.editor.select(0, 2)
        e.tap(e.key(KeyType.BACK))
        assertEquals(" ", e.editor.value)
        e.editor.select(0, 0)
        e.tap(e.key(KeyType.BACK))
        assertEquals(1, e.editor.backspaceKeys) // nothing before the caret → raw key event
    }

    @Test
    fun backspaceHoldAcceleratesToWords() = runTest {
        val e = env(initial = "das ist ein ziemlich langer satz mit vielen woertern")
        val back = e.key(KeyType.BACK)
        e.controller.interpreter.onDown(0, back.centerX, back.centerY)
        e.scheduler.advanceBy(400 + 55L * 10)
        // 12 characters deleted one by one …
        assertEquals("das ist ein ziemlich langer satz mit vie", e.editor.value)
        // … then whole words.
        e.scheduler.advanceBy(55)
        assertEquals("das ist ein ziemlich langer satz mit ", e.editor.value)
        e.scheduler.advanceBy(220)
        assertEquals("das ist ein ziemlich langer satz ", e.editor.value)
        e.controller.interpreter.onUp(0, back.centerX, back.centerY)
    }

    @Test
    fun longPressAlternatives() = runTest {
        val e = env(initial = "x")
        assertEquals(listOf("3", "è", "é", "ê", "ë", "ē", "ė", "ę", "ě", "ĕ", "ə"), e.controller.alternativesFor(e.key("e")))
        assertEquals(listOf("ß", "ś", "š", "ş", "ș", "ŝ"), e.controller.alternativesFor(e.key("s")))
        assertEquals(listOf("…", "·", "•"), e.controller.alternativesFor(e.key(".")))
        assertTrue(e.controller.alternativesFor(e.key(KeyType.SPACE)).isEmpty())
        e.tap(e.key(KeyType.SHIFT))
        assertEquals("ẞ", e.controller.alternativesFor(e.key("s")).first())
        assertEquals("È", e.controller.alternativesFor(e.key("e"))[1])

        val noAccents = env(settings(SettingKeys.LONG_PRESS_ACCENTS to false), initial = "x")
        assertEquals(listOf("3"), noAccents.controller.alternativesFor(noAccents.key("e")))
        assertTrue(noAccents.controller.alternativesFor(noAccents.key("x")).isEmpty())
        val noDigits = env(settings(SettingKeys.LONG_PRESS_DIGITS to false), initial = "x")
        assertEquals("è", noDigits.controller.alternativesFor(noDigits.key("e")).first())
        assertEquals(null, noDigits.controller.digitHintFor(noDigits.key("e")))
        assertEquals("0", e.controller.digitHintFor(e.key("p")))
        assertEquals(null, e.controller.digitHintFor(e.key("ü"))) // index 10
    }

    @Test
    fun longPressTypesSelectedAlternative() = runTest {
        val e = env(initial = "x")
        val a = e.key("a")
        e.controller.interpreter.onDown(0, a.centerX, a.centerY)
        e.scheduler.advanceBy(400)
        val popup = e.controller.popup!!
        val target = popup.items.indexOf("ä")
        e.controller.interpreter.onMove(0, popup.cells[target].centerX, popup.cells[target].centerY)
        e.controller.interpreter.onUp(0, popup.cells[target].centerX, popup.cells[target].centerY)
        assertEquals("xä", e.editor.value)
        assertEquals(null, e.controller.popup)
    }

    @Test
    fun symbolPagesReturnToLettersAfterSpace() = runTest {
        val e = env(initial = "a")
        e.tap(e.key(KeyType.SYMBOLS))
        assertEquals(Page.SYMBOLS1, e.controller.page)
        e.controller.layout(400f, metrics)
        e.tap(e.key("1"))
        assertEquals(Page.SYMBOLS1, e.controller.page)
        e.tap(e.key(KeyType.SYMBOLS2))
        assertEquals(Page.SYMBOLS2, e.controller.page)
        e.controller.layout(400f, metrics)
        e.tap(e.key(KeyType.SPACE))
        assertEquals(Page.LETTERS, e.controller.page)
        assertEquals("a1 ", e.editor.value)
        // Number fields start on the symbols page.
        val number = env(field = FieldInfo(kind = FieldKind.NUMBER))
        assertEquals(Page.SYMBOLS1, number.controller.page)
    }

    @Test
    fun enterInsertsNewlineOrPerformsAction() = runTest {
        val e = env(initial = "hallo")
        e.tap(e.key(KeyType.ENTER))
        assertEquals("hallo\n", e.editor.value)
        val send = env(field = FieldInfo(enterAction = EnterAction.SEND), initial = "hallo")
        send.tap(send.key(KeyType.ENTER))
        assertEquals(listOf(EnterAction.SEND), send.editor.editorActions)
        assertEquals("hallo", send.editor.value)
        assertEquals("Senden", send.controller.labelFor(send.key(KeyType.ENTER)) { shared.strings[it] })
        assertEquals("⏎", e.controller.labelFor(e.key(KeyType.ENTER)) { shared.strings[it] })
    }

    @Test
    fun enterFlushesPendingCorrectionFirst() = runTest {
        val e = env(field = FieldInfo(enterAction = EnterAction.SEND), aiActive = true)
        e.type("Ich bin hir")
        e.tap(e.key(KeyType.ENTER))
        assertTrue(e.controller.enterWaiting)
        assertTrue(e.editor.editorActions.isEmpty())
        runCurrent()
        val call = e.ai.corrections.single()
        assertEquals("Ich bin hir", call.segment)
        call.reply.complete("Ich bin hier")
        runCurrent()
        assertEquals("Ich bin hier", e.editor.value)
        assertEquals(listOf(EnterAction.SEND), e.editor.editorActions)
        assertFalse(e.controller.enterWaiting)
    }

    @Test
    fun secondEnterTapContinuesAtOnce() = runTest {
        val e = env(field = FieldInfo(enterAction = EnterAction.SEND), aiActive = true)
        e.type("Ich bin hir")
        e.tap(e.key(KeyType.ENTER))
        runCurrent()
        assertTrue(e.controller.enterWaiting)
        e.tap(e.key(KeyType.ENTER))
        assertEquals(listOf(EnterAction.SEND), e.editor.editorActions)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(EnterAction.SEND), e.editor.editorActions) // exactly once
    }

    @Test
    fun emojiRecentsAndPanel() = runTest {
        val e = env()
        e.tap(e.key(KeyType.EMOJI))
        assertEquals("emoji", e.events.last())
        e.controller.insertEmoji("😀")
        e.controller.insertEmoji("👍")
        e.controller.insertEmoji("😀")
        assertEquals("😀👍😀", e.editor.value)
        assertEquals(listOf("😀", "👍"), e.controller.settings!!.emojiRecents)
        repeat(40) { e.controller.insertEmoji("x$it") }
        assertEquals(32, e.controller.settings!!.emojiRecents.size)
        e.controller.emojiBackspace()
        assertEquals("x3", e.editor.value.takeLast(2))
    }

    @Test
    fun languagesModeAndLayouts() = runTest {
        val e = env(
            settings(
                SettingKeys.LANGUAGES to listOf("de-CH", "en", "fr"),
                SettingKeys.ACTIVE_LANGUAGE to "de-CH",
                SettingKeys.KEYBOARD_MODE to "hex",
                SettingKeys.keyboard("en") to "qwertz",
            ),
        )
        assertEquals("qwertz", e.controller.keyboardId)
        assertEquals(KeyboardMode.HEX, e.controller.mode)
        e.controller.cycleLanguage()
        assertEquals("en", e.controller.language.id)
        assertEquals("qwertz", e.controller.keyboardId) // per-language override
        e.controller.cycleLanguage()
        assertEquals("azerty", e.controller.keyboardId)
        e.controller.cycleLanguage()
        assertEquals("de-CH", e.controller.language.id)
        assertEquals(listOf("lang:en", "lang:fr", "lang:de-CH"), e.events.filter { it.startsWith("lang") })
        e.controller.toggleKeyboardMode()
        assertEquals(KeyboardMode.NORMAL, e.controller.mode)
        assertEquals("mode:normal", e.events.last())
        val layout = e.controller.layout(400f, metrics)!!
        assertEquals(KeyboardMode.NORMAL, layout.geometryMode)
        assertEquals("DE-CH", e.controller.labelFor(layout.keys.first { it.type == KeyType.SPACE }) { it })
    }

    @Test
    fun spaceDragMovesCursor() = runTest {
        val e = env(initial = "hallo welt")
        val space = e.key(KeyType.SPACE)
        e.controller.interpreter.onDown(0, space.centerX, space.centerY)
        e.controller.interpreter.onMove(0, space.centerX - 35f, space.centerY)
        e.controller.interpreter.onUp(0, space.centerX - 35f, space.centerY)
        assertEquals(7, e.editor.cursor)
        assertEquals("hallo welt", e.editor.value)
    }
}
