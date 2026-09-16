package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.*
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.ImageDirector
import com.narrate.app.engine.ImageSubject
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
import java.io.File

/**
 * Visual continuity: the first picture of someone becomes their reference, and every later
 * picture of that subject is generated from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageContinuityTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var director: ImageDirector
    private val fake = FakeImageProvider()

    private val worldId = "world-img"
    private val placeId = "loc-apartment"
    private val playerId = "pc"
    private val npcId = "npc"

    private class FakeImageProvider : AiProvider {
        override val id = ProviderId.OPENAI
        val prompts = mutableListOf<String>()
        val referenceCounts = mutableListOf<Int>()
        override suspend fun chat(request: LlmRequest, apiKey: String) =
            LlmResponse("", request.model, id)
        override suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult {
            prompts += request.prompt
            referenceCounts += request.references.size
            // A one-pixel PNG is enough: only the plumbing is under test.
            return ImageResult(PNG, "image/png", request.model, id, request.references.isNotEmpty())
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(
            ModelInfo(
                "gpt-image-1", ProviderId.OPENAI, supportsText = false,
                supportsImageGeneration = true, supportsImageReferences = true
            )
        )
        companion object {
            val PNG: ByteArray = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
                0x49, 0x48, 0x44, 0x52
            )
        }
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
        settings.setImageModel(ProviderId.OPENAI, "gpt-image-1")
        settings.setUseReferenceImages(true)
        ProviderRegistry.override(ProviderId.OPENAI, fake)
        director = ImageDirector(repo, settings)

        repo.saveWorld(
            WorldEntity(
                id = worldId, name = "Tidewater", artStyle = "Grainy 70s film stock",
                currentLocationId = placeId, storyTime = "Day 3, night", timeOfDay = "night", turnCount = 6
            )
        )
        repo.saveLocation(
            LocationEntity(id = placeId, worldId = worldId, name = "Apartment", description = "A third-floor walk-up.")
        )
        repo.saveCharacter(
            CharacterEntity(
                id = playerId, worldId = worldId, name = "Vale", isPlayer = true,
                appearance = "Tall, grey hair, burn scar on the left wrist.",
                outfit = "A wet canvas coat", currentLocationId = placeId
            )
        )
        repo.saveCharacter(
            CharacterEntity(
                id = npcId, worldId = worldId, name = "Elena",
                appearance = "Short, heavyset, chipped front tooth.", currentLocationId = placeId
            )
        )
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    @Test
    fun `the first portrait becomes the reference for the next one`() = runBlocking {
        val snapshot = repo.snapshot(worldId)!!
        val first = director.generate(snapshot, ImageSubject.Character(npcId))
        assertTrue(first.exceptionOrNull()?.message ?: "ok", first.isSuccess)
        val image = first.getOrThrow()

        assertEquals("Elena - First Appearance", image.label)
        assertEquals("PORTRAIT", image.type)
        assertTrue("the image is written to the world's own folder", File(image.filePath).exists())
        assertTrue("the locked appearance must be in the prompt", fake.prompts[0].contains("chipped front tooth"))
        assertTrue("the world's art direction must be in the prompt", fake.prompts[0].contains("Grainy 70s film stock"))
        assertEquals("nothing to reference yet", 0, fake.referenceCounts[0])

        val identity = repo.visualForSubject(npcId)
        assertNotNull(identity)
        assertEquals(image.id, identity!!.primaryImageId)
        assertEquals(image.id, repo.character(npcId)!!.portraitImageId)

        // Second time round, the established portrait is sent back to the model.
        val second = director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(npcId))
        assertTrue(second.isSuccess)
        assertEquals("the established portrait must be used as a reference", 1, fake.referenceCounts[1])
        assertTrue(second.getOrThrow().usedReferences.isNotBlank())
        assertEquals("Elena - Apartment - Night", second.getOrThrow().label)
    }

    @Test
    fun `a scene carries every present subject and their references`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(npcId))
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId))

        val scene = director.generate(repo.snapshot(worldId)!!, ImageSubject.CurrentScene)
        assertTrue(scene.exceptionOrNull()?.message ?: "ok", scene.isSuccess)
        val image = scene.getOrThrow()

        assertEquals("SCENE", image.type)
        assertTrue(image.subjectNames.contains("Vale"))
        assertTrue(image.subjectNames.contains("Elena"))
        assertEquals("Apartment", image.locationName)
        val prompt = fake.prompts.last()
        assertTrue(prompt.contains("burn scar on the left wrist"))
        assertTrue(prompt.contains("chipped front tooth"))
        assertTrue(prompt.contains("A wet canvas coat"))
        assertTrue("both portraits should seed the scene", fake.referenceCounts.last() >= 2)

        // A group scene must not hijack an individual's profile portrait.
        val elenaPortrait = repo.character(npcId)!!.portraitImageId
        assertTrue(elenaPortrait != image.id)
    }

    @Test
    fun `the album records what each image is of`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(placeId))
        val images = repo.imageDao.all(worldId)
        assertEquals(1, images.size)
        val image = images.first()
        assertEquals("LOCATION", image.type)
        assertEquals("Apartment - Night", image.label)
        assertEquals("Day 3, night", image.storyTime)
        assertEquals(6, image.turnIndex)
        assertEquals("OPENAI", image.provider)
        assertEquals("gpt-image-1", image.model)
        assertEquals(image.id, repo.location(placeId)!!.imageId)
    }

    @Test
    fun `an image is refused cleanly when no model is chosen`() = runBlocking {
        settings.setImageModel(ProviderId.OPENAI, "")
        val result = director.generate(repo.snapshot(worldId)!!, ImageSubject.CurrentScene)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No image model selected"))
    }
}
