package com.narrate.app.ai

/** Single place the app resolves a [ProviderId] to a working client. */
object ProviderRegistry {

    private val defaults: Map<ProviderId, AiProvider> = mapOf(
        ProviderId.OPENAI to OpenAiProvider(),
        ProviderId.ANTHROPIC to AnthropicProvider(),
        ProviderId.GOOGLE to GeminiProvider(),
        ProviderId.XAI to XaiProvider()
    )

    private val overrides = mutableMapOf<ProviderId, AiProvider>()

    fun get(id: ProviderId): AiProvider = overrides[id] ?: defaults.getValue(id)

    fun all(): List<AiProvider> = ProviderId.entries.map { get(it) }

    fun textModels(): List<ModelInfo> = all().flatMap { it.catalog() }.filter { it.supportsText }

    fun imageModels(): List<ModelInfo> = all().flatMap { it.catalog() }.filter { it.supportsImageGeneration }

    /** Test seam: substitute a provider implementation. */
    fun override(id: ProviderId, provider: AiProvider?) {
        if (provider == null) overrides.remove(id) else overrides[id] = provider
    }
}
