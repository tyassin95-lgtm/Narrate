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
import com.narrate.app.ui.codex.CodexViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Deleting an image, and knowing when one is being drawn.
 *
 * An image is rarely only an album entry: it may be a portrait, a map icon, or the reference
 * later pictures are seeded from. Removing it has to release all of those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumManagementTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var director: ImageDirector
    private val fake = StubImageProvider()

    private val worldId = "world-album"
    private val wardId = "loc-ward"
    private val playerId = "pc"
    private val badgeId = "item-badge"

    private class StubImageProvider : AiProvider {
        override val id = ProviderId.OPENAI
        /** When set, generation waits here so a test can observe work in flight. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun chat(request: LlmRequest, apiKey: String) = LlmResponse("", request.model, id)
        override suspend fun generateImage(request: ImageRequest, apiKey: String): ImageResult {
            gate?.await()
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
        Dispatchers.setMain(Dispatchers.Unconfined)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
        settings = context.container.settings
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setImageModel(ProviderId.OPENAI, "gpt-image-1")
        ProviderRegistry.override(ProviderId.OPENAI, fake)
        director = ImageDirector(repo, settings)

        repo.saveWorld(WorldEntity(id = worldId, name = "Calder City", currentLocationId = wardId))
        repo.saveLocation(LocationEntity(id = wardId, worldId = worldId, name = "Night Ward"))
        repo.saveCharacter(
            CharacterEntity(id = playerId, worldId = worldId, name = "Adrian Voss", isPlayer = true,
                appearance = "Dark hair, green eyes.", currentLocationId = wardId)
        )
        repo.saveItems(listOf(ItemEntity(id = badgeId, worldId = worldId, name = "Hospital ID badge", holderId = playerId)))
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
        Dispatchers.resetMain()
    }

    @Test
    fun `deleting an image removes it from the album and from the device`() = runBlocking {
        val image = director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId)).getOrThrow()
        assertTrue(File(image.filePath).exists())

        repo.deleteImage(image)

        assertNull(repo.image(image.id))
        assertTrue("the file must go too", !File(image.filePath).exists())
        assertTrue(repo.imageDao.all(worldId).isEmpty())
    }

    @Test
    fun `everything that pointed at a deleted image lets go of it`() = runBlocking {
        val portrait = director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId)).getOrThrow()
        val place = director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId)).getOrThrow()
        val badge = director.generate(repo.snapshot(worldId)!!, ImageSubject.Item(badgeId)).getOrThrow()
        assertEquals(portrait.id, repo.character(playerId)!!.portraitImageId)
        assertEquals(place.id, repo.location(wardId)!!.imageId)
        assertEquals(badge.id, repo.itemDao.get(badgeId)!!.imageId)

        repo.deleteImage(portrait)
        repo.deleteImage(place)
        repo.deleteImage(badge)

        assertNull("no dangling portrait", repo.character(playerId)!!.portraitImageId)
        assertNull("no dangling map icon", repo.location(wardId)!!.imageId)
        assertNull("no dangling inventory icon", repo.itemDao.get(badgeId)!!.imageId)
        assertNull("no dangling cover", repo.world(worldId)!!.coverImageId)
        assertNull(repo.visualForSubject(playerId)!!.primaryImageId)
    }

    @Test
    fun `a subject with two pictures falls back to the one that is left`() = runBlocking {
        val first = director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId)).getOrThrow()
        val second = director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId)).getOrThrow()
        assertEquals(second.id, repo.visualForSubject(playerId)!!.primaryImageId)

        repo.deleteImage(second)

        val identity = repo.visualForSubject(playerId)!!
        assertEquals("the earlier picture takes over as the reference", first.id, identity.primaryImageId)
        assertTrue(!identity.referenceImageIds.contains(second.id))
        assertEquals(first.id, repo.character(playerId)!!.portraitImageId)
    }

    @Test
    fun `deleting an image leaves other images untouched`() = runBlocking {
        val portrait = director.generate(repo.snapshot(worldId)!!, ImageSubject.Character(playerId)).getOrThrow()
        val place = director.generate(repo.snapshot(worldId)!!, ImageSubject.Location(wardId)).getOrThrow()

        repo.deleteImage(portrait)

        assertEquals(listOf(place.id), repo.imageDao.all(worldId).map { it.id })
        assertTrue(File(place.filePath).exists())
        assertEquals(place.id, repo.location(wardId)!!.imageId)
    }

    @Test
    fun `the codex reports which subject is being drawn and refuses a second at once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = CodexViewModel(ApplicationProvider.getApplicationContext(), worldId)

        viewModel.generateItemImage(badgeId)
        // The screen's state is assembled from the database, so wait for it as the screen does.
        val drawing = withTimeout(5_000) { viewModel.state.first { it.generating } }
        assertEquals("the row that asked can show a spinner", badgeId, drawing.generatingSubjectId)
        assertTrue(drawing.isDrawing(badgeId))
        assertTrue("and other rows know to wait", !drawing.isDrawing(playerId))

        // Pressing again, or pressing another draw button, must not start a second request.
        viewModel.generateItemImage(badgeId)
        viewModel.generatePortrait(playerId)
        assertEquals(badgeId, viewModel.state.value.generatingSubjectId)

        gate.complete(Unit)
        fake.gate = null
        withTimeout(5_000) { viewModel.state.first { !it.generating } }
        assertEquals("only one image, however many times it was pressed", 1, repo.imageDao.all(worldId).size)
    }

    @Test
    fun `drawing clears its progress when it finishes`() = runBlocking {
        val viewModel = CodexViewModel(ApplicationProvider.getApplicationContext(), worldId)
        viewModel.generateItemImage(badgeId)
        val finished = withTimeout(5_000) {
            viewModel.state.first { it.message?.startsWith("Saved:") == true }
        }
        assertNull("nothing is drawing once it is done", finished.generatingSubjectId)
    }
}
