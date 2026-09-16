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
}
