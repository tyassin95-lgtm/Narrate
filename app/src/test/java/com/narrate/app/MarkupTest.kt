package com.narrate.app

import com.narrate.app.ui.markup.Block
import com.narrate.app.ui.markup.CommKind
import com.narrate.app.ui.markup.MarkupParser
import com.narrate.app.ui.markup.inlineStyled
import com.narrate.app.ui.theme.NarrateColors
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

    /**
     * Spoken dialogue is coloured so it stands out from the narration around it. It stopped
     * standing out because the quote pattern only recognised the straight `"` pair, and models
     * type typographic quotes as often as not - so half the speech in a scene was rendered as
     * ordinary prose.
     */
    @Test
    fun `spoken dialogue is given its own colour`() {
        val styled = inlineStyled("She turned. \"You knew I would come,\" she said.")
        val spoken = styled.spanStyles.filter { it.item.color == NarrateColors.Gold }
        assertEquals(1, spoken.size)
        assertEquals("\"You knew I would come,\"", styled.text.substring(spoken[0].start, spoken[0].end))
    }

    @Test
    fun `typographic quotation marks are dialogue too`() {
        val styled = inlineStyled("She turned. \u201cYou knew I would come,\u201d she said.")
        val spoken = styled.spanStyles.filter { it.item.color == NarrateColors.Gold }
        assertEquals("curly quotes are speech and must be coloured as speech", 1, spoken.size)
        // The marks the narrator used are kept rather than normalised away.
        assertTrue(styled.text.contains("\u201cYou knew I would come,\u201d"))
    }

    @Test
    fun `narration around the speech keeps its ordinary styling`() {
        val styled = inlineStyled("She turned. \"I know.\" The rain kept on.")
        val gold = styled.spanStyles.single { it.item.color == NarrateColors.Gold }
        assertTrue("the narration before the line is not coloured", gold.start > "She turned.".length - 1)
        assertTrue("nor the narration after it", gold.end < styled.text.length - "The rain kept on.".length)
    }

    @Test
    fun `speech and emphasis coexist without swallowing each other`() {
        val styled = inlineStyled("**Elena** says \"I forged it\" and *smiles*.")
        assertEquals(1, styled.spanStyles.count { it.item.color == NarrateColors.Gold })
        assertEquals(3, styled.spanStyles.size)
        assertEquals("Elena says \"I forged it\" and smiles.", styled.text)
    }

    @Test
    fun `a lone quotation mark does not colour the rest of the scene`() {
        val styled = inlineStyled("He said something about the 6\" pipe and left it at that.")
        assertTrue(styled.spanStyles.none { it.item.color == NarrateColors.Gold })
    }

    @Test
    fun `dialogue in typographic quotes is not glued to the paragraph after it`() {
        val markup = """
            \u201cYou are late,\u201d she said, and she did not look up from the ledger she was
            marking, which was the whole of the welcome he was going to get tonight.
            He put the keys down where she could see them.
        """.trimIndent()
        val prose = MarkupParser.parse(markup).filterIsInstance<Block.Prose>()
        assertEquals("a wrapped line that closed its quote has finished", 2, prose.size)
        assertTrue(prose[1].text.startsWith("He put the keys down"))
    }
}
