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

    /**
     * How this model wants its request written.
     *
     * OpenAI changed the parameter names with the reasoning families, and the newer ones
     * reject the older spelling outright. Guessing from the model id gets it right for
     * everything known today; the retry below gets it right for everything else, because a
     * model the app has never heard of must not be a model the app cannot talk to.
     */
    private data class Shape(
        val completionTokens: Boolean,
        val temperature: Boolean,
        val reasoning: Boolean
    )

    override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
        var shape = Shape(
            completionTokens = usesCompletionTokens(request.model),
            temperature = !usesCompletionTokens(request.model),
            reasoning = request.reasoningEffort != null
        )
        var lastFailure: ProviderException? = null
        repeat(MAX_SHAPE_ATTEMPTS) {
            try {
                return send(request, apiKey, shape)
            } catch (e: ProviderException) {
                // Only an objection to a parameter is worth another attempt. Anything else -
                // a bad key, a rate limit, a model that does not exist - is the real answer.
                val adapted = adapt(shape, e.message.orEmpty()) ?: throw e
                lastFailure = e
                shape = adapted
            }
        }
        throw lastFailure ?: ProviderException(id, "The request could not be shaped for ${request.model}.")
    }

    /**
     * True for the families that take `max_completion_tokens` and refuse `temperature`:
     * the o-series, and gpt-5 and everything after it.
     */
    private fun usesCompletionTokens(model: String): Boolean {
        if (Regex("^o\\d").containsMatchIn(model)) return true
        val generation = Regex("^gpt-(\\d+)").find(model)?.groupValues?.get(1)?.toIntOrNull()
        return generation != null && generation >= 5
    }

    /** The same request, written the way the provider's own complaint asks for. */
    private fun adapt(shape: Shape, message: String): Shape? {
        val complaint = message.lowercase()
        val fixed = when {
            !shape.completionTokens && complaint.contains("max_completion_tokens") ->
                shape.copy(completionTokens = true, temperature = false)
            shape.completionTokens && complaint.contains("max_tokens") &&
                !complaint.contains("max_completion_tokens") ->
                shape.copy(completionTokens = false, temperature = true)
            shape.temperature && complaint.contains("temperature") -> shape.copy(temperature = false)
            shape.reasoning && complaint.contains("reasoning_effort") -> shape.copy(reasoning = false)
            else -> null
        }
        return fixed?.takeIf { it != shape }
    }

    private suspend fun send(
        request: LlmRequest,
        apiKey: String,
        shape: Shape
    ): LlmResponse {
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
        val payload = buildJsonObject {
            put("model", request.model)
            put("messages", messages)
            if (shape.completionTokens) {
                put("max_completion_tokens", request.maxTokens)
            } else {
                put("max_tokens", request.maxTokens)
            }
            if (shape.temperature) put("temperature", request.temperature)
            if (shape.reasoning && request.reasoningEffort != null) {
                put("reasoning_effort", request.reasoningEffort)
            }
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(Http.json(payload.toString()))
                .build(),
            timeoutSeconds = request.timeoutSeconds
        )
        val root = AppJson.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull.orEmpty()
        val usage = root["usage"]?.jsonObject
        val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) {
            throw EmptyResponseException(
                provider = id,
                model = request.model,
                finishReason = finishReason,
                outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
            )
        }
        return LlmResponse(
            text = text,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: request.model,
            provider = id,
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            finishReason = finishReason,
            truncated = finishReason == "length"
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
            val listed = AppJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element ->
                    val modelId = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    ModelTaxonomy.describe(id, modelId)
                }
            ModelTaxonomy.sort(listed).ifEmpty { catalog() }
        }.getOrElse { catalog() }
    }

    /** Fallback only, for before a key is entered. The live listing always wins. */
    override fun catalog(): List<ModelInfo> = ModelTaxonomy.sort(
        listOfNotNull(
            ModelTaxonomy.describe(id, "gpt-6-astra"),
            ModelTaxonomy.describe(id, "gpt-5.6-sol"),
            ModelTaxonomy.describe(id, "gpt-5.6-terra"),
            ModelTaxonomy.describe(id, "gpt-5.6-luna"),
            ModelTaxonomy.describe(id, "gpt-5.4"),
            ModelTaxonomy.describe(id, "gpt-5.4-mini"),
            ModelTaxonomy.describe(id, "gpt-5.1"),
            ModelTaxonomy.describe(id, "gpt-5-mini"),
            ModelTaxonomy.describe(id, "gpt-image-2"),
            ModelTaxonomy.describe(id, "gpt-image-1.5"),
            ModelTaxonomy.describe(id, "gpt-image-1"),
            ModelTaxonomy.describe(id, "gpt-image-1-mini")
        )
    )

    private fun requireKey(apiKey: String) {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
    }

    private companion object {
        /** One attempt per parameter the provider might object to, and no more. */
        const val MAX_SHAPE_ATTEMPTS = 4
        const val MAX_PROMPT = 4000
        const val MAX_REFERENCES = 4
    }
}
