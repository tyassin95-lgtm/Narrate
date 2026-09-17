package com.narrate.app.engine

import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelPricing
import com.narrate.app.ai.ProviderId
import com.narrate.app.core.newId
import com.narrate.app.data.entity.UsageEntity
import com.narrate.app.data.repo.WorldRepository

/** What a stretch of usage adds up to. */
data class UsageSummary(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val images: Int = 0,
    val cost: Double = 0.0,
    /** True when at least one call used a model with no price on record. */
    val hasUnpricedCalls: Boolean = false
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** Usage grouped under one heading, such as a model or a kind of work. */
data class UsageGroup(val label: String, val detail: String, val summary: UsageSummary)

/**
 * Records what each call to a provider cost, and adds it up afterwards.
 *
 * Token counts come from the provider's own usage figures where it reports them, and are
 * estimated from text length where it does not. Cost is always an estimate against the
 * price list in [ModelPricing], and is reported as unpriced rather than guessed when a
 * model is not on it.
 */
class UsageRecorder(private val repository: WorldRepository) {

    suspend fun recordChat(
        worldId: String,
        turnIndex: Int,
        purpose: String,
        response: LlmResponse,
        promptCharacters: Int = 0
    ) {
        val inputTokens = response.inputTokens.takeIf { it > 0 } ?: estimateTokens(promptCharacters)
        val outputTokens = response.outputTokens.takeIf { it > 0 } ?: estimateTokens(response.text.length)
        val cost = ModelPricing.estimateChatCost(response.provider, response.model, inputTokens, outputTokens)
        repository.recordUsage(
            UsageEntity(
                id = newId(),
                worldId = worldId,
                turnIndex = turnIndex,
                purpose = purpose,
                provider = response.provider.name,
                model = response.model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCost = cost ?: 0.0,
                costKnown = cost != null
            )
        )
    }

    suspend fun recordImage(
        worldId: String,
        turnIndex: Int,
        provider: ProviderId,
        model: String,
        images: Int = 1
    ) {
        val cost = ModelPricing.estimateImageCost(provider, model, images)
        repository.recordUsage(
            UsageEntity(
                id = newId(),
                worldId = worldId,
                turnIndex = turnIndex,
                purpose = PURPOSE_IMAGE,
                provider = provider.name,
                model = model,
                images = images,
                estimatedCost = cost ?: 0.0,
                costKnown = cost != null
            )
        )
    }

    private fun estimateTokens(characters: Int): Int =
        if (characters <= 0) 0 else (characters / 4.0).toInt().coerceAtLeast(1)

    companion object {
        const val PURPOSE_NARRATION = "NARRATION"
        const val PURPOSE_REPAIR = "REPAIR"
        const val PURPOSE_CHAPTER = "CHAPTER"
        const val PURPOSE_CREATION = "CREATION"
        const val PURPOSE_IMAGE = "IMAGE"

        fun summarise(events: List<UsageEntity>): UsageSummary = UsageSummary(
            calls = events.size,
            inputTokens = events.sumOf { it.inputTokens.toLong() },
            outputTokens = events.sumOf { it.outputTokens.toLong() },
            images = events.sumOf { it.images },
            cost = events.sumOf { it.estimatedCost },
            hasUnpricedCalls = events.any { !it.costKnown }
        )

        fun byModel(events: List<UsageEntity>): List<UsageGroup> = events
            .groupBy { it.provider to it.model }
            .map { (key, group) ->
                UsageGroup(
                    label = key.second.ifBlank { "unknown model" },
                    detail = ProviderId.fromId(key.first).displayName,
                    summary = summarise(group)
                )
            }
            .sortedByDescending { it.summary.cost }

        fun byPurpose(events: List<UsageEntity>): List<UsageGroup> = events
            .groupBy { it.purpose }
            .map { (purpose, group) ->
                UsageGroup(
                    label = when (purpose) {
                        PURPOSE_NARRATION -> "Narration"
                        PURPOSE_REPAIR -> "Completing cut-off turns"
                        PURPOSE_CHAPTER -> "Chapter summaries"
                        PURPOSE_CREATION -> "World and character creation"
                        PURPOSE_IMAGE -> "Images"
                        else -> purpose.lowercase().replaceFirstChar { it.uppercase() }
                    },
                    detail = "",
                    summary = summarise(group)
                )
            }
            .sortedByDescending { it.summary.cost }
    }
}
