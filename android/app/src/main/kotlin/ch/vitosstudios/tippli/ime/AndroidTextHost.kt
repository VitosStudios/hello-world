package ch.vitosstudios.tippli.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import ch.vitosstudios.tippli.core.keyboard.EditorHost
import ch.vitosstudios.tippli.core.keyboard.EnterAction
import ch.vitosstudios.tippli.core.keyboard.FieldInfo
import ch.vitosstudios.tippli.core.keyboard.FieldKind
import ch.vitosstudios.tippli.core.text.Unicode
import kotlin.math.max
import kotlin.math.min

/** The text a tips request works on (SPEC §7.2). */
data class TextScope(val text: String, val isSelection: Boolean, val beforeLength: Int, val afterLength: Int)

/**
 * [EditorHost] on top of the current [InputConnection] (SPEC §9.1).
 *
 * Every edit made through this class records the selection it will produce.
 * `onUpdateSelection` reports matching one of these expectations are our own
 * (keyboard or engine) changes; anything else is an external change – the user
 * moved the cursor or the app changed the text – and is reported through
 * [onExternalChange].
 */
class AndroidTextHost(private val service: InputMethodService) : EditorHost {

    var onExternalChange: (() -> Unit)? = null

    private class Expectation(val start: Int, val end: Int, val at: Long)

    private var info: EditorInfo? = null
    private var fieldInfo = FieldInfo()
    private var selStart = -1
    private var selEnd = -1
    private val expected = ArrayDeque<Expectation>()
    private var batchDepth = 0
    private val batchStates = ArrayList<Pair<Int, Int>>()
    private var preBatchStart = -1
    private var preBatchEnd = -1

    private val ic: InputConnection? get() = service.currentInputConnection

    fun startInput(info: EditorInfo?, field: FieldInfo) {
        this.info = info
        fieldInfo = field
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        expected.clear()
        batchStates.clear()
        batchDepth = 0
    }

    /** Called from `InputMethodService.onUpdateSelection`. */
    fun onUpdateSelection(newStart: Int, newEnd: Int) {
        val now = SystemClock.uptimeMillis()
        while (expected.isNotEmpty() && now - expected.first().at > EXPECTATION_TTL_MS) expected.removeFirst()
        val index = expected.indexOfFirst { it.start == newStart && it.end == newEnd }
        if (index >= 0) {
            repeat(index + 1) { expected.removeFirst() }
            selStart = newStart
            selEnd = newEnd
            return
        }
        if (newStart == selStart && newEnd == selEnd) return
        expected.clear()
        selStart = newStart
        selEnd = newEnd
        onExternalChange?.invoke()
    }

    val hasSelection: Boolean get() = selStart >= 0 && selStart != selEnd

    /** Records the selection our last operation produces. */
    private fun record(start: Int, end: Int) {
        if (start < 0 || end < 0) return
        val oldStart = selStart
        val oldEnd = selEnd
        selStart = start
        selEnd = end
        if (batchDepth > 0) {
            batchStates += start to end
            return
        }
        if (start == oldStart && end == oldEnd) return // the editor will not report an unchanged selection
        pushExpectation(start, end)
    }

    private fun pushExpectation(start: Int, end: Int) {
        expected.addLast(Expectation(start, end, SystemClock.uptimeMillis()))
        while (expected.size > MAX_EXPECTATIONS) expected.removeFirst()
    }

    // ------------------------------------------------------------------ TextHost

    override fun textBeforeCursor(maxChars: Int): String =
        ic?.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()

    override fun textAfterCursor(maxChars: Int): String =
        if (maxChars <= 0) "" else ic?.getTextAfterCursor(maxChars, 0)?.toString().orEmpty()

    override fun selectedText(): String {
        // Avoid an IPC round trip when we know that nothing is selected.
        if (selStart >= 0 && selStart == selEnd) return ""
        return ic?.getSelectedText(0)?.toString().orEmpty()
    }

    override fun delete(text: String) {
        if (text.isEmpty()) return
        val connection = ic ?: return
        connection.deleteSurroundingTextInCodePoints(text.codePointCount(0, text.length), 0)
        if (selStart >= 0) record(min(selStart, selEnd) - text.length, max(selStart, selEnd) - text.length)
    }

    override fun insert(text: String) {
        val connection = ic ?: return
        connection.commitText(text, 1)
        if (selStart >= 0) {
            val position = min(selStart, selEnd) + text.length
            record(position, position)
        }
    }

    override fun batch(block: () -> Unit) {
        val connection = ic
        if (connection == null) {
            block()
            return
        }
        if (batchDepth == 0) {
            preBatchStart = selStart
            preBatchEnd = selEnd
            batchStates.clear()
        }
        batchDepth++
        connection.beginBatchEdit()
        try {
            block()
        } finally {
            batchDepth--
            connection.endBatchEdit()
            if (batchDepth == 0) {
                // The editor reports the final state (and maybe intermediate ones).
                batchStates.filter { it.first != preBatchStart || it.second != preBatchEnd }
                    .forEach { pushExpectation(it.first, it.second) }
                batchStates.clear()
            }
        }
    }

    // ------------------------------------------------------------------ EditorHost

    override fun deleteSelection() {
        val connection = ic ?: return
        connection.commitText("", 1)
        if (selStart >= 0) {
            val position = min(selStart, selEnd)
            record(position, position)
        }
    }

    override fun sendBackspaceKey() {
        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun moveCursor(delta: Int) {
        if (delta == 0) return
        val connection = ic ?: return
        if (selStart < 0) {
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(delta)) { service.sendDownUpKeyEvents(code) }
            return
        }
        val target = if (delta < 0) {
            val base = min(selStart, selEnd)
            val before = textBeforeCursor(-delta * 8 + 8)
            var i = before.length
            repeat(-delta) { if (i > 0) i -= Unicode.lastGraphemeLength(before.substring(0, i)) }
            base - (before.length - i)
        } else {
            val base = max(selStart, selEnd)
            val after = textAfterCursor(delta * 8 + 8)
            var i = 0
            repeat(delta) { if (i < after.length) i += Unicode.firstGraphemeLength(after.substring(i)) }
            base + i
        }
        if (target < 0) return
        connection.setSelection(target, target)
        record(target, target)
    }

    override fun performEditorAction(action: EnterAction) {
        val connection = ic ?: return
        val raw = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        connection.performEditorAction(if (raw != EditorInfo.IME_ACTION_UNSPECIFIED) raw else FieldClassifier.actionId(action))
    }

    override fun insertNewline(): Boolean {
        // Multi-line text fields get a real "\n"; everything else gets an Enter key
        // event so single-line fields and terminals can react to it.
        if (fieldInfo.kind != FieldKind.RAW && FieldClassifier.isMultiLine(info)) {
            insert("\n")
            return true
        }
        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        return false
    }

    // ------------------------------------------------------------------ tips panel

    /** Selected text, or the whole field as far as it can be read (max [maxChars]). */
    fun readScope(maxChars: Int = MAX_SCOPE): TextScope {
        val selected = selectedText()
        if (selected.isNotEmpty()) return TextScope(selected, isSelection = true, beforeLength = 0, afterLength = 0)
        val before = textBeforeCursor(maxChars)
        val after = textAfterCursor(maxChars - before.length)
        return TextScope(before + after, isSelection = false, beforeLength = before.length, afterLength = after.length)
    }

    /** Replaces the scope text (SPEC §7.2 "Ersetzen"). */
    fun replaceScope(scope: TextScope, replacement: String) {
        val connection = ic ?: return
        batch {
            if (scope.isSelection) {
                insert(replacement)
            } else {
                if (hasSelection) deleteSelection()
                val beforeLength = min(scope.beforeLength, textBeforeCursor(scope.beforeLength).length)
                val afterLength = min(scope.afterLength, textAfterCursor(scope.afterLength).length)
                connection.deleteSurroundingText(beforeLength, afterLength)
                if (selStart >= 0) {
                    val position = min(selStart, selEnd) - beforeLength
                    record(position, position)
                }
                insert(replacement)
            }
        }
    }

    /** Inserts [text] after the scope ("Einfügen" of a translation). */
    fun insertAfterScope(scope: TextScope, text: String) {
        val connection = ic ?: return
        batch {
            if (selStart >= 0) {
                val end = if (scope.isSelection) max(selStart, selEnd) else max(selStart, selEnd) + scope.afterLength
                connection.setSelection(end, end)
                record(end, end)
            }
            val before = textBeforeCursor(1)
            val separator = when {
                before.isEmpty() -> ""
                scope.isSelection -> if (before.last().isWhitespace()) "" else " "
                else -> if (before.last() == '\n') "" else "\n"
            }
            insert(separator + text)
        }
    }

    companion object {
        const val MAX_SCOPE = 4000
        private const val MAX_EXPECTATIONS = 64
        private const val EXPECTATION_TTL_MS = 2_000L
    }
}
