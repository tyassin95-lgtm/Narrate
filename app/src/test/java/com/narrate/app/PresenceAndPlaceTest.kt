package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.LocationLinkEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.ContinuityGuard
import com.narrate.app.engine.PlayerDelta
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.WorldDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where somebody is and whether they are in the scene are two different questions.
 *
 * A neighbour calling through his own front door at two people on the pavement was reported as
 * having appeared out of nowhere, because presence was decided by comparing location ids. And
 * a place discovered mid-scene had no parent and no route, so it floated in the corner of the
 * map and every arrival at it read as a jump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PresenceAndPlaceTest {

    private lateinit var repo: WorldRepository

    private val street = LocationEntity(id = "street", worldId = "w", name = "Maple Street", type = "DISTRICT")
    private val house = LocationEntity(
        id = "house", worldId = "w", name = "118 Maple Street", type = "BUILDING", parentId = "street"
    )
    private val pavement = LocationEntity(
        id = "pavement", worldId = "w", name = "Maple Street Outside 118 Maple Street",
        type = "LANDMARK", parentId = "street"
    )
    private val hall = LocationEntity(
        id = "hall", worldId = "w", name = "118 Maple Street Hallway", type = "ROOM", parentId = "house"
    )
    private val hospital = LocationEntity(
        id = "hospital", worldId = "w", name = "University Hospital", type = "BUILDING", parentId = "street"
    )

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "pavement"
    )
    private val jonah = CharacterEntity(id = "jonah", worldId = "w", name = "Jonah Bell", currentLocationId = "house")
    private val mara = CharacterEntity(id = "mara", worldId = "w", name = "Mara Chen", currentLocationId = "hospital")

    private fun snapshot(characters: List<CharacterEntity> = listOf(player, jonah, mara)) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "pavement", turnCount = 3),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(street, house, pavement, hall, hospital),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = emptyList(),
        memories = emptyList(),
        visualIdentities = emptyList()
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    @Test
    fun `a voice through the door is not somebody teleporting`() {
        // The reported warning, exactly: Jonah never came outside.
        val issues = ContinuityGuard.auditNarration(
            snapshot(),
            "Jonah calls through the closed door. \"Liv?\" Nobody answers him.",
            movedNames = emptySet()
        )
        val presence = issues.single { it.category == "presence" }
        assertEquals(
            "a man inside the house he is standing outside of has not moved",
            ContinuityGuard.SEVERITY_INFO,
            presence.severity
        )
        assertTrue(presence.description.contains("within earshot"))
    }

    @Test
    fun `somebody genuinely across town acting in the scene is still a warning`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(),
            "Mara turns from the desk and says, \"You're late again.\"",
            movedNames = emptySet()
        )
        val presence = issues.single { it.category == "presence" && it.description.contains("Mara") }
        assertEquals(ContinuityGuard.SEVERITY_WARNING, presence.severity)
    }

    @Test
    fun `earshot reaches through containment and across a threshold, and no further`() {
        val world = snapshot()
        assertTrue("the house he is standing outside", world.withinEarshot("house"))
        assertTrue("the street the pavement is part of", world.withinEarshot("street"))
        assertFalse("a building across the district", world.withinEarshot("hospital"))
        assertFalse("a room deep inside another building", world.withinEarshot("hall"))
    }

    @Test
    fun `answering somebody who is calling through a door is offered to the player`() {
        val verdict = ChoiceGuard.vet(
            snapshot(),
            listOf(
                Choice("c0", "Answer Jonah through the door", kind = "ACTION"),
                Choice("c1", "Ask Mara what she wants", kind = "ACTION")
            )
        )
        assertEquals(listOf("c0"), verdict.kept.map { it.id })
        assertTrue(verdict.rejected.single().reason.contains("not here"))
    }

    @Test
    fun `the narrator is told who is close enough to be heard`() {
        val state = WorldDigest.currentState(snapshot())
        assertTrue(state.contains("WITHIN EARSHOT"))
        assertTrue(state.contains("Jonah Bell: in 118 Maple Street"))
        assertTrue(state.contains("If one of them actually comes through, that is a move"))
        assertFalse("somebody across town is not in earshot", state.substringAfter("WITHIN EARSHOT")
            .substringBefore("##").contains("Mara Chen"))
    }

    @Test
    fun `a place discovered mid-scene is attached to the map rather than left floating`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "street"))
        repo.saveLocations(listOf(street, house))
        repo.saveCharacter(player.copy(currentLocationId = "street"))

        val applied = StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(player = PlayerDelta(location = "The third floor of 118 Maple Street")),
            turnIndex = 6,
            narration = "The stairwell light is out. Her door is the one at the end."
        )

        val created = repo.locationDao.all("w").first { it.name.contains("third floor", true) }
        assertEquals("it belongs to the building it names", "house", created.parentId)
        assertEquals("ROOM", created.type)
        assertEquals(created.id, applied.world.currentLocationId)
        assertTrue(
            "and there is a way to get there from where they set out",
            repo.linkDao.all("w").any {
                (it.fromId == "street" && it.toId == created.id) || (it.toId == "street" && it.fromId == created.id)
            }
        )
    }

    @Test
    fun `a place with nothing in its name still lands next to where it was found`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w2", name = "Calder City", currentLocationId = "street2"))
        repo.saveLocations(
            listOf(
                LocationEntity(id = "district2", worldId = "w2", name = "Eastgate", type = "DISTRICT"),
                LocationEntity(id = "street2", worldId = "w2", name = "Harker Street", parentId = "district2")
            )
        )
        repo.saveCharacter(
            CharacterEntity(id = "pc2", worldId = "w2", name = "Adrian", isPlayer = true, currentLocationId = "street2")
        )

        StateApplier(repo).apply(
            repo.snapshot("w2")!!,
            StateDelta(player = PlayerDelta(location = "The Juniper Cafe")),
            turnIndex = 2,
            narration = "The window is fogged from the inside."
        )

        val cafe = repo.locationDao.all("w2").first { it.name.contains("Juniper") }
        assertEquals("it sits in the district the player was standing in", "district2", cafe.parentId)
        assertNotNull(repo.linkDao.all("w2").firstOrNull { it.fromId == "street2" || it.toId == "street2" })
    }

    // --- Freeform input ------------------------------------------------------------------

    @Test
    fun `an instruction with a line of dialogue in it is not all dialogue`() {
        // Exactly what was typed, and exactly what came back quoted in full.
        val input = "Ask why she left the party, keeping your voice gentle. " +
            "\"Let me walk you home then I'll go home\""
        val turn = Prompts.readPlayerInput(input, "SPEECH")

        assertEquals(listOf("Let me walk you home then I'll go home"), turn.said)
        assertEquals("Ask why she left the party, keeping your voice gentle", turn.done)

        val instruction = Prompts.playerInputInstruction(input, "SPEECH")
        assertTrue(instruction.contains("WHAT THEY DO: Ask why she left the party"))
        assertTrue(instruction.contains("not something they said out loud"))
        assertTrue(instruction.contains("WHAT THEY SAY, WORD FOR WORD"))
        assertTrue(instruction.contains("Let me walk you home"))
        assertTrue("neither half may be dropped", instruction.contains("Losing half of"))
    }

    @Test
    fun `plain speech is still all speech`() {
        val turn = Prompts.readPlayerInput("I'm Adrian. I work nights at the hospital.", "SPEECH")
        assertEquals(listOf("I'm Adrian. I work nights at the hospital."), turn.said)
        assertEquals("", turn.done)
    }

    @Test
    fun `a plain action stays an action`() {
        val turn = Prompts.readPlayerInput("Search the desk drawers", "ACTION")
        assertEquals(emptyList<String>(), turn.said)
        assertEquals("Search the desk drawers", turn.done)
        assertFalse(
            Prompts.playerInputInstruction("Search the desk drawers", "ACTION")
                .contains("WHAT THEY SAY")
        )
    }

    @Test
    fun `several spoken lines in one message all survive`() {
        val turn = Prompts.readPlayerInput(
            "Hand her the water. \"Drink this.\" Then sit down opposite her. \"Start at the beginning.\"",
            "ACTION"
        )
        assertEquals(listOf("Drink this.", "Start at the beginning."), turn.said)
        assertTrue(turn.done.contains("Hand her the water"))
        assertTrue(turn.done.contains("sit down opposite her"))
    }
}
