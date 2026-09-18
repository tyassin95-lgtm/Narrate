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

    /**
     * A narrator on a script. Replies are queued, so a turn that needs a second call - a
     * truncated reply being completed - can be driven deterministically.
     */
    private class ScriptedProvider : AiProvider {
        override val id = ProviderId.OPENAI
        private val queue = ArrayDeque<LlmResponse>()
        var nextResponse: String = ""
            set(value) {
                field = value
                queue.clear()
            }
        var lastPrompt: String = ""
        val prompts = mutableListOf<String>()
        var calls = 0

        fun enqueue(text: String, truncated: Boolean = false, inputTokens: Int = 0, outputTokens: Int = 0) {
            queue.addLast(
                LlmResponse(
                    text = text,
                    model = "scripted-model",
                    provider = ProviderId.OPENAI,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    finishReason = if (truncated) "length" else "stop",
                    truncated = truncated
                )
            )
        }

        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            calls++
            lastPrompt = request.system + "\n" + request.messages.joinToString("\n") { it.content }
            prompts += lastPrompt
            val queued = queue.removeFirstOrNull()
            return queued?.copy(model = request.model)
                ?: LlmResponse(nextResponse, request.model, id, finishReason = "stop")
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

    // --- the missing-choices investigation ------------------------------------------------

    @Test
    fun `a reply cut off at the token ceiling is completed rather than left without choices`() = runBlocking {
        // The narrator runs long and is truncated mid-sentence: no choices, no state block.
        scripted.enqueue(
            """
            ===NARRATION===
            The tide is further out than it should be at this hour, and the mud smells of iron.
            Elena is already on the steps with her coat buttoned to the throat, and when she sees
            you she does not
            """.trimIndent(),
            truncated = true
        )
        // The completion picks up mid-sentence and supplies what was missing.
        scripted.enqueue(
            """
            ===NARRATION===
            wave. She simply waits, watching the water come back in.
            ===CHOICES===
            - Ask her how long she has been waiting
            - "You knew I would come." -- test how much she has guessed
            - Say nothing and watch the tide with her
            ===STATE===
            {
              "story_time": "Day 1, dusk",
              "summary": "Vale met Elena on the harbour steps at low tide.",
              "characters_new": [{"name": "Elena Vasquez", "role": "dock clerk", "location": "Old Harbour"}],
              "memories": [{"text": "Elena waited for Vale on the harbour steps.", "kind": "EVENT",
                "importance": 3, "subjects": ["Elena Vasquez"]}]
            }
            ===END===
            """.trimIndent()
        )

        val result = director.take(worldId, "Go down to the harbour", "ACTION")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)
        assertTrue("the turn should have been completed by a second call", result.getOrThrow().wasRepaired)
        assertEquals(2, scripted.calls)

        val turn = repo.turnDao.last(worldId)!!
        assertTrue("the prose must be joined, not restarted", turn.narration.contains("she does not wave"))
        assertTrue(turn.narration.startsWith("The tide is further out"))
        assertTrue("the completed turn must carry choices", turn.choicesJson.contains("how long she has been waiting"))
        assertEquals("Day 1, dusk", repo.world(worldId)!!.storyTime)
        assertEquals(1, repo.characterDao.all(worldId).count { it.name.contains("Elena") })

        // The completion prompt must ask for a continuation, not a rewrite.
        val repairPrompt = scripted.prompts.last()
        assertTrue(repairPrompt.contains("cut off"))
        assertTrue(repairPrompt.contains("Do not repeat or rewrite anything you already wrote"))
    }

    @Test
    fun `a complete reply that simply forgot its choices gets them without rewriting the prose`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            The office is empty. Someone has taken the ledger from the desk and left the drawer open.
            ===STATE===
            {"story_time": "Day 1, noon", "summary": "Vale found the ledger missing.",
             "memories": [{"text": "The harbour ledger was taken from the office.", "kind": "DISCOVERY",
               "importance": 4, "subjects": []}]}
            ===END===
            """.trimIndent()
        )
        scripted.enqueue(
            """
            ===CHOICES===
            - Search the drawer
            - Ask the dockhands who came through
            - Leave before anyone finds you here
            ===END===
            """.trimIndent()
        )

        val result = director.take(worldId, "Check the office", "ACTION")
        assertTrue(result.isSuccess)
        val turn = repo.turnDao.last(worldId)!!
        assertEquals(
            "the narration the player already read must be untouched",
            "The office is empty. Someone has taken the ledger from the desk and left the drawer open.",
            turn.narration
        )
        assertTrue(turn.choicesJson.contains("Search the drawer"))
        // The first reply's state block is authoritative: the memory must not be recorded twice.
        assertEquals(1, repo.memoryDao.all(worldId).size)

        val repairPrompt = scripted.prompts.last()
        assertTrue(repairPrompt.contains("Do not rewrite or resend the narration"))
    }

    @Test
    fun `choices written as a plain list at the end of the prose are still offered`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            The rain starts again as you reach the gate. The watchman does not look up from his paper.

            What do you do?
            - Knock on the window
            - Walk around to the yard
            - Wait for the rain to pass
            ===STATE===
            {"story_time": "Day 1, evening", "summary": "Vale reached the gate in the rain."}
            ===END===
            """.trimIndent()
        )

        val result = director.take(worldId, "Head for the gate", "ACTION")
        assertTrue(result.isSuccess)
        assertEquals("no second call should be needed", 1, scripted.calls)
        val turn = repo.turnDao.last(worldId)!!
        assertTrue(turn.choicesJson.contains("Knock on the window"))
        assertTrue("the list must be lifted out of the prose", !turn.narration.contains("Knock on the window"))
        assertTrue("the prompt line goes with it", !turn.narration.contains("What do you do?"))
        assertTrue(turn.narration.contains("The watchman does not look up"))
    }

    @Test
    fun `a turn is not repaired twice however incomplete the reply stays`() = runBlocking {
        scripted.enqueue("The fog thickens.", truncated = true)
        scripted.enqueue("Still nothing useful.")
        val result = director.take(worldId, "Wait", "ACTION")
        assertTrue(result.isSuccess)
        assertEquals("exactly one completion attempt", 2, scripted.calls)
        assertEquals(1, repo.turnDao.all(worldId).size)
    }

    @Test
    fun `a completion that returns only choices never leaks its JSON into the prose`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            The lamp gutters and goes out, and the room is dark for a moment before your eyes adjust.
            """.trimIndent(),
            truncated = true
        )
        // The model follows the instruction to omit the narration section entirely.
        scripted.enqueue(
            """
            ===CHOICES===
            - Find the matches
            - Wait for your eyes to adjust
            ===STATE===
            {"story_time": "Day 1, night", "summary": "The lamp went out."}
            ===END===
            """.trimIndent()
        )

        val result = director.take(worldId, "Watch the lamp", "ACTION")
        assertTrue(result.isSuccess)
        val turn = repo.turnDao.last(worldId)!!
        assertEquals(
            "The lamp gutters and goes out, and the room is dark for a moment before your eyes adjust.",
            turn.narration
        )
        assertTrue("no JSON may reach the page", !turn.narration.contains("story_time"))
        assertTrue("no markers may reach the page", !turn.narration.contains("==="))
        assertTrue(turn.choicesJson.contains("Find the matches"))
        assertEquals("Day 1, night", repo.world(worldId)!!.storyTime)
    }

    // --- suggested actions -------------------------------------------------------------

    @Test
    fun `a lent object stays the player's, and the choice offering it back is dropped`() = runBlocking {
        // Turn one: Adrian gives Liv his jacket because she is freezing.
        scripted.enqueue(
            """
            ===NARRATION===
            You shrug out of the jacket and hold it out before you can think better of it.
            ===CHOICES===
            - Wait and see if she takes it
            - "Keep it. I'm two streets away."
            ===STATE===
            {
              "story_time": "Day 1, 2:02 AM",
              "summary": "Vale lent Liv his jacket.",
              "characters_new": [{"name": "Liv Marchetti", "location": "Old Harbour",
                "appearance": "Dark curls, soaked through."}],
              "items_new": [{"name": "wool jacket", "owner": "Vale", "held_by": "Liv Marchetti",
                "significance": "Lent on the first night."}]
            }
            ===END===
            """.trimIndent()
        )
        director.take(worldId, "Offer her your jacket", "ACTION")

        val jacket = repo.itemDao.all(worldId).single { it.name.contains("jacket") }
        val player = repo.characterDao.player(worldId)!!
        val liv = repo.characterDao.all(worldId).single { it.name.contains("Liv") }
        assertEquals("it is still his", player.id, jacket.ownerId)
        assertEquals("she is the one wearing it", liv.id, jacket.holderId)

        // Turns later, the narrator offers to give it back to her.
        scripted.enqueue(
            """
            ===NARRATION===
            Her door sticks on the frame. She gets it open with a shoulder.
            ===CHOICES===
            - Ask if she wants her jacket back
            - "Get inside before you freeze."
            - Wait until the door closes before you go
            ===STATE===
            {"story_time": "Day 1, 2:40 AM", "summary": "They reached her door."}
            ===END===
            """.trimIndent()
        )
        val result = director.take(worldId, "Walk her home", "ACTION")
        assertTrue(result.isSuccess)

        val offered = result.getOrThrow().parsed.choices.map { it.label }
        assertTrue("the ownership inversion must not reach the player", offered.none { it.contains("her jacket back") })
        assertEquals("the sound ones survive", 2, offered.size)

        // And it is recorded, so the player can see the guard working.
        val issues = repo.issueDao.forTurn(worldId, 1)
        assertTrue(issues.any { it.category == "suggested-action" && it.description.contains("jacket") })
    }

    @Test
    fun `the narrator is told what the player is holding and what is only lent`() = runBlocking {
        scripted.enqueue(
            """
            ===STATE===
            {"items_new": [{"name": "wool jacket", "owner": "Vale", "held_by": "Vale"}]}
            """.trimIndent()
        )
        director.take(worldId, "Check your pockets", "ACTION")
        scripted.prompts.clear()

        scripted.enqueue(minimalResponse("Day 1, noon"))
        director.take(worldId, "Look around", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("THIS MOMENT"))
        assertTrue(prompt.contains("WHAT VALE CAN ACTUALLY USE"))
        assertTrue(prompt.contains("theirs, in hand, available to use or offer"))
    }

    @Test
    fun `a question left hanging is put in front of the narrator`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            She stops on the step and looks back at you. "So are you going to tell me what you were doing out at two in the morning?"
            ===CHOICES===
            - Say nothing
            - Shrug
            ===STATE===
            {"story_time": "Day 1, 2:40 AM"}
            ===END===
            """.trimIndent()
        )
        director.take(worldId, "Walk her home", "ACTION")
        scripted.prompts.clear()

        scripted.enqueue(minimalResponse("Day 1, 2:41 AM"))
        director.take(worldId, "Think about it", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("QUESTIONS PUT TO VALE"))
        assertTrue(prompt.contains("what you were doing out at two in the morning?"))
        assertTrue(prompt.contains("the player's to answer"))
        assertTrue(prompt.contains("written as the words they would say"))
    }

    @Test
    fun `a turn that leaves the character mute is corrected on the next one`() = runBlocking {
        // The scene from the report: she introduces herself, asks his name, and the turn stops.
        scripted.enqueue(
            """
            ===NARRATION===
            She studies him with tired, bright curiosity.

            "I'm Liv, by the way." Her fingers touch the chain at her throat. "What's your name?"
            ===CHOICES===
            - Tell her
            - Say nothing
            ===STATE===
            {"story_time": "Day 1, 2:40 AM"}
            ===END===
            """.trimIndent()
        )
        director.take(worldId, "Walk with her", "ACTION")

        val issues = repo.issueDao.forTurn(worldId, 0)
        val mute = issues.single { it.category == "player-voice" }
        assertTrue(mute.description.contains("What's your name?"))
        assertTrue(mute.resolution.contains("answers ordinary questions about himself"))

        scripted.prompts.clear()
        scripted.enqueue(minimalResponse("Day 1, 2:41 AM"))
        director.take(worldId, "Keep walking", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue("the correction has to reach the next turn", prompt.contains("CONTINUITY CORRECTIONS"))
        assertTrue(prompt.contains("said nothing"))
    }

    @Test
    fun `the narrator is told to write dialogue out rather than describe it`() = runBlocking {
        scripted.enqueue(minimalResponse("Day 1, noon"))
        director.take(worldId, "Look around", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("SPEECH IS WRITTEN OUT, NOT DESCRIBED"))
        assertTrue(prompt.contains("MAKE THEM DIFFERENT IN KIND, NOT IN WORDING"))
        assertTrue(prompt.contains("ANSWER THE MOMENT"))
        assertTrue(prompt.contains("NEVER CONFUSE WHO IS WHO"))
    }

    @Test
    fun `when every suggestion is unusable the narrator is asked again, and told why`() = runBlocking {
        scripted.enqueue(
            """
            ===STATE===
            {"items_new": [{"name": "umbrella", "owner": "Marcus Reyes", "held_by": "Marcus Reyes"}],
             "characters_new": [{"name": "Marcus Reyes", "location": "Old Harbour"}]}
            """.trimIndent()
        )
        director.take(worldId, "Meet Marcus", "ACTION")
        scripted.prompts.clear()

        // Both suggestions offer an umbrella the player does not have.
        scripted.enqueue(
            """
            ===NARRATION===
            The rain comes down harder.
            ===CHOICES===
            - Offer her the umbrella
            - Hand over the umbrella and walk on
            ===STATE===
            {"story_time": "Day 1, noon"}
            ===END===
            """.trimIndent()
        )
        scripted.enqueue(
            """
            ===CHOICES===
            - "Here, get under the awning with me."
            - Turn your collar up and keep walking
            - Ask her how far she has to go
            ===END===
            """.trimIndent()
        )

        val result = director.take(worldId, "Stand in the rain", "ACTION")
        assertTrue(result.isSuccess)
        assertTrue("a second call was needed", result.getOrThrow().wasRepaired)
        assertEquals(3, result.getOrThrow().parsed.choices.size)

        val repairPrompt = scripted.prompts.last()
        assertTrue(repairPrompt.contains("rejected because they contradicted the world state"))
        assertTrue(repairPrompt.contains("umbrella"))
    }

    @Test
    fun `good suggestions are never touched`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            She waits.
            ===CHOICES===
            - "Are you okay? You look frozen."
            - Ask where she is heading
            - Say nothing and walk on
            - Yell at her to watch where she is going
            ===STATE===
            {"story_time": "Day 1, noon"}
            ===END===
            """.trimIndent()
        )
        val result = director.take(worldId, "Steady her", "ACTION")
        assertEquals("no second call should be needed", 1, scripted.calls)
        assertEquals(4, result.getOrThrow().parsed.choices.size)
        assertEquals("SPEECH", result.getOrThrow().parsed.choices.first().kind)
        assertTrue(repo.issueDao.forTurn(worldId, 0).none { it.category == "suggested-action" })
    }

    // --- pacing ---------------------------------------------------------------------------

    @Test
    fun `a sandbox world tells the narrator not to manufacture drama`() = runBlocking {
        repo.saveWorld(repo.world(worldId)!!.copy(playStyle = "SANDBOX"))
        scripted.nextResponse = minimalResponse("Day 1, midday")
        director.take(worldId, "Sit on the wall and watch the boats", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("PACING: SANDBOX"))
        assertTrue(prompt.contains("Do not manufacture emergencies"))
        assertTrue(prompt.contains("Nothing dramatic is owed to any turn"))
        assertTrue("choices should suit a quiet world", prompt.contains("At least one option should be small and unhurried"))
        assertTrue(prompt.contains("The CHOICES section is never optional"))
    }

    @Test
    fun `a dramatic world keeps its pressure`() = runBlocking {
        repo.saveWorld(repo.world(worldId)!!.copy(playStyle = "DRAMATIC"))
        scripted.nextResponse = minimalResponse("Day 1, midday")
        director.take(worldId, "Run", "ACTION")

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("PACING: DRAMATIC"))
        assertTrue(!prompt.contains("Do not manufacture emergencies"))
    }

    @Test
    fun `the play style survives a reload`() = runBlocking {
        repo.saveWorld(repo.world(worldId)!!.copy(playStyle = "SANDBOX"))
        val reopened = WorldRepository(ApplicationProvider.getApplicationContext())
        assertEquals("SANDBOX", reopened.world(worldId)!!.playStyle)
    }

    @Test
    fun `the narrator cannot introduce a second character with the player's name`() = runBlocking {
        scripted.enqueue(
            """
            ===NARRATION===
            Someone calls your name across the quay.
            ===CHOICES===
            - Turn around
            ===STATE===
            {"story_time": "Day 1, noon",
             "characters_new": [{"name": "Vale", "role": "a stranger with your name",
               "location": "Old Harbour"}]}
            ===END===
            """.trimIndent()
        )
        val result = director.take(worldId, "Walk the quay", "ACTION")
        assertTrue(result.isSuccess)

        val cast = repo.characterDao.all(worldId)
        assertEquals("the player must stay unique", 1, cast.count { it.name == "Vale" })
        assertTrue(cast.first { it.name == "Vale" }.isPlayer)
        assertTrue(
            result.getOrThrow().issues.any { it.category == "player-identity" }
        )
    }

    // --- usage ----------------------------------------------------------------------------

    @Test
    fun `every call is recorded against the world with an estimated cost`() = runBlocking {
        settings.setNarrationModel(ProviderId.OPENAI, "gpt-5.4-mini")
        scripted.enqueue(minimalResponse("Day 1, midday"), inputTokens = 12_000, outputTokens = 900)
        director.take(worldId, "Look around", "ACTION")

        val usage = repo.usageForWorld(worldId)
        assertEquals(1, usage.size)
        val event = usage.first()
        assertEquals("NARRATION", event.purpose)
        assertEquals("gpt-5.4-mini", event.model)
        assertEquals("OPENAI", event.provider)
        assertEquals(12_000, event.inputTokens)
        assertEquals(900, event.outputTokens)
        assertTrue("a listed model must price", event.costKnown)
        // 12k in at $0.75/M plus 900 out at $4.50/M.
        assertEquals(0.009 + 0.00405, event.estimatedCost, 0.00001)
    }

    @Test
    fun `token counts are estimated when the provider reports none`() = runBlocking {
        scripted.enqueue(minimalResponse("Day 1, midday"))
        director.take(worldId, "Look around", "ACTION")
        val event = repo.usageForWorld(worldId).first()
        assertTrue("input tokens should be estimated from the prompt", event.inputTokens > 100)
        assertTrue(event.outputTokens > 0)
    }

    @Test
    fun `a completion call is recorded separately so its cost is visible`() = runBlocking {
        scripted.enqueue("The fog thickens.", truncated = true)
        scripted.enqueue(
            """
            ===CHOICES===
            - Turn back
            - Keep walking
            ===STATE===
            {"story_time": "Day 1, night", "summary": "Vale walked into the fog."}
            ===END===
            """.trimIndent()
        )
        director.take(worldId, "Walk into the fog", "ACTION")

        val usage = repo.usageForWorld(worldId)
        assertEquals(2, usage.size)
        assertEquals(setOf("NARRATION", "REPAIR"), usage.map { it.purpose }.toSet())
    }

    @Test
    fun `usage is deleted with its world`() = runBlocking {
        scripted.nextResponse = minimalResponse("Day 1, midday")
        director.take(worldId, "Look around", "ACTION")
        assertTrue(repo.usageForWorld(worldId).isNotEmpty())
        repo.deleteWorld(worldId)
        assertTrue(repo.usageForWorld(worldId).isEmpty())
        assertTrue(repo.characterDao.all(worldId).isEmpty())
        assertTrue(repo.turnDao.all(worldId).isEmpty())
    }

    @Test
    fun `a scene that has stalled gets a way out of it, whatever the narrator offered`() = runBlocking {
        // Four turns of the player passing the time, exactly as the playthrough ran.
        listOf("Sit down and wait", "Check the time", "Watch the water", "Wait a bit longer")
            .forEachIndexed { index, input ->
                scripted.enqueue(
                    """
                    ===NARRATION===
                    The tide is where it was. Nothing has changed on the quay.
                    ===CHOICES===
                    - Check the time again
                    - Keep watching the water
                    - Wait a little longer
                    ===STATE===
                    {"story_time": "Day 1, 9:0$index AM", "summary": "Waiting."}
                    ===END===
                    """.trimIndent()
                )
                director.take(worldId, input, "ACTION")
            }

        val last = repo.turnDao.all(worldId).last()
        val offered = last.choicesJson
        assertTrue(
            "the menu of ways to keep waiting is refused: ${'$'}offered",
            !offered.contains("Check the time") && !offered.contains("Keep watching")
        )
        assertTrue(
            "and the player is handed an exit instead: ${'$'}offered",
            offered.contains("Let the time pass")
        )
        val issues = repo.issueDao.forTurn(worldId, 3)
        assertTrue(issues.any { it.category == "suggested-action" })
    }

    @Test
    fun `a turn whose prose and clock disagree about the hour is written down`() = runBlocking {
        scripted.enqueue(minimalResponse("Day 1, 9:00 AM"))
        director.take(worldId, "Look around", "ACTION")

        scripted.enqueue(
            """
            ===NARRATION===
            Twenty minutes later the bell on the chandler's door goes and somebody comes out.
            ===CHOICES===
            - Go over
            - Stay where you are
            ===STATE===
            {"story_time": "Day 1, 9:03 AM", "summary": "Someone comes out."}
            ===END===
            """.trimIndent()
        )
        director.take(worldId, "Watch the door", "ACTION")

        val issues = repo.issueDao.forTurn(worldId, 1)
        val clock = issues.single { it.category == "clock" }
        assertTrue(clock.description.contains("20 minutes"))
        assertTrue(clock.description.contains("moved 3"))
    }

    private fun minimalResponse(storyTime: String) = """
        ===NARRATION===
        Nothing much happens.
        ===CHOICES===
        - Wait a while longer
        - Walk down to the water
        ===STATE===
        {"story_time": "$storyTime", "summary": "A quiet moment."}
        ===END===
    """.trimIndent()
}
