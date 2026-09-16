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
}
