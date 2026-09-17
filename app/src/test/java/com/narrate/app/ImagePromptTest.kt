package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.*
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.ImageDirector
import com.narrate.app.engine.ImageSubject
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
 * An image request must be about the thing that was asked for.
 *
 * A hospital ID badge came back as a leather satchel because the prompt named no subject at
 * all: an item taken from a starting inventory has only a name, and the name was the one
 * field the prompt never used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImagePromptTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var director: ImageDirector
    private val fake = CapturingImageProvider()

    private val worldId = "world-img"
    private val wardId = "loc-ward"
    private val playerId = "pc"
    private val npcId = "npc"
    private val badgeId = "item-badge"
    private val satchelId = "item-satchel"

    private class CapturingImageProvider : AiProvider {
        override val id = ProviderId.OPENAI
        val prompts = mutableListOf<String>()
        override suspend fun chat(request: LlmRequest, apiKey: String) = LlmResponse("", request.model, id)
        override suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult {
            prompts += request.prompt
            return ImageResult(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png", request.model, id, false)
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(
            ModelInfo("gpt-image-1", ProviderId.OPENAI, supportsText = false, supportsImageGeneration = true)
        )
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
        ProviderRegistry.override(ProviderId.OPENAI, fake)
        director = ImageDirector(repo, settings)

        repo.saveWorld(
            WorldEntity(
                id = worldId, name = "Calder City", genre = "Slice of life",
                currentLocationId = wardId, storyTime = "Day 1, 2:00 AM", timeOfDay = "night", turnCount = 1
            )
        )
        repo.saveLocation(
            LocationEntity(id = wardId, worldId = worldId, name = "Night Ward", type = "ROOM",
                description = "A long room of curtained bays.")
        )
        repo.saveCharacter(
            CharacterEntity(id = playerId, worldId = worldId, name = "Adrian Voss", isPlayer = true,
                appearance = "Dark hair, green eyes.", currentLocationId = wardId)
        )
        repo.saveCharacter(
            CharacterEntity(id = npcId, worldId = worldId, name = "Lena Morales", currentLocationId = wardId)
        )
        repo.saveItems(
            listOf(
                // Exactly as a starting inventory creates it: a name and nothing else.
                ItemEntity(id = badgeId, worldId = worldId, name = "Hospital ID badge",
                    holderId = playerId, significance = "Carried from the beginning."),
                ItemEntity(id = satchelId, worldId = worldId, name = "Leather satchel",
                    appearance = "A scuffed brown leather messenger bag.", locationId = wardId)
            )
        )
    }

    @After
    fun tearDown() = ProviderRegistry.override(ProviderId.OPENAI, null)

    @Test
    fun `an item with only a name is still the subject of its own image`() = runBlocking {
        val result = director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        assertTrue(result.exceptionOrNull()?.message ?: "ok", result.isSuccess)

        val prompt = fake.prompts.single()
        assertTrue("the item must be named", prompt.contains("THE OBJECT: Hospital ID badge"))
        assertTrue("and named in the opening line too", prompt.contains("Calder City: Hospital ID badge"))
        assertTrue(
            "with no description on record the model must be told to draw the named thing",
            prompt.contains("Do not substitute a different subject")
        )
        assertTrue(prompt.contains("Show this object and nothing else"))
        assertTrue("its owner is context", prompt.contains("It belongs to the player character."))
        assertTrue("so is where it is", prompt.contains("currently at Night Ward"))
        assertTrue("another item must not leak in", !prompt.contains("scuffed brown leather"))
        assertTrue("nor the wider scene's cast", !prompt.contains("Lena Morales"))
    }

    @Test
    fun `an item with a description uses it exactly`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(satchelId))
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("THE OBJECT: Leather satchel"))
        assertTrue(prompt.contains("A scuffed brown leather messenger bag."))
        assertTrue("an described item needs no fallback instruction", !prompt.contains("Do not substitute"))
        assertTrue("the badge must not leak in", !prompt.contains("Hospital ID badge"))
    }

    @Test
    fun `an item's condition and recent change reach the prompt`() = runBlocking {
        repo.saveItems(
            listOf(repo.itemDao.get(badgeId)!!.copy(state = "snapped lanyard, cracked photo"))
        )
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        assertTrue(fake.prompts.single().contains("snapped lanyard, cracked photo"))
    }

    @Test
    fun `the album records a carried item at its holder's location, not as unknown`() = runBlocking {
        val image = director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId)).getOrThrow()
        assertEquals("Night Ward", image.locationName)
        assertEquals("Hospital ID badge", image.subjectNames)
        assertEquals("ITEM", image.type)
    }

    @Test
    fun `a character portrait names the character`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(npcId))
        val prompt = fake.prompts.single()
        assertTrue("an NPC with no appearance on record must still be named", prompt.contains("WHO THIS IS: Lena Morales"))
        assertTrue(prompt.contains("Do not substitute a different subject"))
        assertTrue("the player must not be drawn instead", !prompt.contains("Dark hair, green eyes"))
    }

    @Test
    fun `a described character is reproduced rather than reinvented`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId))
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("WHO THIS IS: Adrian Voss"))
        assertTrue(prompt.contains("Dark hair, green eyes."))
        assertTrue(prompt.contains("must be reproduced exactly"))
    }

    @Test
    fun `a location image names the place`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId))
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("THE PLACE: Night Ward (room)"))
        assertTrue(prompt.contains("A long room of curtained bays."))
    }

    @Test
    fun `a scene names everyone in frame`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.CurrentScene)
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("THE PROTAGONIST - Adrian Voss"))
        assertTrue(prompt.contains("ALSO PRESENT - Lena Morales"))
        assertTrue(prompt.contains("WHERE: Night Ward"))
    }

    @Test
    fun `an object's owner is described so a likeness on it is the right person`() = runBlocking {
        // The badge came back showing a woman; the player character is a man, and the prompt
        // never said who owned it.
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("It belongs to the player character."))
        assertTrue(prompt.contains("it must show Adrian Voss"))
        assertTrue(prompt.contains("Dark hair, green eyes."))
    }

    @Test
    fun `an object nobody is carrying does not claim an owner`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(satchelId))
        assertTrue(!fake.prompts.single().contains("It belongs to"))
    }

    @Test
    fun `the prompt never promises a reference image that was not attached`() = runBlocking {
        // First image establishes a reference for the badge.
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        assertTrue("nothing to reference yet", !fake.prompts.first().contains("reference image"))

        // A model with no reference support must not be told one is attached, and above all
        // must not be told to keep a face on a picture of an object.
        settings.setImageModel(ProviderId.OPENAI, "dall-e-3")
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        val withoutSupport = fake.prompts.last()
        assertTrue("no reference was attached, so none may be claimed", !withoutSupport.contains("reference image"))
        assertTrue("and no face should be invited onto an object", !withoutSupport.contains("face"))
    }

    @Test
    fun `a model that does take references is told what to preserve, in the subject's own terms`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        val second = fake.prompts.last()
        assertTrue(second.contains("A reference image of this exact object is attached"))
        assertTrue("an object has shape and wear, not a face", second.contains("the same shape, materials, markings and wear"))
        assertTrue(!second.contains("face, build"))
    }

    @Test
    fun `a portrait reference is still described as an identity`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId))
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId))
        val second = fake.prompts.last()
        assertTrue(second.contains("Keep the same identity - face, build and distinguishing features"))
    }

    @Test
    fun `a place reference is described as architecture`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId))
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId))
        assertTrue(fake.prompts.last().contains("Keep the same architecture, layout and materials"))
    }

    @Test
    fun `the fallback instruction reads as English`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId))
        val prompt = fake.prompts.single()
        assertTrue("the article was doubled", !prompt.contains("what a an object"))
        assertTrue(prompt.contains("what an object called"))

        fake.prompts.clear()
        director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(npcId))
        assertTrue(fake.prompts.single().contains("what a person called"))
    }

    @Test
    fun `every prompt still ends with the no-text rule exactly once`() = runBlocking {
        director.generate(repo.snapshot(worldId)!!, ImageSubject.CurrentScene)
        val prompt = fake.prompts.single()
        assertEquals(1, prompt.split("No text, captions, watermarks or borders").size - 1)
        assertTrue(prompt.trimEnd().endsWith("in the image."))
    }

    @Test
    fun `a generated item image is saved against the item and survives a reload`() = runBlocking {
        val image = director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId)).getOrThrow()

        val reopened = WorldRepository(ApplicationProvider.getApplicationContext())
        val item = reopened.itemDao.get(badgeId)!!
        assertEquals("the inventory row can now show a picture", image.id, item.imageId)

        // And the visual identity is on record for the next generation to build on.
        val identity = reopened.visualForSubject(badgeId)!!
        assertEquals(image.id, identity.primaryImageId)
        assertEquals("Hospital ID badge", identity.subjectName)
    }

    @Test
    fun `a generated location image is saved against the location for the map`() = runBlocking {
        val image = director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId)).getOrThrow()
        val reopened = WorldRepository(ApplicationProvider.getApplicationContext())
        assertEquals(image.id, reopened.location(wardId)!!.imageId)
    }
}
