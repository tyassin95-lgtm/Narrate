package com.narrate.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The model lists discovered from each provider, held for the life of the process.
 *
 * Settings is opened and closed often, and its view model is rebuilt every time. Caching
 * here means the lists survive that, so reopening Settings does not re-query every provider.
 */
class ModelCache {

    private val _models = MutableStateFlow(
        ProviderId.entries.associateWith { ProviderRegistry.get(it).catalog() }
    )
    val models: StateFlow<Map<ProviderId, List<ModelInfo>>> = _models.asStateFlow()

    private val discovered = mutableSetOf<ProviderId>()

    /** True once this provider has answered with its own list rather than the fallback. */
    fun hasDiscovered(provider: ProviderId): Boolean = synchronized(discovered) { provider in discovered }

    fun put(provider: ProviderId, models: List<ModelInfo>) {
        if (models.isEmpty()) return
        synchronized(discovered) { discovered += provider }
        _models.value = _models.value + (provider to models)
    }

    fun forProvider(provider: ProviderId): List<ModelInfo> = _models.value[provider].orEmpty()
}
