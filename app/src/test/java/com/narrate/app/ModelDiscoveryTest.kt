package com.narrate.app

import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ModelPricing
import com.narrate.app.ai.ModelTaxonomy
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model discovery has to survive what providers actually return: hundreds of ids covering
 * embeddings, speech and a decade of superseded releases, in no useful order.
 */
class ModelDiscoveryTest {

    /** A realistic slice of what /v1/models returns for an OpenAI key. */
    private val openAiListing = listOf(
        "babbage-002", "chatgpt-4o-latest", "dall-e-2", "dall-e-3", "davinci-002",
        "gpt-3.5-turbo", "gpt-3.5-turbo-instruct", "gpt-4", "gpt-4-0613", "gpt-4-turbo",
        "gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-audio-preview", "gpt-4o-mini",
        "gpt-4o-mini-tts", "gpt-4o-transcribe", "gpt-5", "gpt-5-mini", "gpt-5-nano",
        "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.4-mini", "gpt-5.6-luna", "gpt-5.6-sol",
        "gpt-5.6-terra", "gpt-6-astra", "gpt-image-1", "gpt-image-1-mini", "gpt-image-2",
        "o1", "o3", "o4-mini", "omni-moderation-latest", "text-embedding-3-large",
        "text-embedding-ada-002", "tts-1", "whisper-1"
    )

    private fun describeAll(provider: ProviderId, ids: List<String>): List<ModelInfo> =
        ModelTaxonomy.sort(ids.mapNotNull { ModelTaxonomy.describe(provider, it) })

    @Test
    fun `models that cannot narrate or draw are dropped`() {
        val ids = describeAll(ProviderId.OPENAI, openAiListing).map { it.id }
        listOf(
            "text-embedding-3-large", "text-embedding-ada-002", "whisper-1", "tts-1",
            "omni-moderation-latest", "gpt-4o-mini-tts", "gpt-4o-transcribe",
            "gpt-4o-audio-preview", "davinci-002", "babbage-002"
        ).forEach { assertTrue("$it should have been filtered out", it !in ids) }
    }

    @Test
    fun `the current flagships come first, not whatever sorts first alphabetically`() {
        val text = describeAll(ProviderId.OPENAI, openAiListing).filter { it.supportsText }
        assertEquals("gpt-6-astra", text.first().id)
        val topFive = text.take(5).map { it.id }
        assertTrue("gpt-5.6-sol" in topFive)
        assertTrue("gpt-5.6-terra" in topFive)
        assertTrue(
            "this is the bug: gpt-5 must not be the only modern model visible",
            text.indexOf(text.first { it.id == "gpt-5.4" }) < text.indexOf(text.first { it.id == "gpt-5" })
        )
        assertTrue(
            "legacy releases sort last",
            text.indexOfFirst { it.id == "gpt-3.5-turbo" } > text.indexOfFirst { it.id == "gpt-4.1" }
        )
    }

    @Test
    fun `image models are separated from narration models`() {
        val all = describeAll(ProviderId.OPENAI, openAiListing)
        val images = all.filter { it.supportsImageGeneration }.map { it.id }
        assertEquals(listOf("gpt-image-2", "gpt-image-1", "gpt-image-1-mini", "dall-e-3", "dall-e-2"), images)
        assertTrue(all.none { it.supportsText && it.supportsImageGeneration })
        assertTrue(all.first { it.id == "gpt-image-1" }.supportsImageReferences)
        assertTrue(!all.first { it.id == "dall-e-3" }.supportsImageReferences)
    }

    @Test
    fun `claude versions compare correctly across the dashed naming scheme`() {
        val listing = listOf(
            "claude-3-5-haiku-latest", "claude-haiku-4-5", "claude-opus-4-1", "claude-opus-4-5",
            "claude-opus-5", "claude-sonnet-4-5", "claude-sonnet-5", "claude-fable-5-1"
        )
        val sorted = describeAll(ProviderId.ANTHROPIC, listing).map { it.id }
        assertEquals("claude-fable-5-1", sorted.first())
        assertTrue(sorted.indexOf("claude-opus-5") < sorted.indexOf("claude-opus-4-5"))
        assertTrue(sorted.indexOf("claude-opus-5") < sorted.indexOf("claude-sonnet-5"))
        assertTrue(sorted.indexOf("claude-sonnet-4-5") < sorted.indexOf("claude-3-5-haiku-latest"))
    }

    @Test
    fun `gemini image models are recognised and keep reference support`() {
        val listing = listOf(
            "gemini-2.5-flash", "gemini-3.8-flash", "gemini-3.1-flash-image", "imagen-4.0-generate-001",
            "gemini-embedding-2", "veo-3.1"
        )
        val all = describeAll(ProviderId.GOOGLE, listing)
        val ids = all.map { it.id }
        assertTrue("gemini-embedding-2" !in ids)
        assertTrue("veo-3.1" !in ids)
        assertEquals("gemini-3.8-flash", all.first { it.supportsText }.id)
        assertTrue(all.first { it.id == "gemini-3.1-flash-image" }.supportsImageReferences)
        assertTrue(!all.first { it.id == "imagen-4.0-generate-001" }.supportsImageReferences)
    }

    @Test
    fun `grok models sort by version and images are split out`() {
        val all = describeAll(ProviderId.XAI, listOf("grok-3", "grok-4.6", "grok-4.3", "grok-imagine-image-2.0"))
        assertEquals("grok-4.6", all.first().id)
        assertTrue(all.first { it.id == "grok-imagine-image-2.0" }.supportsImageGeneration)
    }

    @Test
    fun `every provider offers a usable fallback before a key is entered`() {
        ProviderRegistry.all().forEach { provider ->
            val catalog = provider.catalog()
            assertTrue("${provider.id} has no fallback models", catalog.isNotEmpty())
            assertTrue("${provider.id} lists no narration model", catalog.any { it.supportsText })
        }
        assertTrue(ProviderRegistry.imageModels().isNotEmpty())
    }

    // --- pricing --------------------------------------------------------------------------

    @Test
    fun `prices are found for exact ids and for dated releases of the same family`() {
        assertEquals(1.25, ModelPricing.of(ProviderId.OPENAI, "gpt-5")!!.inputPerMTok, 0.001)
        assertEquals(2.50, ModelPricing.of(ProviderId.OPENAI, "gpt-5.4")!!.inputPerMTok, 0.001)
        assertEquals(0.25, ModelPricing.of(ProviderId.OPENAI, "gpt-5-mini")!!.inputPerMTok, 0.001)
        // A dated or suffixed release prices as its family.
        assertEquals(5.0, ModelPricing.of(ProviderId.ANTHROPIC, "claude-opus-5-20260115")!!.inputPerMTok, 0.001)
        assertEquals(2.0, ModelPricing.of(ProviderId.ANTHROPIC, "claude-sonnet-5")!!.inputPerMTok, 0.001)
        assertEquals(0.30, ModelPricing.of(ProviderId.GOOGLE, "gemini-2.5-flash-002")!!.inputPerMTok, 0.001)
        assertEquals(2.0, ModelPricing.of(ProviderId.XAI, "grok-4.6")!!.inputPerMTok, 0.001)
    }

    @Test
    fun `the longest matching prefix wins so a mini is never priced as its parent`() {
        val mini = ModelPricing.of(ProviderId.OPENAI, "gpt-5.4-mini")!!
        val parent = ModelPricing.of(ProviderId.OPENAI, "gpt-5.4")!!
        assertEquals(0.75, mini.inputPerMTok, 0.001)
        assertEquals(2.50, parent.inputPerMTok, 0.001)
        assertTrue(mini.inputPerMTok < parent.inputPerMTok)
    }

    @Test
    fun `an unknown model is reported as unpriced rather than guessed`() {
        assertNull(ModelPricing.of(ProviderId.OPENAI, "some-unreleased-model"))
        assertNull(ModelPricing.summary(ProviderId.OPENAI, "some-unreleased-model"))
        assertNull(ModelPricing.estimateChatCost(ProviderId.OPENAI, "some-unreleased-model", 1000, 1000))
        assertNull(ModelPricing.band(ProviderId.OPENAI, "some-unreleased-model"))
        assertNull(ModelPricing.of(ProviderId.OPENAI, ""))
    }

    @Test
    fun `chat cost is the published rate applied to the tokens used`() {
        // 1M in at $1.25 and 100k out at $10.00.
        val cost = ModelPricing.estimateChatCost(ProviderId.OPENAI, "gpt-5", 1_000_000, 100_000)!!
        assertEquals(1.25 + 1.0, cost, 0.0001)
    }

    @Test
    fun `image cost is per image where the provider prices it that way`() {
        assertEquals(0.04, ModelPricing.estimateImageCost(ProviderId.XAI, "grok-imagine-image-2.0")!!, 0.0001)
        assertEquals(0.08, ModelPricing.estimateImageCost(ProviderId.XAI, "grok-imagine-image-2.0", 2)!!, 0.0001)
        assertNotNull(ModelPricing.estimateImageCost(ProviderId.OPENAI, "gpt-image-1"))
        assertNull("a text model has no per-image price", ModelPricing.estimateImageCost(ProviderId.OPENAI, "gpt-5"))
    }

    @Test
    fun `cost bands rank the flagships above the small models`() {
        assertEquals("CHEAPEST", ModelPricing.band(ProviderId.OPENAI, "gpt-5-nano"))
        assertEquals("LUXURY", ModelPricing.band(ProviderId.OPENAI, "gpt-5.5-pro"))
        assertEquals("CHEAPEST", ModelPricing.band(ProviderId.XAI, "grok-imagine-image"))
        assertTrue(ModelPricing.summary(ProviderId.OPENAI, "gpt-5")!!.contains("per 1M tokens"))
        assertTrue(ModelPricing.summary(ProviderId.XAI, "grok-imagine-image")!!.contains("per image"))
    }

    @Test
    fun `money is readable at every scale`() {
        assertEquals("$0", ModelPricing.money(0.0))
        assertEquals("$0.0042", ModelPricing.money(0.00423))
        assertEquals("$0.125", ModelPricing.money(0.125))
        assertEquals("$3.40", ModelPricing.money(3.4))
        assertEquals("$120", ModelPricing.money(120.0))
    }
}
