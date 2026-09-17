package com.narrate.app

import com.narrate.app.ui.markup.Block
import com.narrate.app.ui.markup.CommKind
import com.narrate.app.ui.markup.MarkupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupTest {

    @Test
    fun `each communication format becomes its own block`() {
        val markup = """
            She reads it twice.

            [[sms from="Elena"]]I'm outside.[[/sms]]
            [[email from="a@b.c" to="you" subject="Overdue"]]Pay by Friday.[[/email]]
            [[call with="Marcus" status="incoming"]]
            Marcus: "Leave now."
            [[/call]]
            [[system]]SIGNAL LOST[[/system]]

            ---

            The line goes dead.
        """.trimIndent()

        val blocks = MarkupParser.parse(markup)
        val comms = blocks.filterIsInstance<Block.Comm>()
        assertEquals(
            listOf(CommKind.SMS, CommKind.EMAIL, CommKind.CALL, CommKind.SYSTEM),
            comms.map { it.kind }
        )
        assertEquals("Elena", comms[0].attributes["from"])
        assertEquals("Overdue", comms[1].attributes["subject"])
        assertEquals("incoming", comms[2].attributes["status"])
        assertTrue(blocks.any { it is Block.SceneBreak })
        assertTrue(blocks.filterIsInstance<Block.Prose>().any { it.text.contains("The line goes dead") })
    }

    @Test
    fun `unclosed markup is stripped rather than shown raw`() {
        val blocks = MarkupParser.parse("He starts to speak [[sms from=\"X\"]] and stops.")
        assertTrue(blocks.all { it is Block.Prose })
        assertTrue(blocks.none { (it as Block.Prose).text.contains("[[") })
    }

    @Test
    fun `stripMarkup produces clean text for prompts and previews`() {
        val plain = MarkupParser.stripMarkup("**Elena** turns. [[thought]]She knew.[[/thought]] *Finally.*")
        assertEquals("Elena turns. She knew. Finally.", plain)
    }

    @Test
    fun `hard-wrapped prose is rejoined into one paragraph`() {
        val wrapped = """
            The tide is further out than it should be at this hour, and the mud smells of iron and
            old rope. Elena is already on the steps with her coat buttoned to the throat, watching
            the water come back in.

            She does not look up when you arrive.
        """.trimIndent()
        val prose = MarkupParser.parse(wrapped).filterIsInstance<Block.Prose>()
        assertEquals(2, prose.size)
        assertTrue(prose[0].text.contains("old rope. Elena is already"))
        assertTrue(prose[0].text.lines().size == 1)
        assertEquals("She does not look up when you arrive.", prose[1].text)
    }

    @Test
    fun `paragraphs separated by a single newline stay separate`() {
        val markup = """
            He set the cup down.
            Outside, the rain kept on.
        """.trimIndent()
        val prose = MarkupParser.parse(markup).filterIsInstance<Block.Prose>()
        assertEquals(2, prose.size)
    }

    @Test
    fun `dialogue lines are never glued together`() {
        val markup = """
            "You are late," she said.
            "I know."
        """.trimIndent()
        val prose = MarkupParser.parse(markup).filterIsInstance<Block.Prose>()
        assertEquals(2, prose.size)
    }
}
