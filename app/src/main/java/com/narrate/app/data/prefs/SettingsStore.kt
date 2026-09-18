package com.narrate.app.data.prefs

import android.content.Context
import com.narrate.app.ai.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which model does which job. The player picks all three independently. */
data class ModelChoice(val provider: ProviderId, val model: String) {
    val isSet: Boolean get() = model.isNotBlank()
}

data class AppSettings(
    val narration: ModelChoice = ModelChoice(ProviderId.OPENAI, ""),
    val simulation: ModelChoice = ModelChoice(ProviderId.OPENAI, ""),
    val image: ModelChoice = ModelChoice(ProviderId.OPENAI, ""),
    val temperature: Float = 0.9f,
    val maxTokens: Int = 8192,
    val recentTurnWindow: Int = 8,
    val memoryRetrievalCount: Int = 24,
    val useReferenceImages: Boolean = true,
    val autoGenerateSceneImages: Boolean = false,
    val simulateOffscreenWorld: Boolean = true,
    val strictContinuity: Boolean = true,
    /** Unlocks the transcript export. Off by default; it changes nothing about play. */
    val developerMode: Boolean = false,
    val configuredProviders: Set<ProviderId> = emptySet()
) {
    val hasNarrationModel: Boolean get() = narration.isSet
    val hasImageModel: Boolean get() = image.isSet
}

/**
 * Settings live in plain preferences; API keys live in [SecureStore]. Exposed as a
 * [StateFlow] so every screen reacts the moment the player changes a model.
 */
class SettingsStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("narrate_settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(app)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings = AppSettings(
        narration = ModelChoice(
            ProviderId.fromId(prefs.getString("narration_provider", null)),
            prefs.getString("narration_model", "") ?: ""
        ),
        simulation = ModelChoice(
            ProviderId.fromId(prefs.getString("simulation_provider", null)),
            prefs.getString("simulation_model", "") ?: ""
        ),
        image = ModelChoice(
            ProviderId.fromId(prefs.getString("image_provider", null)),
            prefs.getString("image_model", "") ?: ""
        ),
        temperature = prefs.getFloat("temperature", 0.9f),
        maxTokens = prefs.getInt("max_tokens", 8192),
        recentTurnWindow = prefs.getInt("recent_turns", 8),
        memoryRetrievalCount = prefs.getInt("memory_count", 24),
        useReferenceImages = prefs.getBoolean("use_reference_images", true),
        autoGenerateSceneImages = prefs.getBoolean("auto_images", false),
        simulateOffscreenWorld = prefs.getBoolean("simulate_offscreen", true),
        strictContinuity = prefs.getBoolean("strict_continuity", true),
        developerMode = prefs.getBoolean("developer_mode", false),
        configuredProviders = ProviderId.entries.filter { secure.get(keyName(it)).isNotBlank() }.toSet()
    )

    private fun refresh() {
        _settings.value = load()
    }

    fun apiKey(provider: ProviderId): String = secure.get(keyName(provider))

    fun setApiKey(provider: ProviderId, value: String) {
        secure.put(keyName(provider), value.trim())
        refresh()
    }

    fun hasApiKey(provider: ProviderId): Boolean = apiKey(provider).isNotBlank()

    fun setNarrationModel(provider: ProviderId, model: String) = edit {
        putString("narration_provider", provider.name)
        putString("narration_model", model)
    }

    fun setSimulationModel(provider: ProviderId, model: String) = edit {
        putString("simulation_provider", provider.name)
        putString("simulation_model", model)
    }

    fun setImageModel(provider: ProviderId, model: String) = edit {
        putString("image_provider", provider.name)
        putString("image_model", model)
    }

    fun setTemperature(value: Float) = edit { putFloat("temperature", value) }
    fun setMaxTokens(value: Int) = edit { putInt("max_tokens", value) }
    fun setRecentTurnWindow(value: Int) = edit { putInt("recent_turns", value) }
    fun setMemoryRetrievalCount(value: Int) = edit { putInt("memory_count", value) }
    fun setUseReferenceImages(value: Boolean) = edit { putBoolean("use_reference_images", value) }
    fun setAutoGenerateSceneImages(value: Boolean) = edit { putBoolean("auto_images", value) }
    fun setSimulateOffscreenWorld(value: Boolean) = edit { putBoolean("simulate_offscreen", value) }
    fun setStrictContinuity(value: Boolean) = edit { putBoolean("strict_continuity", value) }
    fun setDeveloperMode(value: Boolean) = edit { putBoolean("developer_mode", value) }

    /** Effective simulation choice: falls back to the narration model when unset. */
    fun simulationOrNarration(): ModelChoice =
        if (current.simulation.isSet) current.simulation else current.narration

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        refresh()
    }

    private fun keyName(provider: ProviderId) = "api_key_${provider.name}"
}
