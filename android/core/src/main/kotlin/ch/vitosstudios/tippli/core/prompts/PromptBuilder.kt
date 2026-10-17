package ch.vitosstudios.tippli.core.prompts

import ch.vitosstudios.tippli.core.data.KeyboardDefinition
import ch.vitosstudios.tippli.core.data.Language
import ch.vitosstudios.tippli.core.data.LayoutsFile
import ch.vitosstudios.tippli.core.data.PromptTemplates
import ch.vitosstudios.tippli.core.data.TranslateTarget

/** A system + user prompt pair. */
data class Prompt(val system: String, val user: String)

/** One `{raw, corrected}` pair of the correction history (SPEC §5.8). */
data class HistoryEntry(val raw: String, val corrected: String)

/** Which tips sections are enabled (settings `tips*`, `rewriteVariants`). */
data class TipsOptions(
    val rewrites: Boolean = true,
    val explanations: Boolean = true,
    val writing: Boolean = true,
    val emotion: Boolean = true,
    val rewriteVariants: List<String> = emptyList(),
)

/** Builds the prompts of SPEC §6.4 from the shared templates. */
class PromptBuilder(private val templates: PromptTemplates, private val layouts: LayoutsFile) {

    private val parts get() = templates.parts

    fun correction(language: Language, strength: String, keyboardId: String, context: String, segment: String): Prompt =
        Prompt(
            system = fill(
                templates.correctionSystem,
                mapOf(
                    "language" to language.promptName,
                    "languageRules" to language.rules,
                    "strengthRule" to (parts.strengthRules[strength] ?: parts.strengthRules["normal"].orEmpty()),
                    "keyboardLayout" to keyboardLayout(keyboardId),
                ),
            ),
            user = fill(templates.correctionUser, mapOf("context" to context, "segment" to segment)),
        )

    /** Connection test (SPEC §6.4): correction prompts with empty context and the test segment. */
    fun connectionTest(language: Language, strength: String, keyboardId: String): Prompt =
        correction(language, strength, keyboardId, context = "", segment = parts.testSegment)

    val testSegment: String get() = parts.testSegment

    /**
     * `{{keyboardLayout}}`: the keyboard's name plus its three letter rows (normal variant,
     * plain character keys only) joined with " / ",
     * e.g. `QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm`.
     */
    fun keyboardLayout(keyboardId: String): String {
        val keyboard: KeyboardDefinition = layouts.keyboards[keyboardId] ?: return keyboardId
        val rows = keyboard.normal.rows.joinToString(" / ") { row ->
            row.keys.filterNot { it.isSpecial }.joinToString("") { it.key }
        }
        return "${keyboard.name}: $rows"
    }

    fun tips(language: Language, options: TipsOptions, text: String, history: List<HistoryEntry>): Prompt {
        val variants = options.rewriteVariants.mapNotNull { id -> parts.rewriteVariants.firstOrNull { it.id == id } }
        val rewritesOn = options.rewrites && variants.isNotEmpty()
        val disabled = buildList {
            if (!rewritesOn) add(parts.sections["rewrites"] ?: "rewrites")
            if (!options.explanations) add(parts.sections["explanations"] ?: "explanations")
            if (!options.writing) add(parts.sections["writing"] ?: "tips")
            if (!options.emotion) add(parts.sections["emotion"] ?: "emotion")
        }
        val variantText = if (rewritesOn) {
            variants.joinToString(", ") { "${it.id} (\"${it.label}\": ${it.instruction})" }
        } else {
            "none"
        }
        val corrections = history.takeLast(MAX_HISTORY).joinToString("\n") { "${it.raw} -> ${it.corrected}" }
            .ifEmpty { "(none)" }
        return Prompt(
            system = fill(
                templates.tipsSystem,
                mapOf(
                    "uiLanguage" to parts.uiLanguage,
                    "language" to language.promptName,
                    "variants" to variantText,
                    "disabledSections" to (if (disabled.isEmpty()) "none" else disabled.joinToString(", ")),
                ),
            ),
            user = fill(templates.tipsUser, mapOf("text" to text, "corrections" to corrections)),
        )
    }

    fun mood(paragraph: String): Prompt =
        Prompt(system = fill(templates.moodSystem, mapOf("uiLanguage" to parts.uiLanguage)), user = paragraph)

    fun translate(target: TranslateTarget, text: String): Prompt =
        Prompt(system = fill(templates.translateSystem, mapOf("targetLanguage" to target.promptName)), user = text)

    companion object {
        const val MAX_HISTORY = 20
        private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

        /**
         * Replaces `{{name}}` placeholders literally in one pass, so text inserted for one
         * placeholder is never scanned for further placeholders. Unknown placeholders stay.
         */
        fun fill(template: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }
    }
}
