package com.narrate.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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

    /** A client with a different call timeout, sharing this one's connection pool. */
    private fun clientFor(timeoutSeconds: Int?): OkHttpClient = when (timeoutSeconds) {
        null -> client
        else -> client.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    suspend fun execute(
        provider: ProviderId,
        request: Request,
        timeoutSeconds: Int? = null
    ): String {
        val response = call(provider, clientFor(timeoutSeconds).newCall(request), timeoutSeconds)
        return response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ProviderException(provider, "HTTP ${it.code} - ${extractError(body)}")
            }
            body
        }
    }

    suspend fun executeBytes(provider: ProviderId, request: Request): Pair<ByteArray, String> {
        val response = call(provider, client.newCall(request), timeoutSeconds = null)
        return response.use {
            val bytes = it.body?.bytes() ?: ByteArray(0)
            if (!it.isSuccessful) {
                throw ProviderException(provider, "HTTP ${it.code} - ${extractError(String(bytes))}")
            }
            bytes to (it.body?.contentType()?.toString() ?: "image/png")
        }
    }

    /**
     * Runs one call, and hangs up the moment the caller walks away.
     *
     * A blocking `execute()` inside `withContext` keeps running after its coroutine is
     * cancelled: the player who abandons a generation would still be paying for the tokens it
     * goes on to produce, and the thread stays held until the timeout. Enqueueing instead
     * means cancelling the coroutine cancels the request itself.
     */
    private suspend fun call(
        provider: ProviderId,
        call: Call,
        timeoutSeconds: Int?
    ): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.closeQuietly() }
                }

                override fun onFailure(call: Call, e: java.io.IOException) {
                    // A failure caused by our own cancellation is not worth reporting: the
                    // continuation is already gone and nobody is waiting for an explanation.
                    if (call.isCanceled()) {
                        continuation.cancel()
                    } else {
                        continuation.resumeWithException(
                            ProviderException(provider, describeNetworkFailure(e, timeoutSeconds), e)
                        )
                    }
                }
            })
        }
    }

    private fun Response.closeQuietly() = runCatching { close() }

    /** Says plainly when a call ran out of time rather than failing outright. */
    private fun describeNetworkFailure(t: Throwable, timeoutSeconds: Int?): String {
        val timedOut = t is java.net.SocketTimeoutException ||
            t is java.io.InterruptedIOException ||
            t.message?.contains("timeout", ignoreCase = true) == true
        if (!timedOut) return "Network error: ${t.message ?: t::class.simpleName}"
        val limit = timeoutSeconds ?: 360
        return "The request took longer than ${limit / 60} minutes and was given up on. " +
            "The model may be overloaded, or this may be a slow reasoning model - try again, " +
            "or choose a faster model in Settings."
    }

    /** Pull the human-readable message out of the many shapes of vendor error JSON. */
    private fun extractError(body: String): String {
        if (body.isBlank()) return "empty response"
        val message = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?: Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
        return (message ?: body).take(400).replace("\\n", " ")
    }
}
