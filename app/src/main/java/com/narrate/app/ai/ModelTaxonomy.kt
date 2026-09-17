package com.narrate.app.ai

/**
 * Makes sense of the raw model lists providers return.
 *
 * A provider's /models endpoint is a catalogue of everything the key can reach: embeddings,
 * speech, moderation, years of superseded releases. Narrate needs two questions answered -
 * can this model narrate, or can it draw - and it needs the current ones at the top, because
 * an alphabetical list buries this year's flagship under a decade of legacy names.
 */
object ModelTaxonomy {

    /** Models that cannot narrate or draw, whatever else they are good at. */
    private val unusable = listOf(
        "embedding", "embed-", "whisper", "tts", "text-to-speech", "speech", "transcribe",
        "moderation", "guard", "rerank", "similarity", "search-document", "search-query",
        "audio", "realtime", "veo-", "sora", "video", "aqa", "learnlm", "davinci", "babbage",
        "curie", "ada", "computer-use", "codestral", "edit-"
    )

    private val imageMarkers = listOf("image", "dall-e", "imagen", "imagine")

    /** Kept, but sorted below everything current and labelled in the UI. */
    private val legacyMarkers = listOf(
        "gpt-3.5", "gpt-4-0613", "gpt-4-turbo", "gpt-4-32k", "claude-3", "claude-2",
        "gemini-1.0", "gemini-1.5", "grok-2", "grok-beta", "instruct", "-0301", "-0314", "-0613"
    )

    fun isUsable(modelId: String): Boolean {
        val id = modelId.lowercase()
        // "gpt-image" contains no unusable marker, but "gpt-4o-audio-preview" does.
        return unusable.none { id.contains(it) }
    }

    fun isImageModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return imageMarkers.any { id.contains(it) }
    }

    /** Image models that accept a reference image, which is what holds a face steady. */
    fun supportsImageReferences(provider: ProviderId, modelId: String): Boolean {
        val id = modelId.lowercase()
        if (!isImageModel(id)) return false
        return when (provider) {
            ProviderId.OPENAI -> id.startsWith("gpt-image") || id.contains("chatgpt-image")
            ProviderId.GOOGLE -> !id.startsWith("imagen")
            else -> false
        }
    }

    fun isLegacy(modelId: String): Boolean {
        val id = modelId.lowercase()
        return legacyMarkers.any { id.contains(it) }
    }

    fun isPreview(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("preview") || id.contains("-exp") || id.contains("experimental")
    }

    /**
     * A rough "how current is this" score.
     *
     * Family comes first and version second, because version numbers do not compare across
     * families: dall-e-3 is not newer than gpt-image-2, and claude-haiku-4-5 is not a bigger
     * model than claude-opus-5. Superseded releases are pushed below everything current.
     */
    fun recency(provider: ProviderId, modelId: String): Double {
        val id = modelId.lowercase()
        val version = versionOf(id)
        val family = when (provider) {
            ProviderId.OPENAI -> when {
                id.startsWith("gpt-") -> 3.0
                id.startsWith("chatgpt") || id.startsWith("chat-") -> 2.0
                id.startsWith("o") -> 1.0
                else -> 0.0
            }
            ProviderId.ANTHROPIC -> when {
                id.contains("fable") || id.contains("mythos") -> 3.0
                id.contains("opus") -> 2.5
                id.contains("sonnet") -> 2.0
                id.contains("haiku") -> 1.5
                else -> 0.0
            }
            ProviderId.GOOGLE -> when {
                id.contains("pro") -> 2.5
                id.contains("flash") -> 2.0
                else -> 1.0
            }
            ProviderId.XAI -> if (id.startsWith("grok")) 2.0 else 0.0
        }
        val penalty = (if (isLegacy(id)) 100_000.0 else 0.0) + (if (isPreview(id)) 5.0 else 0.0)
        return family * 1_000.0 + version * 10.0 - penalty
    }

    /**
     * Reads the version out of a model id. Providers write it three ways -
     * "gpt-5.6-sol", "claude-sonnet-4-5", "grok-4.6" - and all three must compare correctly.
     */
    private fun versionOf(id: String): Double {
        val dotted = Regex("(\\d+)\\.(\\d+)").find(id)
        if (dotted != null) {
            return dotted.groupValues[1].toDouble() + dotted.groupValues[2].toDouble() / 10.0
        }
        val dashed = Regex("-(\\d+)-(\\d)(?![0-9])").find(id)
        if (dashed != null) {
            return dashed.groupValues[1].toDouble() + dashed.groupValues[2].toDouble() / 10.0
        }
        val single = Regex("[-a-z](\\d+)(?![0-9.])").findAll(id)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it < 100 }
            .maxOrNull()
        return single ?: 0.0
    }

    /** Newest and most capable first; legacy last; stable alphabetical tiebreak. */
    fun sort(models: List<ModelInfo>): List<ModelInfo> =
        models.sortedWith(
            compareByDescending<ModelInfo> { recency(it.provider, it.id) }.thenBy { it.id }
        )

    /**
     * Turns one raw id from a provider listing into a [ModelInfo], or null if the model
     * cannot narrate or draw.
     */
    fun describe(provider: ProviderId, modelId: String, displayName: String? = null): ModelInfo? {
        if (modelId.isBlank() || !isUsable(modelId)) return null
        val image = isImageModel(modelId)
        val notes = buildList {
            if (isLegacy(modelId)) add("legacy")
            if (isPreview(modelId)) add("preview")
            if (supportsImageReferences(provider, modelId)) add("reference images")
        }
        return ModelInfo(
            id = modelId,
            provider = provider,
            label = displayName?.takeIf { it.isNotBlank() } ?: modelId,
            supportsText = !image,
            supportsImageGeneration = image,
            supportsImageReferences = supportsImageReferences(provider, modelId),
            note = notes.joinToString(" - ")
        )
    }
}
