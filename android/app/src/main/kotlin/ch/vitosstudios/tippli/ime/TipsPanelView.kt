package ch.vitosstudios.tippli.ime

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.data.TranslateTarget
import ch.vitosstudios.tippli.core.tips.TipsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tips panel (SPEC §7.2): replaces the key area, scrollable, with rewrites,
 * explanations, writing tips, the emotion check and translation. Built from
 * plain Views (no Compose inside the IME).
 */
@SuppressLint("ViewConstructor")
class TipsPanelView(context: Context, private val ime: TippliImeService) : LinearLayout(context) {

    private val strings get() = ime.strings
    private val palette get() = ime.keyboardTheme.palette
    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private val header = LinearLayout(context)
    private val title = TextView(context)
    private val closeButton = TextView(context)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)

    private var job: Job? = null
    private var translateJob: Job? = null
    private var scope: TextScope? = null
    private var scrollToEmotion = false
    private var translationContainer: LinearLayout? = null

    init {
        orientation = VERTICAL
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16f), dp(4f), dp(4f), dp(4f))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        header.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        closeButton.text = "✕"
        closeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        closeButton.gravity = Gravity.CENTER
        closeButton.contentDescription = strings["tips.close"]
        closeButton.setOnClickListener { ime.closePanels() }
        header.addView(closeButton, LayoutParams(dp(44f), dp(40f)))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.orientation = VERTICAL
        content.setPadding(dp(12f), 0, dp(12f), dp(16f))
        scroll.isFillViewport = true
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Opens the panel for the current selection or the whole field (SPEC §7.2). */
    fun open(scrollToEmotion: Boolean) {
        this.scrollToEmotion = scrollToEmotion
        job?.cancel()
        translateJob?.cancel()
        applyColors()
        val current = ime.host.readScope()
        scope = current
        title.text = strings["tips.title"] + " · " +
            strings[if (current.isSelection) "tips.scopeSelection" else "tips.scopeAll"]
        when {
            current.text.isBlank() -> showInfo(strings["tips.emptyText"])
            !ime.aiAllowedInField -> showInfo(strings["toolbar.aiLocked"])
            else -> load()
        }
    }

    fun close() {
        job?.cancel()
        translateJob?.cancel()
        job = null
        translateJob = null
        content.removeAllViews()
    }

    private fun applyColors() {
        setBackgroundColor(palette.panel)
        title.setTextColor(palette.keyText)
        closeButton.setTextColor(palette.keyText)
        closeButton.background = ripple(null, 22f)
    }

    // ------------------------------------------------------------------ states

    private fun load() {
        val currentScope = scope ?: return
        val client = ime.tipsClient()
        if (client == null) {
            showError(AiException(AiErrorKind.NO_KEY)) { load() }
            return
        }
        showLoading()
        job = ime.serviceScope.launch {
            try {
                val result = ime.app.tips.tips(
                    client, ime.controller.language, ime.tipsOptions(),
                    currentScope.text.take(AndroidTextHost.MAX_SCOPE), ime.engine.history(),
                )
                showResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                showError(e) { load() }
            } catch (e: Exception) {
                showError(AiException(AiErrorKind.BAD_RESPONSE, e.message, e)) { load() }
            }
        }
    }

    private fun showLoading() {
        content.removeAllViews()
        content.addView(loadingRow(strings["tips.loading"]))
    }

    private fun showInfo(text: String) {
        content.removeAllViews()
        content.addView(infoText(text).apply { setPadding(dp(4f), dp(24f), dp(4f), dp(24f)); gravity = Gravity.CENTER })
    }

    private fun showError(error: AiException, retry: () -> Unit) {
        content.removeAllViews()
        content.addView(errorCard(error, retry))
    }

    private fun showResult(result: TipsResult) {
        content.removeAllViews()
        val settings = ime.settings
        var emotionAnchor: View? = null

        if (settings.tipsRewrites && result.rewrites.isNotEmpty()) {
            content.addView(sectionTitle(strings["tips.rewrites"]))
            result.rewrites.forEach { content.addView(textCard(it.label.ifBlank { it.id }, it.text, allowInsert = false)) }
        }
        if (settings.tipsExplanations) {
            content.addView(sectionTitle(strings["tips.explanations"]))
            if (result.explanations.isEmpty()) {
                content.addView(infoText(strings["tips.noCorrections"]))
            } else {
                val card = card()
                result.explanations.forEach { e ->
                    val line = buildString {
                        append(e.from).append(" → ").append(e.to)
                        if (e.why.isNotBlank()) append(": ").append(e.why)
                    }
                    card.addView(bodyText(line).apply { setPadding(0, dp(3f), 0, dp(3f)) })
                }
                content.addView(card)
            }
        }
        if (settings.tipsWriting) {
            content.addView(sectionTitle(strings["tips.writing"]))
            if (result.tips.isEmpty()) {
                content.addView(infoText(strings["tips.noTips"]))
            } else {
                val card = card()
                result.tips.forEach { tip -> card.addView(bodyText("•  $tip").apply { setPadding(0, dp(3f), 0, dp(3f)) }) }
                content.addView(card)
            }
        }
        if (settings.tipsEmotion) {
            val anchor = sectionTitle(strings["tips.emotion"])
            emotionAnchor = anchor
            content.addView(anchor)
            val emotion = result.emotion
            val levels = strings.list("tips.emotionLevels")
            val card = card()
            val levelText = levels.getOrNull(emotion.level) ?: emotion.label
            card.addView(TextView(context).apply {
                text = levelText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when (emotion.level) {
                        0 -> palette.success
                        1 -> palette.keyText
                        2 -> palette.warning
                        else -> palette.danger
                    },
                )
            })
            if (emotion.reason.isNotBlank()) card.addView(bodyText(emotion.reason).apply { setPadding(0, dp(4f), 0, 0) })
            content.addView(card)
            if (emotion.level > 0 && emotion.calmer.isNotBlank()) {
                content.addView(textCard(strings["tips.calmer"], emotion.calmer, allowInsert = false))
            }
            ime.engine.setMood(emotion.level, emotion.reason)
        }
        if (settings.tipsTranslate) {
            content.addView(sectionTitle(strings["tips.translate"]))
            val targets = settings.translateTargets.mapNotNull { id -> ime.app.shared.translateTargets.firstOrNull { it.id == id } }
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL }
            targets.forEach { target -> chips.addView(chip(target.name) { translate(target) }) }
            content.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            val container = LinearLayout(context).apply { orientation = VERTICAL }
            translationContainer = container
            content.addView(container)
        }
        if (content.childCount == 0) content.addView(infoText(strings["tips.noTips"]))
        if (scrollToEmotion) {
            emotionAnchor?.let { anchor -> scroll.post { scroll.smoothScrollTo(0, anchor.top) } }
        }
    }

    private fun translate(target: TranslateTarget) {
        val currentScope = scope ?: return
        val container = translationContainer ?: return
        val client = ime.tipsClient() ?: return
        translateJob?.cancel()
        container.removeAllViews()
        container.addView(loadingRow(null))
        translateJob = ime.serviceScope.launch {
            try {
                val translation = ime.app.tips.translate(client, target, currentScope.text.take(AndroidTextHost.MAX_SCOPE))
                container.removeAllViews()
                container.addView(textCard(target.name, translation, allowInsert = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                container.removeAllViews()
                container.addView(errorCard(e) { translate(target) })
            } catch (e: Exception) {
                container.removeAllViews()
                container.addView(errorCard(AiException(AiErrorKind.BAD_RESPONSE, e.message, e)) { translate(target) })
            }
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(color)
    }

    private fun ripple(color: Int?, radiusDp: Float): RippleDrawable {
        val mask = rounded(0xFFFFFFFF.toInt(), radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(KeyboardTheme.withAlpha(palette.keyText, 0x30)),
            color?.let { rounded(it, radiusDp) },
            mask,
        )
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        background = rounded(palette.card, 12f)
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        elevation = dp(1f).toFloat()
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
            bottomMargin = dp(2f)
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.secondaryText)
        setPadding(dp(4f), dp(14f), dp(4f), dp(2f))
    }

    private fun bodyText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(palette.keyText)
        setLineSpacing(0f, 1.15f)
    }

    private fun infoText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(palette.secondaryText)
        setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
    }

    private fun actionButton(label: String, filled: Boolean, onClick: (TextView) -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (filled) palette.accentText else palette.accent)
        background = ripple(if (filled) palette.accent else null, 18f)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        minHeight = dp(36f)
        setOnClickListener { onClick(this) }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6f)
        }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(palette.keyText)
        background = ripple(palette.specialKey, 16f)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(6f), dp(6f), dp(4f))
        }
    }

    private fun loadingRow(label: String?): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), dp(20f), dp(4f), dp(20f))
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.accent)
        }, LayoutParams(dp(22f), dp(22f)))
        if (label != null) {
            addView(infoText(label).apply { setPadding(dp(12f), 0, 0, 0) })
        }
    }

    /** A card with a label, a text and Ersetzen / (Einfügen) / Kopieren actions. */
    private fun textCard(label: String, text: String, allowInsert: Boolean): View {
        val card = card()
        card.addView(TextView(context).apply {
            this.text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accent)
        })
        card.addView(bodyText(text).apply { setPadding(0, dp(4f), 0, dp(8f)) })
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(actionButton(strings["tips.replace"], filled = true) { replace(text) })
        if (allowInsert) actions.addView(actionButton(strings["tips.insert"], filled = false) { insert(text) })
        actions.addView(actionButton(strings["tips.copy"], filled = false) { button -> copy(text, button) })
        card.addView(actions)
        card.isClickable = true
        card.foreground = ripple(null, 12f)
        card.setOnClickListener { replace(text) }
        return card
    }

    private fun errorCard(error: AiException, retry: () -> Unit): View {
        val card = card()
        card.addView(bodyText(strings[error.kind.stringPath]))
        error.detail?.takeIf { it.isNotBlank() && error.kind != AiErrorKind.NO_KEY }?.let {
            card.addView(infoText(it).apply { setPadding(0, dp(4f), 0, 0); maxLines = 3 })
        }
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        if (error.kind != AiErrorKind.NO_KEY) actions.addView(actionButton(strings["tips.retry"], filled = true) { retry() })
        if (error.kind == AiErrorKind.NO_KEY || error.kind == AiErrorKind.INVALID_KEY || error.kind == AiErrorKind.BAD_MODEL) {
            actions.addView(actionButton("⚙︎ " + strings["toolbar.settings"], filled = false) { ime.openSettings() })
        }
        card.addView(actions)
        return card
    }

    // ------------------------------------------------------------------ actions

    private fun replace(text: String) {
        val currentScope = scope ?: return
        ime.replaceScope(currentScope, text)
    }

    private fun insert(text: String) {
        val currentScope = scope ?: return
        ime.insertAfterScope(currentScope, text)
    }

    private fun copy(text: String, button: TextView) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Tippli", text))
        val original = button.text
        button.text = strings["tips.copied"]
        button.postDelayed({ button.text = original }, 1500L)
    }
}
