package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.entity.MemoryEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.WorldConcept
import com.narrate.app.engine.WorldForge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A save is the player's world, and it has to survive everything that happens around it:
 * leaving a screen mid-write, rewinding a scene, deleting a world for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SaveIntegrityTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var forge: WorldForge

    private class SilentProvider : AiProvider {
        override val id = ProviderId.OPENAI
        override suspend fun chat(request: LlmRequest, apiKey: String) =
            LlmResponse("{}", request.model, id, finishReason = "stop")
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
        settings = SettingsStore(context)
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        ProviderRegistry.override(ProviderId.OPENAI, SilentProvider())
        forge = WorldForge(repo, settings)
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    @Test
    fun `a world is never left half written when the player walks away`() = runBlocking {
        // Creation writes the world row first and the protagonist several steps later. A
        // cancelled write between the two would leave a world on the shelf that opens onto
        // nothing: no character, no map, no way to play it.
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val write = scope.launch {
            forge.persist(
                concept = WorldConcept(name = "Eastgate Rotations", premise = "A teaching hospital."),
                customPrompt = "Eastgate Rotations: a teaching hospital.",
                narrationLength = "LONG",
                contentGuidelines = "",
                character = CharacterConcept(name = "Adrian Voss", appearance = "Dark hair, green eyes."),
                characterPrompt = "Adrian Voss: a resident.",
                build = null,
                playStyle = PlayStyle.GENTLE
            )
        }
        write.cancel()
        withTimeout(10_000) { write.join() }

        val world = repo.worldDao.observeAll().let { repo.mostRecentWorld() }
        assertNotNull("the world was saved", world)
        val player = repo.characterDao.player(world!!.id)
        assertNotNull("and so was the protagonist it cannot be played without", player)
        assertEquals("Adrian Voss", player!!.name)
        assertEquals("and the world knows who it belongs to", player.id, world.playerCharacterId)
    }

    @Test
    fun `rewinding forgets what the turns it removed established`() = runBlocking {
        val world = WorldEntity(id = "w1", name = "Eastgate Rotations", turnCount = 4)
        repo.saveWorld(world)
        (0..3).forEach { index ->
            repo.saveTurn(
                TurnEntity(
                    id = "t$index", worldId = "w1", index = index,
                    narration = "Turn $index.", summary = "Turn $index."
                )
            )
        }
        repo.saveMemories(
            listOf(
                MemoryEntity(id = "m1", worldId = "w1", text = "Liv borrowed the jacket.", turnIndex = 1),
                MemoryEntity(id = "m3", worldId = "w1", text = "Liv gave the jacket back.", turnIndex = 3)
            )
        )

        repo.rewindTo("w1", 2)

        assertEquals("the later turns are gone", 2, repo.turnDao.count("w1"))
        val remembered = repo.memoryDao.all("w1").map { it.text }
        assertTrue("what happened before the rewind still stands", remembered.any { it.contains("borrowed") })
        assertTrue(
            "but the world must not remember a turn that no longer happened: $remembered",
            remembered.none { it.contains("gave the jacket back") }
        )
        assertEquals(2, repo.worldDao.get("w1")!!.turnCount)
    }

    @Test
    fun `deleting a world leaves nothing of it behind`() = runBlocking {
        val world = forge.persist(
            concept = WorldConcept(name = "Eastgate Rotations", premise = "A teaching hospital."),
            customPrompt = "Eastgate Rotations: a teaching hospital.",
            narrationLength = "LONG",
            contentGuidelines = "",
            character = CharacterConcept(name = "Adrian Voss"),
            characterPrompt = "Adrian Voss: a resident.",
            build = null,
            playStyle = PlayStyle.GENTLE
        )
        val file = repo.writeImageFile(world.id, ByteArray(8) { 1 })
        repo.saveImage(
            com.narrate.app.data.entity.ImageEntity(
                id = "img1", worldId = world.id, filePath = file.absolutePath, label = "A ward"
            )
        )
        assertTrue(file.exists())

        repo.deleteWorld(world.id)

        assertEquals(null, repo.worldDao.get(world.id))
        assertEquals(0, repo.characterDao.all(world.id).size)
        assertEquals(0, repo.memoryDao.all(world.id).size)
        assertEquals(0, repo.imageDao.all(world.id).size)
        assertTrue("its pictures go with it", !File(file.absolutePath).exists())
    }
}
