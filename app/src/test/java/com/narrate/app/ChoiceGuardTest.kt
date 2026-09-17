package com.narrate.app

import com.narrate.app.data.entity.*
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suggested actions are checked against the world before the player sees them.
 *
 * The case that prompted this: the player lent Liv his jacket in turn one, and by turn eight
 * the game offered "Ask if she wants her jacket back" - handing the player's own coat away and
 * inverting who owned it.
 */
class ChoiceGuardTest {

    private val sidewalk = LocationEntity(id = "loc-walk", worldId = "w", name = "Eastgate Sidewalk")
    private val hospital = LocationEntity(id = "loc-hosp", worldId = "w", name = "University Hospital")

    private val adrian = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-walk"
    )
    private val liv = CharacterEntity(
        id = "npc-liv", worldId = "w", name = "Liv Marchetti", currentLocationId = "loc-walk",
        physicalState = "shivering, soaked through"
    )
    private val marcus = CharacterEntity(
        id = "npc-marcus", worldId = "w", name = "Marcus Reyes", currentLocationId = "loc-hosp"
    )

    /** Adrian's jacket, lent to Liv. His property; her shoulders. */
    private val jacket = ItemEntity(
        id = "item-jacket", worldId = "w", name = "wool jacket",
        ownerId = "pc", holderId = "npc-liv"
    )
    private val badge = ItemEntity(
        id = "item-badge", worldId = "w", name = "hospital ID badge", ownerId = "pc", holderId = "pc"
    )
    private val umbrella = ItemEntity(
        id = "item-umbrella", worldId = "w", name = "umbrella", locationId = "loc-hosp"
    )

    private fun snapshot(
        characters: List<CharacterEntity> = listOf(adrian, liv, marcus),
        items: List<ItemEntity> = listOf(jacket, badge, umbrella)
    ) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "loc-walk", turnCount = 8),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(sidewalk, hospital),
        links = emptyList(),
        items = items,
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = emptyList(),
        memories = emptyList(),
        visualIdentities = emptyList()
    )

    private fun choice(label: String, detail: String = "", kind: String = "ACTION") =
        Choice("c", label, detail, kind)

    private fun vet(vararg labels: String) = ChoiceGuard.vet(snapshot(), labels.map { choice(it) })

    @Test
    fun `the reported case is rejected`() {
        val verdict = vet("Ask if she wants her jacket back.")
        assertTrue("it must not reach the player", verdict.kept.isEmpty())
        assertEquals("item-ownership", verdict.rejected.single().category)
        assertTrue(verdict.rejected.single().reason.contains("belongs to the player"))
        assertTrue(verdict.rejected.single().reason.contains("Liv Marchetti is only borrowing"))
    }

    @Test
    fun `offering to give the player's own jacket back to the borrower is rejected`() {
        listOf(
            "Give her the jacket back and tell her to keep warm.",
            "Hand Liv her jacket back before you go.",
            "Offer to return her jacket now that she is inside."
        ).forEach {
            assertTrue("\"$it\" should be rejected", vet(it).kept.isEmpty())
        }
    }

    @Test
    fun `asking for the player's own property back is perfectly fine`() {
        val verdict = vet(
            "\"Keep the jacket. I'll get it another time.\"",
            "Ask for your jacket back before she goes inside.",
            "Tell her the jacket is hers to keep now."
        )
        assertEquals("all three are legitimate", 3, verdict.kept.size)
    }

    @Test
    fun `offering something the player is not carrying is rejected`() {
        val verdict = vet("Offer her the umbrella to keep the rain off.")
        assertTrue(verdict.kept.isEmpty())
        assertEquals("item-possession", verdict.rejected.single().category)
        assertTrue(verdict.rejected.single().reason.contains("University Hospital"))
    }

    @Test
    fun `offering something the player is carrying is allowed`() {
        val verdict = vet("Show her your hospital ID badge so she knows you are a doctor.")
        assertEquals(1, verdict.kept.size)
    }

    @Test
    fun `an option that talks to the player is rejected`() {
        val verdict = vet("Ask Adrian whether he wants to walk her home.")
        assertTrue(verdict.kept.isEmpty())
        assertEquals("player-identity", verdict.rejected.single().category)
    }

    @Test
    fun `an option written from outside the player is rejected`() {
        assertTrue(vet("Adrian says nothing and keeps walking.").kept.isEmpty())
    }

    @Test
    fun `an option written as an NPC's action is rejected`() {
        val verdict = vet("Liv asks whether you live nearby.")
        assertTrue(verdict.kept.isEmpty())
        assertEquals("npc-perspective", verdict.rejected.single().category)
    }

    @Test
    fun `speaking to someone who is not here is rejected`() {
        val verdict = vet("Ask Marcus what he saw on the ward.")
        assertTrue(verdict.kept.isEmpty())
        assertEquals("presence", verdict.rejected.single().category)
        assertTrue(verdict.rejected.single().reason.contains("University Hospital"))
    }

    @Test
    fun `phoning someone who is elsewhere is allowed once you can reach them`() {
        // Marcus is a colleague whose number Adrian has: the state file says so.
        val reachable = marcus.copy(playerContact = "PHONE")
        val verdict = ChoiceGuard.vet(
            snapshot(characters = listOf(adrian, liv, reachable)),
            listOf(Choice("c0", "Call Marcus and ask him to cover the rest of your shift."))
        )
        assertEquals(1, verdict.kept.size)
    }

    @Test
    fun `phoning someone whose number was never exchanged is not`() {
        val verdict = vet("Call Marcus and ask him to cover the rest of your shift.")
        assertTrue(verdict.kept.isEmpty())
        assertEquals("no-channel", verdict.rejected.single().category)
    }

    @Test
    fun `ordinary contextual suggestions pass untouched`() {
        val verdict = vet(
            "\"Are you okay? You look frozen. Do you want to sit down?\"",
            "Ask where she is coming from and whether anything happened.",
            "Say nothing and let her decide whether she wants company.",
            "Walk on and leave her to it."
        )
        assertEquals(4, verdict.kept.size)
        assertTrue(!verdict.hasProblems)
    }

    @Test
    fun `an item nobody has recorded is not policed`() {
        // The world has no "coffee" object, so the guard has no opinion about it.
        assertEquals(1, vet("Offer to buy her a coffee from the all-night place.").kept.size)
    }

    @Test
    fun `rejections explain themselves for the narrator`() {
        val verdict = vet("Ask if she wants her jacket back.", "Offer her the umbrella.")
        assertEquals(2, verdict.problems().size)
        assertTrue(verdict.problems().first().contains("jacket"))
    }

    @Test
    fun `a world with no player is left alone`() {
        val verdict = ChoiceGuard.vet(
            snapshot(characters = listOf(liv)),
            listOf(choice("Ask if she wants her jacket back."))
        )
        assertEquals(1, verdict.kept.size)
    }
}
