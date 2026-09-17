package com.narrate.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.*
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.WorldForge
import com.narrate.app.ui.create.CreateStep
import com.narrate.app.ui.create.CreateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * World generation must behave the same whichever provider is behind it.
 *
 * The reported failure was GPT-5.6 Luna appearing to hang and produce nothing while Grok
 * worked. A reasoning model that spends its whole budget thinking returns an empty message,
 * which is indistinguishable from a hang unless the app names it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationRobustnessTest {

    private lateinit var application: Application
    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var forge: WorldForge
    private val scripted = ScriptedProvider()

    /** A model that can be told to answer, to answer with nothing, or to fail outright. */
    private class ScriptedProvider : AiProvider {
        override val id = ProviderId.OPENAI
        private val replies = ArrayDeque<Result<String>>()
        val requests = mutableListOf<LlmRequest>()

        fun enqueue(text: String) = replies.addLast(Result.success(text))
        fun enqueueEmpty(finishReason: String = "length") = replies.addLast(
            Result.failure(EmptyResponseException(ProviderId.OPENAI, "gpt-5.6-luna", finishReason, 8000))
        )
        fun enqueueFailure(t: Throwable) = replies.addLast(Result.failure(t))

        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            requests += request
            val reply = replies.removeFirstOrNull() ?: Result.success("{}")
            return LlmResponse(reply.getOrThrow(), request.model, id, finishReason = "stop")
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("gpt-5.6-luna", ProviderId.OPENAI))
    }

    private val conceptJson = """
        {"name": "Calder City", "genre": "Slice of life", "tone": "quiet",
         "premise": "A teaching hospital.", "history": "", "rules": "", "themes": "",
         "art_style": "", "opening_situation": "A night shift."}
    """.trimIndent()

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(Dispatchers.Unconfined)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(application).clearAllTables()
        }
        repo = WorldRepository(application)
        settings = application.container.settings
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "gpt-5.6-luna")
        ProviderRegistry.override(ProviderId.OPENAI, scripted)
        forge = WorldForge(repo, settings)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
        Dispatchers.resetMain()
    }

    @Test
    fun `a model that returns nothing is retried with real room before giving up`() = runBlocking {
        scripted.enqueueEmpty()
        scripted.enqueue(conceptJson)

        val result = forge.expandWorld("Calder City: a teaching hospital.", PlayStyle.GENTLE)
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)
        assertEquals("Calder City", result.getOrThrow().name)

        assertEquals("it should take exactly two attempts", 2, scripted.requests.size)
        assertTrue(
            "the retry must give the model more room than the attempt that returned nothing",
            scripted.requests[1].maxTokens > scripted.requests[0].maxTokens
        )
    }

    @Test
    fun `a model that returns nothing twice fails with an explanation, not silence`() = runBlocking {
        scripted.enqueueEmpty()
        scripted.enqueueEmpty()

        val result = forge.expandWorld("Calder City: a teaching hospital.", PlayStyle.GENTLE)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("it must name the model", message.contains("gpt-5.6-luna"))
        assertTrue("and why nothing came back", message.contains("returned no text"))
        assertTrue("and what to do about it", message.contains("Raise the response length limit"))
    }

    @Test
    fun `creation asks for more time and less deliberation than a turn`() = runBlocking {
        scripted.enqueue(conceptJson)
        forge.expandWorld("Calder City: a teaching hospital.", PlayStyle.GENTLE)

        val request = scripted.requests.single()
        assertEquals("low", request.reasoningEffort)
        assertNotNull("creation gets its own timeout", request.timeoutSeconds)
        assertTrue("and a generous one", request.timeoutSeconds!! >= 600)
    }

    @Test
    fun `an empty reply explains itself differently when the model simply stopped`() {
        val stopped = EmptyResponseException(ProviderId.OPENAI, "gpt-5.6-luna", "stop", 0)
        assertTrue(stopped.message!!.contains("Try again, or choose a different model"))
        assertTrue(!stopped.message!!.contains("Raise the response length limit"))

        val starved = EmptyResponseException(ProviderId.ANTHROPIC, "claude-opus-5", "max_tokens", 4000)
        assertTrue(starved.message!!.contains("Raise the response length limit"))
    }

    @Test
    fun `a failed build returns the player to their character instead of a permanent spinner`() {
        scripted.enqueueFailure(ProviderException(ProviderId.OPENAI, "HTTP 500 - upstream error"))
        val viewModel = CreateViewModel(application)
        viewModel.setWorldPrompt("Calder City: a teaching hospital.")
        viewModel.editCharacter { it.copy(name = "Adrian Voss") }
        viewModel.build()

        val state = viewModel.state.value
        assertEquals(CreateStep.CHARACTER_DETAILS, state.step)
        assertTrue("the spinner must stop", !state.generating)
        assertTrue("and say what went wrong", state.error!!.contains("upstream error"))
    }

    @Test
    fun `cancelling when nothing is running does not invent an error`() {
        val viewModel = CreateViewModel(application)
        viewModel.setWorldPrompt("Calder City")
        viewModel.cancelGeneration()
        val state = viewModel.state.value
        assertTrue(!state.generating)
        assertEquals(null, state.error)
    }

    @Test
    fun `a generation in flight can be abandoned and the screen recovers`() = runBlocking {
        // A provider that never answers stands in for a model that is taking too long.
        val stalling = object : AiProvider {
            override val id = ProviderId.OPENAI
            override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun listModels(apiKey: String) = catalog()
            override fun catalog() = listOf(ModelInfo("gpt-5.6-luna", ProviderId.OPENAI))
        }
        ProviderRegistry.override(ProviderId.OPENAI, stalling)

        val viewModel = CreateViewModel(application)
        viewModel.setWorldPrompt("Calder City: a teaching hospital.")
        viewModel.editCharacter { it.copy(name = "Adrian Voss") }
        viewModel.build()
        assertTrue("the build should be waiting on the model", viewModel.state.value.generating)
        assertEquals(CreateStep.BUILDING, viewModel.state.value.step)

        viewModel.cancelGeneration()
        val state = viewModel.state.value
        assertTrue("the spinner must stop", !state.generating)
        assertEquals("and the player is put back where they can act", CreateStep.CHARACTER_DETAILS, state.step)
        assertEquals("Generation cancelled.", state.error)
    }

    @Test
    fun `world building survives a provider that answers on the second attempt`() = runBlocking {
        scripted.enqueueEmpty()
        scripted.enqueue(
            """
            {"starting_location": "Night Ward", "starting_time": "Day 1, night",
             "locations": [{"name": "Night Ward", "type": "ROOM", "description": "Curtained bays."}],
             "characters": [], "factions": [], "threads": [], "established_facts": []}
            """.trimIndent()
        )
        val build = forge.buildWorld(
            concept = com.narrate.app.engine.WorldConcept(name = "Calder City"),
            customPrompt = "Calder City: a teaching hospital.",
            character = com.narrate.app.engine.CharacterConcept(name = "Adrian Voss"),
            playStyle = PlayStyle.GENTLE
        )
        assertTrue(build.exceptionOrNull()?.message ?: "ok", build.isSuccess)
        assertEquals("Night Ward", build.getOrThrow().locations.single().name)
    }

    @Test
    fun `usage is still recorded when a generation needed two attempts`() = runBlocking {
        scripted.enqueueEmpty()
        scripted.enqueue(conceptJson)
        forge.expandWorld("Calder City: a teaching hospital.", PlayStyle.GENTLE)
        // Only the attempt that produced text is billed as usage; the empty one threw.
        assertEquals(1, repo.usageDao.forWorld("").size)
    }

    @Test
    fun `a world entity created before this change still generates`() = runBlocking {
        scripted.enqueue("""{"name": "Adrian Voss", "role": "resident"}""")
        val result = forge.expandCharacter(WorldEntity(name = "Calder City"), "Adrian Voss: a resident.")
        assertTrue(result.isSuccess)
        assertEquals("Adrian Voss", result.getOrThrow().name)
    }
}
