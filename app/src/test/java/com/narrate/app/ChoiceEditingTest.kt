package com.narrate.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.Choice
import com.narrate.app.ui.play.PlayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A suggested action is a prompt, not a commitment. Tapping one loads its text for editing,
 * and what the world is told depends on whether the player changed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChoiceEditingTest {

    private lateinit var application: Application
    private lateinit var viewModel: PlayViewModel
    private val worldId = "world-choice"

    private class SilentProvider : AiProvider {
        override val id = ProviderId.OPENAI
        override suspend fun chat(request: LlmRequest, apiKey: String) =
            LlmResponse("===NARRATION===\nNothing.\n===CHOICES===\n- Wait\n===STATE===\n{}", request.model, id)
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(Dispatchers.Unconfined)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(application).clearAllTables()
        }
        ProviderRegistry.override(ProviderId.OPENAI, SilentProvider())
        val repo = WorldRepository(application)
        repo.saveWorld(WorldEntity(id = worldId, name = "Calder City", currentLocationId = "loc"))
        repo.saveLocation(LocationEntity(id = "loc", worldId = worldId, name = "Night Ward"))
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc")
        )
        viewModel = PlayViewModel(application, worldId)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
        Dispatchers.resetMain()
    }

    private val spoken = Choice("c0", "\"You knew I would come.\"", "test how much she has guessed", "SPEECH")
    private val plain = Choice("c1", "Ask her how long she has been waiting", kind = "ACTION")

    @Test
    fun `a choice becomes editable text rather than an instant submission`() {
        assertEquals(
            "\"You knew I would come.\" - test how much she has guessed",
            viewModel.choiceText(spoken)
        )
        assertEquals("Ask her how long she has been waiting", viewModel.choiceText(plain))
    }

    @Test
    fun `sending a suggestion untouched is still recorded as a choice`() {
        val text = viewModel.choiceText(plain)
        assertEquals("CHOICE", viewModel.kindFor(text, plain, speaking = false))
        // Whitespace the keyboard may add does not make it a different action.
        assertEquals("CHOICE", viewModel.kindFor("  $text  ", plain, speaking = false))
    }

    @Test
    fun `editing a suggestion makes it the player's own action`() {
        val edited = viewModel.choiceText(plain) + ", and watch her hands"
        assertEquals("ACTION", viewModel.kindFor(edited, plain, speaking = false))
        assertEquals("SPEECH", viewModel.kindFor(edited, plain, speaking = true))
    }

    @Test
    fun `typing from scratch is never attributed to a suggestion`() {
        assertEquals("ACTION", viewModel.kindFor("Leave through the service door", null, speaking = false))
        assertEquals("SPEECH", viewModel.kindFor("I know what you did", null, speaking = true))
    }

    @Test
    fun `an empty input submits nothing`() {
        viewModel.submit("   ", "ACTION")
        assertEquals(0, viewModel.state.value.turns.size)
    }
}
