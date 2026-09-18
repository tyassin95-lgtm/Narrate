package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.ChapterEntity
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.WorldDigest
import com.narrate.app.ui.codex.CodexUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Everything a real nineteen-turn playthrough turned up.
 *
 * The transcript export earned its keep immediately: an NPC's home address drawn on the map
 * before the player had heard of it, a hole in the story where turns were too old to replay
 * and too new to be in a chapter, a chapter titled "## The Stranger", and a suggestion about
 * the player's own coat refused because somebody else was wearing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaythroughFixesTest {

    private lateinit var repo: WorldRepository
    private val worldId = "w"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    // --- The map is the player's knowledge, not the world's index ------------------------

    @Test
    fun `an NPC's home is not drawn on the map before the player has heard of it`() {
        val state = CodexUiState(
            locations = listOf(
                LocationEntity(id = "street", worldId = worldId, name = "Maple Street", discovered = true),
                LocationEntity(id = "flat", worldId = worldId, name = "Adrian's Apartment", discovered = true),
                LocationEntity(id = "home", worldId = worldId, name = "Nina Alvarez's Shared Apartment", discovered = false),
                LocationEntity(id = "cafe", worldId = worldId, name = "Juniper Cafe", discovered = false)
            )
        )

        assertEquals(
            listOf("Maple Street", "Adrian's Apartment"),
            state.discoveredLocations.map { it.name }
        )
        assertTrue(
            "the world still keeps them, so people have somewhere to be",
            state.locations.any { it.name == "Nina Alvarez's Shared Apartment" }
        )
    }

    // --- No hole in the middle of the story ---------------------------------------------

    @Test
    fun `turns too old to replay and too new for a chapter are still in the digest`() = runBlocking {
        repo.saveWorld(WorldEntity(id = worldId, name = "Calder City", currentLocationId = "loc", turnCount = 19))
        repo.saveLocation(LocationEntity(id = "loc", worldId = worldId, name = "Maple Street"))
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc")
        )
        (0..18).forEach { index ->
            repo.saveTurn(
                TurnEntity(
                    id = "t$index", worldId = worldId, index = index,
                    narration = "Turn $index happened.", summary = "Summary of turn $index.",
                    storyTime = "Day 1", locationName = "Maple Street"
                )
            )
        }
        repo.saveChapter(
            ChapterEntity(
                id = "ch", worldId = worldId, title = "The Stranger Who Would Not Go Home",
                summary = "They met on the pavement.", fromTurn = 0, toTurn = 3, storyTime = "Day 1"
            )
        )

        // A twelve-turn replay window, as the reported save had.
        val snapshot = repo.snapshot(worldId, recentTurnWindow = 12)!!
        assertEquals("turns 4 to 6 are the gap", listOf(4, 5, 6), snapshot.earlierTurns.map { it.index })

        val history = WorldDigest.history(snapshot)
        assertTrue(history.contains("The Stranger Who Would Not Go Home"))
        assertTrue(history.contains("SINCE THEN"))
        assertTrue(history.contains("Summary of turn 4."))
        assertTrue(history.contains("Summary of turn 6."))
        assertFalse("what is replayed in full is not repeated here", history.contains("Summary of turn 7."))
    }

    @Test
    fun `a world with nothing older than its replay window has no gap section`() = runBlocking {
        repo.saveWorld(WorldEntity(id = worldId, name = "Calder City", turnCount = 3))
        (0..2).forEach {
            repo.saveTurn(TurnEntity(id = "t$it", worldId = worldId, index = it, summary = "s$it"))
        }
        val snapshot = repo.snapshot(worldId, recentTurnWindow = 8)!!
        assertTrue(snapshot.earlierTurns.isEmpty())
        assertFalse(WorldDigest.history(snapshot).contains("SINCE THEN"))
    }

    // --- The player's own coat -----------------------------------------------------------

    private fun coatSnapshot(): WorldSnapshot {
        val player = CharacterEntity(
            id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc"
        )
        val liv = CharacterEntity(id = "liv", worldId = worldId, name = "Liv", currentLocationId = "loc")
        return WorldSnapshot(
            world = WorldEntity(id = worldId, name = "Calder City", currentLocationId = "loc", turnCount = 12),
            player = player,
            characters = listOf(player, liv),
            locations = listOf(LocationEntity(id = "loc", worldId = worldId, name = "Liv's Rented Room")),
            links = emptyList(),
            items = listOf(
                ItemEntity(
                    id = "coat", worldId = worldId, name = "Resident physician's coat",
                    ownerId = "pc", holderId = "liv", state = "Loaned to Liv and being worn"
                )
            ),
            factions = emptyList(),
            relationships = emptyList(),
            threads = emptyList(),
            chapters = emptyList(),
            recentTurns = emptyList(),
            memories = emptyList(),
            visualIdentities = emptyList()
        )
    }

    @Test
    fun `telling her to keep the coat is a real move and survives`() {
        val verdict = ChoiceGuard.vet(
            coatSnapshot(),
            listOf(
                Choice("c0", "\"Keep the coat tonight. I'll manage the walk home.\"", kind = "SPEECH"),
                Choice("c1", "\"You can keep it until you're warm.\"", kind = "SPEECH"),
                Choice("c2", "Ask her for the coat back before you go", kind = "ACTION")
            )
        )
        assertEquals(
            "the coat is his to talk about, whoever is wearing it",
            listOf("c0", "c1", "c2"),
            verdict.kept.map { it.id }
        )
    }

    @Test
    fun `handing his own coat back to the person borrowing it is still refused`() {
        val verdict = ChoiceGuard.vet(
            coatSnapshot(),
            listOf(
                Choice("c0", "Offer to return her coat now that she is inside", kind = "ACTION"),
                Choice("c1", "Give her back her coat", kind = "ACTION")
            )
        )
        assertTrue(verdict.kept.isEmpty())
        assertTrue(verdict.rejected.all { it.category == "item-ownership" })
    }

    // --- The doctrine the transcript showed was missing ----------------------------------

    @Test
    fun `the narrator is told not to narrate its own machinery`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Calder City"))
        assertTrue(prompt.contains("NEVER NARRATE THE RULES THEMSELVES"))
        assertTrue(prompt.contains("is dialogue"))
        assertTrue(prompt.contains("the machinery showing through the prose"))
    }

    @Test
    fun `a place is named like a place`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Calder City"))
        assertTrue(prompt.contains("Maple Street Outside 214 Maple Street"))
        assertTrue(prompt.contains("never"))
    }

    @Test
    fun `an answer belongs to whoever gave it`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Calder City"))
        assertTrue(prompt.contains("His answers are his, and they are attributed to him"))
        assertTrue(prompt.contains("Never have the person who asked"))
    }
}
