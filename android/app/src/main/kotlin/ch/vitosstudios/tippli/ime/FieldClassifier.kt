package ch.vitosstudios.tippli.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import ch.vitosstudios.tippli.core.keyboard.EnterAction
import ch.vitosstudios.tippli.core.keyboard.FieldInfo
import ch.vitosstudios.tippli.core.keyboard.FieldKind

/** Classifies an editor per SPEC §4.8 and decides what the enter key does (SPEC §4.3). */
object FieldClassifier {

    fun classify(info: EditorInfo?): FieldInfo {
        if (info == null) return FieldInfo(kind = FieldKind.RAW)
        val type = info.inputType
        val inputClass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val kind = when (inputClass) {
            InputType.TYPE_NULL -> FieldKind.RAW
            InputType.TYPE_CLASS_NUMBER ->
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldKind.SECRET else FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.SECRET
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.ADDRESS
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return FieldInfo(
            kind = kind,
            incognito = incognito,
            enterAction = enterAction(info),
            numeric = inputClass == InputType.TYPE_CLASS_NUMBER,
        )
    }

    /** True when the field accepts several lines. */
    fun isMultiLine(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return type and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
    }

    private fun enterAction(info: EditorInfo): EnterAction {
        if (isMultiLine(info)) return EnterAction.NEWLINE
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            else -> EnterAction.NEWLINE
        }
    }

    /** The `EditorInfo.IME_ACTION_*` id for an [EnterAction]. */
    fun actionId(action: EnterAction): Int = when (action) {
        EnterAction.SEND -> EditorInfo.IME_ACTION_SEND
        EnterAction.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        EnterAction.GO -> EditorInfo.IME_ACTION_GO
        EnterAction.NEXT -> EditorInfo.IME_ACTION_NEXT
        EnterAction.DONE -> EditorInfo.IME_ACTION_DONE
        EnterAction.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
        EnterAction.NEWLINE -> EditorInfo.IME_ACTION_UNSPECIFIED
    }
}
