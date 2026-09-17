package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.NewCharacter
import com.narrate.app.engine.NewLocation
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.WorldConcept
import com.narrate.app.engine.WorldForge
import kotlinx.coroutines.runBlocking
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
 * Where everybody is standing when the world opens.
 *
 * The reported failure: a new world put eight named characters on the same square of pavement
 * as the player. Their recorded locations were resolved by exact string match, and every name
 * that did not match landed on the player's own starting spot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorldPopulationTest {

    private lateinit var repo: WorldRepository
    private lateinit var forge: WorldForge

    private class Silent : AiProvider {
        override val id = ProviderId.OPENAI
        override suspend fun chat(request: LlmRequest, apiKey: String) =
            LlmResponse("{}", request.model, id, finishReason = "stop")
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
        val settings = SettingsStore(context)
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        ProviderRegistry.override(ProviderId.OPENAI, Silent())
        forge = WorldForge(repo, settings)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    private val opening =
        "Adrian is walking home down Maple Street when he nearly walks into Liv outside number 118."

    private fun places() = listOf(
        NewLocation(name = "Maple Street", type = "DISTRICT", description = "Terraced houses."),
        NewLocation(name = "Calder General Hospital", type = "BUILDING", description = "The teaching hospital."),
        NewLocation(name = "The Night Ward", type = "ROOM", parent = "Calder General Hospital"),
        NewLocation(name = "Eastgate Rentals", type = "BUILDING", description = "Four flats.")
    )

    private suspend fun build(characters: List<NewCharacter>, start: String = "Maple Street") =
        forge.persist(
            concept = WorldConcept(
                name = "Calder City",
                premise = "A teaching hospital and the streets around it.",
                openingSituation = opening
            ),
            customPrompt = "Calder City: a teaching hospital.",
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss", role = "resident"),
            characterPrompt = "Adrian Voss: a resident.",
            build = WorldForge.WorldBuildOutcome(
                concept = WorldConcept(name = "Calder City", openingSituation = opening),
                locations = places(),
                characters = characters,
                factions = emptyList(),
                threads = emptyList(),
                startingLocation = start,
                startingTime = "Day 1, morning",
                facts = emptyList()
            ),
            playStyle = PlayStyle.GENTLE
        )

    @Test
    fun `a place written slightly differently is still that place`() = runBlocking {
        val world = build(
            listOf(
                NewCharacter(name = "Mara Ellison", role = "attending", location = "the Night Ward", importance = 4),
                NewCharacter(name = "Noah Pike", role = "porter", location = "calder general hospital", importance = 3)
            )
        )
        val cast = repo.characterDao.all(world.id).filter { !it.isPlayer }
        val ward = repo.locationDao.all(world.id).first { it.name == "The Night Ward" }
        val hospital = repo.locationDao.all(world.id).first { it.name == "Calder General Hospital" }

        assertEquals(ward.id, cast.first { it.name == "Mara Ellison" }.currentLocationId)
        assertEquals(hospital.id, cast.first { it.name == "Noah Pike" }.currentLocationId)
        assertTrue(
            "nobody is dumped on the player's doorstep",
            cast.none { it.currentLocationId == world.currentLocationId }
        )
    }

    @Test
    fun `somewhere the builder named but never declared is written onto the map`() = runBlocking {
        val world = build(
            listOf(NewCharacter(name = "Dr Sasha Vole", role = "pathologist", location = "The Morgue", importance = 3))
        )
        val morgue = repo.locationDao.all(world.id).firstOrNull { it.name == "The Morgue" }
        assertNotNull("the place exists rather than the person being moved", morgue)
        assertEquals(
            morgue!!.id,
            repo.characterDao.all(world.id).first { it.name == "Dr Sasha Vole" }.currentLocationId
        )
    }

    @Test
    fun `a world does not open with eight people standing on the pavement`() = runBlocking {
        // Every one of them placed where the player starts, which is what the failure looked like.
        val crowd = (1..8).map { index ->
            NewCharacter(
                name = "Neighbour $index",
                role = "resident of Maple Street",
                location = "Maple Street",
                homeLocation = "Eastgate Rentals",
                importance = 3
            )
        } + NewCharacter(
            name = "Liv Mercer", role = "designer", location = "Maple Street", importance = 4
        )

        val world = build(crowd)
        val cast = repo.characterDao.all(world.id).filter { !it.isPlayer }
        val here = cast.filter { it.currentLocationId == world.currentLocationId }

        assertEquals("everyone is still in the world", 9, cast.size)
        assertTrue("but not all of them are in the opening shot: ${here.map { it.name }}", here.size <= 2)
        assertTrue(
            "the person the opening scene names is one of the two",
            here.any { it.name == "Liv Mercer" }
        )
        assertTrue("and the rest are somewhere of their own", cast.all { it.currentLocationId != null })
    }

    @Test
    fun `a small opening cast is left exactly as the builder placed it`() = runBlocking {
        val world = build(
            listOf(
                NewCharacter(name = "Liv Mercer", role = "designer", location = "Maple Street", importance = 4),
                NewCharacter(name = "Mara Ellison", role = "attending", location = "The Night Ward", importance = 4)
            )
        )
        val cast = repo.characterDao.all(world.id).filter { !it.isPlayer }
        assertEquals(
            listOf("Liv Mercer"),
            cast.filter { it.currentLocationId == world.currentLocationId }.map { it.name }
        )
    }
}
