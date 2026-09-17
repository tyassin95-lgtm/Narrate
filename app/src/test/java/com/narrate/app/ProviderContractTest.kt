package com.narrate.app

import com.narrate.app.ai.AnthropicProvider
import com.narrate.app.ai.ChatMessage
import com.narrate.app.ai.GeminiProvider
import com.narrate.app.ai.Http
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.OpenAiProvider
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.XaiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What actually goes out on the wire.
 *
 * Every provider has its own rules about what a request may contain, and breaking one of them
 * is a 400 for the player rather than a story. These run against a real socket so the payload
 * under test is the payload the provider would receive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderContractTest {

    private lateinit var server: ServerSocket
    private lateinit var loop: Thread
    private val bodies = mutableListOf<String>()
    private val paths = mutableListOf<String>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    /** Held closed by a handler that is asked to stall, so a cancellation can be observed. */
    private var stall: CountDownLatch? = null
    private val served = CountDownLatch(1)

    private val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    private val openAiReply = """
        {"model": "gpt-6-astra", "choices": [{"message": {"content": "Once."}, "finish_reason": "stop"}],
         "usage": {"prompt_tokens": 10, "completion_tokens": 4}}
    """.trimIndent()

    /**
     * A socket that speaks just enough HTTP to be a provider.
     *
     * The point of these tests is the bytes that leave the app, so the request is read off a
     * real connection rather than from a stubbed client that could quietly agree with itself.
     */
    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        loop = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: return@thread
                runCatching { serve(client) }
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.append(byte.toChar())
        }
        val headers = head.toString()
        paths += headers.lineSequence().first().split(' ').getOrElse(1) { "" }
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        bodies += String(body, Charsets.UTF_8)
        served.countDown()
        stall?.await(20, TimeUnit.SECONDS)

        val (code, payload) = replies.removeFirstOrNull() ?: (200 to openAiReply)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(
                ("HTTP/1.1 $code OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            write(bytes)
            flush()
        }
    }

    @After
    fun tearDown() {
        stall?.countDown()
        server.close()
    }

    private fun request(model: String, temperature: Double = 0.9, reasoning: String? = null) = LlmRequest(
        model = model,
        system = "You narrate.",
        messages = listOf(ChatMessage.user("Begin.")),
        maxTokens = 4000,
        temperature = temperature,
        reasoningEffort = reasoning
    )

    @Test
    fun `a reasoning model gets the parameters it accepts and none it refuses`() = runBlocking {
        // gpt-6-astra is in Narrate's own catalog, so the player can pick it. A model the app
        // offers has to be a model the app can address.
        OpenAiProvider(baseUrl).chat(request("gpt-6-astra", reasoning = "low"), "key")

        val body = bodies.single()
        assertTrue("it must use the newer token parameter", body.contains("max_completion_tokens"))
        assertFalse("and not the older one", body.contains("\"max_tokens\""))
        assertFalse("reasoning models reject temperature outright", body.contains("temperature"))
        assertTrue(body.contains("\"reasoning_effort\":\"low\""))
    }

    @Test
    fun `an older model still gets the parameters it expects`() = runBlocking {
        OpenAiProvider(baseUrl).chat(request("gpt-4o"), "key")

        val body = bodies.single()
        assertTrue(body.contains("\"max_tokens\""))
        assertFalse(body.contains("max_completion_tokens"))
        assertTrue(body.contains("temperature"))
    }

    @Test
    fun `a provider that objects to a parameter is answered rather than given up on`() = runBlocking {
        // An unknown family guessed wrong. The provider says so, and the same request is
        // rewritten the way it asked instead of the turn failing.
        replies += 400 to """{"error": {"message": "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}"""
        replies += 200 to openAiReply

        val response = OpenAiProvider(baseUrl).chat(request("granite-9"), "key")

        assertEquals("Once.", response.text)
        assertEquals(2, bodies.size)
        assertTrue("the first attempt used the older spelling", bodies[0].contains("\"max_tokens\""))
        assertTrue("the retry used the one it was told to", bodies[1].contains("max_completion_tokens"))
    }

    @Test
    fun `a failure that is not about a parameter is reported as it is`() {
        replies += 401 to """{"error": {"message": "Incorrect API key provided."}}"""

        val failure = runCatching { runBlocking { OpenAiProvider(baseUrl).chat(request("gpt-4o"), "key") } }
            .exceptionOrNull()
        assertTrue(failure is ProviderException)
        assertTrue(failure!!.message!!.contains("Incorrect API key"))
        assertEquals("a bad key is not worth retrying", 1, bodies.size)
    }

    @Test
    fun `claude is never sent a temperature it rejects`() = runBlocking {
        replies += 200 to """{"model": "claude-opus-5", "stop_reason": "end_turn",
            "content": [{"type": "text", "text": "Once."}],
            "usage": {"input_tokens": 9, "output_tokens": 3}}"""

        // 1.05 is what world generation asks for, and the narration slider goes to 1.5.
        AnthropicProvider(baseUrl).chat(request("claude-opus-5", temperature = 1.05), "key")

        val temperature = Regex("\"temperature\":([0-9.]+)").find(bodies.single())!!.groupValues[1].toDouble()
        assertTrue("Claude's ceiling is 1.0: $temperature", temperature <= 1.0)
    }

    @Test
    fun `gemini is never sent an empty conversation`() = runBlocking {
        replies += 200 to """{"candidates": [{"content": {"parts": [{"text": "Once."}]},
            "finishReason": "STOP"}], "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2}}"""

        GeminiProvider(baseUrl).chat(
            LlmRequest(
                model = "gemini-3.5-flash",
                system = "You narrate.",
                messages = listOf(ChatMessage.user("   ")),
                maxTokens = 2000
            ),
            "key"
        )

        val body = bodies.single()
        assertFalse("an empty contents array is a 400", body.contains("\"contents\":[]"))
        assertTrue(body.contains("\"text\":\"Begin.\""))
    }

    @Test
    fun `grok is addressed in the shape it shares with openai`() = runBlocking {
        replies += 200 to """{"model": "grok-4.6", "choices": [{"message": {"content": "Once."},
            "finish_reason": "stop"}], "usage": {"prompt_tokens": 4, "completion_tokens": 2}}"""

        val response = XaiProvider(baseUrl).chat(request("grok-4.6"), "key")

        assertEquals("Once.", response.text)
        assertTrue(bodies.single().contains("\"max_tokens\""))
        assertEquals("/chat/completions", paths.single())
    }

    @Test
    fun `abandoning a generation hangs up on the provider instead of paying for it`() = runBlocking<Unit> {
        // A player who cancels should stop being billed. A blocking call would keep running to
        // completion with nobody listening, which is both a wasted turn and a held thread.
        stall = CountDownLatch(1)
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val call = scope.launch {
            runCatching {
                Http.execute(
                    ProviderId.OPENAI,
                    Request.Builder().url("$baseUrl/chat/completions")
                        .post(Http.json("""{"model":"gpt-4o"}"""))
                        .build()
                )
            }
        }

        assertTrue("the request should have reached the server", served.await(10, TimeUnit.SECONDS))
        call.cancel()
        withTimeout(10_000) { call.join() }
        assertTrue("the coroutine must not still be waiting on the response", call.isCancelled)
        stall?.countDown()
    }
}
