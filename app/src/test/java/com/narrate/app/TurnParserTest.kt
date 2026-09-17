package com.narrate.app

import com.narrate.app.engine.TurnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnParserTest {

    private val wellFormed = """
        ===NARRATION===
        The rain has not stopped since Tuesday. **Elena** is waiting under the awning.

        [[sms from="Marcus"]]Don't come to the harbour.[[/sms]]

        ===CHOICES===
        - Ask her what she meant
        - "I saw the manifest." -- show your hand
        - Walk away

        ===STATE===
        {
          "story_time": "Day 2, evening",
          "summary": "Elena admitted she forged the manifest.",
          "player": {"location": "Harbour Steps", "knowledge_add": ["Elena forged the manifest"]},
          "characters_update": [{"name": "Elena", "affinity_delta": 5, "location": "Harbour Steps",
            "movement_reason": "she followed you down"}],
          "memories": [{"text": "Elena admitted forging the manifest.", "kind": "DISCOVERY", "importance": 5,
            "subjects": ["Elena"]}]
        }
        ===END===
    """.trimIndent()

    @Test
    fun `parses all four sections`() {
        val parsed = TurnParser.parse(wellFormed)
        assertTrue(parsed.stateParsed)
        assertTrue(parsed.narration.contains("The rain has not stopped"))
        assertTrue(parsed.narration.contains("[[sms"))
        assertTrue("narration must not leak the state block", !parsed.narration.contains("story_time"))
        assertEquals(3, parsed.choices.size)
        assertEquals("Day 2, evening", parsed.delta.storyTime)
        assertEquals("Harbour Steps", parsed.delta.player?.location)
        assertEquals(5, parsed.delta.charactersUpdate.first().affinityDelta)
        assertEquals(1, parsed.delta.memories.size)
    }

    @Test
    fun `detail is split from the choice label`() {
        val parsed = TurnParser.parse(wellFormed)
        val spoken = parsed.choices[1]
        assertEquals("\"I saw the manifest.\"", spoken.label)
        assertEquals("show your hand", spoken.detail)
        assertEquals("SPEECH", spoken.kind)
    }

    @Test
    fun `narration survives when the model forgets the markers`() {
        val parsed = TurnParser.parse("She turns, and the door closes behind her.")
        assertEquals("She turns, and the door closes behind her.", parsed.narration)
        assertTrue(parsed.choices.isEmpty())
        assertTrue(!parsed.stateParsed)
    }

    @Test
    fun `fenced and trailing-comma json still parses`() {
        val raw = """
            ===NARRATION===
            The lamp gutters.
            ===STATE===
            ```json
            {
              "story_time": "Day 9, night",
              "summary": "Nothing happened.",
            }
            ```
        """.trimIndent()
        val parsed = TurnParser.parse(raw)
        assertTrue(parsed.stateParsed)
        assertEquals("Day 9, night", parsed.delta.storyTime)
        assertEquals("The lamp gutters.", parsed.narration.trim())
    }

    @Test
    fun `braces inside strings do not truncate the state block`() {
        val raw = """
            ===STATE===
            {"summary": "He wrote {redacted} on the form", "story_time": "Day 1, noon"}
        """.trimIndent()
        val parsed = TurnParser.parse(raw)
        assertTrue(parsed.stateParsed)
        assertEquals("Day 1, noon", parsed.delta.storyTime)
    }

    @Test
    fun `unparseable state never loses the prose`() {
        val raw = """
            ===NARRATION===
            The harbour bell rings twice.
            ===STATE===
            { this is not json at all
        """.trimIndent()
        val parsed = TurnParser.parse(raw)
        assertTrue(!parsed.stateParsed)
        assertEquals("The harbour bell rings twice.", parsed.narration.trim())
        assertTrue(parsed.parseNotes.isNotEmpty())
    }

    @Test
    fun `choices survive a model that writes its own headers`() {
        val raw = """
            The door closes behind her.

            **Choices:**
            1. Follow her out
            2. Read the letter she left
            3. Stay where you are

            ===STATE===
            {"story_time": "Day 2, night"}
        """.trimIndent()
        val parsed = TurnParser.parse(raw)
        assertEquals(3, parsed.choices.size)
        assertEquals("Follow her out", parsed.choices[0].label)
        assertTrue(parsed.stateParsed)
        assertTrue("the header must not remain in the prose", !parsed.narration.contains("Choices:"))
    }

    @Test
    fun `an OPTIONS header is understood too`() {
        val parsed = TurnParser.parse(
            """
            ===NARRATION===
            The engine coughs and dies.
            OPTIONS
            - Get out and look
            - Try the ignition again
            """.trimIndent()
        )
        assertEquals(2, parsed.choices.size)
    }

    @Test
    fun `a trailing list with no header at all is still offered to the player`() {
        val parsed = TurnParser.parse(
            """
            ===NARRATION===
            Rain hammers the tin roof. Marcus is late, and the tea has gone cold.

            What do you do?
            - Wait another ten minutes
            - Call him
            - Leave
            """.trimIndent()
        )
        assertEquals(3, parsed.choices.size)
        assertEquals("Call him", parsed.choices[1].label)
        assertTrue(!parsed.narration.contains("What do you do?"))
        assertTrue(parsed.narration.contains("Rain hammers the tin roof"))
    }

    @Test
    fun `a bulleted list inside the scene is not mistaken for choices`() {
        val parsed = TurnParser.parse(
            """
            ===NARRATION===
            The inventory sheet is pinned to the wall.

            - Two crates of salt
            - A coil of rope
            - Someone's initials, scratched into the wood

            You put it back exactly as you found it, and the room is quiet again.
            ===CHOICES===
            - Leave the room
            """.trimIndent()
        )
        assertEquals(1, parsed.choices.size)
        assertEquals("Leave the room", parsed.choices[0].label)
        assertTrue("the list belongs to the scene", parsed.narration.contains("A coil of rope"))
    }

    @Test
    fun `choices placed in the state block are recovered`() {
        val parsed = TurnParser.parse(
            """
            ===NARRATION===
            She waits.
            ===STATE===
            {"story_time": "Day 1, noon", "choices": ["Answer her", "Turn away"]}
            """.trimIndent()
        )
        assertEquals(2, parsed.choices.size)
        assertEquals("Answer her", parsed.choices[0].label)
        assertTrue(parsed.stateParsed)
    }

    @Test
    fun `a reply cut off mid-sentence is recognised as unfinished`() {
        val cut = TurnParser.parse(
            """
            ===NARRATION===
            The tide is further out than it should be at this hour, and Elena is already on the steps
            with her coat buttoned to the throat, and when she sees you she does not
            """.trimIndent()
        )
        assertTrue(cut.looksUnfinished)
        assertTrue(!cut.isComplete)

        val finished = TurnParser.parse(
            """
            ===NARRATION===
            The tide is further out than it should be at this hour. Elena waits on the steps.
            ===CHOICES===
            - Go to her
            ===STATE===
            {"story_time": "Day 1, dusk"}
            """.trimIndent()
        )
        assertTrue(!finished.looksUnfinished)
        assertTrue(finished.isComplete)
    }

    @Test
    fun `duplicate and over-long choice lines are cleaned up`() {
        val parsed = TurnParser.parse(
            """
            ===CHOICES===
            - Ask her what she meant
            - Ask her what she meant
            > **Leave without a word**
            - 
            - Wait
            """.trimIndent()
        )
        assertEquals(3, parsed.choices.size)
        assertEquals("Leave without a word", parsed.choices[1].label)
    }

    @Test
    fun `a line that ends on a typographic quote is finished, not cut off`() {
        // Judging this unfinished buys a repair call, and its cost, for every turn that ends
        // on someone speaking - which is most of them.
        assertTrue(
            "straight quotes have always read as finished",
            !TurnParser.endsMidSentence("She looked up at the sound of the door. \"You came after all.\"")
        )
        assertTrue(
            "and a curly pair is the same sentence",
            !TurnParser.endsMidSentence("She looked up at the sound of the door. \u201cYou came after all.\u201d")
        )
        assertTrue(
            "an ellipsis is a deliberate ending too",
            !TurnParser.endsMidSentence("She started to answer, then thought better of it\u2026")
        )
        assertTrue(
            "something genuinely cut off is still caught",
            TurnParser.endsMidSentence("She looked up at the sound of the door and started to say something abou")
        )
    }
}
