package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.prompts.HistoryEntry
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.prompts.TipsOptions
import ch.vitosstudios.tippli.core.tips.TipsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptBuilderTest {
    private val shared = TestShared.data
    private val builder = PromptBuilder(shared.prompts, shared.layouts)
    private val deCh = shared.language("de-CH")!!

    @Test
    fun keyboardLayoutDescription() {
        assertEquals(
            "QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm",
            builder.keyboardLayout("qwertz"),
        )
        assertEquals("QWERTY: qwertyuiop / asdfghjkl / zxcvbnm", builder.keyboardLayout("qwerty"))
        assertEquals("AZERTY (Français): azertyuiop / qsdfghjklm / wxcvbn'", builder.keyboardLayout("azerty"))
    }

    @Test
    fun correctionPromptFillsEveryPlaceholder() {
        val p = builder.correction(deCh, "strong", "qwertz", context = "Hallo.\n", segment = "ich bin hir ")
        assertFalse(p.system.contains("{{"))
        assertFalse(p.user.contains("{{"))
        assertTrue(p.system.contains("Language: Swiss Standard German (de-CH)"))
        assertTrue(p.system.contains(deCh.rules))
        assertTrue(p.system.contains(shared.prompts.parts.strengthRules.getValue("strong")))
        assertTrue(p.system.contains("Keyboard: QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm"))
        assertTrue(p.user.contains("<context>Hallo.\n</context>"))
        assertTrue(p.user.contains("<segment>ich bin hir </segment>"))
    }

    @Test
    fun placeholdersAreReplacedInOnePass() {
        val p = builder.correction(deCh, "normal", "qwertz", context = "{{segment}}", segment = "x")
        assertTrue(p.user.contains("<context>{{segment}}</context>"))
        assertEquals("a b {{c}}", PromptBuilder.fill("{{x}} {{y}} {{c}}", mapOf("x" to "a", "y" to "b")))
    }

    @Test
    fun connectionTestUsesTestSegment() {
        val p = builder.connectionTest(deCh, "normal", "qwertz")
        assertTrue(p.user.contains("<context></context>"))
        assertTrue(p.user.contains("<segment>${shared.prompts.parts.testSegment}</segment>"))
    }

    @Test
    fun tipsPrompt() {
        val p = builder.tips(
            deCh,
            TipsOptions(rewrites = true, explanations = true, writing = false, emotion = true,
                rewriteVariants = listOf("formal", "shorter", "unknown")),
            text = "das isch super",
            history = listOf(HistoryEntry("ich bin hir ", "Ich bin hier "), HistoryEntry("a", "b")),
        )
        assertFalse(p.system.contains("{{"))
        assertTrue(p.system.contains("formal (\"Formeller\": more formal and polite), shorter (\"Kürzer\": shorter and to the point)"))
        assertTrue(p.system.contains("return them as empty lists (or level 0 with empty strings): tips."))
        assertTrue(p.system.contains(shared.prompts.parts.uiLanguage))
        assertTrue(p.user.contains("<text>das isch super</text>"))
        assertTrue(p.user.contains("ich bin hir  -> Ich bin hier \na -> b"))

        val none = builder.tips(deCh, TipsOptions(rewriteVariants = listOf("formal")), "x", emptyList())
        assertTrue(none.system.contains("empty strings): none."))
        assertTrue(none.user.contains("(none)"))

        val noVariants = builder.tips(deCh, TipsOptions(rewriteVariants = emptyList()), "x", emptyList())
        assertTrue(noVariants.system.contains("empty strings): rewrites."))
    }

    @Test
    fun moodAndTranslatePrompts() {
        val mood = builder.mood("Das ist UNMÖGLICH!!!")
        assertEquals("Das ist UNMÖGLICH!!!", mood.user)
        assertTrue(mood.system.contains(shared.prompts.parts.uiLanguage))
        val target = shared.translateTargets.first { it.id == "en" }
        val tr = builder.translate(target, "Hallo")
        assertTrue(tr.system.contains("into English."))
        assertEquals("Hallo", tr.user)
    }

    @Test
    fun tipsParsing() {
        val text = """
            Here you go:
            ```json
            {"rewrites": [{"id": "formal", "label": "Formeller", "text": "Guten Tag"}],
             "explanations": [{"from": "hir", "to": "hier", "why": "Tippfehler"}],
             "tips": ["Kürzer schreiben"],
             "emotion": {"level": "2", "label": "Gereizt", "reason": "Viele Ausrufezeichen", "calmer": "Ruhig"}}
            ```
        """.trimIndent()
        val r = TipsParser.parseTips(text)
        assertEquals("Guten Tag", r.rewrites.single().text)
        assertEquals("hier", r.explanations.single().to)
        assertEquals(listOf("Kürzer schreiben"), r.tips)
        assertEquals(2, r.emotion.level)
        assertEquals("Ruhig", r.emotion.calmer)

        val partial = TipsParser.parseTips("""{"tips": []}""")
        assertTrue(partial.rewrites.isEmpty())
        assertEquals(0, partial.emotion.level)

        assertEquals(3, TipsParser.parseMood("""{"level": 3, "reason": "Wut"}""").level)
        assertEquals(0, TipsParser.parseMood("""{"reason": ""}""").level)
        for (bad in listOf("no json here", "{not json}", "")) {
            try {
                TipsParser.parseTips(bad)
                fail("expected badResponse for '$bad'")
            } catch (e: AiException) {
                assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
            }
        }
    }
}
