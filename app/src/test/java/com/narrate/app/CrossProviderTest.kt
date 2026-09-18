package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.ContinuityGuard
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
 * Two playthroughs of the same world, one on Grok and one on GPT, read side by side.
 *
 * The failures they share are the app's, not the model's: a turn logged at "unknown" because
 * the place was invented during it, a pavement filed inside the house it stands outside of, a
 * boyfriend on the telephone reported as having walked into the flat, a street called Cedar
 * reported as a missing person, and a suggestion offering back the line the player had just
 * spoken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossProviderTest {

    private lateinit var repo: WorldRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    // --- Where the turn happened ---------------------------------------------------------

    @Test
    fun `a turn that invents the place it ends in is not logged as unknown`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "street"))
        repo.saveLocations(
            listOf(LocationEntity(id = "street", worldId = "w", name = "Juniper Street", type = "LANDMARK"))
        )
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "street")
        )

        val applied = StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(player = PlayerDelta(location = "Voss Apartment 3B")),
            turnIndex = 4,
            narration = "The stairwell light clicks on as they climb."
        )

        assertEquals("Voss Apartment 3B", applied.currentLocationName)
        assertNotEquals("unknown", applied.currentLocationName)
    }

    // --- Outside is not inside -----------------------------------------------------------

    @Test
    fun `the pavement outside a house is beside it, not in it`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "street"))
        repo.saveLocations(
            listOf(
                LocationEntity(id = "district", worldId = "w", name = "Eastgate Residential Streets", type = "DISTRICT"),
                LocationEntity(id = "street", worldId = "w", name = "Maple Street", type = "LANDMARK", parentId = "district"),
                LocationEntity(id = "rental", worldId = "w", name = "Liv's Shared Rental", type = "BUILDING", parentId = "district")
            )
        )
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = "w", name = "Adrian", isPlayer = true, currentLocationId = "street")
        )

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(player = PlayerDelta(location = "Eastgate Sidewalk outside Liv's Shared Rental")),
            turnIndex = 8,
            narration = "They stop at the gate."
        )

        val pavement = repo.locationDao.all("w").first { it.name.contains("Sidewalk outside") }
        assertEquals("it belongs beside the rental, in what contains them both", "district", pavement.parentId)
        assertEquals("and a pavement is not a room", "LANDMARK", pavement.type)
        assertTrue(
            "with a way onto the property it stands outside of",
            repo.linkDao.all("w").any {
                (it.fromId == "rental" && it.toId == pavement.id) || (it.toId == "rental" && it.fromId == pavement.id)
            }
        )
    }

    @Test
    fun `a landing outside a flat is in the building, not in the flat`() {
        assertEquals("Adrian's Apartment", PlaceIdentity.standsOutside("Landing outside Adrian's Apartment"))
        assertEquals("Liv's Shared Rental", PlaceIdentity.standsOutside("Eastgate Sidewalk outside Liv's Shared Rental"))
        assertEquals(null, PlaceIdentity.standsOutside("Voss Apartment 3B Bedroom"))
        assertEquals("LANDMARK", PlaceIdentity.typeFromName("Eastgate Sidewalk outside 214 Maple Street", "ROOM"))
        assertEquals("ROOM", PlaceIdentity.typeFromName("Voss Apartment 3B Main Room", "BUILDING"))
    }

    // --- A phone call is not an arrival ---------------------------------------------------

    private val flat = LocationEntity(id = "flat", worldId = "w", name = "Voss Apartment 3B", type = "BUILDING")
    private val juniper = LocationEntity(id = "juniper", worldId = "w", name = "418 Juniper", type = "BUILDING")
    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "flat"
    )
    private val liv = CharacterEntity(id = "liv", worldId = "w", name = "Liv Mercer", currentLocationId = "flat")
    private val daniel = CharacterEntity(id = "dan", worldId = "w", name = "Daniel Cross", currentLocationId = "juniper")

    private fun snapshot(turns: List<TurnEntity> = emptyList()) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "flat", turnCount = 12),
        player = player,
        characters = listOf(player, liv, daniel),
        locations = listOf(flat, juniper),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = turns,
        memories = emptyList(),
        visualIdentities = emptyList()
    )

    @Test
    fun `a boyfriend on the telephone has not walked into the flat`() {
        // Narrated as prose rather than in a call block, which is what one provider did.
        val narration = """
            The phone lights up on the table between them. Liv answers it in the hallway.

            Daniel says, "Tessa misunderstood what she saw," and the line goes quiet for a
            moment. Daniel calls back twice more before she blocks the number.
        """.trimIndent()

        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = emptySet())
        assertTrue(
            "a voice on a call is not a body in the room: ${issues.map { it.description }}",
            issues.none { it.category == "presence" && it.description.contains("Daniel") }
        )
    }

    @Test
    fun `somebody who actually walks in is still reported`() {
        val narration = "Daniel steps through the door without knocking. \"We need to talk,\" he says."
        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = emptySet())
        assertTrue(issues.any { it.category == "presence" && it.description.contains("Daniel") })
    }

    // --- Cedar is a street ----------------------------------------------------------------

    @Test
    fun `a street the player keeps walking down is not a missing person`() {
        val recent = (5..8).map {
            TurnEntity(
                id = "t$it", worldId = "w", index = it,
                narration = "She says she is just over on Cedar, two streets that way, and they keep walking."
            )
        }
        val issues = ContinuityGuard.auditNarration(
            snapshot(recent),
            "They turn toward Cedar, the houses thinning as they go.",
            movedNames = emptySet()
        )
        assertTrue(
            "Cedar is somewhere, not somebody: ${issues.map { it.description }}",
            issues.none { it.category == "unrecorded-person" }
        )
    }

    @Test
    fun `a pronoun is never reported as an uncatalogued character`() {
        val recent = (5..8).map {
            TurnEntity(
                id = "t$it", worldId = "w", index = it,
                narration = "He watches her go. She said something about the party, then, \"She told me anyway.\""
            )
        }
        val issues = ContinuityGuard.auditNarration(
            snapshot(recent),
            "She keeps her hands in the sleeves of the jacket. He says nothing.",
            movedNames = emptySet()
        )
        assertTrue(issues.none { it.category == "unrecorded-person" })
    }

    @Test
    fun `a person the story keeps discussing is still reported`() {
        val recent = (5..8).map {
            TurnEntity(
                id = "t$it", worldId = "w", index = it,
                narration = "She reads it twice. It was Tessa who told her, and Tessa's message is still open."
            )
        }
        val issues = ContinuityGuard.auditNarration(
            snapshot(recent),
            "She says Tessa warned her weeks ago and she did not listen.",
            movedNames = emptySet()
        )
        assertTrue(issues.any { it.category == "unrecorded-person" && it.description.contains("Tessa") })
    }

    // --- Suggestions that offer nothing ---------------------------------------------------

    @Test
    fun `the line the player just spoke is not offered back to them`() {
        val lastTurn = TurnEntity(
            id = "t6", worldId = "w", index = 6, inputType = "CHOICE",
            playerInput = "\"Yeah, overthinking sounds familiar.\""
        )
        val verdict = ChoiceGuard.vet(
            snapshot(listOf(lastTurn)),
            listOf(
                Choice("c0", "\"Yeah, overthinking sounds familiar.\"", detail = "already said", kind = "SPEECH"),
                Choice("c1", "Ask how the party went", kind = "SPEECH")
            )
        )
        assertEquals(listOf("c1"), verdict.kept.map { it.id })
        assertEquals("already-done", verdict.rejected.single().category)
    }

    // --- Invariants that hold whichever model answered ------------------------------------

    @Test
    fun `the clock is read the same whichever way a provider writes it`() {
        // "Day 1, 2:16 AM" from one provider, "Day 1, 02:16" from the other.
        assertEquals(com.narrate.app.engine.StoryClock.read("Day 1, 2:16 AM")?.minutes, 136)
        assertEquals(com.narrate.app.engine.StoryClock.read("Day 1, 02:16")?.minutes, 136)
        assertEquals(com.narrate.app.engine.StoryClock.read("Day 2, 8:05 AM")?.day, 2)
        assertEquals(com.narrate.app.engine.StoryClock.read("Day 1, 11:30 PM")?.minutes, 23 * 60 + 30)
        assertEquals("vague is not wrong, only vague", null, com.narrate.app.engine.StoryClock.read("Day 1, evening"))
    }

    @Test
    fun `time is not allowed to run backwards inside a day`() {
        assertTrue(com.narrate.app.engine.StoryClock.wentBackwards("Day 1, 02:30", "Day 1, 2:16 AM"))
        assertFalse(com.narrate.app.engine.StoryClock.wentBackwards("Day 1, 2:16 AM", "Day 1, 02:30"))
        assertFalse("a new day starts over, and that is not backwards",
            com.narrate.app.engine.StoryClock.wentBackwards("Day 1, 23:40", "Day 2, 00:10"))
        assertFalse("nothing to compare is nothing to complain about",
            com.narrate.app.engine.StoryClock.wentBackwards("Day 1, early morning", "Day 1, 02:16"))
    }

    @Test
    fun `the world follows the player when the two disagree about where they are`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w3", name = "Calder City", currentLocationId = "street3"))
        repo.saveLocations(
            listOf(
                LocationEntity(id = "street3", worldId = "w3", name = "Juniper Street", type = "LANDMARK"),
                LocationEntity(id = "flat3", worldId = "w3", name = "Voss Apartment 3B", type = "BUILDING")
            )
        )
        repo.saveCharacter(
            CharacterEntity(id = "pc3", worldId = "w3", name = "Adrian", isPlayer = true, currentLocationId = "street3")
        )

        // A provider that moves the character without touching the world row.
        val applied = StateApplier(repo).apply(
            repo.snapshot("w3")!!,
            StateDelta(
                charactersUpdate = listOf(
                    com.narrate.app.engine.CharacterUpdate(
                        name = "Adrian", location = "Voss Apartment 3B", movementReason = "he went up"
                    )
                )
            ),
            turnIndex = 5,
            narration = "He climbs the stairs and lets himself in."
        )

        assertEquals("flat3", applied.world.currentLocationId)
        assertEquals("flat3", repo.worldDao.get("w3")!!.currentLocationId)
        assertEquals("Voss Apartment 3B", applied.currentLocationName)
    }

    @Test
    fun `a genuinely new suggestion is never mistaken for a repeat`() {
        val lastTurn = TurnEntity(
            id = "t6", worldId = "w", index = 6, inputType = "ACTION", playerInput = "Walk her to the gate"
        )
        val verdict = ChoiceGuard.vet(
            snapshot(listOf(lastTurn)),
            listOf(
                Choice("c0", "Wait at the gate until she is inside", kind = "ACTION"),
                Choice("c1", "Walk back toward Maple Street", kind = "ACTION")
            )
        )
        assertEquals(2, verdict.kept.size)
    }
}
