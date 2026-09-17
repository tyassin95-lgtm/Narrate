package com.narrate.app

import com.narrate.app.engine.AuthoredCanon
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.WorldConcept
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's own words are facts, not prompts. These cover the part of that promise which
 * can be guaranteed in code: a name they chose survives whatever the model returns.
 */
class AuthoredCanonTest {

    private val adrian = """
        Adrian Voss: Dark hair, striking green eyes, handsome, wellkept short stubble, dresses
        nicely, fit. Abandoned young. Adrian grew up in group homes and foster placements and
        aged out at eighteen with nothing and no one. Now he's thirty, in his last year of
        emergency medicine residency at University Hospital, off Fridays.
    """.trimIndent()

    @Test
    fun `a name written before a colon is the character's name`() {
        assertEquals("Adrian Voss", AuthoredCanon.characterName(adrian))
    }

    @Test
    fun `names are found however the player phrases them`() {
        assertEquals("Elena Vasquez", AuthoredCanon.characterName("A dock clerk named Elena Vasquez."))
        assertEquals("Marcus Reyes", AuthoredCanon.characterName("My character is Marcus Reyes, a diver."))
        assertEquals("Tomas Lund", AuthoredCanon.characterName("I am Tomas Lund and I run the ferry."))
        assertEquals("Sister Aldreth", AuthoredCanon.characterName("Name: Sister Aldreth"))
        assertEquals("Vale", AuthoredCanon.characterName("Vale - a tidal engineer who signed something."))
        assertEquals("Ines Calder", AuthoredCanon.characterName("I want to play Ines Calder, a smuggler."))
    }

    @Test
    fun `a heading the player wrote is not taken as part of their name`() {
        // Written in the wild as "Adrian Voss Apperance: Dark hair, striking green eyes..."
        assertEquals(
            "Adrian Voss",
            AuthoredCanon.characterName("Adrian Voss Apperance: Dark hair, striking green eyes, fit.")
        )
        assertEquals(
            "Elena Vasquez",
            AuthoredCanon.characterName("Elena Vasquez Appearance: short, heavyset.")
        )
        assertEquals(
            "Marcus Reyes",
            AuthoredCanon.characterName("Marcus Reyes - Backstory: he grew up on the docks.")
        )
        // A real name that merely ends in an ordinary word is left alone.
        assertEquals("Mary Story", AuthoredCanon.characterName("Mary Story: a diver."))
    }

    @Test
    fun `a player who named nobody is not given a name by accident`() {
        assertNull(AuthoredCanon.characterName("A drifter with no name, hunted across three counties."))
        assertNull(AuthoredCanon.characterName("someone quiet who works nights"))
        assertNull(AuthoredCanon.characterName(""))
        assertNull(AuthoredCanon.characterName("The world is cold and I am tired of it"))
    }

    @Test
    fun `world names are recognised the same way`() {
        assertEquals("Eastgate Rotations", AuthoredCanon.worldName("Eastgate Rotations: a teaching hospital."))
        assertEquals("Tidewater", AuthoredCanon.worldName("World name: Tidewater"))
        assertEquals("Blackwood Station", AuthoredCanon.worldName("A city called Blackwood Station, half sunk."))
        assertEquals("The Long Quiet", AuthoredCanon.worldName("""A colony ship called "The Long Quiet"."""))
        assertNull(AuthoredCanon.worldName("somewhere rainy and industrial"))
    }

    @Test
    fun `a renamed character is put back`() {
        val generated = CharacterConcept(name = "Marcus Webb", role = "ER resident", summary = "A doctor.")
        val (enforced, violations) = AuthoredCanon.enforceCharacter(adrian, generated)
        assertEquals("Adrian Voss", enforced.name)
        assertEquals(1, violations.size)
        assertTrue(violations.first().description.contains("Adrian Voss"))
        assertTrue(violations.first().description.contains("Marcus Webb"))
        // Everything the model was free to add is left alone.
        assertEquals("ER resident", enforced.role)
    }

    @Test
    fun `a model that honoured the name is left untouched`() {
        val generated = CharacterConcept(name = "Adrian Voss", role = "ER resident")
        val (enforced, violations) = AuthoredCanon.enforceCharacter(adrian, generated)
        assertEquals("Adrian Voss", enforced.name)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a renamed world is put back`() {
        val generated = WorldConcept(name = "Saint Mercy General", genre = "medical drama")
        val (enforced, violations) = AuthoredCanon.enforceWorld(
            "Eastgate Rotations: a teaching hospital on the east side.",
            generated
        )
        assertEquals("Eastgate Rotations", enforced.name)
        assertEquals(1, violations.size)
        assertEquals("medical drama", enforced.genre)
    }

    @Test
    fun `nothing is enforced when the player named nothing`() {
        val generated = CharacterConcept(name = "Anything At All")
        val (enforced, violations) = AuthoredCanon.enforceCharacter("someone quiet", generated)
        assertEquals("Anything At All", enforced.name)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `the brief hands the model the exact words and forbids revising them`() {
        val brief = AuthoredCanon.brief("THE PLAYER'S CHARACTER", adrian)
        assertTrue("the text must appear verbatim", brief.contains("striking green eyes"))
        assertTrue(brief.contains("Adrian Voss"))
        assertTrue(brief.contains("change or replace any name"))
        assertTrue(brief.contains("their text wins"))
        assertEquals("", AuthoredCanon.brief("X", ""))
    }

    @Test
    fun `names the player used are collected so generation cannot reuse them`() {
        val names = AuthoredCanon.mentionedNames("Adrian Voss works with Elena Vasquez at University Hospital.")
        assertTrue(names.any { it.contains("Adrian Voss") })
        assertTrue(names.any { it.contains("Elena Vasquez") })
    }
}
