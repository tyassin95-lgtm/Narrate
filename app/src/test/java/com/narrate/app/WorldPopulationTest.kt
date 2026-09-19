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

    private suspend fun build(
        characters: List<NewCharacter>,
        start: String = "Maple Street",
        facts: List<com.narrate.app.engine.MemoryDelta> = emptyList()
    ) =
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
                facts = facts
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
    fun `a new world opens with a map of where the player is standing, not the city`() = runBlocking {
        // The complaint, exactly: every NPC's home was on the map before the player's
        // character had any idea those people existed, let alone where they slept.
        val world = build(
            listOf(
                NewCharacter(name = "Liv Mercer", role = "designer", location = "Maple Street", importance = 4),
                NewCharacter(
                    name = "Mara Ellison", role = "attending", location = "The Night Ward",
                    homeLocation = "Eastgate Rentals", importance = 4
                )
            )
        )

        val onMap = repo.locationDao.all(world.id).filter { it.discovered }.map { it.name }
        assertEquals(
            "only where the story opens, and what contains it",
            listOf("Maple Street"),
            onMap
        )
        assertTrue(
            "the rest of the city still exists, so people have somewhere to be",
            repo.locationDao.all(world.id).size >= 4
        )

        val cast = repo.knowledgeDao.all(world.id)
            .filter { it.subjectType == "CHARACTER" && it.field == "exists" }
            .map { it.subjectName }
        assertEquals(
            "and the only person they have met is the one in the opening scene",
            listOf("Liv Mercer"),
            cast
        )
        assertTrue(
            "seeing her tells them what she looks like, and no more",
            repo.knowledgeDao.all(world.id)
                .filter { it.subjectName == "Liv Mercer" }
                .map { it.field }
                .containsAll(listOf("exists", "name", "appearance", "outfit"))
        )
        assertTrue(
            "not her job, not her plans, not where she lives",
            repo.knowledgeDao.all(world.id).none {
                it.subjectName == "Liv Mercer" && it.field in listOf("role", "goals", "home", "secrets")
            }
        )
    }

    @Test
    fun `background the builder invented is remembered but never pinned`() = runBlocking {
        // A model rated its own invention a five, and "Eastgate formed in the 1980s from
        // university expansion" started outranking what the player had actually written.
        val world = build(
            characters = listOf(NewCharacter(name = "Liv Mercer", role = "designer", location = "Maple Street")),
            facts = listOf(
                com.narrate.app.engine.MemoryDelta(
                    text = "Eastgate formed in the 1980s from university expansion.",
                    kind = "FACT",
                    importance = 5
                )
            )
        )
        val memories = repo.memoryDao.all(world.id)
        val invented = memories.first { it.text.contains("1980s") }
        assertTrue("it is still remembered", invented.text.isNotBlank())
        assertEquals("but it does not outrank what the player wrote", false, invented.pinned)
        assertTrue(
            "which is pinned",
            memories.any { it.pinned && it.text.contains("The player wrote this world themselves") }
        )
    }

    @Test
    fun `a room is drawn beside the building it is in`() = runBlocking {
        val world = build(listOf(NewCharacter(name = "Liv Mercer", role = "designer", location = "Maple Street")))
        val places = repo.locationDao.all(world.id)
        val hospital = places.first { it.name == "Calder General Hospital" }
        val ward = places.first { it.name == "The Night Ward" }
        val distance = kotlin.math.hypot(hospital.mapX - ward.mapX, hospital.mapY - ward.mapY)
        assertTrue("the ward is inside the hospital and should look it: $distance", distance < 0.2f)
        assertTrue(
            "and no two places sit on top of each other",
            places.all { a -> places.none { b -> b.id != a.id && kotlin.math.hypot(a.mapX - b.mapX, a.mapY - b.mapY) < 0.05f } }
        )
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
