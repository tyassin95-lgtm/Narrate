package com.narrate.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.ConceptCompleteness
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.TurnParser
import com.narrate.app.engine.WorldConcept
import com.narrate.app.engine.WorldForge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creation must produce a complete sheet, not whichever fields the model felt like writing.
 *
 * Models drop fields, and which ones they drop changes from run to run - which is exactly the
 * randomness the player sees as "sometimes the backstory is empty". The gaps are named, asked
 * for on their own, and merged in without ever overwriting something already written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreationCompletenessTest {

    private lateinit var application: Application
    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var forge: WorldForge
    private val scripted = ScriptedProvider()

    private val authoredWorld = """
        Eastgate Rotations: a teaching hospital and the rentals around it. The Gallery is the
        third-floor corridor where the residents sleep between shifts.
    """.trimIndent()

    private val authoredCharacter =
        "Adrian Voss: dark hair, striking green eyes, wellkept short stubble. Last year of residency."

    private class ScriptedProvider : AiProvider {
        override val id = ProviderId.OPENAI
        private val queue = ArrayDeque<Result<String>>()
        val prompts = mutableListOf<String>()
        fun enqueue(text: String) = queue.addLast(Result.success(text))
        fun enqueueFailure() = queue.addLast(
            Result.failure(ProviderException(ProviderId.OPENAI, "HTTP 503 - upstream unavailable"))
        )
        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            prompts += request.system + "\n" + request.messages.joinToString("\n") { it.content }
            val reply = queue.removeFirstOrNull() ?: Result.success("{}")
            return LlmResponse(reply.getOrThrow(), request.model, id, finishReason = "stop")
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    private val completeWorldJson = """
        {"name": "Eastgate Rotations", "tagline": "Nights on the ward", "genre": "medical drama",
         "tone": "quiet", "premise": "A teaching hospital under strain.",
         "history": "Founded in 1911 as a fever hospital.", "rules": "No magic; medicine is ordinary.",
         "themes": "Care and exhaustion.", "art_style": "Muted film still",
         "opening_situation": "A night shift on the Night Ward."}
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
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        ProviderRegistry.override(ProviderId.OPENAI, scripted)
        forge = WorldForge(repo, settings)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
        Dispatchers.resetMain()
    }

    @Test
    fun `a complete generation is used as it stands`() = runBlocking {
        scripted.enqueue(completeWorldJson)
        val world = forge.expandWorld(authoredWorld, PlayStyle.GENTLE).getOrThrow()

        assertEquals("nothing is missing, so nothing more is asked for", 1, scripted.prompts.size)
        assertTrue(ConceptCompleteness.missingWorldFields(world).isEmpty())
    }

    @Test
    fun `fields the model dropped are asked for and filled in`() = runBlocking {
        scripted.enqueue(
            """
            {"name": "Eastgate Rotations", "genre": "medical drama", "tone": "quiet",
             "premise": "A teaching hospital under strain.",
             "opening_situation": "A night shift on the Night Ward."}
            """.trimIndent()
        )
        scripted.enqueue(
            """
            {"tagline": "Nights on the ward", "history": "Founded in 1911 as a fever hospital.",
             "rules": "No magic; medicine is ordinary.", "themes": "Care and exhaustion.",
             "art_style": "Muted film still"}
            """.trimIndent()
        )

        val world = forge.expandWorld(authoredWorld, PlayStyle.GENTLE).getOrThrow()

        assertTrue(
            "every expected field ends up populated: " +
                ConceptCompleteness.missingWorldFields(world).joinToString(),
            ConceptCompleteness.missingWorldFields(world).isEmpty()
        )
        assertEquals("Founded in 1911 as a fever hospital.", world.history)
        assertEquals("what was already there is untouched", "medical drama", world.genre)

        // The top-up asks for the gaps only, and says what is already settled.
        val topUp = scripted.prompts[1]
        assertTrue(topUp.contains("history"))
        assertTrue(topUp.contains("tagline"))
        assertTrue("it must not invite a rewrite of the premise", topUp.contains("may not be changed"))
        assertTrue(topUp.contains("A teaching hospital under strain."))
        assertTrue("and the player's own text stays in front of it", topUp.contains("The Gallery"))
    }

    @Test
    fun `a top-up never overwrites a field that already has a value`() {
        val base = WorldConcept(name = "Eastgate Rotations", genre = "medical drama", history = "Founded in 1911.")
        val patch = WorldConcept(name = "Saint Mercy", genre = "space opera", themes = "Care and exhaustion.")
        val merged = ConceptCompleteness.mergeWorld(base, patch)

        assertEquals("Eastgate Rotations", merged.name)
        assertEquals("medical drama", merged.genre)
        assertEquals("Founded in 1911.", merged.history)
        assertEquals("only the blank field takes the patch", "Care and exhaustion.", merged.themes)
    }

    @Test
    fun `a reply that was cut off keeps the fields that did arrive`() = runBlocking {
        // The model ran out of room in the middle of "history".
        scripted.enqueue(
            """
            {"name": "Eastgate Rotations", "tagline": "Nights on the ward", "genre": "medical drama",
             "tone": "quiet", "premise": "A teaching hospital under strain.",
             "history": "Founded in 1911 as a fever ho
            """.trimIndent()
        )
        scripted.enqueue("""{"history": "Founded in 1911 as a fever hospital.", "rules": "No magic.",
            "themes": "Care and exhaustion.", "art_style": "Muted film still",
            "opening_situation": "A night shift."}""")

        val world = forge.expandWorld(authoredWorld, PlayStyle.GENTLE).getOrThrow()

        assertEquals("Eastgate Rotations", world.name)
        assertEquals("what arrived before the cut is kept", "A teaching hospital under strain.", world.premise)
        assertTrue(ConceptCompleteness.missingWorldFields(world).isEmpty())
    }

    @Test
    fun `a half-written object is closed rather than thrown away`() {
        val salvaged = TurnParser.salvageJsonObject(
            """{"name": "Eastgate", "premise": "A hospital.", "history": "Founded in 1911 as a fever ho"""
        )
        assertNotNull(salvaged)
        val concept = com.narrate.app.core.AppJson.decodeFromString(WorldConcept.serializer(), salvaged!!)
        assertEquals("Eastgate", concept.name)
        assertEquals("A hospital.", concept.premise)

        // A reply with no object in it at all is still honestly nothing.
        assertNull(TurnParser.salvageJsonObject("I'm sorry, I can't help with that."))
    }

    @Test
    fun `a failed top-up leaves the generated world intact`() = runBlocking {
        scripted.enqueue(
            """
            {"name": "Eastgate Rotations", "genre": "medical drama", "tone": "quiet",
             "premise": "A teaching hospital under strain."}
            """.trimIndent()
        )
        scripted.enqueueFailure()

        val result = forge.expandWorld(authoredWorld, PlayStyle.GENTLE)
        assertTrue("an incomplete world is still the player's world", result.isSuccess)
        val world = result.getOrThrow()
        assertEquals("Eastgate Rotations", world.name)
        assertEquals("A teaching hospital under strain.", world.premise)
    }

    @Test
    fun `character gaps are filled without touching the player's own words`() = runBlocking {
        scripted.enqueue(
            """
            {"name": "Adrian Voss", "role": "emergency medicine resident",
             "appearance": "Dark hair, striking green eyes, wellkept short stubble.",
             "summary": "In his last year of residency."}
            """.trimIndent()
        )
        scripted.enqueue(
            """
            {"personality": "Dry, watchful, slow to trust.", "backstory": "Raised in group homes.",
             "appearance": "Sandy hair, brown eyes.", "outfit": "Scrubs and a battered fleece.",
             "voice": "Quiet, precise.", "goals": "Finish the year.", "fears": "Being sent back.",
             "secrets": "He has not slept properly in a month.",
             "ties_to_world": "He sleeps in The Gallery between shifts."}
            """.trimIndent()
        )

        val character = forge.expandCharacter(
            WorldEntity(name = "Eastgate Rotations", authoredCanon = authoredWorld),
            authoredCharacter
        ).getOrThrow()

        assertTrue(ConceptCompleteness.missingCharacterFields(character).isEmpty())
        assertEquals("Adrian Voss", character.name)
        assertTrue(
            "the appearance the player wrote is never replaced by the top-up",
            character.appearance.contains("striking green eyes")
        )
        assertTrue("and the gaps are genuinely filled", character.backstory.contains("group homes"))
    }

    @Test
    fun `a suggestion the player picked is completed before the world is built`() = runBlocking {
        val viewModel = com.narrate.app.ui.create.CreateViewModel(application)
        // No authored text: the player took a suggested world and a suggested character, and
        // both arrived with gaps. This path never went through an expansion at all.
        viewModel.chooseWorldConcept(
            WorldConcept(name = "Eastgate Rotations", genre = "medical drama", premise = "A teaching hospital.")
        )
        viewModel.chooseCharacterConcept(
            CharacterConcept(name = "Adrian Voss", role = "resident", appearance = "Dark hair, green eyes.")
        )

        scripted.enqueue(
            """
            {"tagline": "Nights on the ward", "tone": "quiet", "history": "Founded in 1911.",
             "rules": "No magic.", "themes": "Care and exhaustion.", "art_style": "Muted film still",
             "opening_situation": "A night shift on the Night Ward."}
            """.trimIndent()
        )
        scripted.enqueue(
            """
            {"summary": "In his last year of residency.", "personality": "Dry, watchful.",
             "backstory": "Raised in group homes.", "outfit": "Scrubs.", "voice": "Quiet.",
             "goals": "Finish the year.", "fears": "Being sent back.", "secrets": "He barely sleeps.",
             "ties_to_world": "He sleeps in The Gallery between shifts."}
            """.trimIndent()
        )
        scripted.enqueue(
            """
            {"starting_location": "Night Ward", "starting_time": "Day 1, night",
             "locations": [{"name": "Night Ward", "type": "ROOM", "description": "Curtained bays."}],
             "characters": [], "factions": [], "threads": [], "established_facts": []}
            """.trimIndent()
        )

        viewModel.build()
        // The build writes to the database, so it finishes after this call returns.
        val settled = kotlinx.coroutines.withTimeout(10_000) {
            viewModel.state.first { it.createdWorldId != null || it.error != null }
        }

        val worldId = settled.createdWorldId
        assertNotNull(settled.error ?: "built", worldId)
        val saved = repo.worldDao.get(worldId!!)!!
        assertTrue("the saved world has its history", saved.history.isNotBlank())
        assertTrue(saved.themes.isNotBlank())
        assertTrue(saved.artStyle.isNotBlank())
        assertEquals("and what the player picked is unchanged", "medical drama", saved.genre)

        val player = repo.characterDao.player(worldId)!!
        assertTrue("the character sheet is complete too", player.backstory.isNotBlank())
        assertTrue(player.personality.isNotBlank())
        assertEquals("Dark hair, green eyes.", player.appearance)
    }

    @Test
    fun `an array that was cut off keeps the suggestions that finished`() = runBlocking {
        scripted.enqueue(
            """
            [{"name": "Eastgate Rotations", "genre": "medical drama", "premise": "A teaching hospital."},
             {"name": "The Gallery", "genre": "slice of life", "premise": "Four flats above a laundrette."},
             {"name": "Nightside", "genre": "quiet horror", "premise": "The ward af
            """.trimIndent()
        )
        val concepts = forge.worldConcepts("a hospital", playStyle = PlayStyle.GENTLE).getOrThrow()
        assertTrue("the finished suggestions survive the cut", concepts.size >= 2)
        assertEquals("Eastgate Rotations", concepts[0].name)
        assertEquals("The Gallery", concepts[1].name)
    }
}
