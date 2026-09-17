package com.narrate.app.ai

/** The four supported back ends. Each one is reachable with the player's own key. */
enum class ProviderId(val displayName: String, val keyHint: String) {
    OPENAI("OpenAI", "sk-..."),
    ANTHROPIC("Anthropic Claude", "sk-ant-..."),
    GOOGLE("Google Gemini", "AIza..."),
    XAI("xAI Grok", "xai-...");

    companion object {
        fun fromId(value: String?): ProviderId = entries.firstOrNull { it.name == value } ?: OPENAI
    }
}

/** What a model can be used for. Drives which pickers a model shows up in. */
data class ModelInfo(
    val id: String,
    val provider: ProviderId,
    val label: String = id,
    val supportsText: Boolean = true,
    val supportsImageGeneration: Boolean = false,
    val supportsImageReferences: Boolean = false,
    val note: String = ""
)

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.9,
    val stopSequences: List<String> = emptyList(),
    /**
     * How long to allow this one call. World building is legitimately slow on a reasoning
     * model, so it is given more room than a turn rather than the whole app waiting longer.
     */
    val timeoutSeconds: Int? = null,
    /**
     * Passed to reasoning models that accept it. Structured JSON work does not need deep
     * deliberation, and a model that spends its whole budget thinking returns nothing at all.
     */
    val reasoningEffort: String? = null
)

data class LlmResponse(
    val text: String,
    val model: String,
    val provider: ProviderId,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** The provider's own word for why generation stopped, as it spells it. */
    val finishReason: String = "",
    /** True when the reply was cut off at the token ceiling rather than finished. */
    val truncated: Boolean = false
)

/** A reference image handed to an image model so a subject keeps the same face across turns. */
data class ImageReference(val bytes: ByteArray, val label: String, val mimeType: String = "image/png") {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ImageReference && label == other.label && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * label.hashCode() + bytes.contentHashCode()
}

data class ImageRequest(
    val model: String,
    val prompt: String,
    val references: List<ImageReference> = emptyList(),
    val size: String = "1024x1024",
    val negativePrompt: String = ""
)

data class ImageResult(
    val bytes: ByteArray,
    val mimeType: String,
    val model: String,
    val provider: ProviderId,
    val usedReferences: Boolean
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ImageResult && model == other.model && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * model.hashCode() + bytes.contentHashCode()
}

class ProviderException(val provider: ProviderId, message: String, cause: Throwable? = null) :
    Exception("${provider.displayName}: $message", cause)

/**
 * The call succeeded but the model wrote nothing.
 *
 * Reasoning models can spend an entire response budget on hidden reasoning and return an
 * empty message, which looks identical to a hang from the outside. Distinguishing it lets
 * the caller retry with more room and explain itself if that still fails.
 */
class EmptyResponseException(
    val provider: ProviderId,
    val model: String,
    val finishReason: String,
    val outputTokens: Int
) : Exception(
    buildString {
        append("${provider.displayName}: $model returned no text")
        if (finishReason.isNotBlank()) append(" (finish reason: $finishReason)")
        append(". ")
        if (finishReason.equals("length", true) || finishReason.equals("max_tokens", true) ||
            finishReason.equals("MAX_TOKENS", true)
        ) {
            append(
                "Reasoning models can use the whole response budget before writing anything. " +
                    "Raise the response length limit in Settings, or pick a model that reasons less."
            )
        } else {
            append("Try again, or choose a different model in Settings.")
        }
    }
)

/**
 * Every provider implements the same shape so the rest of the app never branches on vendor.
 */
interface AiProvider {
    val id: ProviderId

    suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse

    suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult =
        throw ProviderException(id, "This provider does not support image generation.")

    /**
     * What this key can actually reach, asked of the provider itself. The curated [catalog]
     * is only a fallback for when there is no key yet or the listing call fails.
     */
    suspend fun listModels(apiKey: String): List<ModelInfo>

    fun catalog(): List<ModelInfo>
}
