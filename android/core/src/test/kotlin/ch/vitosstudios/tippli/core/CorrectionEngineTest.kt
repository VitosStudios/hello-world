package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.engine.AiStatus
import ch.vitosstudios.tippli.core.engine.CorrectionEngine
import ch.vitosstudios.tippli.core.engine.EngineConfig
import ch.vitosstudios.tippli.core.engine.EngineListener
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.settings.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CorrectionEngineTest {
    private val shared = TestShared.data
    private val prompts = PromptBuilder(shared.prompts, shared.layouts)
    private val endpoint = AiEndpoint(shared.provider("anthropic")!!, apiKey = "sk-test", model = "claude-haiku-5-5")

    private class Env(val engine: CorrectionEngine, val editor: FakeEditor, val ai: FakeAi) {
        var moodShown = 0
        var applied = 0

        /** Types like the keyboard does: insert, then tell the engine. */
        fun type(s: String) {
            var i = 0
            while (i < s.length) {
                val n = Character.charCount(s.codePointAt(i))
                val piece = s.substring(i, i + n)
                editor.insert(piece)
                engine.onTextAppended(piece)
                i += n
            }
        }
    }

    private fun TestScope.env(
        trigger: Trigger = Trigger.LIVE,
        initial: String = "",
        hasKey: Boolean = true,
        allowed: Boolean = true,
        language: String = "de-CH",
        waitOnSendMs: Long = 1500,
    ): Env {
        val editor = FakeEditor(initial)
        val ai = FakeAi(endpoint)
        val engine = CorrectionEngine(editor, prompts, backgroundScope) { testScheduler.currentTime }
        val env = Env(engine, editor, ai)
        engine.listener = object : EngineListener {
            override fun onMoodWarningShown(level: Int) { env.moodShown++ }
            override fun onCorrectionApplied() { env.applied++ }
        }
        engine.configure(
            EngineConfig(
                trigger = trigger, pauseMs = 700, waitOnSendMs = waitOnSendMs,
                language = shared.language(language)!!, keyboardId = "qwertz", hasKey = hasKey,
            ),
            ai,
        )
        engine.onFieldStart(aiAllowed = allowed)
        return env
    }

    @Test
    fun pauseTriggerExcludesWordInProgress() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("ich bin hir unterw")
        advanceTimeBy(699)
        runCurrent()
        assertEquals(0, e.ai.calls.size)
        advanceTimeBy(1)
        runCurrent()
        val call = e.ai.corrections.single()
        assertEquals("ich bin hir ", call.segment)
        assertEquals(AiStatus.WORKING, e.engine.status)
        call.reply.complete("Ich bin hier")
        runCurrent()
        assertEquals("Ich bin hier unterw", e.editor.value)
        assertTrue(e.engine.canUndo)
        assertEquals(AiStatus.READY, e.engine.status)
        assertEquals(1, e.editor.batches)
    }

    @Test
    fun pauseTriggerRestartsOnEveryAppend() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("hallo ")
        advanceTimeBy(500)
        e.type("w")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, e.ai.calls.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, e.ai.calls.size)
    }

    @Test
    fun liveTriggersImmediatelyOnWordEndAndSentenceEnd() = runTest {
        val e = env(Trigger.LIVE)
        e.type("hallo")
        runCurrent()
        assertEquals(0, e.ai.calls.size)
        e.type(" ")
        runCurrent()
        assertEquals("hallo ", e.ai.corrections.single().segment)
        e.ai.calls[0].reply.complete("Hallo ")
        runCurrent()
        assertEquals("Hallo ", e.editor.value)
        // Punctuation after a letter completes a word too; apostrophes and hyphens do not.
        e.type("wie geht's")
        runCurrent()
        assertEquals(2, e.ai.corrections.size) // after "wie " only
        e.ai.calls[1].reply.complete("Hallo wie ")
        runCurrent()
        e.type(",")
        runCurrent()
        assertEquals(3, e.ai.corrections.size)
        assertEquals("Hallo wie geht's,", e.ai.corrections[2].segment)
    }

    @Test
    fun livePauseCoversWordInProgress() = runTest {
        val e = env(Trigger.LIVE)
        e.type("ich bin hir")
        runCurrent()
        // "ich " and "bin " triggered; the first request is in flight, the second coalesced.
        assertEquals(1, e.ai.corrections.size)
        e.ai.calls[0].reply.complete("Ich ")
        runCurrent()
        // The rerun sends the whole paragraph again (word in progress excluded).
        assertEquals("Ich bin ", e.ai.corrections[1].segment)
        e.ai.calls[1].reply.complete("Ich bin ")
        runCurrent()
        assertEquals("Ich bin hir", e.editor.value)
        advanceTimeBy(701)
        runCurrent()
        // Pause trigger: segment unchanged ("Ich bin " equals the last output) → no request.
        assertEquals(2, e.ai.corrections.size)
    }

    @Test
    fun userKeepsTypingDuringRequest() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("ich bin hir ")
        advanceTimeBy(701)
        runCurrent()
        val call = e.ai.corrections.single()
        e.type("unterw")
        call.reply.complete("Ich bin hier ")
        runCurrent()
        assertEquals("Ich bin hier unterw", e.editor.value)
        // Undo restores the raw text and keeps what was typed since.
        e.type("egs")
        assertTrue(e.engine.undo())
        assertEquals("ich bin hir unterwegs", e.editor.value)
        assertFalse(e.engine.canUndo)
    }

    @Test
    fun cursorMoveDropsResult() = runTest {
        val e = env(Trigger.LIVE)
        e.type("ich bin hir ")
        runCurrent()
        e.editor.moveCursor(-2)
        e.engine.onNonAppendChange()
        e.ai.corrections.single().reply.complete("Ich bin hier ")
        runCurrent()
        assertEquals("ich bin hir ", e.editor.value)
        assertFalse(e.engine.canUndo)
    }

    @Test
    fun deleteDuringRequestDropsResult() = runTest {
        val e = env(Trigger.LIVE)
        e.type("ich bin hir ")
        runCurrent()
        e.editor.delete(" ")
        e.engine.onNonAppendChange()
        e.type(" ")
        e.ai.corrections[0].reply.complete("Ich bin hier ")
        runCurrent()
        // Dropped (invalid); the coalesced rerun asks again.
        assertEquals("ich bin hir ", e.editor.value)
        assertEquals(2, e.ai.corrections.size)
    }

    @Test
    fun rerunAfterInFlightResult() = runTest {
        val e = env(Trigger.LIVE)
        e.type("a b ")
        runCurrent()
        assertEquals(1, e.ai.corrections.size)
        e.type("c ")
        runCurrent()
        assertEquals(1, e.ai.corrections.size)
        e.ai.corrections[0].reply.complete("A ")
        runCurrent()
        assertEquals("A b c ", e.editor.value)
        assertEquals(2, e.ai.corrections.size)
        assertEquals("A b c ", e.ai.corrections[1].segment)
    }

    @Test
    fun explicitAndFlushIncludeWordInProgress() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("ich bin hir")
        assertTrue(e.engine.hasPending())
        val flush = async { e.engine.flush() }
        runCurrent()
        val call = e.ai.corrections.single()
        assertEquals("ich bin hir", call.segment)
        call.reply.complete("Ich bin hier")
        runCurrent()
        assertTrue(flush.await())
        assertEquals("Ich bin hier", e.editor.value)
        assertFalse(e.engine.hasPending())
    }

    @Test
    fun flushGivesUpAfterWaitOnSend() = runTest {
        val e = env(Trigger.MANUAL, waitOnSendMs = 1500)
        e.type("ich bin hir")
        // Manual mode has nothing pending automatically …
        assertFalse(e.engine.hasPending())
        // … but the ✨ button corrects now, and flush waits for it.
        e.engine.correctNow()
        runCurrent()
        assertTrue(e.engine.hasPending())
        val flush = async { e.engine.flush() }
        advanceTimeBy(1499)
        runCurrent()
        assertFalse(flush.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(flush.await())
    }

    @Test
    fun flushWhileInFlightReRunsExplicitly() = runTest {
        val e = env(Trigger.LIVE)
        e.type("ich bin hir unterw")
        runCurrent()
        val flush = async { e.engine.flush() }
        runCurrent()
        e.ai.corrections[0].reply.complete("Ich ")
        runCurrent()
        val explicit = e.ai.corrections.last()
        assertEquals("Ich bin hir unterw", explicit.segment)
        explicit.reply.complete("Ich bin hier unterwegs")
        runCurrent()
        assertTrue(flush.await())
        assertEquals("Ich bin hier unterwegs", e.editor.value)
    }

    @Test
    fun undoSuppressesUntilSentenceEnd() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("ich bin hir ")
        advanceTimeBy(701)
        runCurrent()
        e.ai.corrections[0].reply.complete("Ich bin hier ")
        runCurrent()
        assertTrue(e.engine.undo())
        assertEquals("ich bin hir ", e.editor.value)
        e.type("und so ")
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, e.ai.corrections.size)
        // Flush respects the suppression too.
        assertFalse(e.engine.hasPending())
        e.type("weiter.")
        runCurrent()
        assertEquals(2, e.ai.corrections.size)
        assertFalse(e.engine.undo()) // nothing to undo any more
    }

    @Test
    fun cacheAppliesWithoutRequest() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("ich bin hir ")
        advanceTimeBy(701)
        runCurrent()
        e.ai.corrections[0].reply.complete("Ich bin hier ")
        runCurrent()
        // New field with the same raw text: the cached result is applied directly.
        e.editor.text.setLength(0)
        e.editor.cursor = 0
        e.editor.selectionEnd = 0
        e.engine.onFieldStart(aiAllowed = true)
        e.type("ich bin hir ")
        advanceTimeBy(701)
        runCurrent()
        assertEquals(1, e.ai.corrections.size)
        assertEquals("Ich bin hier ", e.editor.value)
    }

    @Test
    fun noRequestForSelectionNoLettersOrUnchangedText() = runTest {
        val e = env(Trigger.LIVE)
        e.type("123 456 ")
        runCurrent()
        assertEquals(0, e.ai.calls.size)
        e.editor.select(0, 3)
        e.engine.correctNow()
        runCurrent()
        assertEquals(0, e.ai.calls.size)
    }

    @Test
    fun errorsTurnDotRedAndPauseAfterThree() = runTest {
        val e = env(Trigger.LIVE)
        repeat(3) { i ->
            e.type("wort$i ")
            runCurrent()
            e.ai.corrections.last().reply.completeExceptionally(AiException(AiErrorKind.SERVER))
            runCurrent()
            assertEquals(AiStatus.ERROR, e.engine.status)
        }
        assertEquals(3, e.ai.corrections.size)
        assertEquals(AiErrorKind.SERVER, e.engine.lastError?.kind)
        e.type("noch ")
        runCurrent()
        assertEquals(3, e.ai.corrections.size) // paused
        advanceTimeBy(30_001)
        e.type("mehr ")
        runCurrent()
        assertEquals(4, e.ai.corrections.size)
        e.ai.corrections.last().reply.complete("Wort0 wort1 wort2 noch mehr ")
        runCurrent()
        assertEquals(AiStatus.READY, e.engine.status)
    }

    @Test
    fun refusalKeepsRawTextSilently() = runTest {
        val e = env(Trigger.LIVE)
        e.type("hallo ")
        runCurrent()
        e.ai.corrections[0].reply.completeExceptionally(AiException(AiErrorKind.REFUSED))
        runCurrent()
        assertEquals("hallo ", e.editor.value)
        assertEquals(AiStatus.READY, e.engine.status)
    }

    @Test
    fun answersInsteadOfCorrectionsAreDropped() = runTest {
        val e = env(Trigger.PAUSE)
        e.type("was ist die hauptstadt von frankreich ")
        advanceTimeBy(701)
        runCurrent()
        e.ai.corrections.last().reply.complete(
            "Die Hauptstadt von Frankreich ist Paris. Paris ist eine wunderschöne Stadt mit vielen Sehenswürdigkeiten.",
        )
        runCurrent()
        assertEquals("was ist die hauptstadt von frankreich ", e.editor.value)
    }

    @Test
    fun statusStates() = runTest {
        val noKey = env(Trigger.LIVE, hasKey = false)
        assertEquals(AiStatus.NO_KEY, noKey.engine.status)
        noKey.type("hallo welt ")
        runCurrent()
        assertEquals(0, noKey.ai.calls.size)

        val locked = env(Trigger.LIVE, allowed = false)
        assertEquals(AiStatus.LOCKED, locked.engine.status)
        locked.type("geheim ")
        runCurrent()
        assertEquals(0, locked.ai.calls.size)

        val off = env(Trigger.LIVE)
        off.engine.toggleSession()
        assertEquals(AiStatus.OFF, off.engine.status)
        off.type("hallo ")
        runCurrent()
        assertEquals(0, off.ai.calls.size)
        off.engine.onFieldStart(aiAllowed = true) // session toggle resets on field change
        assertEquals(AiStatus.READY, off.engine.status)
    }

    @Test
    fun moodCheckAfterSentenceEnd() = runTest {
        val e = env(Trigger.LIVE)
        e.ai.autoReply = { call ->
            when (call.task) {
                AiTask.MOOD -> """{"level": 2, "reason": "Viele Ausrufezeichen"}"""
                else -> call.segment.replace("frch", "frech")
            }
        }
        e.type("Das ist wirklich unglaublich frch")
        runCurrent()
        // Corrections without a sentence end never start a mood check.
        assertEquals(0, e.ai.calls.count { it.task == AiTask.MOOD })
        e.type("!")
        runCurrent()
        assertEquals("Das ist wirklich unglaublich frech!", e.editor.value)
        val moods = e.ai.calls.filter { it.task == AiTask.MOOD }
        assertEquals(1, moods.size)
        assertEquals("Das ist wirklich unglaublich frech!", moods[0].user)
        assertEquals(2, e.engine.moodLevel)
        assertEquals(1, e.moodShown)
        // Rate limit: no second mood check within 20 s.
        e.type(" Und nochmals viel mehr frch!")
        runCurrent()
        assertEquals("Das ist wirklich unglaublich frech! Und nochmals viel mehr frech!", e.editor.value)
        assertEquals(1, e.ai.calls.count { it.task == AiTask.MOOD })
        // After 20 s and enough new text the paragraph is checked again; level 0 hides the chip.
        e.ai.autoReply = { call ->
            when (call.task) {
                AiTask.MOOD -> """{"level": 0, "reason": ""}"""
                else -> call.segment.replace("frch", "frech")
            }
        }
        advanceTimeBy(20_001)
        e.type(" Jetzt ist alles wieder gut frch.")
        runCurrent()
        assertEquals(2, e.ai.calls.count { it.task == AiTask.MOOD })
        assertEquals(0, e.engine.moodLevel)
    }

    @Test
    fun historyIsRecordedAndCapped() = runTest {
        val e = env(Trigger.LIVE)
        e.ai.autoReply = { call -> call.segment.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }
        repeat(25) {
            e.type("w$it ")
            runCurrent()
        }
        val history = e.engine.history()
        assertEquals(20, history.size)
        assertTrue(history.all { it.raw != it.corrected })
        assertEquals("W0 W1 W2 W3 W4 W5 W6 W7 W8 W9 W10 W11 W12 W13 W14 W15 W16 W17 W18 W19 W20 W21 W22 W23 w24 ", history.last().raw)
    }

    @Test
    fun pinyinDoesNotExcludeWordInProgress() = runTest {
        val e = env(Trigger.PAUSE, language = "zh-Hans")
        e.type("nihaowoshi")
        advanceTimeBy(701)
        runCurrent()
        assertEquals("nihaowoshi", e.ai.corrections.single().segment)
        e.ai.corrections[0].reply.complete("你好我是")
        runCurrent()
        assertEquals("你好我是", e.editor.value)
    }
}
