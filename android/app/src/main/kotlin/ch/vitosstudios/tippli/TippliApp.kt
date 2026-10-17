package ch.vitosstudios.tippli

import android.app.Application
import ch.vitosstudios.tippli.core.ai.AiClient
import ch.vitosstudios.tippli.core.ai.AiClientFactory
import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.data.SharedData
import ch.vitosstudios.tippli.core.data.Strings
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.settings.SettingsReader
import ch.vitosstudios.tippli.core.settings.TippliSettings
import ch.vitosstudios.tippli.core.tips.TipsService
import ch.vitosstudios.tippli.data.AssetResourceLoader
import ch.vitosstudios.tippli.data.PrefsSettingsStore
import ch.vitosstudios.tippli.data.SecretStore

/**
 * Process-wide singletons shared by the keyboard service and the settings
 * activity (both run in the same process, so settings changes are visible to
 * the keyboard the next time it is shown).
 */
class TippliApp : Application() {

    val shared: SharedData by lazy { SharedData(AssetResourceLoader(assets)) }
    val strings: Strings get() = shared.strings
    val settingsStore: PrefsSettingsStore by lazy { PrefsSettingsStore(this) }
    val secrets: SecretStore by lazy { SecretStore(this) }
    val prompts: PromptBuilder by lazy { PromptBuilder(shared.prompts, shared.layouts) }
    val tips: TipsService by lazy { TipsService(prompts) }
    val aiClients: AiClientFactory by lazy { AiClientFactory() }

    private val settingsReader by lazy { SettingsReader(shared) }

    fun readSettings(): TippliSettings = settingsReader.read(settingsStore)

    /** Endpoint of the selected provider with its stored key (key may be empty). */
    fun endpointFor(settings: TippliSettings, providerId: String = settings.provider): AiEndpoint? {
        val preset = shared.provider(providerId) ?: return null
        return AiEndpoint(
            preset = preset,
            apiKey = secrets.apiKey(preset.id).orEmpty(),
            model = settings.modelFor(preset),
            baseUrl = settings.baseUrlFor(preset),
        )
    }

    fun clientFor(endpoint: AiEndpoint): AiClient = aiClients.create(endpoint)
}
