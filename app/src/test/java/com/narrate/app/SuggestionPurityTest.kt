package com.narrate.app

import com.narrate.app.engine.ChoiceSanitizer
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.TurnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A suggested action is text the player is about to send as their own turn.
 *
 * The reported failure: an option read "Tell her you'll help her and offer your jacket, making
 * her trust you and causing her to open up about why she's lost", and selecting it sent the
 * prediction along with the action - the player instructing the narrator to force an outcome
 * that is the narrator's to decide.
 */
class SuggestionPurityTest {

    private fun choicesOf(vararg lines: String) = TurnParser.parse(
        """
        ===NARRATION===
        She is shivering on the step.
        ===CHOICES===
        ${lines.joinToString("\n        ") { "- $it" }}
        ===STATE===
        {}
        ===END===
        """.trimIndent()
    ).choices

    @Test
    fun `the reported suggestion keeps the offer and loses the prediction`() {
        val choice = choicesOf(
            "Tell her you'll help her and offer your jacket, making her trust you and causing her to open up about why she's lost"
        ).single()

        assertTrue("the action itself must survive", choice.label.contains("offer your jacket"))
        assertFalse("but not what it is expected to achieve", choice.label.contains("making her trust"))
        assertFalse(choice.label.contains("open up"))
    }

    @Test
    fun `a promised consequence in any of its usual shapes is removed`() {
        val forms = listOf(
            "Ask where she is coming from, which will reveal she has been walking for hours",
            "Offer to walk her home, so she finally admits who she is running from",
            "Hand over the badge, prompting the sergeant to let you both through",
            "Sit down beside her. This will make her trust you."
        )
        forms.forEach { raw ->
            val cleaned = ChoiceSanitizer.clean(raw)
            assertFalse("a prediction survived in: $cleaned", cleaned.contains("will reveal"))
            assertFalse(cleaned.contains("so she finally"))
            assertFalse(cleaned.contains("prompting"))
            assertFalse(cleaned.contains("This will make"))
            assertTrue("the player's own move must remain: $cleaned", cleaned.length > 8)
        }
    }

    @Test
    fun `a note to the narrator is never part of what the player sends`() {
        assertEquals(
            "Follow her into the stairwell",
            ChoiceSanitizer.clean("Follow her into the stairwell (she will not stop you)")
        )
        assertEquals(
            "Ask about the manifest",
            ChoiceSanitizer.clean("Ask about the manifest [leads to the harbour thread]")
        )
    }

    @Test
    fun `the player's own purpose is not mistaken for a promised outcome`() {
        // "making sure" is what the player intends, not what the world is told to do.
        assertEquals(
            "Check the lock again, making sure it is bolted",
            ChoiceSanitizer.clean("Check the lock again, making sure it is bolted")
        )
        assertEquals(
            "Take the long way round, giving yourself time to think",
            ChoiceSanitizer.clean("Take the long way round, giving yourself time to think")
        )
    }

    @Test
    fun `spoken suggestions keep their words and their quotation marks`() {
        val choice = choicesOf(
            "\"You look frozen. Take the jacket, I'm two streets from home.\" -- kindness without questions"
        ).single()

        assertEquals("\"You look frozen. Take the jacket, I'm two streets from home.\"", choice.label)
        assertEquals("kindness without questions", choice.detail)
    }

    @Test
    fun `a quoted line that loses a prediction keeps its closing mark`() {
        val cleaned = ChoiceSanitizer.clean(
            "\"Stay where you are.\" said firmly, causing her to freeze"
        )
        assertEquals(
            "a suggestion must never be left with a dangling quote",
            0,
            cleaned.count { it == '"' } % 2
        )
        assertFalse(cleaned.contains("causing her"))
    }

    @Test
    fun `an intent tag that is really commentary is dropped rather than shown`() {
        val choice = choicesOf("Wait by the door -- this will make her come to you").single()
        assertEquals("Wait by the door", choice.label)
        assertEquals("a prediction is not an intent tag", "", choice.detail)
    }

    @Test
    fun `an ordinary suggestion is passed through untouched`() {
        val untouched = listOf(
            "Ask her how long she has been waiting",
            "Search the desk drawers",
            "\"I know what you did.\"",
            "Head for the harbour before the tide turns"
        )
        untouched.forEach { assertEquals(it, ChoiceSanitizer.clean(it)) }
    }

    @Test
    fun `the narrator is told the rule as well as held to it`() {
        val rules = Prompts.gameMaster(com.narrate.app.data.entity.WorldEntity(name = "Calder City"))
        assertTrue(
            "the prompt must say an option is the move and not its result",
            rules.contains("NOT ITS RESULT")
        )
        assertTrue(rules.contains("Never add what it will achieve"))
    }
}
