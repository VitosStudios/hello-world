package ch.vitosstudios.tippli.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import ch.vitosstudios.tippli.TippliApp
import ch.vitosstudios.tippli.core.ai.AiClient
import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.data.Strings
import ch.vitosstudios.tippli.core.engine.CorrectionEngine
import ch.vitosstudios.tippli.core.engine.EngineConfig
import ch.vitosstudios.tippli.core.engine.EngineListener
import ch.vitosstudios.tippli.core.geometry.Key
import ch.vitosstudios.tippli.core.geometry.KeyType
import ch.vitosstudios.tippli.core.keyboard.FieldInfo
import ch.vitosstudios.tippli.core.keyboard.KeyboardController
import ch.vitosstudios.tippli.core.keyboard.KeyboardControllerListener
import ch.vitosstudios.tippli.core.prompts.TipsOptions
import ch.vitosstudios.tippli.core.settings.KeyboardMode
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.settings.TippliSettings
import ch.vitosstudios.tippli.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt

/**
 * The Tippli keyboard (SPEC §9.1). Owns the platform-agnostic controller and
 * correction engine from `:core` and the Canvas-based views.
 */
class TippliImeService : InputMethodService(), KeyboardControllerListener, EngineListener {

    lateinit var app: TippliApp
        private set

    /** Main-thread scope tied to the service lifecycle. */
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = HandlerScheduler(handler)

    lateinit var host: AndroidTextHost
        private set
    lateinit var engine: CorrectionEngine
        private set
    lateinit var controller: KeyboardController
        private set
    lateinit var settings: TippliSettings
        private set
    lateinit var keyboardTheme: KeyboardTheme
        private set

    val strings: Strings get() = app.strings

    private var fieldInfo = FieldInfo()
    private var endpoint: AiEndpoint? = null
    private var client: AiClient? = null

    private var root: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var tipsPanel: TipsPanelView? = null

    val providerShortName: String get() = endpoint?.preset?.shortName.orEmpty()
    val aiAllowedInField: Boolean get() = fieldInfo.aiAllowed(settings.incognitoNoAI)

    override fun onCreate() {
        super.onCreate()
        app = application as TippliApp
        settings = app.readSettings()
        keyboardTheme = KeyboardTheme.resolve(this, app.shared, settings.theme)
        host = AndroidTextHost(this)
        engine = CorrectionEngine(host, app.prompts, serviceScope) { SystemClock.uptimeMillis() }
        engine.listener = this
        controller = KeyboardController(app.shared, host, engine, scheduler, scheduler, serviceScope)
        controller.listener = this
        host.onExternalChange = { controller.onExternalChange() }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val container = FrameLayout(this)
        val view = KeyboardView(this, this)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, navigationBarInset(insets))
            insets
        }
        root = container
        keyboardView = view
        emojiPanel = null
        tipsPanel = null
        applyTheme()
        return container
    }

    private fun navigationBarInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Settings changed in the app are picked up every time the keyboard is shown (SPEC §8).
        settings = app.readSettings()
        keyboardTheme = KeyboardTheme.resolve(this, app.shared, settings.theme)
        fieldInfo = FieldClassifier.classify(info)
        host.startInput(info, fieldInfo)
        configureEngine()
        engine.onFieldStart(fieldInfo.aiAllowed(settings.incognitoNoAI), resetSession = !restarting)
        controller.startInput(settings, fieldInfo, restarting)
        controller.updateGestureConfig(resources.displayMetrics.density)
        closePanels()
        applyTheme()
        keyboardView?.onKeyboardLayoutChanged()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.onFieldEnd()
        controller.finishInput()
        closePanels()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        controller.interpreter.cancelAll()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        host.onUpdateSelection(newSelStart, newSelEnd)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && (keyboardView?.keysHidden == true)) {
            closePanels()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ configuration

    private fun configureEngine() {
        val ep = app.endpointFor(settings)
        endpoint = ep
        val preset = ep?.preset
        val hasKey = preset != null && (!preset.keyRequired || ep.apiKey.isNotBlank())
        client = if (ep != null && hasKey) app.clientFor(ep) else null
        val language = app.shared.language(settings.activeLanguage) ?: controller.language
        engine.configure(
            EngineConfig(
                aiEnabled = settings.aiEnabled,
                trigger = settings.trigger,
                pauseMs = settings.pauseMs.toLong(),
                strength = settings.strength,
                waitOnSendMs = settings.waitOnSendMs.toLong(),
                moodWarning = settings.moodWarning,
                tipsEmotion = settings.tipsEmotion,
                language = language,
                keyboardId = settings.keyboardFor(language),
                hasKey = hasKey,
            ),
            client,
        )
    }

    private fun applyTheme() {
        val palette = keyboardTheme.palette
        root?.setBackgroundColor(palette.background)
        keyboardView?.theme = keyboardTheme
        emojiPanel?.theme = keyboardTheme
        val window = window?.window ?: return
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (keyboardTheme.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (keyboardTheme.dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    // ------------------------------------------------------------------ panels

    private fun panelParams(): FrameLayout.LayoutParams {
        val view = keyboardView
        val metrics = view?.computeMetrics()
        if (metrics == null) {
            return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val height = (controller.totalHeight(metrics) - metrics.toolbarHeight).roundToInt()
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = metrics.toolbarHeight.roundToInt()
        }
    }

    fun showEmojiPanel() {
        val container = root ?: return
        tipsPanel?.let {
            it.close()
            it.visibility = View.GONE
        }
        controller.interpreter.cancelAll()
        val panel = emojiPanel ?: EmojiPanelView(this, this).also {
            emojiPanel = it
            container.addView(it, panelParams())
        }
        panel.layoutParams = panelParams()
        panel.theme = keyboardTheme
        panel.refresh()
        panel.visibility = View.VISIBLE
        keyboardView?.keysHidden = true
    }

    fun openTips(scrollToEmotion: Boolean) {
        val container = root ?: return
        emojiPanel?.visibility = View.GONE
        controller.interpreter.cancelAll()
        val panel = tipsPanel ?: TipsPanelView(this, this).also {
            tipsPanel = it
            container.addView(it, panelParams())
        }
        panel.layoutParams = panelParams()
        panel.visibility = View.VISIBLE
        keyboardView?.keysHidden = true
        panel.open(scrollToEmotion)
    }

    fun toggleTips() {
        if (tipsPanel?.visibility == View.VISIBLE) closePanels() else openTips(scrollToEmotion = false)
    }

    fun closePanels() {
        emojiPanel?.visibility = View.GONE
        tipsPanel?.let {
            it.close()
            it.visibility = View.GONE
        }
        keyboardView?.keysHidden = false
    }

    // ------------------------------------------------------------------ actions from views

    fun openSettings() {
        requestHideSelf(0)
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    fun undoCorrection() {
        if (engine.undo()) controller.updateAutoShift()
        keyboardView?.invalidate()
    }

    fun insertEmoji(emoji: String) {
        controller.insertEmoji(emoji)
        hapticTap()
    }

    /** Client for tips/translation, or null when the provider needs a key and none is stored. */
    fun tipsClient(): AiClient? = client

    fun tipsOptions(): TipsOptions = TipsOptions(
        rewrites = settings.tipsRewrites,
        explanations = settings.tipsExplanations,
        writing = settings.tipsWriting,
        emotion = settings.tipsEmotion,
        rewriteVariants = settings.rewriteVariants,
    )

    fun replaceScope(scope: TextScope, text: String) {
        host.replaceScope(scope, text)
        engine.onTextReplaced()
        controller.updateAutoShift()
        closePanels()
    }

    fun insertAfterScope(scope: TextScope, text: String) {
        host.insertAfterScope(scope, text)
        engine.onNonAppendChange()
        controller.updateAutoShift()
        closePanels()
    }

    // ------------------------------------------------------------------ feedback (SPEC §4.7)

    private fun hapticTap() {
        if (settings.haptics) keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun onToolbarFeedback() = hapticTap()

    fun onLongPressFeedback() {
        if (settings.haptics) keyboardView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun playClick(key: Key) {
        if (!settings.sound) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        val effect = when (key.type) {
            KeyType.BACK -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio.playSoundEffect(effect, -1f)
    }

    // ------------------------------------------------------------------ KeyboardControllerListener

    override fun onKeyboardChanged() {
        keyboardView?.invalidate()
    }

    override fun onLayoutChanged() {
        keyboardView?.onKeyboardLayoutChanged()
        emojiPanel?.takeIf { it.visibility == View.VISIBLE }?.layoutParams = panelParams()
        tipsPanel?.takeIf { it.visibility == View.VISIBLE }?.layoutParams = panelParams()
    }

    override fun onKeyFeedback(key: Key) {
        hapticTap()
        playClick(key)
    }

    override fun onPopupFeedback() = onLongPressFeedback()

    override fun onNoOpFeedback() {
        if (settings.haptics) keyboardView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onEmojiPanelRequested() = showEmojiPanel()

    override fun persistKeyboardMode(mode: KeyboardMode) {
        app.settingsStore.putString(SettingKeys.KEYBOARD_MODE, mode.id)
        settings = settings.copy(keyboardMode = mode)
    }

    override fun persistActiveLanguage(languageId: String) {
        app.settingsStore.putString(SettingKeys.ACTIVE_LANGUAGE, languageId)
        settings = settings.copy(activeLanguage = languageId)
        configureEngine() // prompt language and keyboard layout follow the language
    }

    override fun persistEmojiRecents(recents: List<String>) {
        app.settingsStore.putStringList(SettingKeys.EMOJI_RECENTS, recents)
        settings = settings.copy(emojiRecents = recents)
    }

    // ------------------------------------------------------------------ EngineListener

    override fun onEngineStateChanged() {
        keyboardView?.invalidate()
    }

    override fun onCorrectionApplied() {
        controller.updateAutoShift()
        keyboardView?.invalidate()
    }

    override fun onMoodWarningShown(level: Int) {
        if (!settings.haptics) return
        // Double tick when the emotion chip appears (SPEC §4.7).
        keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        handler.postDelayed({ keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }, 120L)
    }
}
