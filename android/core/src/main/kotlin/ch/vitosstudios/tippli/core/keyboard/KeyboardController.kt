package ch.vitosstudios.tippli.core.keyboard

import ch.vitosstudios.tippli.core.data.KeyboardDefinition
import ch.vitosstudios.tippli.core.data.Language
import ch.vitosstudios.tippli.core.data.SharedData
import ch.vitosstudios.tippli.core.engine.CorrectionEngine
import ch.vitosstudios.tippli.core.engine.TextHost
import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyArea
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.geometry.KeyboardLayout
import ch.vitosstudios.tippli.core.geometry.LayoutBuilder
import ch.vitosstudios.tippli.core.geometry.LayoutRequest
import ch.vitosstudios.tippli.core.geometry.Page
import ch.vitosstudios.tippli.core.geometry.PixelMetrics
import ch.vitosstudios.tippli.core.geometry.Popup
import ch.vitosstudios.tippli.core.input.Clock
import ch.vitosstudios.tippli.core.input.GestureConfig
import ch.vitosstudios.tippli.core.input.GestureListener
import ch.vitosstudios.tippli.core.input.PointerInterpreter
import ch.vitosstudios.tippli.core.input.Scheduler
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import ch.vitosstudios.tippli.core.settings.TippliSettings
import ch.vitosstudios.tippli.core.text.TextAlgorithms
import ch.vitosstudios.tippli.core.text.Unicode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keyboard-level text operations on top of [TextHost]. */
interface EditorHost : TextHost {
    /** Deletes the current selection. */
    fun deleteSelection()

    /** Sends a raw backspace key (fields without readable text, e.g. deleting chips). */
    fun sendBackspaceKey()

    /** Moves the caret by [delta] characters (graphemes where possible). */
    fun moveCursor(delta: Int)

    /** Performs the field's editor action (send/search/go/next/done). */
    fun performEditorAction(action: EnterAction)

    /** Inserts a line break. Returns true when "\n" was inserted as text (an append). */
    fun insertNewline(): Boolean
}

enum class ShiftState { OFF, ONE_SHOT, CAPS_LOCK }

/** Feedback and persistence the platform provides to the controller (UI thread). */
interface KeyboardControllerListener {
    /** Repaint keys and toolbar (state like shift, pressed keys or popup changed). */
    fun onKeyboardChanged() {}

    /** Geometry changed (page, mode, language): re-measure and re-layout. */
    fun onLayoutChanged() {}

    fun onKeyFeedback(key: Key) {}
    fun onPopupFeedback() {}
    fun onNoOpFeedback() {}

    fun onEmojiPanelRequested() {}

    fun persistKeyboardMode(mode: KeyboardMode) {}
    fun persistActiveLanguage(languageId: String) {}
    fun persistEmojiRecents(recents: List<String>) {}
}

/** One swipe-left deletion on the restore stack (SPEC §4.6). */
data class RestoreEntry(val text: String, val contextBefore: String)

/**
 * Platform-agnostic keyboard state machine: pages, shift/caps, long-press
 * alternatives, restore stack, space/enter/backspace behaviour and the bridge
 * between gestures, text field and correction engine (SPEC §3–§4).
 * All methods must be called on the UI thread.
 */
class KeyboardController(
    private val shared: SharedData,
    private val host: EditorHost,
    val engine: CorrectionEngine,
    scheduler: Scheduler,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : GestureListener {

    var listener: KeyboardControllerListener? = null

    val interpreter = PointerInterpreter(this, scheduler, clock, GestureConfig.forDensity(1f))

    var settings: TippliSettings? = null
        private set
    var field = FieldInfo()
        private set
    var language: Language = shared.languages.firstOrNull() ?: Language(id = "de-CH")
        private set
    var keyboardId: String = language.keyboard
        private set
    private var keyboard: KeyboardDefinition? = null
    var mode: KeyboardMode = KeyboardMode.HEX
        private set
    var page: Page = Page.LETTERS
        private set
    var shift: ShiftState = ShiftState.OFF
        private set
    private var shiftAuto = false
    private var lastShiftTapAt = Long.MIN_VALUE
    private var previousKeyWasSpace = false
    private var lastSpaceAt = Long.MIN_VALUE
    private val restoreStack = ArrayDeque<RestoreEntry>()
    private var enterJob: Job? = null

    /** True while enter waits for the flush (spinner on the enter key). */
    val enterWaiting: Boolean get() = enterJob?.isActive == true

    var popup: Popup? = null
        private set

    private var layoutCache: KeyboardLayout? = null
    private var layoutKey: Any? = null

    val restoreStackSize: Int get() = restoreStack.size
    val isShifted: Boolean get() = shift != ShiftState.OFF

    // ------------------------------------------------------------------ configuration

    /** Called on every field start with fresh settings (SPEC §8: re-read on show). */
    fun startInput(settings: TippliSettings, field: FieldInfo, restarting: Boolean = false) {
        this.settings = settings
        this.field = field
        mode = settings.keyboardMode
        applyLanguage(settings.activeLanguage)
        if (!restarting) {
            page = field.startPage
            shift = ShiftState.OFF
            shiftAuto = false
            restoreStack.clear()
            previousKeyWasSpace = false
        }
        cancelEnterWait()
        interpreter.cancelAll()
        popup = null
        updateAutoShift()
        listener?.onLayoutChanged()
    }

    fun finishInput() {
        cancelEnterWait()
        interpreter.cancelAll()
        popup = null
        restoreStack.clear()
    }

    /** Updates gesture thresholds for the display density and long-press setting. */
    fun updateGestureConfig(density: Float) {
        interpreter.config = GestureConfig.forDensity(density, (settings?.longPressMs ?: 350).toLong())
    }

    private fun applyLanguage(id: String) {
        val s = settings
        language = shared.language(id) ?: shared.languages.firstOrNull() ?: language
        val wanted = s?.keyboardFor(language) ?: language.keyboard
        keyboardId = if (shared.keyboard(wanted) != null) wanted else language.keyboard
        keyboard = shared.keyboard(keyboardId) ?: shared.layouts.keyboards.values.firstOrNull()
    }

    // ------------------------------------------------------------------ layout

    /** Height of the keyboard (all pages and panels) for the given metrics. */
    fun totalHeight(metrics: PixelMetrics): Float =
        LayoutBuilder.totalHeight(metrics, mode, settings?.numberRow == true)

    /** Current geometry for [width] and [metrics]; cached until something changes. */
    fun layout(width: Float, metrics: PixelMetrics): KeyboardLayout? {
        val kb = keyboard ?: return null
        val numberRow = settings?.numberRow == true
        val cacheKey = listOf(width, metrics, keyboardId, mode, page, numberRow)
        if (cacheKey != layoutKey || layoutCache == null) {
            layoutCache = LayoutBuilder.build(
                LayoutRequest(width, metrics, shared.layouts, kb, mode, page, numberRow),
            )
            layoutKey = cacheKey
        }
        return layoutCache
    }

    override fun currentLayout(): KeyboardLayout? = layoutCache

    // ------------------------------------------------------------------ labels

    /** Label of a key as drawn (SPEC §3.6). */
    fun labelFor(key: Key, strings: (String) -> String): String = when (key.type) {
        KeyType.CHAR -> if (isShifted && page == Page.LETTERS) Unicode.upper(key.text) else key.text
        KeyType.SHIFT -> when (shift) {
            ShiftState.OFF -> "⇧"
            ShiftState.ONE_SHOT -> "⬆"
            ShiftState.CAPS_LOCK -> "⇪"
        }
        KeyType.BACK -> "⌫"
        KeyType.SPACE -> if (key.primarySpace) language.short else ""
        KeyType.ENTER -> field.enterAction.labelKey?.let(strings) ?: "⏎"
        KeyType.SYMBOLS -> strings("keys.symbols")
        KeyType.SYMBOLS2 -> strings("keys.symbols2")
        KeyType.SYMBOLS1 -> strings("keys.symbols")
        KeyType.LETTERS -> strings("keys.letters")
        KeyType.EMOJI -> "😊"
        KeyType.NEXT_KEYBOARD -> "🌐"
        KeyType.UNKNOWN -> ""
    }

    /** The tiny long-press digit hint of a key, or null (SPEC §3.6). */
    fun digitHintFor(key: Key): String? {
        val s = settings ?: return null
        if (!s.longPressDigits || page != Page.LETTERS || key.type != KeyType.CHAR) return null
        if (key.area != KeyArea.LETTERS || key.row != 0 || key.column >= 10) return null
        return ((key.column + 1) % 10).toString()
    }

    // ------------------------------------------------------------------ gesture callbacks

    override fun alternativesFor(key: Key): List<String> {
        if (key.type != KeyType.CHAR) return emptyList()
        val s = settings ?: return emptyList()
        val items = ArrayList<String>()
        digitHintFor(key)?.let { items += it }
        if (s.longPressAccents) items += shared.accents[key.code.lowercase()].orEmpty()
        val upper = isShifted && page == Page.LETTERS
        return if (upper) items.map { Unicode.upper(it) }.distinct() else items.distinct()
    }

    override fun onKeyDown(key: Key) {
        listener?.onKeyFeedback(key)
    }

    override fun onTap(key: Key, shiftHeld: Boolean) {
        when (key.type) {
            KeyType.CHAR -> typeCharacter(key, forceUpper = shiftHeld)
            KeyType.SPACE -> typeSpace()
            KeyType.ENTER -> onEnter()
            KeyType.SHIFT -> tapShift()
            KeyType.BACK -> Unit // handled on down
            KeyType.SYMBOLS, KeyType.SYMBOLS1 -> setPage(Page.SYMBOLS1)
            KeyType.SYMBOLS2 -> setPage(Page.SYMBOLS2)
            KeyType.LETTERS -> setPage(Page.LETTERS)
            KeyType.EMOJI -> listener?.onEmojiPanelRequested()
            KeyType.NEXT_KEYBOARD, KeyType.UNKNOWN -> Unit
        }
        if (key.type != KeyType.SPACE) previousKeyWasSpace = false
        listener?.onKeyboardChanged()
    }

    override fun onSwipeUp(key: Key) {
        // Upper-case letter without touching the shift state (SPEC §4.1).
        commitTyped(Unicode.upper(key.text), consumesShift = false)
        previousKeyWasSpace = false
        listener?.onKeyboardChanged()
    }

    override fun onBackspace(): Int {
        clearRestoreStack()
        previousKeyWasSpace = false
        val removed = if (host.selectedText().isNotEmpty()) {
            host.deleteSelection()
            1
        } else {
            val before = host.textBeforeCursor(32)
            if (before.isEmpty()) {
                host.sendBackspaceKey()
                0
            } else {
                val n = Unicode.lastGraphemeLength(before)
                host.delete(before.substring(before.length - n))
                1
            }
        }
        afterNonAppendChange()
        return removed
    }

    override fun onBackspaceWord() {
        clearRestoreStack()
        val before = host.textBeforeCursor(WORD_CONTEXT)
        val word = TextAlgorithms.wordDelete(before)
        if (word.isEmpty()) host.sendBackspaceKey() else host.delete(word)
        afterNonAppendChange()
    }

    override fun onSwipeDeleteWord() {
        previousKeyWasSpace = false
        val selected = host.selectedText()
        if (selected.isNotEmpty()) {
            host.deleteSelection()
            pushRestore(selected)
            afterNonAppendChange()
            return
        }
        val before = host.textBeforeCursor(WORD_CONTEXT)
        val word = TextAlgorithms.wordDelete(before)
        if (word.isEmpty()) {
            listener?.onNoOpFeedback()
            return
        }
        host.delete(word)
        pushRestore(word)
        afterNonAppendChange()
    }

    private fun pushRestore(text: String) {
        val context = host.textBeforeCursor(RESTORE_CONTEXT)
        restoreStack.addLast(RestoreEntry(text, context))
        while (restoreStack.size > MAX_RESTORE) restoreStack.removeFirst()
    }

    override fun onSwipeRestore() {
        previousKeyWasSpace = false
        while (restoreStack.isNotEmpty()) {
            val entry = restoreStack.removeLast()
            val before = host.textBeforeCursor(entry.contextBefore.length.coerceAtLeast(1))
            if (before.endsWith(entry.contextBefore)) {
                host.insert(entry.text)
                engine.onNonAppendChange()
                updateAutoShift()
                listener?.onKeyboardChanged()
                return
            }
            restoreStack.clear() // the text changed under us: the stack is stale
        }
        if (engine.undo()) {
            updateAutoShift()
            listener?.onKeyboardChanged()
            return
        }
        listener?.onNoOpFeedback()
    }

    override fun onCursorMove(delta: Int) {
        previousKeyWasSpace = false
        clearRestoreStack()
        host.moveCursor(delta)
        afterNonAppendChange()
    }

    override fun onShiftLongPress() {
        shift = ShiftState.CAPS_LOCK
        shiftAuto = false
        lastShiftTapAt = Long.MIN_VALUE
        listener?.onPopupFeedback()
        listener?.onKeyboardChanged()
    }

    override fun onPopupShown(popup: Popup) {
        this.popup = popup
        listener?.onPopupFeedback()
        listener?.onKeyboardChanged()
    }

    override fun onPopupChanged(popup: Popup) {
        this.popup = popup
        listener?.onKeyboardChanged()
    }

    override fun onPopupDismissed() {
        popup = null
        listener?.onKeyboardChanged()
    }

    override fun onAlternativeSelected(key: Key, text: String) {
        commitTyped(text, consumesShift = true)
        previousKeyWasSpace = false
    }

    override fun onPressedKeysChanged() {
        listener?.onKeyboardChanged()
    }

    // ------------------------------------------------------------------ typing

    private fun typeCharacter(key: Key, forceUpper: Boolean) {
        val upper = forceUpper || (isShifted && page == Page.LETTERS)
        commitTyped(if (upper) Unicode.upper(key.text) else key.text, consumesShift = page == Page.LETTERS)
    }

    /** Inserts typed text, tells the engine and updates shift (SPEC §4.2). */
    private fun commitTyped(text: String, consumesShift: Boolean) {
        if (text.isEmpty()) return
        clearRestoreStack()
        host.insert(text)
        engine.onTextAppended(text)
        if (consumesShift && shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            shiftAuto = false
        }
        updateAutoShift()
        listener?.onKeyboardChanged()
    }

    private fun typeSpace() {
        val s = settings
        val now = clock.now()
        val doubleSpace = s != null && s.doubleSpacePeriod && previousKeyWasSpace &&
            now - lastSpaceAt < DOUBLE_SPACE_MS && field.kind == FieldKind.TEXT && !language.pinyin
        if (doubleSpace && TextAlgorithms.doubleSpacePeriod(host.textBeforeCursor(8))) {
            clearRestoreStack()
            host.batch {
                host.delete(" ")
                host.insert(". ")
            }
            engine.onNonAppendChange()
            engine.onTextAppended(". ")
            previousKeyWasSpace = false
            updateAutoShift()
        } else {
            commitTyped(" ", consumesShift = false)
            previousKeyWasSpace = true
        }
        lastSpaceAt = now
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    private fun tapShift() {
        val now = clock.now()
        val doubleTap = lastShiftTapAt != Long.MIN_VALUE && now - lastShiftTapAt <= DOUBLE_TAP_MS
        when {
            shift == ShiftState.CAPS_LOCK -> {
                shift = ShiftState.OFF
                lastShiftTapAt = Long.MIN_VALUE
            }
            doubleTap -> {
                shift = ShiftState.CAPS_LOCK
                lastShiftTapAt = Long.MIN_VALUE
            }
            shift == ShiftState.ONE_SHOT -> {
                shift = ShiftState.OFF
                lastShiftTapAt = now
            }
            else -> {
                shift = ShiftState.ONE_SHOT
                lastShiftTapAt = now
            }
        }
        shiftAuto = false
    }

    private fun setPage(newPage: Page) {
        if (page == newPage) return
        page = newPage
        interpreter.cancelAll()
        listener?.onLayoutChanged()
    }

    /** Enter key (SPEC §4.3): flush first if a correction is pending, then act. */
    fun onEnter() {
        if (enterJob?.isActive == true) {
            // Second tap during the wait: continue at once.
            cancelEnterWait()
            finishEnter()
            return
        }
        val wait = settings?.waitOnSendMs ?: 0
        if (wait > 0 && engine.hasPending()) {
            enterJob = scope.launch {
                engine.flush()
                enterJob = null
                finishEnter()
                listener?.onKeyboardChanged()
            }
            listener?.onKeyboardChanged()
        } else {
            finishEnter()
        }
    }

    private fun cancelEnterWait() {
        enterJob?.cancel()
        enterJob = null
    }

    private fun finishEnter() {
        clearRestoreStack()
        previousKeyWasSpace = false
        if (field.enterAction.isEditorAction) {
            host.performEditorAction(field.enterAction)
            engine.onNonAppendChange()
        } else if (host.insertNewline()) {
            engine.onTextAppended("\n")
        } else {
            engine.onNonAppendChange()
        }
        if (page != Page.LETTERS) setPage(Page.LETTERS)
        updateAutoShift()
        listener?.onKeyboardChanged()
    }

    // ------------------------------------------------------------------ toolbar / panels

    fun toggleKeyboardMode() {
        mode = mode.toggled()
        listener?.persistKeyboardMode(mode)
        interpreter.cancelAll()
        listener?.onLayoutChanged()
    }

    /** Language chip tap: next enabled language (SPEC §5.9). */
    fun cycleLanguage() {
        val langs = settings?.languages.orEmpty()
        if (langs.size < 2) return
        val next = langs[(langs.indexOf(language.id) + 1).mod(langs.size)]
        selectLanguage(next)
    }

    fun selectLanguage(id: String) {
        val s = settings ?: return
        if (shared.language(id) == null) return
        settings = s.copy(activeLanguage = id)
        applyLanguage(id)
        listener?.persistActiveLanguage(id)
        interpreter.cancelAll()
        updateAutoShift()
        listener?.onLayoutChanged()
    }

    /** Emoji panel tap (SPEC §4.9). */
    fun insertEmoji(emoji: String) {
        commitTyped(emoji, consumesShift = false)
        previousKeyWasSpace = false
        val s = settings ?: return
        val recents = (listOf(emoji) + s.emojiRecents.filter { it != emoji }).take(MAX_EMOJI_RECENTS)
        settings = s.copy(emojiRecents = recents)
        listener?.persistEmojiRecents(recents)
    }

    /** Emoji panel ⌫. */
    fun emojiBackspace() {
        onBackspace()
        listener?.onKeyboardChanged()
    }

    /**
     * The selection or text changed outside the keyboard (user moved the cursor,
     * the app changed the text). SPEC §4.6, §5.1.
     */
    fun onExternalChange() {
        clearRestoreStack()
        previousKeyWasSpace = false
        engine.onNonAppendChange()
        updateAutoShift()
        listener?.onKeyboardChanged()
    }

    private fun afterNonAppendChange() {
        engine.onNonAppendChange()
        updateAutoShift()
        listener?.onKeyboardChanged()
    }

    private fun clearRestoreStack() {
        restoreStack.clear()
    }

    /** Auto-capitalisation (SPEC §4.2). */
    fun updateAutoShift() {
        val s = settings
        if (s == null || !s.autoCapitalize || language.pinyin || !field.autoCapAllowed) {
            if (shift == ShiftState.ONE_SHOT && shiftAuto) {
                shift = ShiftState.OFF
                shiftAuto = false
            }
            return
        }
        if (shift == ShiftState.CAPS_LOCK) return
        val capitalize = TextAlgorithms.autoCapitalize(host.textBeforeCursor(AUTO_CAP_CONTEXT))
        if (capitalize) {
            if (shift == ShiftState.OFF) {
                shift = ShiftState.ONE_SHOT
                shiftAuto = true
            }
        } else if (shift == ShiftState.ONE_SHOT && shiftAuto) {
            shift = ShiftState.OFF
            shiftAuto = false
        }
    }

    companion object {
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_SPACE_MS = 400L
        const val MAX_RESTORE = 20
        const val MAX_EMOJI_RECENTS = 32
        const val WORD_CONTEXT = 200
        const val RESTORE_CONTEXT = 48
        const val AUTO_CAP_CONTEXT = 64
    }
}
