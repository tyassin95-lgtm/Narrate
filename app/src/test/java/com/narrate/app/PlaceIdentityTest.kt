package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.CharacterUpdate
import com.narrate.app.engine.PlaceIdentity
import com.narrate.app.engine.PlayerDelta
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two flats are not one flat.
 *
 * The reported failure: the player walked Liv home, and because "Liv's apartment" and
 * "Adrian's apartment" share the words "s" and "apartment", the new address was merged into
 * the player's own and both of them were recorded standing in the wrong flat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaceIdentityTest {

    private lateinit var repo: WorldRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    @Test
    fun `places that differ only in whose they are are different places`() {
        assertFalse(PlaceIdentity.samePlace("Liv's apartment", "Adrian's apartment"))
        assertFalse(PlaceIdentity.samePlace("Adrian's flat", "Liv's flat"))
        assertFalse(PlaceIdentity.samePlace("Room 4B", "Room 2A"))
        assertFalse(PlaceIdentity.samePlace("Eastgate Rentals", "Eastgate Hospital"))
        assertFalse(PlaceIdentity.samePlace("The Gallery", "Harker Street"))
    }

    @Test
    fun `the same place written two ways is still the same place`() {
        assertTrue(PlaceIdentity.samePlace("Night Ward", "the Night Ward"))
        assertTrue(PlaceIdentity.samePlace("The Gallery", "the gallery"))
        assertTrue(PlaceIdentity.samePlace("Liv's apartment", "Liv's flat"))
        assertTrue(PlaceIdentity.samePlace("Harker Street", "Harker St"))
    }

    @Test
    fun `a name resolves to the most specific place that matches`() {
        val places = listOf(
            LocationEntity(id = "a", worldId = "w", name = "Eastgate Rentals"),
            LocationEntity(id = "b", worldId = "w", name = "Liv's apartment"),
            LocationEntity(id = "c", worldId = "w", name = "Adrian's apartment")
        )
        assertEquals("b", repo.resolveLocation(places, "Liv's apartment")?.id)
        assertEquals("c", repo.resolveLocation(places, "Adrian's apartment")?.id)
        assertEquals(
            "somewhere genuinely new must not be merged into an existing address",
            null,
            repo.resolveLocation(places, "Marguerite's apartment")?.id
        )
    }

    @Test
    fun `walking an NPC home puts everyone at her address, not the player's`() = runBlocking {
        val worldId = "w"
        repo.saveWorld(WorldEntity(id = worldId, name = "Eastgate", currentLocationId = "loc-adrian"))
        repo.saveLocations(
            listOf(
                LocationEntity(id = "loc-adrian", worldId = worldId, name = "Adrian's apartment", type = "BUILDING"),
                LocationEntity(id = "loc-street", worldId = worldId, name = "Harker Street", type = "DISTRICT")
            )
        )
        repo.saveCharacters(
            listOf(
                CharacterEntity(id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-street"),
                CharacterEntity(id = "liv", worldId = worldId, name = "Liv Carrow", currentLocationId = "loc-street")
            )
        )

        val snapshot = repo.snapshot(worldId)!!
        val applied = StateApplier(repo).apply(
            snapshot,
            StateDelta(
                player = PlayerDelta(location = "Liv's apartment"),
                charactersUpdate = listOf(
                    CharacterUpdate(
                        name = "Liv Carrow",
                        location = "Liv's apartment",
                        movementReason = "he walked her home"
                    )
                )
            ),
            turnIndex = 4,
            narration = "The stairwell light was out. She found her key by feel."
        )

        val places = repo.locationDao.all(worldId)
        val livs = places.first { it.name.equals("Liv's apartment", true) }
        assertNotEquals("her address is not his address", "loc-adrian", livs.id)
        assertEquals("the player is at her door, not his", livs.id, applied.world.currentLocationId)
        assertEquals(livs.id, repo.characterDao.player(worldId)!!.currentLocationId)
        assertEquals(livs.id, repo.characterDao.get("liv")!!.currentLocationId)

        val adrians = places.first { it.id == "loc-adrian" }
        assertEquals("his flat is untouched", "Adrian's apartment", adrians.name)
        assertEquals(
            "and it was not quietly merged away",
            1,
            places.count { it.name.equals("Adrian's apartment", true) }
        )
    }

    @Test
    fun `arriving somewhere already on the map does not create a second copy`() = runBlocking {
        val worldId = "w2"
        repo.saveWorld(WorldEntity(id = worldId, name = "Eastgate", currentLocationId = "loc-street"))
        repo.saveLocations(
            listOf(
                LocationEntity(id = "loc-street", worldId = worldId, name = "Harker Street", type = "DISTRICT"),
                LocationEntity(id = "loc-liv", worldId = worldId, name = "Liv's apartment", type = "BUILDING")
            )
        )
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-street")
        )

        val applied = StateApplier(repo).apply(
            repo.snapshot(worldId)!!,
            StateDelta(player = PlayerDelta(location = "the apartment Liv rents")),
            turnIndex = 5,
            narration = "Her door was the one with the bicycle against it."
        )

        assertEquals("loc-liv", applied.world.currentLocationId)
        assertEquals("no duplicate address", 2, repo.locationDao.all(worldId).size)
    }
}
