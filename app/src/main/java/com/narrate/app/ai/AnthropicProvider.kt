package com.narrate.app.ai

import com.narrate.app.core.AppJson
import kotlinx.serialization.json.*
import okhttp3.Request

/** Anthropic Claude via the Messages API. Text only; image duties fall to another provider. */
class AnthropicProvider(private val baseUrl: String = "https://api.anthropic.com/v1") : AiProvider {

    override val id = ProviderId.ANTHROPIC

    override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
        val payload = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (request.system.isNotBlank()) put("system", request.system)
            put("messages", buildJsonArray {
                // Claude requires strictly alternating roles starting with user.
                normalize(request.messages).forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        })
                    })
                }
            })
            if (request.stopSequences.isNotEmpty()) {
                put("stop_sequences", buildJsonArray { request.stopSequences.forEach { add(it) } })
            }
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(Http.json(payload.toString()))
                .build()
        )
        val root = AppJson.parseToJsonElement(body).jsonObject
        val text = root["content"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .ifBlank { throw ProviderException(id, "No content returned.") }
        val usage = root["usage"]?.jsonObject
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return LlmResponse(
            text = text,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: request.model,
            provider = id,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            finishReason = stopReason,
            truncated = stopReason == "max_tokens"
        )
    }

    /** Merge same-role neighbours and make sure the conversation opens on a user turn. */
    private fun normalize(messages: List<ChatMessage>): List<ChatMessage> {
        val cleaned = messages.filter { it.content.isNotBlank() }
        if (cleaned.isEmpty()) return listOf(ChatMessage.user("Begin."))
        val result = mutableListOf<ChatMessage>()
        cleaned.forEach { message ->
            val last = result.lastOrNull()
            if (last != null && last.role == message.role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n\n" + message.content)
            } else {
                result += message
            }
        }
        if (result.first().role != "user") result.add(0, ChatMessage.user("Continue the world."))
        return result
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) return catalog()
        return runCatching {
            val body = Http.execute(
                id,
                Request.Builder()
                    .url("$baseUrl/models?limit=100")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .get()
                    .build()
            )
            val listed = AppJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    val modelId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ModelTaxonomy.describe(id, modelId, obj["display_name"]?.jsonPrimitive?.contentOrNull)
                }
            ModelTaxonomy.sort(listed).ifEmpty { catalog() }
        }.getOrElse { catalog() }
    }

    /** Fallback only, for before a key is entered. The live listing always wins. */
    override fun catalog(): List<ModelInfo> = ModelTaxonomy.sort(
        listOfNotNull(
            ModelTaxonomy.describe(id, "claude-fable-5-1", "Claude Fable 5.1"),
            ModelTaxonomy.describe(id, "claude-opus-5", "Claude Opus 5"),
            ModelTaxonomy.describe(id, "claude-sonnet-5", "Claude Sonnet 5"),
            ModelTaxonomy.describe(id, "claude-opus-4-8", "Claude Opus 4.8"),
            ModelTaxonomy.describe(id, "claude-sonnet-4-6", "Claude Sonnet 4.6"),
            ModelTaxonomy.describe(id, "claude-haiku-4-5", "Claude Haiku 4.5")
        )
    )

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
