package com.narrate.app

import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.engine.ChoiceSanitizer
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.TurnParser
import com.narrate.app.ui.markup.Block
import com.narrate.app.ui.markup.CommKind
import com.narrate.app.ui.markup.MarkupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose words are whose, and how they reach the page.
 *
 * Three reported failures: the player's spoken line was reported rather than said, an NPC
 * answered by handing the player's own sentence back ("Your number, definitely your number"),
 * and a suggestion arrived carrying broken message markup into the input box.
 */
class NarrationVoiceTest {

    @Test
    fun `words the player actually said are spoken on the page`() {
        val instruction = Prompts.playerInputInstruction(
            "\"I probably need your number first if I want to contact you.\"",
            "SPEECH"
        )
        assertTrue(instruction.contains("exactly as written"))
        assertTrue(instruction.contains("spoken dialogue in quotation marks"))
        assertTrue(instruction.contains("do not summarise"))
        assertTrue(instruction.contains("I probably need your number first"))
    }

    @Test
    fun `a suggestion the player accepted is still something they said out loud`() {
        // Accepting a spoken suggestion arrives as CHOICE, not SPEECH. Reading the quotation
        // marks rather than the label is what keeps those words in the player's mouth.
        val instruction = Prompts.playerInputInstruction(
            "\"You look frozen. Take the jacket, I'm two streets from home.\"",
            "CHOICE"
        )
        assertTrue(instruction.contains("You look frozen. Take the jacket"))
        assertTrue(instruction.contains("spoken dialogue in quotation marks"))
    }

    @Test
    fun `an action with no dialogue is not given a speech instruction`() {
        val instruction = Prompts.playerInputInstruction("Search the desk drawers", "ACTION")
        assertFalse(instruction.contains("spoken dialogue in quotation marks"))
        assertTrue(instruction.contains("Search the desk drawers"))
    }

    @Test
    fun `the narrator is told to answer from its own side of the conversation`() {
        val craft = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(
            "the reported exchange is the example, because it is the mistake",
            craft.contains("\"My number. Here")
        )
        assertTrue(craft.contains("Your number, definitely your number"))
        assertTrue(craft.contains("echoing the player's words back unchanged"))
    }

    @Test
    fun `a message block opened twice becomes one message`() {
        // The reported malformation: the narrator repeats the opening tag instead of closing it.
        val blocks = MarkupParser.parse("[[sms from=\"me\"]]On my way.[[sms from=\"me\"]]")
        val sms = blocks.filterIsInstance<Block.Comm>().single()
        assertEquals(CommKind.SMS, sms.kind)
        assertEquals("On my way.", sms.body)
        assertEquals("me", sms.attributes["from"])
    }

    @Test
    fun `a message left open at the end of the turn still renders as a message`() {
        val blocks = MarkupParser.parse(
            """
            He reaches for the phone.

            [[sms from="Liv"]]I got in ok.
            """.trimIndent()
        )
        val sms = blocks.filterIsInstance<Block.Comm>().single()
        assertEquals("I got in ok.", sms.body)
        assertTrue(blocks.filterIsInstance<Block.Prose>().any { it.text.contains("reaches for the phone") })
    }

    @Test
    fun `a stray tag inside a sentence is still stripped rather than made into a message`() {
        val blocks = MarkupParser.parse("He starts to speak [[sms from=\"X\"]] and stops.")
        assertTrue(blocks.all { it is Block.Prose })
        assertTrue(blocks.none { (it as Block.Prose).text.contains("[[") })
    }

    @Test
    fun `markup never reaches the input box through a suggestion`() {
        val choices = TurnParser.parse(
            """
            ===NARRATION===
            The ward is quiet.
            ===CHOICES===
            - [[sms from="me"]]Let her know you got home[[sms from="me"]]
            - Text Liv that you got home
            ===STATE===
            {}
            ===END===
            """.trimIndent()
        ).choices

        assertEquals("Let her know you got home", choices[0].label)
        assertTrue(choices.none { it.label.contains("[[") })
        assertEquals("Text Liv that you got home", ChoiceSanitizer.clean("Text Liv that you got home"))
    }

    @Test
    fun `the narrator is told to keep markup out of its suggestions`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("Never put formatting markup"))
        assertTrue(prompt.contains("becomes a broken message on screen"))
    }

    @Test
    fun `a message is distinguished from an arrival in the laws themselves`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("A MESSAGE IS NOT AN ARRIVAL"))
        assertTrue(prompt.contains("NOBODY CAN BE CONTACTED UNTIL THEY HAVE BEEN"))
        assertTrue(prompt.contains("Present, reachable and arriving are three different things"))
    }
}
