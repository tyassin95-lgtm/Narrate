package com.narrate.app.ai

import android.util.Base64
import com.narrate.app.core.AppJson
import kotlinx.serialization.json.*
import okhttp3.Request

/**
 * Google Gemini. Text uses generateContent. Image generation uses either a native image
 * model (which accepts inline reference images, giving true visual continuity) or Imagen.
 */
class GeminiProvider(private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta") : AiProvider {

    override val id = ProviderId.GOOGLE

    override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                request.messages.filter { it.content.isNotBlank() }.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", message.content) })
                        })
                    })
                }
            })
            if (request.system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.system) })
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
            })
            put("safetySettings", buildJsonArray {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    add(buildJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            })
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/models/${request.model}:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(Http.json(payload.toString()))
                .build()
        )
        val root = AppJson.parseToJsonElement(body).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
        if (text.isBlank()) {
            val reason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
                ?: root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw ProviderException(id, "No content returned" + (reason?.let { " (finish reason: $it)" } ?: "."))
        }
        val usage = root["usageMetadata"]?.jsonObject
        return LlmResponse(
            text = text,
            model = request.model,
            provider = id,
            inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    override suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult {
        if (apiKey.isBlank()) throw ProviderException(id, "No API key set. Add one in Settings.")
        return if (request.model.startsWith("imagen")) {
            imagen(request, apiKey)
        } else {
            nativeImage(request, apiKey)
        }
    }

    /** Native image models take the subject's existing portrait inline as a visual anchor. */
    private suspend fun nativeImage(request: ImageRequest, apiKey: String): ImageResult {
        val usedReferences = request.references.isNotEmpty()
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        request.references.take(3).forEach { reference ->
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", reference.mimeType)
                                    put("data", Base64.encodeToString(reference.bytes, Base64.NO_WRAP))
                                })
                            })
                        }
                        add(buildJsonObject { put("text", request.prompt.take(4000)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add("IMAGE") })
            })
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/models/${request.model}:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(Http.json(payload.toString()))
                .build()
        )
        val parts = AppJson.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val inline = parts.firstNotNullOfOrNull { part ->
            val obj = part.jsonObject
            (obj["inlineData"] ?: obj["inline_data"])?.jsonObject
        } ?: throw ProviderException(id, "No image data returned by ${request.model}.")
        val data = inline["data"]?.jsonPrimitive?.contentOrNull
            ?: throw ProviderException(id, "Image part contained no data.")
        val mime = (inline["mimeType"] ?: inline["mime_type"])?.jsonPrimitive?.contentOrNull ?: "image/png"
        return ImageResult(Base64.decode(data, Base64.DEFAULT), mime, request.model, id, usedReferences)
    }

    private suspend fun imagen(request: ImageRequest, apiKey: String): ImageResult {
        val payload = buildJsonObject {
            put("instances", buildJsonArray {
                add(buildJsonObject { put("prompt", request.prompt.take(4000)) })
            })
            put("parameters", buildJsonObject {
                put("sampleCount", 1)
                put("aspectRatio", if (request.size.startsWith("1792")) "16:9" else "1:1")
            })
        }
        val body = Http.execute(
            id,
            Request.Builder()
                .url("$baseUrl/models/${request.model}:predict")
                .addHeader("x-goog-api-key", apiKey)
                .post(Http.json(payload.toString()))
                .build()
        )
        val prediction = AppJson.parseToJsonElement(body).jsonObject["predictions"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ProviderException(id, "No image returned.")
        val data = (prediction["bytesBase64Encoded"] ?: prediction["image"])?.jsonPrimitive?.contentOrNull
            ?: throw ProviderException(id, "Image prediction contained no bytes.")
        return ImageResult(Base64.decode(data, Base64.DEFAULT), "image/png", request.model, id, usedReferences = false)
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) return catalog()
        return runCatching {
            val body = Http.execute(
                id,
                Request.Builder().url("$baseUrl/models?pageSize=200").addHeader("x-goog-api-key", apiKey).get().build()
            )
            AppJson.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val raw = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val modelId = raw.removePrefix("models/")
                val methods = obj["supportedGenerationMethods"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                val isImage = modelId.contains("image") || modelId.startsWith("imagen")
                if (!isImage && methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
                ModelInfo(
                    id = modelId,
                    provider = id,
                    label = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
                    supportsText = !isImage,
                    supportsImageGeneration = isImage,
                    supportsImageReferences = isImage && !modelId.startsWith("imagen")
                )
            }.sortedBy { it.id }
        }.getOrElse { catalog() }
    }

    override fun catalog(): List<ModelInfo> = listOf(
        ModelInfo("gemini-2.5-pro", id, "Gemini 2.5 Pro", note = "Flagship narration"),
        ModelInfo("gemini-2.5-flash", id, "Gemini 2.5 Flash", note = "Fast simulation"),
        ModelInfo("gemini-2.0-flash", id, "Gemini 2.0 Flash"),
        ModelInfo(
            "gemini-2.5-flash-image", id, "Gemini 2.5 Flash Image",
            supportsText = false, supportsImageGeneration = true, supportsImageReferences = true,
            note = "Supports reference images"
        ),
        ModelInfo("imagen-4.0-generate-001", id, "Imagen 4", supportsText = false, supportsImageGeneration = true)
    )
}
