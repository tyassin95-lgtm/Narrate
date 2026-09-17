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
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.TurnDirector
import com.narrate.app.engine.WorldConcept
import com.narrate.app.engine.WorldForge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An opening the player writes is where their story starts.
 *
 * The reported failure: the world opened somewhere else entirely and the opening was filed
 * away as a thread - something that might happen later - so the scene the player asked for
 * never arrived. It is the first scene, established canon from turn one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OpeningSceneTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var forge: WorldForge
    private val scripted = ScriptedProvider()

    private val authoredWorld = """
        Eastgate Rotations: a teaching hospital and the rentals around it. The story begins on
        the Night Ward at 3am, with Adrian holding the door for a woman carrying a soaked coat.
    """.trimIndent()

    private val opening =
        "The story begins on the Night Ward at 3am, with Adrian holding the door for a woman " +
            "carrying a soaked coat."

    private class ScriptedProvider : AiProvider {
        override val id = ProviderId.OPENAI
        private val queue = ArrayDeque<String>()
        val prompts = mutableListOf<String>()
        fun enqueue(text: String) = queue.addLast(text)
        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            prompts += request.system + "\n" + request.messages.joinToString("\n") { it.content }
            return LlmResponse(queue.removeFirstOrNull() ?: "{}", request.model, id, finishReason = "stop")
        }
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
        settings = SettingsStore(context)
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        ProviderRegistry.override(ProviderId.OPENAI, scripted)
        forge = WorldForge(repo, settings)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    /** A world whose builder opens somewhere else and files the opening as a future thread. */
    private val disobedientBuild = """
        {
          "starting_location": "Eastgate Rentals",
          "starting_time": "Day 1, morning",
          "locations": [
            {"name": "Night Ward", "type": "ROOM", "description": "Curtained bays at the end of the corridor."},
            {"name": "Eastgate Rentals", "type": "BUILDING", "description": "Four flats above a laundrette."}
          ],
          "characters": [{"name": "Lena Morales", "role": "night-shift nurse", "location": "Night Ward"}],
          "factions": [],
          "threads": [
            {"title": "The woman with the soaked coat",
             "description": "A woman carrying a soaked coat will arrive at the Night Ward at 3am, and Adrian will hold the door for her.",
             "status": "ACTIVE", "urgency": 3, "next_beat": "She arrives."},
            {"title": "The rota dispute",
             "description": "The consultants are fighting over the winter rota and someone will lose their nights.",
             "status": "ACTIVE", "urgency": 2, "next_beat": "A memo goes round."}
          ],
          "established_facts": []
        }
    """.trimIndent()

    private suspend fun buildAndPersist(): com.narrate.app.data.entity.WorldEntity {
        scripted.enqueue(disobedientBuild)
        val concept = WorldConcept(
            name = "Eastgate Rotations",
            tagline = "Nights on the ward",
            genre = "medical drama",
            tone = "quiet",
            premise = "A teaching hospital under strain.",
            history = "Founded in 1911.",
            rules = "No magic.",
            themes = "Care and exhaustion.",
            artStyle = "Muted film still",
            openingSituation = opening
        )
        val build = forge.buildWorld(concept, authoredWorld, CharacterConcept(name = "Adrian Voss"), PlayStyle.GENTLE)
        assertTrue(build.exceptionOrNull()?.message ?: "ok", build.isSuccess)
        return forge.persist(
            concept = concept,
            customPrompt = authoredWorld,
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss", appearance = "Dark hair, green eyes."),
            characterPrompt = "Adrian Voss: a resident.",
            build = build.getOrNull(),
            playStyle = PlayStyle.GENTLE
        )
    }

    @Test
    fun `the opening the player wrote is saved as the world's first scene`() = runBlocking {
        val world = buildAndPersist()
        assertEquals("the opening is stored word for word", opening, world.openingNarration)
    }

    @Test
    fun `the opening is not filed away as something still to come`() = runBlocking {
        val world = buildAndPersist()
        val threads = repo.threadDao.all(world.id)
        assertFalse(
            "the first scene must not be waiting in the thread list",
            threads.any { it.title.contains("soaked coat", ignoreCase = true) }
        )
        assertTrue("unrelated threads are untouched", threads.any { it.title == "The rota dispute" })
    }

    @Test
    fun `the story starts where the opening says, not where the builder wandered off to`() = runBlocking {
        val world = buildAndPersist()
        val start = repo.locationDao.all(world.id).first { it.id == world.currentLocationId }
        assertEquals("Night Ward", start.name)

        val player = repo.characterDao.player(world.id)!!
        assertEquals("and the player is standing in it", start.id, player.currentLocationId)
    }

    @Test
    fun `the opening is pinned as canon so retrieval can never drop it`() = runBlocking {
        val world = buildAndPersist()
        val canon = repo.memoryDao.all(world.id).filter { it.kind == "CANON" }
        assertTrue(
            "the world must remember how it began",
            canon.any { it.text.contains("soaked coat") && it.pinned }
        )
    }

    @Test
    fun `the builder is told the opening is happening now`() = runBlocking {
        buildAndPersist()
        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains(opening))
        assertTrue(prompt.contains("THE FIRST SCENE"))
        assertTrue(prompt.contains("Do not turn it into a thread"))
    }

    @Test
    fun `the narrator is told to begin exactly there on the first turn`() = runBlocking {
        val world = buildAndPersist()
        scripted.prompts.clear()
        scripted.enqueue(
            """
            ===NARRATION===
            The door swings and she comes in out of the rain, coat over one arm.
            ===CHOICES===
            - "Here, let me take that."
            ===STATE===
            {"story_time": "Day 1, 3am", "summary": "A woman arrives on the Night Ward.",
             "threads": [{"title": "The woman with the soaked coat",
               "description": "A woman carrying a soaked coat arrives at the Night Ward at 3am and Adrian holds the door for her.",
               "status": "ACTIVE", "urgency": 3}]}
            ===END===
            """.trimIndent()
        )

        val director = TurnDirector(repo, settings)
        val result = director.take(world.id, "", "OPENING")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)

        val prompt = scripted.prompts.first()
        assertTrue("the opening scene reaches the narrator", prompt.contains(opening))
        assertTrue(prompt.contains("THE SCENE THIS STORY BEGINS ON"))
        assertTrue(prompt.contains("That is the first scene. Begin exactly there"))
        assertTrue(prompt.contains("Do not file the opening itself as a thread"))

        // And if it files one anyway, the world does not end up waiting for a scene it is in.
        val threads = repo.threadDao.all(world.id)
        assertFalse(
            "a thread that merely retells the opening is dropped on the opening turn",
            threads.any { it.title.contains("soaked coat", ignoreCase = true) }
        )
    }

    @Test
    fun `only a thread that retells the opening is dropped`() {
        // The guard has to be narrow: it removes the opening wearing a thread's hat, and
        // nothing else, or a legitimate thread would vanish with it.
        assertTrue(
            com.narrate.app.engine.OpeningScene.restatesOpening(
                "The woman with the soaked coat",
                "A woman carrying a soaked coat arrives at the Night Ward at 3am and Adrian holds the door for her.",
                opening
            )
        )
        assertFalse(
            com.narrate.app.engine.OpeningScene.restatesOpening(
                "The rota dispute",
                "The consultants are fighting over the winter rota and someone will lose their nights.",
                opening
            )
        )
        assertFalse(
            "a thread that follows on from the opening is not the opening",
            com.narrate.app.engine.OpeningScene.restatesOpening(
                "Who the woman is",
                "Nobody on the ward knows her name, and the admissions desk has no record of her.",
                opening
            )
        )
    }

    @Test
    fun `a first turn the player typed still opens on their scene`() = runBlocking {
        val world = buildAndPersist()
        scripted.prompts.clear()
        scripted.enqueue(
            """
            ===NARRATION===
            The door swings and she comes in out of the rain.
            ===CHOICES===
            - "Here, let me take that."
            ===STATE===
            {"story_time": "Day 1, 3am", "summary": "A woman arrives."}
            ===END===
            """.trimIndent()
        )

        // The opening call failed - no signal, a bad key - and the player tried again by
        // acting instead. Their scene must not be lost because of a network error.
        val result = TurnDirector(repo, settings).take(world.id, "Hold the door open", "ACTION")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)

        val prompt = scripted.prompts.first()
        assertTrue("the opening is still where the story starts", prompt.contains(opening))
        assertTrue(prompt.contains("THE SCENE THIS STORY BEGINS ON"))
        assertTrue("and what they typed is part of it", prompt.contains("Hold the door open"))
    }

    @Test
    fun `a world with no opening of its own still gets a first turn`() = runBlocking {
        val world = forge.persist(
            concept = WorldConcept(name = "Eastgate Rotations", premise = "A teaching hospital."),
            customPrompt = "",
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss"),
            characterPrompt = "",
            build = null,
            playStyle = PlayStyle.GENTLE
        )
        assertEquals("", world.openingNarration)

        scripted.enqueue(
            """
            ===NARRATION===
            The corridor is quiet.
            ===CHOICES===
            - Walk down to the ward
            ===STATE===
            {"story_time": "Day 1, night", "summary": "A quiet start."}
            ===END===
            """.trimIndent()
        )
        val result = TurnDirector(repo, settings).take(world.id, "", "OPENING")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)
        assertTrue(scripted.prompts.first().contains("This is the opening of the world."))
    }
}
