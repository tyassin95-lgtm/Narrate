package com.narrate.app

import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.ConceptCompleteness
import com.narrate.app.engine.ContinuityGuard
import com.narrate.app.engine.PlaceIdentity
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.StyleWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second pass over the nineteen-turn transcript, where the remaining problems were less
 * about facts than about what the facts mean: a room recorded as a building, a phone that was
 * switched off and then answered, a man who drove the whole evening without existing, and a
 * radiator that ticked in every single turn.
 */
class WorldModelTest {

    private val house = LocationEntity(id = "house", worldId = "w", name = "Liv's House", type = "BUILDING")
    private val room = LocationEntity(
        id = "room", worldId = "w", name = "Liv's Rented Room", type = "ROOM", parentId = "house"
    )
    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "room"
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv Mercer", currentLocationId = "room", playerContact = "PHONE"
    )

    private fun snapshot(turns: List<TurnEntity> = emptyList()) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "room", turnCount = 18),
        player = player,
        characters = listOf(player, liv),
        locations = listOf(house, room),
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

    private fun turn(index: Int, narration: String) = TurnEntity(
        id = "t$index", worldId = "w", index = index, narration = narration, summary = "turn $index"
    )

    // --- A room is not a building --------------------------------------------------------

    @Test
    fun `a place named like a room is recorded as a room`() {
        assertEquals("ROOM", PlaceIdentity.typeFromName("Liv's Rented Room", "BUILDING"))
        assertEquals("ROOM", PlaceIdentity.typeFromName("The Night Ward", "BUILDING"))
        assertEquals("ROOM", PlaceIdentity.typeFromName("Caleb Rusk's Office", ""))
        assertEquals("BUILDING", PlaceIdentity.typeFromName("Liv's House", "ROOM"))
        assertEquals("BUILDING", PlaceIdentity.typeFromName("Maple Court Apartments", "BUILDING"))
        assertEquals("a type nothing argues with is left alone", "DISTRICT", PlaceIdentity.typeFromName("Eastgate", "DISTRICT"))
    }

    @Test
    fun `the narrator is told a house and a bedroom are different places`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Calder City"))
        assertTrue(prompt.contains("Places contain each other"))
        assertTrue(prompt.contains("shared house is not the same place as somebody's bedroom"))
        assertTrue(prompt.contains("THE CLOCK MOVES"))
    }

    // --- A phone that was switched off ---------------------------------------------------

    @Test
    fun `a message from a phone she just switched off is caught`() {
        val previous = turn(
            17,
            "Liv opens the door a few inches. \"I might not answer straight away,\" she says. " +
                "\"I'm going to turn the phone off for a while.\""
        )
        val issues = ContinuityGuard.auditNarration(
            snapshot(listOf(previous)),
            "He reaches the pavement.\n\n[[sms from=\"Liv\"]]Settled. Door locked.[[/sms]]",
            movedNames = emptySet()
        )
        val flagged = issues.single { it.category == "device-state" }
        assertTrue(flagged.description.contains("Liv Mercer"))
        assertTrue(flagged.resolution.contains("stays off"))
    }

    @Test
    fun `an ordinary message from a phone nobody turned off is not flagged`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(listOf(turn(17, "She locks the door behind her."))),
            "[[sms from=\"Liv\"]]Settled.[[/sms]]",
            movedNames = emptySet()
        )
        assertTrue(issues.none { it.category == "device-state" })
    }

    // --- Evan ----------------------------------------------------------------------------

    @Test
    fun `somebody the story keeps talking about who does not exist is reported`() {
        val recent = listOf(
            turn(15, "She says, \"It was Evan. He told them.\""),
            turn(16, "The messages are from Evan, all four of them."),
            turn(17, "She will not say Evan's name again tonight.")
        )
        val issues = ContinuityGuard.auditNarration(
            snapshot(recent),
            "He thinks about what she said about Evan on the walk back.",
            movedNames = emptySet()
        )
        val flagged = issues.single { it.category == "unrecorded-person" }
        assertTrue(flagged.description.contains("Evan"))
        assertTrue(flagged.resolution.contains("characters_new"))
    }

    @Test
    fun `a name mentioned once is not turned into a character`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(listOf(turn(17, "The ward was quiet."))),
            "She mentions a girl called Priya from her course, once, and moves on.",
            movedNames = emptySet()
        )
        assertTrue(issues.none { it.category == "unrecorded-person" })
    }

    @Test
    fun `somebody already in the world is never reported as missing from it`() {
        val recent = (15..17).map { turn(it, "Liv Mercer says something about the coat.") }
        val issues = ContinuityGuard.auditNarration(
            snapshot(recent),
            "Liv Mercer pulls the coat tighter.",
            movedNames = emptySet()
        )
        assertTrue(issues.none { it.category == "unrecorded-person" })
    }

    // --- The radiator ---------------------------------------------------------------------

    @Test
    fun `an image used in every turn is handed back to the narrator as a habit`() {
        val turns = (14..18).map {
            turn(
                it,
                "The radiator ticks behind her. Her phone buzzes against the counter, and the " +
                    "glitter on her cheek catches the light."
            )
        }
        val overused = StyleWatch.overusedImages(snapshot(turns))
        assertTrue("radiator: $overused", overused.contains("radiator"))
        assertTrue(overused.contains("glitter"))
        assertTrue(overused.contains("buzzes") || overused.contains("phone"))

        val rendered = StyleWatch.render(snapshot(turns))
        assertTrue(rendered.contains("IMAGES YOU HAVE ALREADY USED"))
        assertTrue(rendered.contains("write less rather than describing the"))
    }

    @Test
    fun `a name is never counted as a stylistic tic`() {
        val turns = (14..18).map { turn(it, "Liv looks at Adrian Voss across the room in Calder City.") }
        val overused = StyleWatch.overusedImages(snapshot(turns))
        assertFalse("a story about Liv is supposed to keep saying Liv", overused.contains("liv"))
        assertFalse(overused.contains("calder"))
    }

    @Test
    fun `a scene with varied prose is left alone`() {
        val turns = listOf(
            turn(14, "The stairwell smells of concrete dust."),
            turn(15, "A bus exhales at the stop and pulls away."),
            turn(16, "She sets the glass down on the counter."),
            turn(17, "The key turns on the second attempt."),
            turn(18, "Someone laughs in the flat above.")
        )
        assertEquals(emptyList<String>(), StyleWatch.overusedImages(snapshot(turns)))
        assertEquals("", StyleWatch.render(snapshot(turns)))
    }

    // --- Fields that should stay empty ------------------------------------------------------

    @Test
    fun `a character with no secret is not given one`() {
        val concept = CharacterConcept(
            name = "Adrian Voss", role = "resident", summary = "A tired doctor.",
            personality = "Shy, quiet.", backstory = "Group homes.", appearance = "Dark hair.",
            outfit = "Scrubs.", voice = "Quiet.", goals = "Finish the year.", tiesToWorld = "He works nights."
        )
        val missing = ConceptCompleteness.missingCharacterFields(concept)
        assertFalse("a secret is not a field to fill: $missing", missing.contains("secrets"))
        assertFalse(missing.contains("fears"))
        assertTrue("everything that genuinely belongs on a sheet is still required", missing.isEmpty())
    }

    @Test
    fun `the narrator is told to keep its guesses out of the record`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Calder City"))
        assertTrue(prompt.contains("not what you think somebody felt about"))
        assertTrue(prompt.contains("Anyone who matters to the story exists"))
        assertTrue(prompt.contains("SHOW IT, DO NOT EXPLAIN IT"))
        assertTrue(prompt.contains("DO NOT PAD"))
    }
}
