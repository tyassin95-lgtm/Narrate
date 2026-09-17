package com.narrate.app.ai

import kotlin.math.abs

/**
 * Published list prices, in USD, for the models Narrate can drive.
 *
 * This is a snapshot, not a feed: providers change prices, and nothing here is fetched at
 * runtime. Everything shown to the player is therefore labelled as indicative and dated with
 * [AS_OF], and a model with no entry is reported as unpriced rather than guessed at.
 *
 * To update, edit this file only. Sources:
 *   OpenAI     https://developers.openai.com/api/docs/pricing
 *   Anthropic  https://platform.claude.com/docs/en/about-claude/pricing
 *   Google     https://ai.google.dev/gemini-api/docs/pricing
 *   xAI        https://docs.x.ai/docs/models
 */
object ModelPricing {

    /** The date the numbers below were read from the providers' own pricing pages. */
    const val AS_OF = "17 September 2026"

    /**
     * Token prices are per million tokens. [perImage] is what one generated image costs;
     * where a provider prices images by token rather than by image, it is an estimate for a
     * single high-quality 1024px image and is marked as such in the UI.
     */
    data class Price(
        val inputPerMTok: Double = 0.0,
        val outputPerMTok: Double = 0.0,
        val perImage: Double? = null,
        val note: String = ""
    ) {
        val isImage: Boolean get() = perImage != null
    }

    private val openAi = mapOf(
        "gpt-6-astra" to Price(10.0, 50.0),
        "gpt-5.6-sol" to Price(4.0, 20.0),
        "gpt-5.6-terra" to Price(2.0, 12.0),
        "gpt-5.6-luna" to Price(0.20, 1.20),
        "gpt-5.6-cyber" to Price(12.50, 75.0),
        "gpt-5.5-cyber" to Price(12.50, 75.0),
        "gpt-5.5-pro" to Price(30.0, 180.0),
        "gpt-5.5" to Price(5.0, 30.0),
        "gpt-5.4-pro" to Price(30.0, 180.0),
        "gpt-5.4-mini" to Price(0.75, 4.50),
        "gpt-5.4-nano" to Price(0.20, 1.25),
        "gpt-5.4" to Price(2.50, 15.0),
        "gpt-5.3-codex" to Price(1.75, 14.0),
        "gpt-5.2-pro" to Price(21.0, 168.0),
        "gpt-5.2" to Price(1.75, 14.0),
        "gpt-5.1" to Price(1.25, 10.0),
        "gpt-5-pro" to Price(15.0, 120.0),
        "gpt-5-mini" to Price(0.25, 2.0),
        "gpt-5-nano" to Price(0.05, 0.40),
        "gpt-5-search-api" to Price(1.25, 10.0),
        "gpt-5" to Price(1.25, 10.0),
        "gpt-4.1-mini" to Price(0.40, 1.60),
        "gpt-4.1-nano" to Price(0.10, 0.40),
        "gpt-4.1" to Price(2.0, 8.0),
        "gpt-4o-mini" to Price(0.15, 0.60),
        "gpt-4o" to Price(2.50, 10.0),
        "chatgpt-4o-latest" to Price(5.0, 15.0),
        "chat-latest" to Price(5.0, 30.0),
        "gpt-rosalind-research" to Price(5.0, 25.0),
        "o4-mini" to Price(1.10, 4.40),
        "o3-pro" to Price(20.0, 80.0),
        "o3-mini" to Price(1.10, 4.40),
        "o3" to Price(2.0, 8.0),
        "o1-pro" to Price(150.0, 600.0),
        "o1" to Price(15.0, 60.0),
        "gpt-3.5-turbo" to Price(0.50, 1.50),
        // Image models are billed per output token; perImage is one 1024px image at high quality.
        "gpt-image-2.5-sunburst" to Price(5.0, 30.0, perImage = 0.125, note = "estimated per image"),
        "gpt-image-2.5-flare" to Price(5.0, 30.0, perImage = 0.125, note = "estimated per image"),
        "gpt-image-2" to Price(5.0, 30.0, perImage = 0.125, note = "estimated per image"),
        "gpt-image-1.5" to Price(5.0, 32.0, perImage = 0.133, note = "estimated per image"),
        "gpt-image-1-mini" to Price(2.0, 8.0, perImage = 0.033, note = "estimated per image"),
        "gpt-image-1" to Price(5.0, 40.0, perImage = 0.167, note = "estimated per image"),
        "chatgpt-image-latest" to Price(5.0, 32.0, perImage = 0.133, note = "estimated per image"),
        "dall-e-3" to Price(perImage = 0.04, note = "1024px standard")
    )

    private val anthropic = mapOf(
        "claude-fable-5-1" to Price(10.0, 50.0),
        "claude-fable-5" to Price(10.0, 50.0),
        "claude-mythos-5-1" to Price(10.0, 50.0),
        "claude-mythos-5" to Price(10.0, 50.0),
        "claude-opus-5" to Price(5.0, 25.0),
        "claude-opus-4-8" to Price(5.0, 25.0),
        "claude-opus-4-7" to Price(5.0, 25.0),
        "claude-opus-4-6" to Price(5.0, 25.0),
        "claude-opus-4-5" to Price(5.0, 25.0),
        "claude-opus-4-1" to Price(15.0, 75.0),
        "claude-opus-4" to Price(15.0, 75.0),
        "claude-sonnet-5" to Price(2.0, 10.0),
        "claude-sonnet-4-6" to Price(3.0, 15.0),
        "claude-sonnet-4-5" to Price(3.0, 15.0),
        "claude-sonnet-4" to Price(3.0, 15.0),
        "claude-haiku-4-5" to Price(1.0, 5.0),
        "claude-3-7-sonnet" to Price(3.0, 15.0),
        "claude-3-5-haiku" to Price(0.80, 4.0)
    )

    private val google = mapOf(
        "gemini-3.8-flash" to Price(0.75, 3.75, note = "promotional through 2026"),
        "gemini-3.7-flash" to Price(0.75, 3.75, note = "promotional through 2026"),
        "gemini-3.6-flash" to Price(0.75, 3.75, note = "promotional through 2026"),
        "gemini-3.5-flash-lite" to Price(0.30, 2.50),
        "gemini-3.5-flash" to Price(1.50, 9.0),
        "gemini-3.1-flash-lite" to Price(0.25, 1.50),
        "gemini-3.1-pro" to Price(2.0, 12.0, note = "up to 200k context"),
        "gemini-2.5-pro" to Price(1.25, 10.0, note = "up to 200k context"),
        "gemini-2.5-flash-lite" to Price(0.10, 0.40),
        "gemini-2.5-flash" to Price(0.30, 2.50),
        "gemini-2.0-flash" to Price(0.10, 0.40),
        "gemini-3.1-flash-lite-image" to Price(0.25, 30.0, perImage = 0.034),
        "gemini-3.1-flash-image" to Price(0.50, 60.0, perImage = 0.067),
        "gemini-3-pro-image" to Price(2.0, 120.0, perImage = 0.134),
        "gemini-2.5-flash-image" to Price(0.30, 30.0, perImage = 0.039),
        "imagen-4.0" to Price(perImage = 0.04)
    )

    private val xai = mapOf(
        "grok-4.6" to Price(2.0, 6.0, note = "up to 200k context"),
        "grok-4.5" to Price(2.0, 6.0, note = "up to 200k context"),
        "grok-4.3" to Price(1.25, 2.50, note = "up to 200k context"),
        "grok-4.20-multi-agent" to Price(1.25, 2.50),
        "grok-4.20" to Price(1.25, 2.50),
        "grok-build-0.1" to Price(1.0, 2.0),
        "grok-4" to Price(3.0, 15.0),
        "grok-3-mini" to Price(0.30, 0.50),
        "grok-3" to Price(3.0, 15.0),
        "grok-imagine-image-quality" to Price(perImage = 0.05),
        "grok-imagine-image-2.0" to Price(perImage = 0.04),
        "grok-imagine-image" to Price(perImage = 0.02),
        "grok-2-image" to Price(perImage = 0.07)
    )

    private fun table(provider: ProviderId) = when (provider) {
        ProviderId.OPENAI -> openAi
        ProviderId.ANTHROPIC -> anthropic
        ProviderId.GOOGLE -> google
        ProviderId.XAI -> xai
    }

    /**
     * Longest-prefix match, so a dated or suffixed release ("claude-opus-5-20260115",
     * "gpt-5.4-2026-03-01") prices as its family without needing its own row.
     */
    fun of(provider: ProviderId, modelId: String): Price? {
        if (modelId.isBlank()) return null
        val id = modelId.lowercase().trim()
        val prices = table(provider)
        prices[id]?.let { return it }
        return prices.entries
            .filter { id.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun estimateChatCost(provider: ProviderId, modelId: String, inputTokens: Int, outputTokens: Int): Double? {
        val price = of(provider, modelId) ?: return null
        if (price.inputPerMTok == 0.0 && price.outputPerMTok == 0.0) return null
        return inputTokens / 1_000_000.0 * price.inputPerMTok +
            outputTokens / 1_000_000.0 * price.outputPerMTok
    }

    fun estimateImageCost(provider: ProviderId, modelId: String, images: Int = 1): Double? {
        val price = of(provider, modelId) ?: return null
        val perImage = price.perImage ?: return null
        return perImage * images
    }

    /** "in $1.25 / out $10.00 per 1M tokens" or "about $0.17 per image". */
    fun summary(provider: ProviderId, modelId: String): String? {
        val price = of(provider, modelId) ?: return null
        val parts = mutableListOf<String>()
        if (price.perImage != null) {
            parts += "about ${money(price.perImage)} per image"
        }
        if (price.inputPerMTok > 0.0 || price.outputPerMTok > 0.0) {
            parts += "in ${money(price.inputPerMTok)} / out ${money(price.outputPerMTok)} per 1M tokens"
        }
        if (price.note.isNotBlank()) parts += price.note
        return parts.joinToString(" - ").ifBlank { null }
    }

    /** A coarse band so the player can compare cost at a glance without reading numbers. */
    fun band(provider: ProviderId, modelId: String): String? {
        val price = of(provider, modelId) ?: return null
        price.perImage?.let {
            return when {
                it <= 0.03 -> "CHEAPEST"
                it <= 0.08 -> "MID"
                else -> "PREMIUM"
            }
        }
        val blended = price.inputPerMTok + price.outputPerMTok / 4.0
        return when {
            blended <= 1.0 -> "CHEAPEST"
            blended <= 5.0 -> "MID"
            blended <= 15.0 -> "PREMIUM"
            else -> "LUXURY"
        }
    }

    fun money(value: Double): String = when {
        value == 0.0 -> "$0"
        abs(value) < 0.01 -> "$" + String.format("%.4f", value)
        abs(value) < 1.0 -> "$" + String.format("%.3f", value).trimEnd('0').trimEnd('.')
        abs(value) < 100.0 -> "$" + String.format("%.2f", value)
        else -> "$" + String.format("%.0f", value)
    }
}
