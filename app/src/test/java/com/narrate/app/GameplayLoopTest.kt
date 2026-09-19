package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.EventEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.EventDelta
import com.narrate.app.engine.Geography
import com.narrate.app.engine.OutfitDelta
import com.narrate.app.engine.PlayerKnowledge
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.SceneDelta
import com.narrate.app.engine.SceneDirector
import com.narrate.app.engine.Schedule
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.SuggestionQuality
import com.narrate.app.engine.Wardrobe
import com.narrate.app.engine.WorldActions
import com.narrate.app.engine.WorldClock
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

/**
 * The forty-one turn Calder City playthrough, as a regression case for the gameplay loop.
 *
 * The save was consistent. It was also unplayable: thirteen turns and twenty-three minutes to
 * get along one pavement, nine more to say goodnight on a doorstep, a day number that never
 * advanced through a night's sleep, a skip control that moved the clock by one minute, six
 * wordings of "say goodnight" on the menus, a woman heard through a window who instantly
 * acquired a name and a face, a party dress still being described four days later, and two
 * rows in the cast for the same doctor.
 *
 * None of those is a bug in the ordinary sense. They are what the old architecture produced
 * when it was working. These tests are about the architecture that replaced it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameplayLoopTest {

    private lateinit var repo: WorldRepository

    private val district = LocationEntity(
        id = "eastgate", worldId = "w", name = "Eastgate", type = "DISTRICT", parentId = "city"
    )
    private val city = LocationEntity(id = "city", worldId = "w", name = "Calder City", type = "SETTLEMENT")
    private val maple = LocationEntity(
        id = "maple", worldId = "w", name = "Maple Street", type = Geography.STREET,
        parentId = "eastgate", mapX = 0.5f, mapY = 0.5f, spanAngle = 0f, spanLength = 0.34f
    )
    private val flat = LocationEntity(
        id = "flat", worldId = "w", name = "Adrian's Apartment", type = "BUILDING",
        parentId = "eastgate", mapX = 0.46f, mapY = 0.56f, visited = true
    )
    private val hospital = LocationEntity(
        id = "hospital", worldId = "w", name = "University Hospital", type = "BUILDING",
        parentId = "eastgate", mapX = 0.7f, mapY = 0.4f
    )

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true,
        currentLocationId = "maple", homeLocationId = "flat",
        appearance = "Dark hair, green eyes, short stubble", outfit = "a dark coat over scrubs",
        outfitContext = Wardrobe.WORK
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv", currentLocationId = "maple",
        appearance = "Dark hair, slight", outfit = "a party dress, no coat",
        outfitContext = Wardrobe.PARTY, outfitSetAt = 0
    )
    private val jenna = CharacterEntity(
        id = "jenna", worldId = "w", name = "Jenna Torres", currentLocationId = "rental",
        role = "waitress", appearance = "Tall, red hair", secrets = "Behind on rent"
    )
    private val rental = LocationEntity(
        id = "rental", worldId = "w", name = "Liv's Shared Rental", type = "BUILDING",
        parentId = "eastgate", mapX = 0.52f, mapY = 0.52f
    )

    private fun world(clockMinute: Long = 23 * 60 + 46, turnCount: Int = 4) = WorldEntity(
        id = "w", name = "Calder City", currentLocationId = "maple",
        clockMinute = clockMinute, calendarEpoch = "2025-09-05", turnCount = turnCount
    )

    private fun snapshot(
        clockMinute: Long = 23 * 60 + 46,
        events: List<EventEntity> = emptyList(),
        characters: List<CharacterEntity> = listOf(player, liv),
        turns: List<TurnEntity> = emptyList()
    ) = WorldSnapshot(
        world = world(clockMinute),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(city, district, maple, flat, hospital, rental),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = turns,
        memories = emptyList(),
        visualIdentities = emptyList(),
        events = events
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    // --- One clock ------------------------------------------------------------------------

    @Test
    fun `the day, the weekday and the hour all come from one number`() {
        val friday = WorldClock.of(world(clockMinute = 23 * 60 + 46))
        assertEquals("Friday", friday.weekdayName)
        assertEquals(1, friday.dayNumber)
        assertEquals("11:46 PM", friday.clock)
        assertEquals("night", friday.timeOfDay)

        // Fourteen minutes later it is Saturday, and there is no way for it not to be.
        val saturday = friday.plus(14)
        assertEquals("Saturday", saturday.weekdayName)
        assertEquals(2, saturday.dayNumber)
        assertEquals("12:00 AM", saturday.clock)
    }

    @Test
    fun `a night's sleep cannot leave the world on the same day`() {
        // The reported failure, exactly: the player slept and woke on "Day 1, 7:00 AM".
        val night = snapshot(clockMinute = 23 * 60 + 42)
        val wake = WorldActions.targetMinute(WorldActions.SLEEP, night, null)
        val woken = WorldClock.stamp(wake, night.world)
        assertEquals(2, woken.dayNumber)
        assertEquals("Saturday", woken.weekdayName)
        assertEquals("7:00 AM", woken.clock)
    }

    @Test
    fun `the world writes the clock and the narrator only says how long the beat took`() = runBlocking {
        repo.saveWorld(world(clockMinute = 9 * 60))
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            // A narrator still trying to write a time would have put one here. There is
            // nowhere to put it any more.
            StateDelta(scene = SceneDelta(status = "CONTINUING", minutes = 25)),
            turnIndex = 4,
            narration = "They walk two streets without saying much."
        )

        val after = repo.world("w")!!
        assertEquals(9 * 60L + 25, after.clockMinute)
        assertEquals("and everything shown to the player derives from it", "morning", after.timeOfDay)
        assertTrue(after.storyTime.contains("Friday"))
    }

    @Test
    fun `a beat that claims no time at all is given some anyway`() {
        // Forty turns at one minute each is the shape of the problem this floor exists for.
        val scene = SceneDirector.readScene(StateDelta(scene = SceneDelta(minutes = 0)))
        assertEquals(SceneDirector.MIN_BEAT_MINUTES, SceneDirector.minutesFor(scene, "ACTION"))
        assertEquals("but the opening is allowed to take none", 0, SceneDirector.minutesFor(scene, "OPENING"))
    }

    // --- The calendar ----------------------------------------------------------------------

    @Test
    fun `an arrangement becomes a date, and time can be skipped to it`() = runBlocking {
        repo.saveWorld(world(clockMinute = 60 + 20))
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player)
        repo.saveCharacter(liv)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(
                scene = SceneDelta(minutes = 10),
                events = listOf(
                    EventDelta(
                        title = "See Liv", kind = "PLAN", whenText = "Friday 8 PM",
                        durationMinutes = 120, withNames = "Liv"
                    )
                )
            ),
            turnIndex = 20,
            narration = "\"Friday, then,\" she says. \"After my last class.\""
        )

        val event = repo.eventDao.all("w").single()
        val at = WorldClock.stamp(event.startMinute, repo.world("w")!!)
        assertEquals("Friday", at.weekdayName)
        assertEquals("8:00 PM", at.clock)
        assertTrue("and it is a week away, not this morning", event.startMinute > repo.world("w")!!.clockMinute)

        // Which means the time controls can point at it by name.
        val options = WorldActions.options(repo.snapshot("w")!!)
        val toLiv = options.firstOrNull { it.detail.contains("See Liv") }
        assertNotNull("the calendar is what makes skipping meaningful: $options", toLiv)
        assertTrue("and it lands just before, not after", toLiv!!.targetMinute < event.startMinute)
    }

    @Test
    fun `a weekly shift happens every week without forty rows`() {
        val shift = EventEntity(
            worldId = "w", title = "ER shift", kind = Schedule.SHIFT,
            startMinute = 8 * 60, durationMinutes = 12 * 60, recurrence = "WEEKLY:MON,TUE"
        )
        val occurrences = Schedule.occurrencesOf(shift, 0, 21L * WorldClock.DAY)
        assertTrue("three weeks of Mondays and Tuesdays", occurrences.size >= 5)
        assertTrue(
            "all of them at eight in the morning",
            occurrences.all { Math.floorMod(it.startMinute, WorldClock.DAY.toLong()) == 8 * 60L }
        )
    }

    @Test
    fun `the time controls are built from what is actually coming`() {
        val friday = WorldClock.of(world(clockMinute = 60 + 20)).nextWeekday(java.time.DayOfWeek.FRIDAY, 20 * 60)
        val options = WorldActions.options(
            snapshot(
                clockMinute = 60 + 20,
                events = listOf(
                    EventEntity(worldId = "w", title = "Coffee with Liv", startMinute = friday, kind = "PLAN")
                )
            )
        )
        assertTrue("there is always something honest to offer", options.isNotEmpty())
        assertTrue("including the thing in the diary", options.any { it.detail.contains("Coffee with Liv") })
        assertTrue("and every one of them is in the future", options.all { it.targetMinute > 60 + 20 })
        assertTrue(
            "no option is a one-minute nudge",
            options.all { it.targetMinute - (60 + 20) >= 30 }
        )
    }

    // --- Scenes rather than sentences -------------------------------------------------------

    @Test
    fun `the narrator is told a turn is a beat, not a line of dialogue`() {
        val rendered = SceneDirector.render(snapshot())
        assertTrue(rendered.contains("A turn is a beat of the story"))
        assertTrue(rendered.contains("play it all the way to wherever it naturally arrives"))
        assertTrue(
            "with the failure named",
            rendered.contains("Thirteen turns to get through one conversation")
        )
        assertTrue("and a list of the only reasons to stop", rendered.contains("something is genuinely being decided"))
        assertTrue("small talk is the narrator's, not a turn", rendered.contains("Never hand one back as a choice"))
        assertTrue("and Liv can act on her own", rendered.contains("Liv can speak more than once this turn"))
    }

    @Test
    fun `a scene that has finished is allowed to offer nothing`() {
        val resolved = com.narrate.app.engine.TurnParser.parse(
            """
            ===NARRATION===
            The door closes. He stands there a second longer than he means to, then walks.
            ===CHOICES===
            ===STATE===
            {"scene": {"status": "RESOLVED", "minutes": 4, "ended_because": "he has gone"}}
            ===END===
            """.trimIndent()
        )
        assertTrue("no options is a finished scene, not a broken turn", resolved.isComplete)
        assertTrue(resolved.choices.isEmpty())

        // Whereas a turn that simply stopped is still incomplete and gets asked again.
        val cutOff = com.narrate.app.engine.TurnParser.parse(
            """
            ===NARRATION===
            She turns to say something and
            """.trimIndent()
        )
        assertFalse(cutOff.isComplete)
    }

    // --- Suggestions that mean something -----------------------------------------------------

    @Test
    fun `six wordings of saying goodnight are one option`() {
        // Every one of these appeared in the playthrough. Not one pair is a string duplicate.
        val goodnights = listOf(
            "Say goodnight and start back toward the grid",
            "Say goodnight and start walking toward the residential grid",
            "Say goodnight and start the walk back toward your own place",
            "Say goodnight and start the walk back toward the grid"
        )
        assertEquals(1, goodnights.map { SuggestionQuality.categoryOf(it) }.toSet().size)

        val verdict = ChoiceGuard.vet(
            snapshot(),
            goodnights.mapIndexed { index, label -> Choice("c$index", label, kind = "SPEECH") }
        )
        assertEquals(1, verdict.kept.size)
        assertTrue(verdict.rejected.all { it.category == "duplicate" })
    }

    @Test
    fun `asking the same question again in different words is refused`() {
        val asked = TurnEntity(
            id = "t", worldId = "w", index = 3,
            playerInput = "Ask what she has in mind for Friday"
        )
        val verdict = ChoiceGuard.vet(
            snapshot(turns = listOf(asked)),
            listOf(
                Choice("c0", "Ask what kind of thing she had in mind for Friday", kind = "SPEECH"),
                Choice("c1", "Tell her about the ward, and why you took the job", kind = "SPEECH")
            )
        )
        assertEquals(listOf("c1"), verdict.kept.map { it.id })
        assertEquals("already-done", verdict.rejected.single().category)
    }

    @Test
    fun `the menu may be one option, and never a chore to make up the numbers`() {
        val verdict = ChoiceGuard.vet(
            snapshot(),
            listOf(
                Choice("c0", "Ask Liv whether she wants to come up", kind = "SPEECH"),
                Choice("c1", "Check the time", kind = "ACTION"),
                Choice("c2", "Stay standing a moment longer and let the silence settle", kind = "ACTION"),
                Choice("c3", "Take a sip of your coffee", kind = "ACTION")
            )
        )
        assertEquals(listOf("c0"), verdict.kept.map { it.id })
        assertEquals(3, verdict.rejected.size)
        assertTrue(verdict.rejected.all { it.category == "filler" })
    }

    @Test
    fun `a chore with a reason behind it is not a chore`() {
        val verdict = ChoiceGuard.vet(
            snapshot(),
            listOf(Choice("c0", "Wait until Liv comes back out, then follow her", kind = "ACTION"))
        )
        assertEquals("waiting for something is a decision about the evening", 1, verdict.kept.size)
    }

    // --- Knowing somebody --------------------------------------------------------------------

    @Test
    fun `a voice through a window is a voice, not a dossier`() = runBlocking {
        repo.saveWorld(world())
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player.copy(currentLocationId = "rental"))
        repo.saveCharacter(jenna)
        // Jenna is upstairs in the same house: within earshot, never seen, never named aloud.
        repo.saveWorld(repo.world("w")!!.copy(currentLocationId = "rental"))
        repo.saveLocation(rental.copy(parentId = "eastgate"))
        repo.saveLocation(
            LocationEntity(
                id = "upstairs", worldId = "w", name = "Upstairs at Liv's", type = "ROOM", parentId = "rental"
            )
        )
        repo.saveCharacter(jenna.copy(currentLocationId = "upstairs"))

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(scene = SceneDelta(minutes = 6)),
            turnIndex = 14,
            narration = "A woman's voice comes down from an open window upstairs, something about the dryer."
        )

        val learned = repo.knowledgeDao.all("w").filter { it.subjectId == "jenna" }.map { it.field }
        assertTrue("that somebody is there", "exists" in learned)
        assertFalse("but not her name", PlayerKnowledge.NAME in learned)
        assertFalse("nor her face", PlayerKnowledge.APPEARANCE in learned)
        assertFalse("nor what she does", PlayerKnowledge.ROLE in learned)

        val snapshot = repo.snapshot("w")!!
        assertEquals(PlayerKnowledge.HEARD_UNSEEN, PlayerKnowledge.acquaintance(snapshot, "jenna"))
        assertTrue(
            "and the screen calls her what the player can honestly call her",
            PlayerKnowledge.displayName(snapshot, repo.character("jenna")!!).contains("voice")
        )
    }

    @Test
    fun `a text gives you a name and a number, not a face`() {
        val rows = PlayerKnowledge.onRemoteContact(
            "w", jenna, "a group message about the ER gap", 24, "", emptySet()
        )
        val fields = rows.map { it.field }
        assertTrue(PlayerKnowledge.NAME in fields)
        assertTrue("exists" in fields)
        assertFalse("they have never been in a room together", PlayerKnowledge.APPEARANCE in fields)
        assertEquals(
            PlayerKnowledge.REMOTE,
            rows.first { it.field == PlayerKnowledge.ACQUAINTANCE }.value
        )
    }

    // --- Two rows for one person -------------------------------------------------------------

    @Test
    fun `a doctor and her title are the same doctor`() = runBlocking {
        repo.saveWorld(world())
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player)
        repo.saveCharacter(
            CharacterEntity(
                id = "e1", worldId = "w", name = "Elena Ruiz", role = "ER doctor at University Hospital",
                currentLocationId = "hospital", playerContact = "PHONE"
            )
        )
        repo.saveCharacter(
            CharacterEntity(
                id = "e2", worldId = "w", name = "Dr. Elena Ruiz", role = "Attending Physician",
                currentLocationId = "hospital", affinity = 10
            )
        )

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(scene = SceneDelta(minutes = 5)),
            turnIndex = 33,
            narration = "The corridor is empty."
        )

        val cast = repo.characterDao.all("w").filter { !it.isPlayer }
        assertEquals("one person, one row", 1, cast.size)
        assertEquals("keeping the fuller name", "Dr. Elena Ruiz", cast.single().name)
        assertTrue("and everything either of them knew", cast.single().playerContact.contains("PHONE"))
        assertEquals(10, cast.single().affinity)
        assertTrue(cast.single().aliases.contains("Elena Ruiz"))
    }

    // --- Clothes that know what day it is ------------------------------------------------------

    @Test
    fun `a party dress is not still a party dress on Tuesday`() {
        val fourDaysOn = 4L * WorldClock.DAY
        val stale = Wardrobe.stale(liv.copy(outfitSetAt = 60), fourDaysOn)
        assertNotNull(stale)
        assertTrue(stale!!.contains("party"))

        // And the glitter has gone, whether or not anybody thought to remove it.
        val glittered = Wardrobe.withDetail(liv, "glitter on one cheek", hours = 8, nowMinute = 60)
        assertEquals(1, Wardrobe.current(glittered, nowMinute = 120).size)
        assertTrue(Wardrobe.current(glittered, nowMinute = fourDaysOn).isEmpty())
        assertEquals(1, Wardrobe.expired(glittered, nowMinute = fourDaysOn).size)
    }

    @Test
    fun `changing clothes is recorded, and the narrator is told what has stopped being true`() = runBlocking {
        repo.saveWorld(world(clockMinute = 60))
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player)
        repo.saveCharacter(liv)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(
                scene = SceneDelta(minutes = 5),
                outfits = listOf(
                    OutfitDelta(
                        character = "Liv", wearing = "a party dress, no coat", context = "PARTY",
                        temporary = "glitter on one cheek", temporaryHours = 6
                    )
                )
            ),
            turnIndex = 1,
            narration = "She pulls the jacket round her shoulders."
        )

        val dressed = repo.character("liv")!!
        assertEquals(Wardrobe.PARTY, dressed.outfitContext)
        assertTrue(dressed.temporaryLook.contains("glitter"))

        val later = repo.snapshot("w")!!.copy(
            world = repo.world("w")!!.copy(clockMinute = 3L * WorldClock.DAY)
        )
        val rendered = Wardrobe.render(later.characters, later.world.clockMinute)
        assertTrue(rendered.contains("NO LONGER TRUE, do not mention: glitter on one cheek"))
        assertTrue(rendered.contains("they would have changed by now"))
    }

    // --- Somewhere to live, and a town it is in -------------------------------------------------

    @Test
    fun `an address puts a building on a street, in order`() {
        assertTrue(Geography.isStreet("Maple Street"))
        assertFalse("a house on it is not the street", Geography.isStreet("1247 Maple Street"))
        assertEquals("Maple Street", Geography.streetNameIn("1247 Maple Street"))
        assertEquals(
            "and the pavement outside it is on the same road",
            "Maple Street",
            Geography.streetNameIn("Eastgate Sidewalk outside 1247 Maple Street")
        )
        assertEquals(1247, Geography.addressNumberIn("1247 Maple Street"))

        val low = Geography.positionOnStreet(maple, 1201, "a")
        val high = Geography.positionOnStreet(maple, 1299, "b")
        assertTrue("numbers run up the road", low.first < high.first)
        assertTrue("and stay on it", kotlin.math.abs(low.second - maple.mapY) < 0.05f)
    }

    @Test
    fun `travel time comes from where places actually are`() {
        val near = Geography.travelMinutes(flat, rental)
        val far = Geography.travelMinutes(flat, hospital)
        assertTrue("the next street is closer than the hospital: $near vs $far", near < far)
        assertEquals("north-east", Geography.bearingWord(flat, hospital))
        assertEquals("and nowhere is no distance at all", 0, Geography.travelMinutes(flat, flat))
    }

    @Test
    fun `a discovered place lands where the story put it, not where the screen had room`() = runBlocking {
        repo.saveWorld(world())
        repo.saveLocations(listOf(city, district, maple, flat, hospital, rental))
        repo.saveCharacter(player)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(
                scene = SceneDelta(minutes = 20),
                player = com.narrate.app.engine.PlayerDelta(location = "1247 Maple Street")
            ),
            turnIndex = 2,
            narration = "The party is still going at 1247 Maple Street."
        )

        val house = repo.locationDao.all("w").first { it.name == "1247 Maple Street" }
        assertEquals("it is on the road it names", "maple", house.streetId)
        assertEquals(1247, house.addressNumber)
        assertTrue(
            "and it sits on that road rather than anywhere there was space",
            kotlin.math.abs(house.mapY - maple.mapY) < 0.05f
        )
    }

    // --- The doctrine that goes with all of it ---------------------------------------------------

    @Test
    fun `the narrator is told the clock is not its own and a plan needs a date`() {
        val prompt = Prompts.gameMaster(world())
        assertTrue(prompt.contains("YOU DO NOT KEEP TIME"))
        assertTrue("plan needs a date", prompt.contains("AN ARRANGEMENT NEEDS A DAY AND A TIME"))
        assertTrue("degrees of knowing", prompt.contains("KNOWING OF SOMEBODY IS NOT KNOWING THEM"))
        assertTrue("clothes are state", prompt.contains("WHAT PEOPLE ARE WEARING IS STATE"))
        assertTrue("places are described once", prompt.contains("A PLACE IS DESCRIBED ONCE"))
        assertTrue("and the world offers things to do", prompt.contains("OPPORTUNITIES, NOT CHORES"))
        assertTrue(
            "with no required number of options",
            prompt.contains("There is no required number of options")
        )
        assertTrue("gate", prompt.contains("EVERY OPTION MUST CHANGE SOMETHING"))
    }
}
