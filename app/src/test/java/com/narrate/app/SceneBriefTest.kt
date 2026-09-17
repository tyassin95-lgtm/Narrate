package com.narrate.app

import com.narrate.app.data.entity.*
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.SceneBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the narrator is told about the present moment before it writes the choices. */
class SceneBriefTest {

    private val sidewalk = LocationEntity(id = "loc-walk", worldId = "w", name = "Eastgate Sidewalk")
    private val adrian = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-walk"
    )
    private val liv = CharacterEntity(
        id = "npc-liv", worldId = "w", name = "Liv Marchetti", currentLocationId = "loc-walk",
        physicalState = "shivering, soaked through", affinity = 12,
        relationshipToPlayer = "a stranger he just walked into"
    )
    private val jacket = ItemEntity(
        id = "item-jacket", worldId = "w", name = "wool jacket", ownerId = "pc", holderId = "npc-liv"
    )
    private val badge = ItemEntity(
        id = "item-badge", worldId = "w", name = "hospital ID badge", ownerId = "pc", holderId = "pc"
    )

    private fun snapshot(turns: List<TurnEntity> = emptyList(), items: List<ItemEntity> = listOf(jacket, badge)) =
        WorldSnapshot(
            world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "loc-walk", turnCount = 8),
            player = adrian,
            characters = listOf(adrian, liv),
            locations = listOf(sidewalk),
            links = emptyList(),
            items = items,
            factions = emptyList(),
            relationships = emptyList(),
            threads = emptyList(),
            chapters = emptyList(),
            recentTurns = turns,
            memories = emptyList(),
            visualIdentities = emptyList()
        )

    private fun turn(narration: String) = TurnEntity(
        id = "t", worldId = "w", index = 7, narration = narration, locationName = "Eastgate Sidewalk"
    )

    @Test
    fun `a question left hanging is picked up`() {
        val narration = """
            She pulls the collar tighter. The rain has not let up since midnight.

            "You're a doctor, then? At the University?"
        """.trimIndent()
        assertEquals("You're a doctor, then? At the University?", SceneBrief.openQuestion(narration))
    }

    @Test
    fun `a question from early in the scene is not treated as still open`() {
        val narration = """
            "Do you have the time?" she had asked, an hour ago, and you had not answered.

            Now she says nothing at all. The bus pulls away without either of you on it, and the
            street is quiet again but for the rain on the awnings and the hum of the sign above
            the shuttered pharmacy across the road.
        """.trimIndent()
        assertNull(SceneBrief.openQuestion(narration))
    }

    @Test
    fun `a scene with nobody asking anything has no open question`() {
        assertNull(SceneBrief.openQuestion("She nods once and looks at the road."))
        assertNull(SceneBrief.openQuestion(""))
        assertNull(SceneBrief.openQuestion("\"Thanks for the coat.\""))
    }

    @Test
    fun `markup never reaches the reasoning about the scene`() {
        val narration = "[[sms from=\"Liv\"]]Are you still awake?[[/sms]] **She waits.**"
        assertEquals("Are you still awake?", SceneBrief.openQuestion(narration))
        assertTrue(!SceneBrief.closingMoment(narration).contains("[["))
        assertTrue(!SceneBrief.closingMoment(narration).contains("**"))
    }

    @Test
    fun `the brief names the player and frames every option as theirs`() {
        val brief = SceneBrief.render(snapshot(), "Help her up", "ACTION")
        assertTrue(brief.contains("The player is Adrian Voss"))
        assertTrue(brief.contains("could do or say next, written from their side of the scene"))
    }

    @Test
    fun `the brief carries the question and asks for an answer among the options`() {
        // A question that decides something stays the player's, and the options must answer it.
        val brief = SceneBrief.render(
            snapshot(turns = listOf(turn("She looks up. \"Do you want to come up for a coffee?\""))),
            "", "ACTION"
        )
        assertTrue(brief.contains("Do you want to come up for a coffee?"))
        assertTrue(brief.contains("the player's to answer"))
        assertTrue(brief.contains("written as the words they would say"))
    }

    @Test
    fun `the brief describes who is present and what state they are in`() {
        val brief = SceneBrief.render(snapshot(), "", "ACTION")
        assertTrue(brief.contains("Liv Marchetti"))
        assertTrue(brief.contains("shivering, soaked through"))
        assertTrue(brief.contains("a stranger he just walked into"))
        assertTrue(brief.contains("warmth 12"))
    }

    @Test
    fun `the brief separates what the player holds from what is theirs but lent`() {
        val brief = SceneBrief.render(snapshot(), "", "ACTION")
        assertTrue(brief.contains("hospital ID badge"))
        assertTrue(brief.contains("theirs, in hand, available to use or offer"))
        assertTrue(brief.contains("wool jacket is Adrian Voss's but Liv Marchetti has it"))
        assertTrue(brief.contains("never have Adrian Voss offer to return it to them"))
    }

    @Test
    fun `an empty-handed player is told not to be offered props`() {
        val brief = SceneBrief.render(snapshot(items = emptyList()), "", "ACTION")
        assertTrue(brief.contains("Nothing in hand"))
        assertTrue(brief.contains("do not have"))
    }

    @Test
    fun `what the player just said is quoted back as theirs`() {
        val spoken = SceneBrief.render(snapshot(), "Are you alright?", "SPEECH")
        assertTrue(spoken.contains("Adrian Voss said: \"Are you alright?\""))
        val acted = SceneBrief.render(snapshot(), "Pick up her bag", "ACTION")
        assertTrue(acted.contains("Adrian Voss: Pick up her bag"))
    }
}
