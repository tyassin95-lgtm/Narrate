package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.MemoryEntity
import com.narrate.app.data.entity.ThreadEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.ContinuityGuard
import com.narrate.app.engine.ItemUpdate
import com.narrate.app.engine.MapPlacement
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.PlayerVoice
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.SceneMomentum
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.StoryClock
import com.narrate.app.engine.StyleWatch
import com.narrate.app.engine.WorldSimulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

/**
 * The thirty-seven turn playthrough, as a regression case.
 *
 * Nothing in that save was wrong, exactly. The continuity held, the map held, nobody
 * teleported - and nine turns in a row were the player sitting at a café table checking the
 * time, turning a page, watching the same sidewalk, being asked questions his character never
 * answered. A world can be perfectly consistent and still not be worth playing, and everything
 * here is one of the specific ways that happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LivingWorldTest {

    private lateinit var repo: WorldRepository

    private val district = LocationEntity(id = "district", worldId = "w", name = "Eastgate", type = "DISTRICT")
    private val cafe = LocationEntity(
        id = "cafe", worldId = "w", name = "Rowan's", type = "BUILDING", parentId = "district",
        notableFeatures = "One espresso machine, a queue that never quite clears, a window onto the street",
        mapX = 0.5f, mapY = 0.5f
    )
    private val library = LocationEntity(
        id = "library", worldId = "w", name = "Eastgate Library", type = "BUILDING", parentId = "district",
        mapX = 0.2f, mapY = 0.8f
    )

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "cafe",
        personality = "Dry, watchful, slow to trust", role = "night porter"
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv Carroway", currentLocationId = "cafe",
        goals = "Get the gallery submission in before Friday", importance = 4
    )
    private val marcus = CharacterEntity(
        id = "marcus", worldId = "w", name = "Marcus Reed", currentLocationId = "library",
        routine = "Reads at Eastgate Library most of the afternoon", importance = 3
    )

    private fun idleTurn(index: Int, input: String) = TurnEntity(
        id = "t$index", worldId = "w", index = index, playerInput = input,
        narration = "The sidewalk is the same sidewalk.", storyTime = "Day 1, 2:0${index} PM",
        locationId = "cafe", locationName = "Rowan's"
    )

    private fun snapshot(
        turns: List<TurnEntity> = emptyList(),
        characters: List<CharacterEntity> = listOf(player, liv, marcus),
        memories: List<MemoryEntity> = emptyList(),
        threads: List<ThreadEntity> = emptyList(),
        items: List<ItemEntity> = emptyList(),
        storyTime: String = "Day 1, 2:05 PM"
    ) = WorldSnapshot(
        world = WorldEntity(
            id = "w", name = "Calder City", currentLocationId = "cafe", turnCount = turns.size,
            storyTime = storyTime, timeOfDay = "afternoon", playStyle = "GENTLE"
        ),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(district, cafe, library),
        links = emptyList(),
        items = items,
        factions = emptyList(),
        relationships = emptyList(),
        threads = threads,
        chapters = emptyList(),
        recentTurns = turns,
        memories = memories,
        visualIdentities = emptyList()
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    // --- Dead time -----------------------------------------------------------------------

    @Test
    fun `passing the time is recognised for what it is`() {
        assertTrue(SceneMomentum.isIdleInput("Check the time"))
        assertTrue(SceneMomentum.isIdleInput("Keep watching the sidewalk"))
        assertTrue(SceneMomentum.isIdleInput("Turn the page"))
        assertTrue("an empty turn is the emptiest turn", SceneMomentum.isIdleInput("   "))
        assertFalse("something said is not nothing", SceneMomentum.isIdleInput("Wait, then say \"You came back\""))
        assertFalse(SceneMomentum.isIdleInput("Go to the counter and order something for her"))
    }

    @Test
    fun `three turns of nothing is a stalled scene`() {
        val stalled = snapshot(
            turns = listOf(
                idleTurn(0, "Sit down at the window"),
                idleTurn(1, "Watch the sidewalk"),
                idleTurn(2, "Check the time")
            )
        )
        val reading = SceneMomentum.read(stalled)
        assertEquals(3, reading.idleTurns)
        assertTrue(reading.stalled)
        assertEquals("Rowan's", reading.place)
    }

    @Test
    fun `a turn that actually put something in the world breaks the run`() {
        val moving = snapshot(
            turns = listOf(
                idleTurn(0, "Wait"),
                idleTurn(1, "Wait"),
                idleTurn(2, "Wait")
            ),
            memories = listOf(MemoryEntity(worldId = "w", text = "Liv agreed to meet on Friday", turnIndex = 2))
        )
        assertEquals("the last turn wrote something down, so it counted", 0, SceneMomentum.read(moving).idleTurns)
        assertFalse(SceneMomentum.read(moving).stalled)
    }

    @Test
    fun `a stalled scene is put to the narrator as a scene to end`() {
        val stalled = snapshot(
            turns = (0..3).map { idleTurn(it, "Check the time") }
        )
        val rendered = SceneMomentum.render(stalled)
        assertTrue(rendered.contains("THIS SCENE HAS STOPPED MOVING"))
        assertTrue(rendered.contains("Rowan's"))
        assertTrue("time may simply be skipped", rendered.contains("Hours may pass in a sentence"))
        assertTrue("and a quiet world is still not an empty one", rendered.contains("Quiet is not the same as empty"))
        assertTrue(rendered.contains("ends somewhere different from where it began"))

        assertEquals("a moving scene gets none of this", "", SceneMomentum.render(snapshot()))
    }

    @Test
    fun `a stalled scene points at the controls instead of inventing another option`() {
        val stalled = snapshot(
            turns = (0..3).map { idleTurn(it, "Wait") },
            threads = listOf(ThreadEntity(worldId = "w", title = "Liv's gallery submission", urgency = 4))
        )
        val rendered = SceneMomentum.render(stalled)
        assertTrue(
            "waiting is a button, so it is not a suggestion",
            rendered.contains("do not offer waiting as a suggested action")
        )
        assertTrue(rendered.contains("skipping ahead, going home and sleeping"))
    }

    // --- Filler suggestions --------------------------------------------------------------

    @Test
    fun `a menu of ways to do nothing is not a menu`() {
        val moving = snapshot(turns = listOf(idleTurn(0, "Ask her about the gallery")))
        val verdict = ChoiceGuard.vet(
            moving,
            listOf(
                Choice("c0", "Wait a little longer", kind = "ACTION"),
                Choice("c1", "Check the time again", kind = "ACTION"),
                Choice("c2", "Keep watching the street", kind = "ACTION"),
                Choice("c3", "Ask Liv what the deadline actually is", kind = "ACTION")
            )
        )
        assertEquals("none of them is a decision", listOf("c3"), verdict.kept.map { it.id })
        assertTrue(verdict.rejected.all { it.category == "filler" })
    }

    @Test
    fun `once the scene has stalled even one more turn of waiting is refused`() {
        val stalled = snapshot(turns = (0..3).map { idleTurn(it, "Wait") })
        val verdict = ChoiceGuard.vet(
            stalled,
            listOf(
                Choice("c0", "Wait a little longer", kind = "ACTION"),
                Choice("c1", "Pay up and walk to the library", kind = "ACTION")
            )
        )
        assertEquals(listOf("c1"), verdict.kept.map { it.id })
        assertTrue(
            "waiting again is refused, whether as a chore or as a repeat",
            verdict.rejected.single().category in setOf("filler", "already-done", "repetitive")
        )
    }

    @Test
    fun `two routes to the same outcome are one option`() {
        val verdict = ChoiceGuard.vet(
            snapshot(),
            listOf(
                Choice("c0", "Ask Liv about the gallery submission", kind = "ACTION"),
                Choice("c1", "Ask Liv about the gallery submission deadline", kind = "ACTION")
            )
        )
        assertEquals(listOf("c0"), verdict.kept.map { it.id })
        assertEquals("duplicate", verdict.rejected.single().category)
    }

    // --- The player's own voice ----------------------------------------------------------

    @Test
    fun `small talk is answered by the character, not queued for the player`() {
        val question = PlayerVoice.triage("Long shift?", snapshot())
        assertEquals(PlayerVoice.Who.CHARACTER, question.who)
        assertTrue(question.grounds.contains("small talk"))
    }

    @Test
    fun `a short question about what he did is still his to answer himself`() {
        // Short is not the same as harmless: four words can be the whole scene.
        listOf("Did you take it?", "Were you there?", "Why now?").forEach {
            assertEquals(it, PlayerVoice.Who.PLAYER, PlayerVoice.triage(it, snapshot()).who)
        }
    }

    // --- Repetition ----------------------------------------------------------------------

    @Test
    fun `a sentence written twice in five turns is handed back to the narrator`() {
        val repeated = "She tucks a strand of hair behind her ear and looks at the window."
        val turns = listOf(
            TurnEntity(id = "a", worldId = "w", index = 0, narration = "The rain keeps on. $repeated"),
            TurnEntity(id = "b", worldId = "w", index = 1, narration = "$repeated The coffee has gone cold.")
        )
        val phrases = StyleWatch.repeatedPhrases(snapshot(turns = turns))
        assertTrue(
            "the repeated sentence should surface: $phrases",
            phrases.any { it.contains("strand of hair behind her") }
        )
        assertTrue(StyleWatch.render(snapshot(turns = turns)).contains("ALREADY WRITTEN"))
    }

    // --- The world having something in it ------------------------------------------------

    @Test
    fun `somebody in the room is given their own reason to be there`() {
        val ticks = WorldSimulator.simulate(snapshot(), lastStoryTime = "Day 1, 1:00 PM")
        val initiative = ticks.single { it.kind == "initiative" }
        assertTrue(initiative.text.contains("Liv Carroway"))
        assertTrue("taken from her own record", initiative.text.contains("gallery submission"))
        assertTrue(initiative.text.contains("not here to be interviewed"))
    }

    @Test
    fun `an empty room is given who could walk into it`() {
        val alone = snapshot(characters = listOf(player, marcus.copy(currentLocationId = "library")))
        val ticks = WorldSimulator.simulate(alone, lastStoryTime = "Day 1, 1:00 PM")
        val arrival = ticks.firstOrNull { it.kind == "arrival" }
        assertNotNull("the man two streets away is nearer than a coincidence", arrival)
        assertTrue(arrival!!.text.contains("Marcus Reed"))
        assertTrue(
            "and the place itself can do something",
            ticks.any { it.kind == "place" && it.text.contains("espresso machine") }
        )
        assertTrue(
            "none of which is offered while somebody is already sitting there",
            WorldSimulator.simulate(snapshot(), "Day 1, 1:00 PM").none { it.kind == "arrival" }
        )
    }

    // --- The clock -----------------------------------------------------------------------

    @Test
    fun `prose that states an interval is making a claim about the clock`() {
        assertEquals(20, StoryClock.statedElapsed("Twenty minutes later the door opens."))
        assertEquals(60, StoryClock.statedElapsed("An hour passed before anyone spoke."))
        assertEquals(30, StoryClock.statedElapsed("Half an hour later, the rain stops."))
        assertNull(StoryClock.statedElapsed("She waits for a while and then gives up."))
        assertEquals(4, StoryClock.elapsed("Day 1, 2:16 PM", "Day 1, 2:20 PM"))
        assertEquals(24 * 60, StoryClock.elapsed("Day 1, 2:16 PM", "Day 2, 2:16 PM"))
    }

    @Test
    fun `four in the morning has no sunlight in it`() {
        assertEquals(
            "Sunlight",
            StoryClock.lightContradiction("Day 2, 3:40 AM", "Sunlight comes through the blinds.")
        )
        assertEquals(
            "moonlight",
            StoryClock.lightContradiction("Day 2, 1:10 PM", "The moonlight catches the glass.")
        )
        assertNull(
            "and dusk is nobody's business but the narrator's",
            StoryClock.lightContradiction("Day 2, 7:30 PM", "The last of the daylight goes.")
        )
    }

    // --- Contact channels ----------------------------------------------------------------

    @Test
    fun `being asked to text somebody means a number changed hands`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(),
            "She gathers her bag. \"Text me when you're home, alright?\"",
            movedNames = emptySet()
        )
        val flagged = issues.single { it.category == "no-channel" }
        assertTrue(flagged.description.contains("Liv Carroway"))
        assertTrue(flagged.resolution.contains("contacts"))
    }

    @Test
    fun `and once the number is recorded the same line is fine`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(),
            "She gathers her bag. \"Text me when you're home, alright?\"",
            movedNames = emptySet(),
            contactsAfter = listOf(player, liv.copy(playerContact = "PHONE"), marcus)
        )
        assertTrue(issues.none { it.category == "no-channel" })
    }

    @Test
    fun `a name behind a preposition is not the one doing the verb`() {
        val issues = ContinuityGuard.auditNarration(
            snapshot(),
            "The chair beside Marcus sat pushed back, and nobody had touched it since.",
            movedNames = emptySet()
        )
        assertTrue(
            "Marcus is at the library and this sentence is about a chair",
            issues.none { it.category == "presence" && it.description.contains("Marcus") }
        )
    }

    // --- State that the prose keeps contradicting ----------------------------------------

    @Test
    fun `something in somebody's hand is not also lying on a table`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe"))
        repo.saveLocations(listOf(district, cafe, library))
        repo.saveCharacter(player)
        repo.saveCharacter(liv)
        repo.saveItems(
            listOf(ItemEntity(id = "book", worldId = "w", name = "dog-eared paperback", locationId = "cafe"))
        )

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(itemsUpdate = listOf(ItemUpdate(name = "dog-eared paperback", heldBy = "Adrian Voss"))),
            turnIndex = 4,
            narration = "He picks it up off the table and pushes it into his coat pocket."
        )

        val book = repo.itemDao.all("w").single()
        assertEquals("pc", book.holderId)
        assertNull("it went with him; it is not still on the table", book.locationId)
    }

    // --- The map meaning something -------------------------------------------------------

    @Test
    fun `a new place is drawn beside what it belongs to`() {
        val placed = MapPlacement.place(anchors = listOf(cafe), existing = listOf(district, cafe, library), seed = 3)
        val distance = hypot(placed.first - cafe.mapX, placed.second - cafe.mapY)
        assertTrue("it lands near Rowan's, not in the next free grid cell: $distance", distance < 0.2f)
        assertTrue(
            "and not on top of anything already drawn",
            listOf(cafe, library).none { hypot(it.mapX - placed.first, it.mapY - placed.second) < 0.07f }
        )
    }

    @Test
    fun `with nothing to anchor to it still takes an orderly spot`() {
        val placed = MapPlacement.place(anchors = emptyList(), existing = emptyList(), seed = 0)
        assertEquals(MapPlacement.grid(0), placed)
    }

    // --- The doctrine that goes with all of it -------------------------------------------

    @Test
    fun `the narrator is told about dead time, initiative and its own arithmetic`() {
        val prompt = Prompts.gameMaster(
            WorldEntity(id = "w", name = "Calder City", playStyle = PlayStyle.GENTLE.name)
        )
        assertTrue(prompt.contains("DEAD TIME"))
        assertTrue(prompt.contains("Waiting is not a scene"))
        assertTrue(prompt.contains("PEOPLE, NOT FIXTURES"))
        assertTrue(prompt.contains("furniture that talks"))
        assertTrue("recurring people have to be distinguishable", prompt.contains("must not sound alike or move alike"))
        assertTrue("no countdown arithmetic", prompt.contains("Do not do arithmetic in the"))
        assertTrue(prompt.contains("has no sunlight in it"))
        assertTrue("and no menus made of nothing", prompt.contains("is not a choice, it is a chore"))
    }
}
