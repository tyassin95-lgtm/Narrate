package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ContinuityIssueEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.TranscriptExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A playthrough, written out so it can be read by somebody who was not there.
 *
 * The point of the export is diagnosis: what the narrator wrote, what the player was offered,
 * what they typed, what the guard noticed, and where everything ended up - without the player
 * having to retype any of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptExportTest {

    private lateinit var repo: WorldRepository
    private val worldId = "w"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)

        repo.saveWorld(
            WorldEntity(
                id = worldId, name = "Calder City", genre = "medical drama", tone = "quiet",
                premise = "A teaching hospital and the streets around it.",
                authoredCanon = "Calder City: everyone here works nights.",
                openingNarration = "Adrian is walking home when he nearly walks into Liv.",
                currentLocationId = "loc-street", turnCount = 2, storyTime = "Day 1, 2:41 AM"
            )
        )
        repo.saveLocations(
            listOf(
                LocationEntity(id = "loc-street", worldId = worldId, name = "Maple Street", type = "DISTRICT"),
                LocationEntity(id = "loc-ward", worldId = worldId, name = "Night Ward", type = "ROOM", discovered = false)
            )
        )
        repo.saveCharacters(
            listOf(
                CharacterEntity(
                    id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true,
                    role = "resident", personality = "Dry, watchful.",
                    authoredCanon = "Adrian Voss: dark hair, green eyes.",
                    currentLocationId = "loc-street"
                ),
                CharacterEntity(
                    id = "liv", worldId = worldId, name = "Liv Mercer", role = "designer",
                    currentLocationId = "loc-street", affinity = 12, trust = 5, playerContact = "PHONE"
                ),
                CharacterEntity(
                    id = "evan", worldId = worldId, name = "Evan Hale", role = "her ex",
                    currentLocationId = "loc-ward", importance = 2
                )
            )
        )
        repo.saveItems(
            listOf(ItemEntity(id = "jacket", worldId = worldId, name = "wool jacket", ownerId = "pc", holderId = "liv"))
        )
        repo.saveTurn(
            TurnEntity(
                id = "t0", worldId = worldId, index = 0, inputType = "OPENING",
                narration = "The porch light buzzes. **Liv** steps back from the kerb.",
                choicesJson = """[{"id":"c0","label":"\"Sorry - long shift.\"","detail":"apologise","kind":"SPEECH"},
                    {"id":"c1","label":"Step around her","kind":"ACTION"}]""",
                storyTime = "Day 1, 2:38 AM", locationName = "Maple Street",
                model = "gpt-5.6-luna", provider = "OPENAI"
            )
        )
        repo.saveTurn(
            TurnEntity(
                id = "t1", worldId = worldId, index = 1, inputType = "SPEECH",
                playerInput = "\"I'm Adrian.\"",
                narration = "[[sms from=\"Liv\"]]Got in ok.[[/sms]]\n\nHe reads it twice.",
                choicesJson = "[]",
                storyTime = "Day 1, 2:41 AM", locationName = "Maple Street",
                model = "gpt-5.6-luna", provider = "OPENAI"
            )
        )
        repo.saveIssues(
            listOf(
                ContinuityIssueEntity(
                    id = "i1", worldId = worldId, turnIndex = 1, severity = "WARNING",
                    category = "player-voice",
                    description = "Adrian Voss was asked \"What's your name?\" and said nothing.",
                    resolution = "He answers ordinary questions about himself."
                )
            )
        )
    }

    private fun export(): String = runBlocking { TranscriptExport.build(repo, worldId)!! }

    @Test
    fun `every turn's narration, input and suggestions are in the file`() {
        val markdown = export()

        assertTrue(markdown.contains("### Turn 0 - Day 1, 2:38 AM - Maple Street"))
        assertTrue(markdown.contains("The porch light buzzes"))
        assertTrue("the markup is kept, because broken markup is what I need to see", markdown.contains("[[sms from=\"Liv\"]]"))
        assertTrue(markdown.contains("**The world opens.**"))
        assertTrue(markdown.contains("**Player (speech):** \"I'm Adrian.\""))
        assertTrue(markdown.contains("1. [SPEECH] \"Sorry - long shift.\""))
        assertTrue(markdown.contains("apologise"))
        assertTrue(markdown.contains("2. [ACTION] Step around her"))
        assertTrue("a turn with no options says so", markdown.contains("(none were offered this turn)"))
    }

    @Test
    fun `what the guard noticed is attached to the turn it happened on`() {
        val markdown = export()
        val turnOne = markdown.substringAfter("### Turn 1")
        assertTrue(turnOne.contains("player-voice"))
        assertTrue(turnOne.contains("said nothing"))
        assertTrue(turnOne.contains("He answers ordinary questions"))
    }

    @Test
    fun `a suggestion the guard threw away is not filed as something that happened`() {
        runBlocking {
            repo.saveIssues(
                listOf(
                    ContinuityIssueEntity(
                        id = "i2", worldId = worldId, turnIndex = 0, severity = "WARNING",
                        category = "suggested-action",
                        description = "A suggested action contradicted the world: it offers the coat.",
                        resolution = "It was not offered to the player."
                    ),
                    ContinuityIssueEntity(
                        id = "i3", worldId = worldId, turnIndex = 0, severity = "WARNING",
                        category = "presence",
                        description = "Liv Mercer appears to act in the scene but is recorded elsewhere.",
                        resolution = "Position left unchanged."
                    )
                )
            )
        }
        val turnZero = export().substringAfter("### Turn 0").substringBefore("### Turn 1")
        assertTrue(turnZero.contains("things that happened in the world"))
        assertTrue(turnZero.contains("appears to act in the scene"))
        assertTrue(turnZero.contains("Caught before it reached the player"))
        assertTrue(turnZero.contains("it offers the coat"))
    }

    @Test
    fun `an intent tag is printed apart from the line the player would send`() {
        val markdown = export()
        assertTrue(markdown.contains("1. [SPEECH] \"Sorry - long shift.\""))
        assertTrue(markdown.contains("intent tag (never sent): apologise"))
    }

    @Test
    fun `the file opens with enough context to read the rest`() {
        val markdown = export()
        assertTrue(markdown.startsWith("# Calder City"))
        assertTrue(markdown.contains("| Turns played | 2 |"))
        assertTrue(markdown.contains("| Story time | Day 1, 2:41 AM |"))
        assertTrue(markdown.contains("| Model on the last turn | OPENAI gpt-5.6-luna |"))
        assertTrue(markdown.contains("> Calder City: everyone here works nights."))
        assertTrue(markdown.contains("The opening scene they asked for"))
        assertTrue(markdown.contains("**Adrian Voss** - resident"))
        assertTrue(markdown.contains("**Personality:** Dry, watchful."))
    }

    @Test
    fun `the state at the end is written out too`() {
        val markdown = export()
        assertTrue(markdown.contains("**Liv Mercer** (designer): Maple Street; affinity 12, trust 5; calls and texts"))
        assertTrue(markdown.contains("**Evan Hale** (her ex): Night Ward; affinity 0, trust 0; no way to contact them"))
        assertTrue(
            "owner and holder are both said, and which is which",
            markdown.contains("**wool jacket**") &&
                markdown.contains("owned by Adrian Voss (the player), LENT to Liv Mercer")
        )
        assertTrue(markdown.contains("**Night Ward** (ROOM) - undiscovered"))
    }

    @Test
    fun `no key, secret or provider credential can ride along`() {
        val markdown = export()
        listOf("api_key", "sk-", "Authorization", "Bearer", "narrate_secure").forEach {
            assertFalse("the export must never carry $it", markdown.contains(it))
        }
    }

    @Test
    fun `a world nobody has played still exports`() {
        runBlocking {
            repo.saveWorld(WorldEntity(id = "empty", name = "Untouched", turnCount = 0))
            val markdown = TranscriptExport.build(repo, "empty")!!
            assertTrue(markdown.contains("# Untouched"))
            assertTrue(markdown.contains("This world has not been played yet"))
        }
    }

    @Test
    fun `a world that is not there exports nothing rather than an empty file`() {
        runBlocking { assertNull(TranscriptExport.build(repo, "gone")) }
    }

    @Test
    fun `the filename says which world and when`() {
        val world = WorldEntity(id = "w", name = "Calder City: Night Shift")
        val at = 1_774_000_000_000L
        val name = TranscriptExport.fileName(world, at)
        assertTrue(name, name.startsWith("narrate-calder-city-night-shift-"))
        assertTrue(name.endsWith(".md"))
        assertEquals("a filename has no spaces or punctuation to go wrong", name, name.trim())
        assertFalse(name.contains(" "))
        assertTrue(TranscriptExport.fileName(WorldEntity(id = "x", name = "***"), at).startsWith("narrate-world-"))
    }
}
