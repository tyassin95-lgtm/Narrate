package com.narrate.app

import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.SceneCompany
import com.narrate.app.engine.WorldDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standing in the same room is not being in the conversation.
 *
 * The reported scene: dinner with Liv, the waiter takes the order, and then the waiter is in
 * the middle of a private conversation - because the state file said "present" and nothing
 * said in what capacity.
 */
class SceneCompanyTest {

    private val restaurant = LocationEntity(id = "loc-rest", worldId = "w", name = "Bellini's", type = "BUILDING")

    private val adrian = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-rest"
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv Carrow", role = "graphic designer",
        currentLocationId = "loc-rest", importance = 4
    )
    private val waiter = CharacterEntity(
        id = "waiter", worldId = "w", name = "Tomas", role = "waiter at Bellini's",
        currentLocationId = "loc-rest", importance = 2
    )
    private val diner = CharacterEntity(
        id = "diner", worldId = "w", name = "A man at the bar", role = "regular",
        currentLocationId = "loc-rest", importance = 1
    )

    private fun snapshot(
        characters: List<CharacterEntity> = listOf(adrian, liv, waiter, diner),
        previouslyPresent: String = "liv"
    ) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Eastgate", currentLocationId = "loc-rest", turnCount = 12),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(restaurant),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = listOf(
            TurnEntity(
                id = "t11", worldId = "w", index = 11,
                narration = "They took the table by the window.", summary = "dinner",
                presentCharacterIds = previouslyPresent
            )
        ),
        memories = emptyList(),
        visualIdentities = emptyList()
    )

    @Test
    fun `the person the player came with is the scene, the waiter is not`() {
        val company = SceneCompany.assess(snapshot())
        assertEquals(listOf("Liv Carrow"), company.with.map { it.name })
        assertEquals(listOf("Tomas", "A man at the bar"), company.alsoHere.map { it.name })
    }

    @Test
    fun `staff who have been in the scene for turns are company, whatever their job is`() {
        // A nurse you have worked beside all night is not background because she is staff.
        val nurse = CharacterEntity(
            id = "nurse", worldId = "w", name = "Priya Raman", role = "night-shift nurse",
            currentLocationId = "loc-rest", importance = 3
        )
        val company = SceneCompany.assess(
            snapshot(characters = listOf(adrian, nurse, waiter), previouslyPresent = "nurse")
        )
        assertEquals(listOf("Priya Raman"), company.with.map { it.name })
        assertEquals(listOf("Tomas"), company.alsoHere.map { it.name })
    }

    @Test
    fun `the narrator is told who is in the conversation and who is furniture`() {
        val rendered = SceneCompany.render(snapshot())
        assertTrue(rendered.contains("WHO ADRIAN VOSS IS WITH"))
        assertTrue(rendered.contains("Liv Carrow"))
        assertTrue(rendered.contains("ALSO IN THE ROOM (present, not in the conversation)"))
        assertTrue(rendered.contains("Tomas"))
        assertTrue(rendered.contains("A waiter takes the order and goes"))
    }

    @Test
    fun `the split reaches the state file the narrator actually reads`() {
        val state = WorldDigest.currentState(snapshot())
        assertTrue("presence is still authoritative", state.contains("PRESENT IN THIS LOCATION"))
        assertTrue(state.contains("WHO ADRIAN VOSS IS WITH"))
        assertTrue(state.contains("ALSO IN THE ROOM"))
    }

    @Test
    fun `an empty room says nothing about company`() {
        val alone = snapshot(characters = listOf(adrian))
        assertEquals("", SceneCompany.render(alone))
        assertTrue(WorldDigest.currentState(alone).contains("nobody else is in this location"))
    }

    @Test
    fun `the doctrine names the test the narrator has to pass before interrupting`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("WHO IS IN THE CONVERSATION"))
        assertTrue(prompt.contains("Being in the room is not being in the conversation"))
        assertTrue(prompt.contains("could they actually hear it"))
        assertTrue(prompt.contains("A private conversation in a public place is still private"))
    }

    @Test
    fun `the world is still allowed to interrupt for a reason`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("Interruptions still happen, and they should"))
        assertTrue(prompt.contains("whether you"))
        assertTrue(
            "the test is a named reason, not the mere existence of another person",
            prompt.contains("not \"somebody else is here, so they")
        )
    }
}
