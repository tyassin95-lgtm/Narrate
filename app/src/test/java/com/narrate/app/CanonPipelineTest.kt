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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the player writes is canon from creation onward.
 *
 * The model here is deliberately disobedient - it renames the character, renames the world
 * and casts the player as one of the locals - because the guarantee has to hold against a
 * model that ignores its instructions, not just one that follows them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CanonPipelineTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var forge: WorldForge
    private val scripted = ScriptedProvider()

    private val authoredCharacter = """
        Adrian Voss: Dark hair, striking green eyes, handsome, wellkept short stubble, dresses
        nicely, fit. Abandoned young, grew up in group homes. Now he's thirty, in his last year
        of emergency medicine residency at University Hospital, off Fridays.
    """.trimIndent()

    private val authoredWorld = """
        Eastgate Rotations: a teaching hospital and the four-unit rentals around it. Everyone
        here works nights. The Gallery is the name of the third-floor corridor where the
        residents sleep between shifts.
    """.trimIndent()

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

    @Test
    fun `expanding a character keeps the name the player chose`() = runBlocking {
        // The model returns someone else entirely.
        scripted.enqueue(
            """
            {"name": "Marcus Webb", "role": "ER resident", "summary": "A tired doctor.",
             "appearance": "Sandy hair, brown eyes.", "goals": "Survive residency."}
            """.trimIndent()
        )
        val world = com.narrate.app.data.entity.WorldEntity(name = "Eastgate Rotations")
        val result = forge.expandCharacter(world, authoredCharacter)
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)
        assertEquals("Adrian Voss", result.getOrThrow().name)
    }

    @Test
    fun `the expansion prompt hands the model the player's exact words`() = runBlocking {
        scripted.enqueue("""{"name": "Adrian Voss"}""")
        forge.expandCharacter(com.narrate.app.data.entity.WorldEntity(name = "Eastgate"), authoredCharacter)

        val prompt = scripted.prompts.first()
        assertTrue("the text must go in verbatim", prompt.contains("striking green eyes"))
        assertTrue(prompt.contains("wellkept short stubble"))
        assertTrue(prompt.contains("change or replace any name"))
        assertTrue(prompt.contains("their text wins"))
        assertTrue("it must not ask for alternatives", !prompt.contains("Propose 3"))
    }

    @Test
    fun `expanding a world keeps the name and terminology the player chose`() = runBlocking {
        scripted.enqueue(
            """
            {"name": "Saint Mercy General", "genre": "medical drama", "tone": "bleak",
             "premise": "A hospital under strain."}
            """.trimIndent()
        )
        val result = forge.expandWorld(authoredWorld, PlayStyle.SANDBOX)
        assertTrue(result.isSuccess)
        assertEquals("Eastgate Rotations", result.getOrThrow().name)
        // What the player did not specify is still the model's to provide.
        assertEquals("medical drama", result.getOrThrow().genre)

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("The Gallery is the name of the third-floor corridor"))
        assertTrue(prompt.contains("PACING") || prompt.contains("Sandbox") || prompt.contains("sandbox"))
    }

    @Test
    fun `a saved world keeps the player's words and their character's name`() = runBlocking {
        val world = forge.persist(
            concept = WorldConcept(name = "Saint Mercy General", genre = "medical drama"),
            customPrompt = authoredWorld,
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Marcus Webb", role = "resident"),
            characterPrompt = authoredCharacter,
            build = null,
            playStyle = PlayStyle.SANDBOX
        )

        assertEquals("the world keeps the name the player gave it", "Eastgate Rotations", world.name)
        assertEquals("the player's text is stored word for word", authoredWorld, world.authoredCanon)

        val player = repo.characterDao.player(world.id)!!
        assertEquals("the character keeps the name the player gave", "Adrian Voss", player.name)
        assertEquals(authoredCharacter, player.authoredCanon)

        // And it is pinned as canon so retrieval can never drop it.
        val canon = repo.memoryDao.all(world.id).filter { it.kind == "CANON" }
        assertEquals(2, canon.size)
        assertTrue(canon.any { it.text.contains("striking green eyes") })
        assertTrue(canon.any { it.text.contains("The Gallery") })
        assertTrue(canon.all { it.pinned })
    }

    @Test
    fun `the world builder cannot cast the player as one of the locals`() = runBlocking {
        // This is the reported failure: the protagonist reappearing as an NPC.
        scripted.enqueue(
            """
            {
              "starting_location": "The Gallery",
              "starting_time": "Day 1, night",
              "locations": [{"name": "The Gallery", "type": "BUILDING", "description": "A corridor."}],
              "characters": [
                {"name": "Adrian Voss", "role": "a resident everyone avoids", "location": "The Gallery"},
                {"name": "Lena Morales", "role": "night-shift nurse", "location": "The Gallery"}
              ],
              "factions": [], "threads": [], "established_facts": []
            }
            """.trimIndent()
        )
        val build = forge.buildWorld(
            concept = WorldConcept(name = "Eastgate Rotations"),
            customPrompt = authoredWorld,
            character = CharacterConcept(name = "Adrian Voss"),
            playStyle = PlayStyle.SANDBOX
        )
        assertTrue(build.isSuccess)

        val world = forge.persist(
            concept = WorldConcept(name = "Eastgate Rotations"),
            customPrompt = authoredWorld,
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss"),
            characterPrompt = authoredCharacter,
            build = build.getOrNull(),
            playStyle = PlayStyle.SANDBOX
        )

        val cast = repo.characterDao.all(world.id)
        assertEquals("only one Adrian Voss may exist", 1, cast.count { it.name == "Adrian Voss" })
        assertTrue("and he is the player", cast.first { it.name == "Adrian Voss" }.isPlayer)
        assertTrue("other locals are untouched", cast.any { it.name == "Lena Morales" })

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("never create another character with their name"))
    }

    @Test
    fun `a suggestion the player deliberately picks is not overruled by their old draft`() = runBlocking {
        // The player wrote about Adrian, then chose a suggested character instead. Creation
        // clears the draft when that happens, so nothing renames their choice back.
        val world = forge.persist(
            concept = WorldConcept(name = "Eastgate Rotations"),
            customPrompt = "",
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Lena Morales", role = "night-shift nurse"),
            characterPrompt = "",
            build = null,
            playStyle = PlayStyle.SANDBOX
        )
        val player = repo.characterDao.player(world.id)!!
        assertEquals("Lena Morales", player.name)
        assertEquals("", player.authoredCanon)
    }

    @Test
    fun `the player's words reach the narrator on every turn`() = runBlocking {
        val world = forge.persist(
            concept = WorldConcept(name = "Eastgate Rotations"),
            customPrompt = authoredWorld,
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss", appearance = "Dark hair, green eyes."),
            characterPrompt = authoredCharacter,
            build = null,
            playStyle = PlayStyle.SANDBOX
        )
        scripted.prompts.clear()
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

        val director = TurnDirector(repo, settings)
        val result = director.take(world.id, "Look around", "ACTION")
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)

        val prompt = scripted.prompts.first()
        assertTrue("the world text must be present verbatim", prompt.contains("The Gallery is the name of the third-floor corridor"))
        assertTrue("the character text must be present verbatim", prompt.contains("wellkept short stubble"))
        assertTrue(prompt.contains("VERBATIM, AND ABSOLUTE"))
        assertTrue(prompt.contains("THE PLAYER'S OWN WORDS ARE LAW"))
        assertTrue(prompt.contains("Never introduce another character who shares the player character's name"))
    }

    @Test
    fun `asking for alternatives still refuses to contradict the world the player wrote`() = runBlocking {
        scripted.enqueue("""[{"name": "Lena Morales", "role": "nurse"}]""")
        val world = com.narrate.app.data.entity.WorldEntity(
            name = "Eastgate Rotations",
            authoredCanon = authoredWorld
        )
        val result = forge.characterConcepts(world, "someone who works nights")
        assertTrue(result.isSuccess)
        assertEquals("Lena Morales", result.getOrThrow().first().name)

        val prompt = scripted.prompts.first()
        assertTrue(prompt.contains("The Gallery is the name of the third-floor corridor"))
        assertTrue(prompt.contains("nothing here may contradict the world text they wrote"))
    }
}
