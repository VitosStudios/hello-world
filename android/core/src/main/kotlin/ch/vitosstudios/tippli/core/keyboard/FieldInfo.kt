package ch.vitosstudios.tippli.core.keyboard

import ch.vitosstudios.tippli.core.geometry.Page

/** Field classes of SPEC §4.8. */
enum class FieldKind {
    /** Normal text: AI and auto-cap per settings. */
    TEXT,
    /** Password, PIN, one-time code: never AI, no auto-cap. */
    SECRET,
    /** Number, phone, date: never AI, no auto-cap, symbols page. */
    NUMBER,
    /** E-mail, URL, username: never AI, no auto-cap. */
    ADDRESS,
    /** Editor without text access (raw key events): never AI, no auto-cap. */
    RAW,
}

/** What the enter key does (SPEC §4.3). */
enum class EnterAction(val labelKey: String?) {
    NEWLINE(null), SEND("keys.send"), SEARCH("keys.search"), GO("keys.go"),
    NEXT("keys.next"), DONE("keys.done"), PREVIOUS(null);

    val isEditorAction: Boolean get() = this != NEWLINE
}

data class FieldInfo(
    val kind: FieldKind = FieldKind.TEXT,
    /** Android `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito keyboards). */
    val incognito: Boolean = false,
    val enterAction: EnterAction = EnterAction.NEWLINE,
    /** Numeric input (e.g. a PIN): starts on the symbols page even when it is a secret. */
    val numeric: Boolean = false,
) {
    fun aiAllowed(incognitoNoAI: Boolean): Boolean =
        kind == FieldKind.TEXT && !(incognito && incognitoNoAI)

    val autoCapAllowed: Boolean get() = kind == FieldKind.TEXT

    val startPage: Page get() = if (kind == FieldKind.NUMBER || numeric) Page.SYMBOLS1 else Page.LETTERS
}
