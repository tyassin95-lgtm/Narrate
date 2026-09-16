package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.LocationLinkEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.TurnDirector
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
 * End-to-end proof that a turn actually changes the world and that the change survives:
 * a scripted narrator reply goes in, and the save file comes out correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TurnPipelineTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var director: TurnDirector
    private val scripted = ScriptedProvider()

    private val worldId = "world-1"
    private val harbourId = "loc-harbour"
    private val warehouseId = "loc-warehouse"
    private val playerId = "pc-1"

    private class ScriptedProvider : AiProvider {
        override val id = ProviderId.OPENAI
        var nextResponse: String = ""
        var lastPrompt: String = ""
        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            lastPrompt = request.system + "\n" + request.messages.joinToString("\n") { it.content }
            return LlmResponse(nextResponse, request.model, id)
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The database is a process singleton, so each test starts from a clean world.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
        settings = SettingsStore(context)
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        ProviderRegistry.override(ProviderId.OPENAI, scripted)
        director = TurnDirector(repo, settings)

        repo.saveWorld(
            WorldEntity(
                id = worldId, name = "Tidewater", premise = "The tide runs on a timetable nobody set.",
                currentLocationId = harbourId, storyTime = "Day 1, morning"
            )
        )
        repo.saveLocations(
            listOf(
                LocationEntity(id = harbourId, worldId = worldId, name = "Old Harbour", type = "DISTRICT"),
                LocationEntity(id = warehouseId, worldId = worldId, name = "Warehouse 9", parentId = harbourId)
            )
        )
        repo.saveLinks(
            listOf(LocationLinkEntity(id = "link-1", worldId = worldId, fromId = harbourId, toId = warehouseId))
        )
        repo.saveCharacter(
            CharacterEntity(
                id = playerId, worldId = worldId, name = "Vale", isPlayer = true,
                appearance = "Tall, close-cropped grey hair, a burn scar across the left wrist.",
                currentLocationId = harbourId
            )
        )
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    @Test
    fun `a turn is persisted and the world state moves with it`() = runBlocking {
        scripted.nextResponse = """
            ===NARRATION===
            The warehouse door gives on the third shove. **Elena** is already inside.

            ===CHOICES===
            - Ask what she is doing here
            - Close the door behind you

            ===STATE===
            {
              "story_time": "Day 1, midday",
              "summary": "Vale broke into Warehouse 9 and found Elena there.",
              "player": {"location": "Warehouse 9", "knowledge_add": ["Elena has a key to Warehouse 9"]},
              "characters_new": [{"name": "Elena Vasquez", "role": "dock clerk",
                "appearance": "Short, heavyset, a chipped front tooth.", "location": "Warehouse 9",
                "routine": "morning at the dock office, evening at home", "importance": 4}],
              "memories": [{"text": "Vale found Elena inside Warehouse 9.", "kind": "DISCOVERY",
                "importance": 4, "subjects": ["Elena Vasquez", "Warehouse 9"]}],
              "threads": [{"title": "What Elena is hiding", "description": "She had no reason to be there.",
                "status": "ACTIVE", "urgency": 3, "next_beat": "She moves the crates before dawn."}]
            }
            ===END===
        """.trimIndent()

        val result = director.take(worldId, "Force the warehouse door", "ACTION")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)

        val world = repo.world(worldId)!!
        assertEquals(1, world.turnCount)
        assertEquals("Day 1, midday", world.storyTime)
        assertEquals("midday", world.timeOfDay)
        assertEquals(warehouseId, world.currentLocationId)

        val characters = repo.characterDao.all(worldId)
        val elena = characters.firstOrNull { it.name == "Elena Vasquez" }
        assertNotNull("the new character must be saved", elena)
        assertEquals(warehouseId, elena!!.currentLocationId)
        assertEquals(4, elena.importance)

        val player = characters.first { it.isPlayer }
        assertEquals(warehouseId, player.currentLocationId)
        assertTrue(player.knowledge.contains("Elena has a key"))

        val turns = repo.turnDao.all(worldId)
        assertEquals(1, turns.size)
        assertTrue(turns[0].narration.contains("The warehouse door gives"))
        assertTrue(turns[0].choicesJson.contains("Ask what she is doing here"))

        val memories = repo.memoryDao.all(worldId)
        assertEquals(1, memories.size)
        assertTrue(memories[0].subjectIds.contains(elena.id))

        assertEquals(1, repo.threadDao.all(worldId).size)

        // A visual identity is opened for anyone with a described appearance.
        assertNotNull(repo.visualForSubject(elena.id))
    }

    @Test
    fun `the next prompt carries the world state, not the model's memory`() = runBlocking {
        scripted.nextResponse = minimalResponse("Day 1, midday")
        director.take(worldId, "Look around", "ACTION")
        scripted.nextResponse = minimalResponse("Day 1, afternoon")
        director.take(worldId, "Wait", "ACTION")

        val prompt = scripted.lastPrompt
        assertTrue("the prompt must state where the player is", prompt.contains("THE PLAYER IS HERE: Old Harbour"))
        assertTrue("the prompt must list every character position", prompt.contains("CHARACTER POSITIONS"))
        assertTrue("the prompt must carry the map", prompt.contains("Warehouse 9"))
        assertTrue("the prompt must carry the world bible", prompt.contains("The tide runs on a timetable"))
        assertTrue("the player's locked appearance must travel with it", prompt.contains("burn scar across the left wrist"))
        assertTrue("the laws of continuity must be in force", prompt.contains("NEVER TELEPORT ANYONE"))
    }

    @Test
    fun `a character reintroduced under the same name is merged, not duplicated`() = runBlocking {
        scripted.nextResponse = """
            ===STATE===
            {"characters_new": [{"name": "Elena Vasquez", "role": "dock clerk", "location": "Old Harbour"}]}
        """.trimIndent()
        director.take(worldId, "Meet Elena", "ACTION")

        scripted.nextResponse = """
            ===STATE===
            {"characters_new": [{"name": "Elena  Vasquez", "role": "smuggler", "location": "Old Harbour"}]}
        """.trimIndent()
        director.take(worldId, "Meet her again", "ACTION")

        val elenas = repo.characterDao.all(worldId).filter { it.name.contains("Elena") }
        assertEquals("the roster must not fill with near-duplicates", 1, elenas.size)
        assertEquals("the established profile wins", "dock clerk", elenas[0].role)
    }

    @Test
    fun `an unexplained teleport is recorded as a continuity issue`() = runBlocking {
        scripted.nextResponse = """
            ===STATE===
            {"characters_new": [{"name": "Marcus Reyes", "location": "Old Harbour"}],
             "locations_new": [{"name": "Cairn Pass", "type": "WILDERNESS"}]}
        """.trimIndent()
        director.take(worldId, "Meet Marcus", "ACTION")

        scripted.nextResponse = """
            ===STATE===
            {"characters_update": [{"name": "Marcus Reyes", "location": "Cairn Pass"}]}
        """.trimIndent()
        director.take(worldId, "Turn away for a second", "ACTION")

        val issues = repo.issueDao.forTurn(worldId, 1)
        assertTrue("the jump must be flagged", issues.any { it.category == "movement" })

        // And the next prompt must tell the narrator about it.
        scripted.nextResponse = minimalResponse("Day 1, evening")
        director.take(worldId, "Follow him", "ACTION")
        assertTrue(scripted.lastPrompt.contains("CONTINUITY CORRECTIONS"))
    }

    @Test
    fun `prose survives a reply with no usable state block`() = runBlocking {
        scripted.nextResponse = "The gulls scatter. Nothing else moves on the quay."
        val result = director.take(worldId, "Wait", "ACTION")
        assertTrue(result.isSuccess)
        val turn = repo.turnDao.last(worldId)!!
        assertEquals("The gulls scatter. Nothing else moves on the quay.", turn.narration)
        assertEquals(1, repo.world(worldId)!!.turnCount)
    }

    @Test
    fun `the save is recoverable through a fresh repository instance`() = runBlocking {
        scripted.nextResponse = minimalResponse("Day 2, dawn")
        director.take(worldId, "Sleep until dawn", "ACTION")

        val reopened = WorldRepository(ApplicationProvider.getApplicationContext())
        val snapshot = reopened.snapshot(worldId)!!
        assertEquals("Day 2, dawn", snapshot.world.storyTime)
        assertEquals(2, snapshot.world.dayNumber)
        assertEquals(1, snapshot.recentTurns.size)
        assertEquals("Vale", snapshot.player?.name)
    }

    private fun minimalResponse(storyTime: String) = """
        ===NARRATION===
        Nothing much happens.
        ===STATE===
        {"story_time": "$storyTime", "summary": "A quiet moment."}
        ===END===
    """.trimIndent()
}
