package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.ai.AiProvider
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ModelInfo
import com.narrate.app.ai.ProviderId
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.engine.TurnDirector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The journal has to keep up with the story.
 *
 * The reported failure: a world thirty turns deep whose journal still read "turns 0-9", with
 * everything since sitting in the turn log unsummarised, because a new chapter waited for ten
 * more compactable turns and the recent-turn window kept most of them out of reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JournalTest {

    private lateinit var repo: WorldRepository
    private lateinit var settings: SettingsStore
    private lateinit var director: TurnDirector
    private val worldId = "w"

    /** Answers every turn the same way, and writes a chapter when asked for one. */
    private class Narrator : AiProvider {
        override val id = ProviderId.OPENAI
        var chapterCalls = 0
        override suspend fun chat(request: LlmRequest, apiKey: String): LlmResponse {
            val asked = request.messages.joinToString("\n") { it.content }
            if (asked.contains("as one chapter of permanent record")) {
                chapterCalls++
                return LlmResponse(
                    "The Long Nights\nThey worked the ward through to the small hours, and nothing " +
                        "they did there stayed behind when they left.",
                    request.model, id, finishReason = "stop"
                )
            }
            return LlmResponse(
                """
                ===NARRATION===
                The ward settles. Somewhere down the corridor a trolley turns a corner.
                ===CHOICES===
                - Check the board
                - Sit down for a moment
                ===STATE===
                {"story_time": "Day 1, night", "summary": "Another quiet stretch on the ward."}
                ===END===
                """.trimIndent(),
                request.model, id, finishReason = "stop"
            )
        }
        override suspend fun listModels(apiKey: String) = catalog()
        override fun catalog() = listOf(ModelInfo("scripted-model", ProviderId.OPENAI))
    }

    private val narrator = Narrator()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
        settings = SettingsStore(context)
        settings.setApiKey(ProviderId.OPENAI, "test-key")
        settings.setNarrationModel(ProviderId.OPENAI, "scripted-model")
        settings.setRecentTurnWindow(8)
        ProviderRegistry.override(ProviderId.OPENAI, narrator)
        director = TurnDirector(repo, settings)

        repo.saveWorld(WorldEntity(id = worldId, name = "Eastgate", currentLocationId = "loc"))
        repo.saveLocation(LocationEntity(id = "loc", worldId = worldId, name = "Night Ward"))
        repo.saveCharacter(
            CharacterEntity(id = "pc", worldId = worldId, name = "Adrian Voss", isPlayer = true, currentLocationId = "loc")
        )
    }

    @After
    fun tearDown() {
        ProviderRegistry.override(ProviderId.OPENAI, null)
    }

    private suspend fun play(turns: Int) {
        repeat(turns) { director.take(worldId, "Keep working", "ACTION") }
    }

    @Test
    fun `the journal keeps going once the story passes the first chapter`() = runBlocking {
        play(19)
        val first = repo.chapterDao.all(worldId).sortedBy { it.fromTurn }
        assertTrue("the journal starts once there is history to summarise", first.isNotEmpty())
        assertEquals("and it starts at the beginning", 0, first.first().fromTurn)
        val coveredAtNineteen = first.last().toTurn

        // Eight more turns. Under the old rule the journal would still have stopped at turn 9
        // and everything since would exist only as individual turn logs.
        play(8)
        val later = repo.chapterDao.all(worldId).sortedBy { it.fromTurn }
        assertTrue("the journal must not stop after the first section", later.size > first.size)
        assertTrue(
            "and its coverage has to move with the story",
            later.last().toTurn > coveredAtNineteen
        )
        assertTrue(later.last().title.isNotBlank())
        assertTrue(later.last().summary.isNotBlank())
    }

    @Test
    fun `chapters never overlap, leave a gap, or reach into the recent turns`() = runBlocking {
        play(34)
        val chapters = repo.chapterDao.all(worldId).sortedBy { it.fromTurn }
        assertTrue("a long world has several chapters", chapters.size >= 3)

        var expected = 0
        chapters.forEach { chapter ->
            assertEquals("chapters must be contiguous", expected, chapter.fromTurn)
            assertTrue(chapter.toTurn >= chapter.fromTurn)
            expected = chapter.toTurn + 1
        }
        val world = repo.worldDao.get(worldId)!!
        assertTrue(
            "the turns still being replayed verbatim are never compacted",
            chapters.last().toTurn <= world.turnCount - 9
        )
    }

    @Test
    fun `a chapter title is a title, not a heading`() = runBlocking {
        // Models like to answer with "## The Long Nights". The journal adds its own heading.
        play(14)
        val chapter = repo.chapterDao.all(worldId).first()
        assertEquals("The Long Nights", chapter.title)
        assertTrue(!chapter.title.startsWith("#"))
    }

    @Test
    fun `compacting never destroys the turns themselves`() = runBlocking {
        play(24)
        assertEquals("every turn is still on record", 24, repo.turnDao.count(worldId))
        assertTrue(repo.chapterDao.all(worldId).isNotEmpty())
        val turns = repo.turnDao.range(worldId, 0, 5)
        assertTrue("including the ones a chapter now summarises", turns.all { it.narration.isNotBlank() })
    }
}
