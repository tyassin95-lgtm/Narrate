package com.narrate.app.ai

import android.util.Base64
import com.narrate.app.core.AppJson
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI. Chat goes through /v1/chat/completions; images through /v1/images/generations,
 * or /v1/images/edits when reference images are supplied so recurring subjects keep their look.
 */
class OpenAiProvider(private val baseUrl: String = "https://api.openai.com/v1") : AiProvider {

    override val id = ProviderId.OPENAI

    override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
        requireKey(apiKey)
        val messages = buildJsonArray {
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
        }
        val usesMaxCompletionTokens = request.model.startsWith("gpt-5") ||
            request.model.startsWith("o1") || request.model.startsWith("o3") || request.model.startsWith("o4")
        val payload = buildJsonObject {
            put("model", request.model)
            put("messages", messages)
            if (usesMaxCompletionTokens) {
                put("max_completion_tokens", request.maxTokens)
            } else {
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
            }
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
        requireKey(apiKey)
        val supportsEdits = request.model.startsWith("gpt-image")
        return if (request.references.isNotEmpty() && supportsEdits) {
            editWithReferences(request, apiKey)
        } else {
            generateFresh(request, apiKey)
        }
    }

    private suspend fun generateFresh(request: ImageRequest, apiKey: String): ImageResult {
        val payload = buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt.take(MAX_PROMPT))
            put("n", 1)
            put("size", request.size)
            if (request.model.startsWith("gpt-image")) {
                put("quality", "high")
                put("output_format", "png")
            } else {
                put("response_format", "b64_json")
            }
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/images/generations")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(Http.json(payload.toString()))
                .build()
        )
        return decodeImage(body, request.model, usedReferences = false)
    }

    /** Image-to-image: the established portrait is sent back in so the face stays the same face. */
    private suspend fun editWithReferences(request: ImageRequest, apiKey: String): ImageResult {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", request.model)
            .addFormDataPart("prompt", request.prompt.take(MAX_PROMPT))
            .addFormDataPart("n", "1")
            .addFormDataPart("size", request.size)
            .addFormDataPart("quality", "high")
        request.references.take(MAX_REFERENCES).forEachIndexed { index, reference ->
            builder.addFormDataPart(
                "image[]",
                "reference_$index.png",
                reference.bytes.toRequestBody(reference.mimeType.toMediaType())
            )
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/images/edits")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(builder.build())
                .build()
        )
        return decodeImage(body, request.model, usedReferences = true)
    }

    private suspend fun decodeImage(body: String, model: String, usedReferences: Boolean): ImageResult {
        val first = AppJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderException(id, "No image returned.")
        val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull
        val bytes = when {
            b64 != null -> Base64.decode(b64, Base64.DEFAULT)
            else -> {
                val url = first["url"]?.jsonPrimitive?.contentOrNull
                    ?: throw ProviderException(id, "Image response contained neither data nor a url.")
                Http.executeBytes(id, Request.Builder().url(url).get().build()).first
            }
        }
        return ImageResult(bytes, "image/png", model, id, usedReferences)
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
                val isImage = modelId.contains("image") || modelId.startsWith("dall-e")
                ModelInfo(
                    id = modelId,
                    provider = id,
                    supportsText = !isImage,
                    supportsImageGeneration = isImage,
                    supportsImageReferences = modelId.startsWith("gpt-image")
                )
            }.sortedBy { it.id }
        }.getOrElse { catalog() }
    }

    override fun catalog(): List<ModelInfo> = listOf(
        ModelInfo("gpt-5", id, "GPT-5", note = "Flagship narration"),
        ModelInfo("gpt-5-mini", id, "GPT-5 mini", note = "Fast simulation"),
        ModelInfo("gpt-4.1", id, "GPT-4.1"),
        ModelInfo("gpt-4o", id, "GPT-4o"),
        ModelInfo("gpt-4o-mini", id, "GPT-4o mini"),
        ModelInfo("gpt-image-1", id, "GPT Image 1", supportsText = false, supportsImageGeneration = true, supportsImageReferences = true, note = "Supports reference images"),
        ModelInfo("dall-e-3", id, "DALL-E 3", supportsText = false, supportsImageGeneration = true)
    )

    private fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
    }

    private companion object {
        const val MAX_PROMPT = 4000
        const val MAX_REFERENCES = 4
    }
}
