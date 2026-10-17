package ch.vitosstudios.tippli.core.engine

/**
 * The engine's view of the text field (SPEC §5). All calls happen on the UI
 * thread. Implementations must treat edits made through this interface as
 * engine/keyboard-made changes (they must not be reported back as external
 * cursor moves).
 */
interface TextHost {
    /** Up to [maxChars] characters before the caret (may be shorter). */
    fun textBeforeCursor(maxChars: Int): String

    fun textAfterCursor(maxChars: Int): String

    /** The selected text, or "" when the selection is empty. */
    fun selectedText(): String

    /** Deletes exactly [text], which is right before the caret. */
    fun delete(text: String)

    /** Inserts [text] at the caret (replacing a selection, if any). */
    fun insert(text: String)

    /** Groups several edits so the editor applies them at once (no flicker). */
    fun batch(block: () -> Unit)
}
