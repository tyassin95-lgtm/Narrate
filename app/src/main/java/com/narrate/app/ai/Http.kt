package com.narrate.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP plumbing. Narration calls are long-running by nature, so the timeouts
 * are generous and failures are surfaced with the provider's own error text.
 */
object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun json(body: String): RequestBody = body.toRequestBody(JSON)

    suspend fun execute(provider: ProviderId, request: Request): String = withContext(Dispatchers.IO) {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (t: Throwable) {
            throw ProviderException(provider, "Network error: ${t.message ?: t::class.simpleName}", t)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ProviderException(provider, "HTTP ${it.code} - ${extractError(body)}")
            }
            body
        }
    }

    suspend fun executeBytes(provider: ProviderId, request: Request): Pair<ByteArray, String> =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request).execute()
            } catch (t: Throwable) {
                throw ProviderException(provider, "Network error: ${t.message ?: t::class.simpleName}", t)
            }
            response.use {
                val bytes = it.body?.bytes() ?: ByteArray(0)
                if (!it.isSuccessful) {
                    throw ProviderException(provider, "HTTP ${it.code} - ${extractError(String(bytes))}")
                }
                bytes to (it.body?.contentType()?.toString() ?: "image/png")
            }
        }

    /** Pull the human-readable message out of the many shapes of vendor error JSON. */
    private fun extractError(body: String): String {
        if (body.isBlank()) return "empty response"
        val message = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
        return (message ?: body).take(400).replace("\\n", " ")
    }
}
