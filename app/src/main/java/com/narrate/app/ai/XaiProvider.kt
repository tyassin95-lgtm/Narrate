package com.narrate.app.ai

import android.util.Base64
import com.narrate.app.core.AppJson
import kotlinx.serialization.json.*
import okhttp3.Request

/** xAI Grok. The chat surface is OpenAI-compatible; images use xAI's own generation endpoint. */
class XaiProvider(private val baseUrl: String = "https://api.x.ai/v1") : AiProvider {

    override val id = ProviderId.XAI

    override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
        val payload = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("messages", buildJsonArray {
                if (request.system.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    })
                }
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(Http.json(payload.toString()))
                .build()
        )
        val root = AppJson.parseToJsonElement(body).jsonObject
        val text = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw ProviderException(id, "No content returned.")
        val usage = root["usage"]?.jsonObject
        return LlmResponse(
            text = text,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: request.model,
            provider = id,
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    override suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
        val payload = buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt.take(3800))
            put("n", 1)
            put("response_format", "b64_json")
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/images/generations")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(Http.json(payload.toString()))
                .build()
        )
        val first = AppJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderException(id, "No image returned.")
        val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull
        val bytes = if (b64 != null) {
            Base64.decode(b64, Base64.DEFAULT)
        } else {
            val url = first["url"]?.jsonPrimitive?.contentOrNull
                ?: throw ProviderException(id, "Image response contained neither data nor a url.")
            Http.executeBytes(id, Request.Builder().url(url).get().build()).first
        }
        return ImageResult(bytes, "image/png", request.model, id, usedReferences = false)
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) return catalog()
        return runCatching {
            val body = Http.execute(
                id,
                Request.Builder().url("$baseUrl/models").addHeader("Authorization", "Bearer $apiKey").get().build()
            )
            AppJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                val modelId = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val isImage = modelId.contains("image")
                ModelInfo(modelId, id, supportsText = !isImage, supportsImageGeneration = isImage)
            }.sortedBy { it.id }
        }.getOrElse { catalog() }
    }

    override fun catalog(): List<ModelInfo> = listOf(
        ModelInfo("grok-4", id, "Grok 4", note = "Flagship narration"),
        ModelInfo("grok-4-fast", id, "Grok 4 Fast", note = "Fast simulation"),
        ModelInfo("grok-3", id, "Grok 3"),
        ModelInfo("grok-3-mini", id, "Grok 3 mini"),
        ModelInfo("grok-2-image-1212", id, "Grok 2 Image", supportsText = false, supportsImageGeneration = true)
    )
}
