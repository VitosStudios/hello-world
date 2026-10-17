package ch.vitosstudios.tippli.core.engine

import ch.vitosstudios.tippli.core.ai.AiClient
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.data.Language
import ch.vitosstudios.tippli.core.prompts.HistoryEntry
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.settings.Trigger
import ch.vitosstudios.tippli.core.text.Segment
import ch.vitosstudios.tippli.core.text.TextAlgorithms
import ch.vitosstudios.tippli.core.text.Unicode
import ch.vitosstudios.tippli.core.tips.TipsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Toolbar AI dot state (SPEC §7.1). */
enum class AiStatus { READY, WORKING, ERROR, OFF, LOCKED, NO_KEY }

/** Engine configuration derived from the settings and the active language. */
data class EngineConfig(
    val aiEnabled: Boolean = true,
    val trigger: Trigger = Trigger.LIVE,
    val pauseMs: Long = 700,
    val strength: String = "normal",
    val waitOnSendMs: Long = 1500,
    val moodWarning: Boolean = true,
    val tipsEmotion: Boolean = true,
    val language: Language = Language(id = "de-CH"),
    val keyboardId: String = "qwertz",
    /** False when the provider needs an API key and none is stored. */
    val hasKey: Boolean = true,
)

/** Callbacks from the engine to the keyboard (UI thread). */
interface EngineListener {
    /** Status, undo availability or mood changed: refresh the toolbar. */
    fun onEngineStateChanged() {}

    /** A correction was applied to the text. */
    fun onCorrectionApplied() {}

    /** The emotion chip appeared (first time since it was hidden): double haptic tick (SPEC §4.7). */
    fun onMoodWarningShown(level: Int) {}
}

/**
 * The correction engine of SPEC §5. Platform-agnostic; talks to the field via
 * [TextHost] and to the model via [AiClient]. All public methods must be
 * called on the thread of [scope] (the UI thread on Android).
 */
class CorrectionEngine(
    private val host: TextHost,
    private val prompts: PromptBuilder,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    var listener: EngineListener? = null

    private class InFlight(val segment: Segment, val explicit: Boolean) {
        val typedSince = StringBuilder()
        var invalid = false
        var job: Job? = null
    }

    private class LastCorrection(val raw: String, val corrected: String, suffix: String) {
        /** Everything typed after the corrected text (initially the suffix kept by the edit). */
        val typed = StringBuilder(suffix)
    }

    var config = EngineConfig()
        private set
    private var client: AiClient? = null
    private var fieldAllowed = true

    /** Toolbar dot toggle; resets to true on field change (SPEC §5.1). */
    var sessionEnabled = true
        private set

    private var debounceJob: Job? = null
    private var inFlight: InFlight? = null
    private var rerun = false
    private var rerunExplicit = false
    private val history = ArrayDeque<HistoryEntry>()
    private var lastCorrection: LastCorrection? = null
    private var suppressUntilBoundary = false
    private var lastCorrectedOutput: String? = null
    private var dirty = false
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }
    private val busy = MutableStateFlow(false)

    private var consecutiveErrors = 0
    private var pausedUntil = Long.MIN_VALUE

    /** The last correction error (red dot), null when the last request succeeded. */
    var lastError: AiException? = null
        private set

    var moodLevel = 0
        private set
    var moodReason = ""
        private set
    private var moodJob: Job? = null
    private var lastMoodCheckAt = Long.MIN_VALUE
    private var lastMoodParagraph: String? = null

    // ------------------------------------------------------------------ configuration

    /** Applies new settings; [client] is null when no provider is usable. */
    fun configure(config: EngineConfig, client: AiClient?) {
        val wasActive = isActive()
        this.config = config
        this.client = client
        if (wasActive && !isActive()) cancelWork()
        notifyState()
    }

    /**
     * A new field gained focus (SPEC §4.8, §5.1). [resetSession] is false when the
     * same editor merely restarted its input (the toolbar toggle then survives).
     */
    fun onFieldStart(aiAllowed: Boolean, resetSession: Boolean = true) {
        cancelWork()
        fieldAllowed = aiAllowed
        if (resetSession) sessionEnabled = true
        history.clear()
        lastCorrection = null
        suppressUntilBoundary = false
        lastCorrectedOutput = null
        dirty = false
        lastError = null
        consecutiveErrors = 0
        pausedUntil = Long.MIN_VALUE
        moodLevel = 0
        moodReason = ""
        lastMoodParagraph = null
        lastMoodCheckAt = Long.MIN_VALUE
        notifyState()
    }

    /** The field lost focus: stop all work. */
    fun onFieldEnd() {
        cancelWork()
        notifyState()
    }

    /** Toolbar AI dot tap. */
    fun toggleSession() {
        sessionEnabled = !sessionEnabled
        if (!sessionEnabled) cancelWork()
        notifyState()
    }

    // ------------------------------------------------------------------ state

    val status: AiStatus
        get() = when {
            !fieldAllowed -> AiStatus.LOCKED
            !config.aiEnabled || !sessionEnabled -> AiStatus.OFF
            !config.hasKey || client == null -> AiStatus.NO_KEY
            lastError != null -> AiStatus.ERROR
            inFlight != null -> AiStatus.WORKING
            else -> AiStatus.READY
        }

    /** True while a correction can be undone (toolbar **Original** button, SPEC §5.5). */
    val canUndo: Boolean get() = lastCorrection != null

    /** Correction history for tip explanations (SPEC §5.8), oldest first. */
    fun history(): List<HistoryEntry> = history.toList()

    /** A correction is pending (debounce, uncorrected text) or in flight (SPEC §4.3). */
    fun hasPending(): Boolean {
        if (!isActive()) return false
        if (inFlight != null || debounceJob?.isActive == true) return true
        return dirty && config.trigger != Trigger.MANUAL && !suppressUntilBoundary
    }

    val isBusy: Boolean get() = inFlight != null

    private fun isActive(): Boolean =
        fieldAllowed && config.aiEnabled && sessionEnabled && config.hasKey && client != null

    private fun autoTriggersAllowed(): Boolean =
        isActive() && config.trigger != Trigger.MANUAL && clock() >= pausedUntil

    // ------------------------------------------------------------------ text events

    /** The keyboard appended [text] at the caret (letters, space, punctuation, emoji, newline). */
    fun onTextAppended(text: String) {
        if (text.isEmpty()) return
        inFlight?.let { if (!it.invalid) it.typedSince.append(text) }
        lastCorrection?.typed?.append(text)
        val boundary = TextAlgorithms.containsSentenceEnd(text)
        if (suppressUntilBoundary) {
            if (!boundary) return
            suppressUntilBoundary = false
        }
        dirty = true
        if (!autoTriggersAllowed()) return
        when (config.trigger) {
            Trigger.LIVE -> if (boundary || completesWord(text)) triggerNow() else restartDebounce()
            Trigger.PAUSE -> if (boundary) triggerNow() else restartDebounce()
            Trigger.WORD -> if (boundary || completesWord(text)) triggerNow()
            Trigger.SENTENCE -> if (boundary) triggerNow()
            Trigger.MANUAL -> Unit
        }
    }

    /**
     * Any change that is not an append by the keyboard: delete, cursor move,
     * selection change, swipe delete/restore (SPEC §5.1).
     */
    fun onNonAppendChange() {
        invalidateInFlight()
        debounceJob?.cancel()
        debounceJob = null
        if (lastCorrection != null) {
            lastCorrection = null
            notifyState()
        }
    }

    /** Text was replaced from the tips panel: clear history and undo (SPEC §7.2). */
    fun onTextReplaced() {
        onNonAppendChange()
        history.clear()
        lastCorrection = null
        lastCorrectedOutput = null
        notifyState()
    }

    /** True if [text] (just appended) completes a word: whitespace/punctuation after a letter or digit. */
    private fun completesWord(text: String): Boolean {
        val first = text.codePointAt(0)
        val isSeparator = (first < 0x10000 && Unicode.isWhitespace(first.toChar())) || isWordEndPunctuation(first)
        if (!isSeparator) return false
        val before = host.textBeforeCursor(text.length + 2)
        if (!before.endsWith(text)) return false
        val prev = Unicode.codePointBefore(before, before.length - text.length)
        return prev >= 0 && Unicode.isLetterOrNumber(prev)
    }

    private fun isWordEndPunctuation(cp: Int): Boolean {
        // Apostrophes, hyphens and underscores belong to words ("don't", "E-Mail").
        if (cp < 0x10000 && "'’-_".indexOf(cp.toChar()) >= 0) return false
        return when (Character.getType(cp)) {
            Character.OTHER_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------ triggers

    private fun restartDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(config.pauseMs)
            debounceJob = null
            if (autoTriggersAllowed()) requestCorrection(explicit = false)
        }
    }

    private fun triggerNow() {
        debounceJob?.cancel()
        debounceJob = null
        requestCorrection(explicit = false)
    }

    /** Toolbar ✨: correct now, including the word in progress (SPEC §5.3). */
    fun correctNow() {
        if (!isActive()) return
        suppressUntilBoundary = false
        pausedUntil = Long.MIN_VALUE
        debounceJob?.cancel()
        debounceJob = null
        requestCorrection(explicit = true)
    }

    /**
     * Flush before send (SPEC §4.3, §5.4.7): correct immediately with the word in
     * progress and wait up to `waitOnSendMs` until it is applied.
     * Returns true when nothing is pending any more.
     */
    suspend fun flush(): Boolean {
        if (!isActive()) return true
        debounceJob?.cancel()
        debounceJob = null
        if (suppressUntilBoundary) return true
        if (inFlight != null) {
            rerun = true
            rerunExplicit = true
        } else {
            requestCorrection(explicit = true)
        }
        if (!busy.value) return true
        return withTimeoutOrNull(config.waitOnSendMs) { busy.first { !it } } != null
    }

    private fun requestCorrection(explicit: Boolean) {
        if (!isActive()) return
        val currentClient = client ?: return
        inFlight?.let {
            rerun = true
            if (explicit) rerunExplicit = true
            return
        }
        if (suppressUntilBoundary) return
        if (host.selectedText().isNotEmpty()) return
        val before = host.textBeforeCursor(BEFORE_CHARS)
        // A word in progress that a non-explicit request leaves out stays "dirty", so a
        // flush before send still corrects it.
        dirty = !explicit && !config.language.pinyin && before.isNotEmpty() &&
            Unicode.isLetterOrNumber(Unicode.lastCodePoint(before))
        val segment = TextAlgorithms.selectSegment(before, explicit, config.language.pinyin)
        if (segment == null || segment.segment == lastCorrectedOutput) return
        cache[segment.segment]?.let { cached ->
            applyCorrection(segment, cached, typedSince = "")
            return
        }
        val request = InFlight(segment, explicit)
        inFlight = request
        updateBusy()
        notifyState()
        val config = config
        request.job = scope.launch {
            val outcome: Result<String> = try {
                val prompt = prompts.correction(
                    config.language, config.strength, config.keyboardId, segment.context, segment.segment,
                )
                Result.success(currentClient.complete(AiTask.CORRECTION, prompt.system, prompt.user))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException(AiErrorKind.BAD_RESPONSE, e.message, e))
            }
            handleResult(request, outcome)
        }
    }

    private fun handleResult(request: InFlight, outcome: Result<String>) {
        if (inFlight !== request) return
        inFlight = null
        outcome.fold(
            onSuccess = { output ->
                consecutiveErrors = 0
                lastError = null
                val cleaned = TextAlgorithms.cleanOutput(request.segment.segment, output, config.language.pinyin)
                if (cleaned != null) cache[request.segment.segment] = cleaned
                if (!request.invalid && cleaned != null) {
                    applyCorrection(request.segment, cleaned, request.typedSince.toString())
                }
            },
            onFailure = { error ->
                val e = error as? AiException ?: AiException(AiErrorKind.BAD_RESPONSE, error.message, error)
                if (e.kind == AiErrorKind.REFUSED) {
                    // Keep the raw text silently (SPEC §6.2).
                    consecutiveErrors = 0
                } else {
                    consecutiveErrors++
                    lastError = e
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        pausedUntil = clock() + ERROR_PAUSE_MS
                        rerun = false
                        rerunExplicit = false
                    }
                }
            },
        )
        if (rerun) {
            val explicit = rerunExplicit
            rerun = false
            rerunExplicit = false
            requestCorrection(explicit)
        }
        updateBusy()
        notifyState()
    }

    /** SPEC §5.4 steps 4–6. Returns true when the text now contains the correction. */
    private fun applyCorrection(segment: Segment, corrected: String, typedSince: String): Boolean {
        val needed = segment.segment.length + segment.trailing.length + typedSince.length
        val before = host.textBeforeCursor(maxOf(BEFORE_CHARS, needed + 16))
        if (!TextAlgorithms.canApply(before, segment.segment, segment.trailing, typedSince)) return false
        lastCorrectedOutput = corrected
        val suffix = segment.trailing + typedSince
        val edit = TextAlgorithms.computeEdit(segment.segment, corrected, suffix) ?: return true
        host.batch {
            host.delete(edit.delete)
            host.insert(edit.insert)
        }
        history.addLast(HistoryEntry(segment.segment, corrected))
        while (history.size > MAX_HISTORY) history.removeFirst()
        lastCorrection = LastCorrection(segment.segment, corrected, suffix)
        listener?.onCorrectionApplied()
        maybeCheckMood(segment.segment)
        notifyState()
        return true
    }

    // ------------------------------------------------------------------ undo

    /** Undo the last AI correction (SPEC §5.5). Returns false when it is not possible. */
    fun undo(): Boolean {
        val last = lastCorrection ?: return false
        val typed = last.typed.toString()
        val tail = last.corrected + typed
        val before = host.textBeforeCursor(maxOf(BEFORE_CHARS, tail.length + 16))
        lastCorrection = null
        if (!before.endsWith(tail)) {
            notifyState()
            return false
        }
        invalidateInFlight()
        debounceJob?.cancel()
        debounceJob = null
        val p = TextAlgorithms.commonPrefixLength(last.raw, last.corrected)
        host.batch {
            host.delete(last.corrected.substring(p) + typed)
            host.insert(last.raw.substring(p) + typed)
        }
        suppressUntilBoundary = true
        lastCorrectedOutput = null
        if (history.lastOrNull()?.let { it.raw == last.raw && it.corrected == last.corrected } == true) {
            history.removeLast()
        }
        notifyState()
        return true
    }

    // ------------------------------------------------------------------ mood check

    private fun maybeCheckMood(segment: String) {
        if (!config.moodWarning || !config.tipsEmotion || !isActive()) return
        if (!TextAlgorithms.containsSentenceEnd(segment)) return
        val now = clock()
        if (lastMoodCheckAt != Long.MIN_VALUE && now - lastMoodCheckAt < MOOD_INTERVAL_MS) return
        if (moodJob?.isActive == true) return
        val paragraph = host.textBeforeCursor(BEFORE_CHARS).substringAfterLast('\n').takeLast(MOOD_MAX_CHARS).trim()
        if (paragraph.isEmpty()) return
        val previous = lastMoodParagraph
        if (previous != null && changeAmount(previous, paragraph) < MOOD_MIN_CHANGE) return
        val currentClient = client ?: return
        lastMoodCheckAt = now
        lastMoodParagraph = paragraph
        moodJob = scope.launch {
            try {
                val prompt = prompts.mood(paragraph)
                val result = TipsParser.parseMood(currentClient.complete(AiTask.MOOD, prompt.system, prompt.user))
                setMood(result.level, result.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The mood check is best effort; errors never disturb typing.
            }
        }
    }

    /** Updates the emotion chip (SPEC §5.7). Also used when the tips panel rated the text. */
    fun setMood(level: Int, reason: String) {
        val wasShown = moodLevel >= 2
        moodLevel = level.coerceIn(0, 3)
        moodReason = reason
        if (!wasShown && moodLevel >= 2) listener?.onMoodWarningShown(moodLevel)
        notifyState()
    }

    // ------------------------------------------------------------------ helpers

    private fun invalidateInFlight() {
        inFlight?.let {
            it.invalid = true
            it.typedSince.setLength(0)
        }
    }

    private fun cancelWork() {
        debounceJob?.cancel()
        debounceJob = null
        inFlight?.job?.cancel()
        inFlight = null
        rerun = false
        rerunExplicit = false
        moodJob?.cancel()
        moodJob = null
        updateBusy()
    }

    private fun updateBusy() {
        busy.value = inFlight != null
    }

    private fun notifyState() {
        listener?.onEngineStateChanged()
    }

    companion object {
        const val BEFORE_CHARS = 1000
        const val MAX_HISTORY = 20
        const val CACHE_SIZE = 20
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val ERROR_PAUSE_MS = 30_000L
        const val MOOD_INTERVAL_MS = 20_000L
        const val MOOD_MIN_CHANGE = 20
        const val MOOD_MAX_CHARS = 1000

        /** Rough amount of change between two versions of a paragraph. */
        fun changeAmount(a: String, b: String): Int {
            var p = 0
            val max = minOf(a.length, b.length)
            while (p < max && a[p] == b[p]) p++
            return maxOf(a.length, b.length) - p
        }
    }
}
