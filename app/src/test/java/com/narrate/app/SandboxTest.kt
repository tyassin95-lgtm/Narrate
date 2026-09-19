package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.ItemUpdate
import com.narrate.app.engine.MapPlacement
import com.narrate.app.engine.ParsedTurn
import com.narrate.app.engine.Possession
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.Refusal
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.StoryClock
import com.narrate.app.engine.TurnParser
import com.narrate.app.engine.WorldActions
import com.narrate.app.engine.WorldDigest
import com.narrate.app.ui.codex.mapArrangement
import com.narrate.app.ui.codex.pinGap
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
 * The world as something to play in rather than something to read about.
 *
 * Four separate complaints turned out to be the same complaint: the player spent their turns
 * on housekeeping instead of decisions. They pressed "wait a little longer" to reach the next
 * scene. They watched their own coat become somebody else's property because the app could not
 * tell lending from giving. They typed something a person might actually do and got the model
 * talking back at them instead of a world answering. And the map was a diagram of the world's
 * database rather than a picture of anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SandboxTest {

    private lateinit var repo: WorldRepository

    private val district = LocationEntity(id = "district", worldId = "w", name = "Eastgate", type = "DISTRICT")
    private val street = LocationEntity(
        id = "street", worldId = "w", name = "Harker Street", type = "DISTRICT",
        parentId = "district", mapX = 0.4f, mapY = 0.4f
    )
    private val flat = LocationEntity(
        id = "flat", worldId = "w", name = "Adrian's Flat", type = "BUILDING",
        parentId = "street", mapX = 0.42f, mapY = 0.43f
    )
    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true,
        currentLocationId = "street", homeLocationId = "flat"
    )
    private val liv = CharacterEntity(id = "liv", worldId = "w", name = "Liv Carroway", currentLocationId = "street")

    private fun snapshot(
        items: List<ItemEntity> = emptyList(),
        storyTime: String = "Day 1, 9:00 PM",
        turns: List<TurnEntity> = emptyList()
    ) = WorldSnapshot(
        world = WorldEntity(
            id = "w", name = "Calder City", currentLocationId = "street",
            storyTime = storyTime, turnCount = turns.size
        ),
        player = player,
        characters = listOf(player, liv),
        locations = listOf(district, street, flat),
        links = emptyList(),
        items = items,
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = turns,
        memories = emptyList(),
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

    // --- Objects -------------------------------------------------------------------------

    @Test
    fun `lending a coat does not give it away`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "street"))
        repo.saveLocations(listOf(district, street, flat))
        repo.saveCharacter(player)
        repo.saveCharacter(liv)
        repo.saveItems(
            listOf(ItemEntity(id = "coat", worldId = "w", name = "wool coat", ownerId = "pc", holderId = "pc"))
        )

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(
                itemsUpdate = listOf(
                    ItemUpdate(name = "wool coat", heldBy = "Liv Carroway", transfer = "lend")
                )
            ),
            turnIndex = 3,
            narration = "He puts it round her shoulders before she can argue."
        )

        val coat = repo.itemDao.all("w").single()
        assertEquals("she is holding it", "liv", coat.holderId)
        assertEquals("it is still his", "pc", coat.ownerId)
        assertEquals(Possession.LENT, coat.possession)
        assertNull("and it is not also lying in the street", coat.locationId)
        assertTrue("with a line in its history", coat.history.contains("lent"))
    }

    @Test
    fun `the narrator is told, in one sentence, that it is still his`() {
        val coat = ItemEntity(
            id = "coat", worldId = "w", name = "wool coat", ownerId = "pc", holderId = "liv",
            possession = Possession.LENT, significance = "His father's."
        )
        val state = WorldDigest.currentState(snapshot(items = listOf(coat)))
        assertTrue(state.contains("LENT to Liv Carroway"))
        assertTrue(state.contains("Lending is not giving"))
        assertTrue(state.contains("may ask for it back"))
        assertTrue(state.contains("Liv Carroway has no claim on it"))
    }

    @Test
    fun `asking for his own coat back is allowed and offering it back to her is not`() {
        val coat = ItemEntity(
            id = "coat", worldId = "w", name = "wool coat", ownerId = "pc", holderId = "liv",
            possession = Possession.LENT
        )
        val verdict = ChoiceGuard.vet(
            snapshot(items = listOf(coat)),
            listOf(
                Choice("c0", "\"I'll need the coat back before I go.\"", kind = "SPEECH"),
                Choice("c1", "Tell her to keep the coat", kind = "SPEECH"),
                Choice("c2", "Ask if she wants her coat back", kind = "SPEECH")
            )
        )
        assertEquals(listOf("c0", "c1"), verdict.kept.map { it.id })
        assertEquals("item-ownership", verdict.rejected.single().category)
    }

    @Test
    fun `an object that was burned is not on the menu`() {
        val letter = ItemEntity(
            id = "letter", worldId = "w", name = "the letter", ownerId = "pc",
            possession = Possession.DESTROYED
        )
        val verdict = ChoiceGuard.vet(
            snapshot(items = listOf(letter)),
            listOf(Choice("c0", "Show her the letter", kind = "ACTION"))
        )
        assertTrue(verdict.kept.isEmpty())
        assertTrue(verdict.rejected.single().reason.contains("destroyed"))
    }

    @Test
    fun `every kind of move an object can make is a different fact`() {
        val base = ItemEntity(id = "i", worldId = "w", name = "key", ownerId = "pc", holderId = "pc")
        fun move(transfer: String, holder: CharacterEntity? = null, place: String? = null) =
            Possession.settle(base, holder, place, transfer, null, turnIndex = 1)

        assertEquals(Possession.LENT, move("lend", liv).possession)
        assertEquals("lending never moves the owner", "pc", move("lend", liv).ownerId)
        assertEquals("giving does", "liv", move("give", liv).ownerId)
        assertEquals(Possession.HELD, move("give", liv).possession)
        assertEquals(Possession.DROPPED, move("drop", place = "street").possession)
        assertEquals(Possession.STORED, move("store", place = "flat").possession)
        assertEquals(Possession.LOST, move("lose").possession)
        assertEquals(Possession.DESTROYED, move("destroy").possession)
        assertNull("something nobody has is not in anybody's hands", move("drop", place = "street").holderId)

        // And with nothing declared at all, the safe reading is lending, not a gift.
        assertEquals(Possession.LENT, Possession.settle(base, liv, null, null, null, 1).possession)
        assertEquals("pc", Possession.settle(base, liv, null, null, null, 1).ownerId)
    }

    // --- Time ----------------------------------------------------------------------------

    @Test
    fun `the time controls promise real time`() {
        val evening = snapshot(storyTime = "Day 1, 9:00 PM")
        assertEquals(60, WorldActions.minimumMinutes(WorldActions.SKIP, evening))
        assertEquals(
            "sleeping at nine at night reaches seven the next morning",
            10 * 60,
            WorldActions.minimumMinutes(WorldActions.SLEEP, evening)
        )
        assertEquals("Day 2, 7:00 AM", StoryClock.advance("Day 1, 9:00 PM", 10 * 60))
        assertEquals("Day 1, 10:30 AM", StoryClock.advance("Day 1, 9:30 AM", 60))
        assertEquals("a world that writes 24-hour time keeps it", "Day 1, 14:05", StoryClock.advance("Day 1, 13:05", 60))
    }

    @Test
    fun `going home is not offered when they are standing in it`() {
        assertTrue(WorldActions.available(WorldActions.HOME, snapshot()))
        val athome = snapshot().let {
            it.copy(
                world = it.world.copy(currentLocationId = "flat"),
                player = player.copy(currentLocationId = "flat")
            )
        }
        assertFalse(WorldActions.available(WorldActions.HOME, athome))
    }

    @Test
    fun `a time control tells the narrator to move the world, not to describe the wait`() {
        val instruction = WorldActions.instruction(WorldActions.SKIP, snapshot())
        assertTrue(instruction.contains("THE PLAYER USED A TIME CONTROL"))
        assertTrue(instruction.contains("Cover the gap"))
        assertTrue(instruction.contains("the moment something is different"))
        assertTrue("the world runs while it passes", instruction.contains("Every NPC followed their routine"))
        assertTrue(instruction.contains("has wasted the press"))

        val sleep = WorldActions.instruction(WorldActions.SLEEP, snapshot())
        assertTrue(sleep.contains("advance story_time to"))
        assertTrue(sleep.contains("Open the turn on waking"))
    }

    // --- Suggestions ---------------------------------------------------------------------

    @Test
    fun `housekeeping never reaches the menu`() {
        listOf(
            "Drink your water",
            "Take a sip of the coffee",
            "Order another coffee",
            "Check the time",
            "Look around the room",
            "Sit down at the bar",
            "Turn the page",
            "Wait for her to come back",
            "Go home",
            "Go to bed",
            "Let the time pass",
            "People-watch for a while",
            "Nurse your drink"
        ).forEach {
            assertTrue("\"$it\" is not a decision", ChoiceGuard.isFiller(it))
        }
    }

    @Test
    fun `things worth choosing are left alone`() {
        listOf(
            "\"You knew, didn't you. The whole time.\"",
            "Ask Liv what happened to the money",
            "Take the envelope out of the drawer while she is in the kitchen",
            "Tell her the truth about the lease",
            "Follow him out onto the street",
            "Wait until he leaves, then go through the desk"
        ).forEach {
            assertFalse("\"$it\" is a real move", ChoiceGuard.isFiller(it))
        }
    }

    @Test
    fun `the narrator is told that time is a button and a chore is not a choice`() {
        val prompt = Prompts.gameMaster(WorldEntity(id = "w", name = "Calder City"))
        assertTrue(prompt.contains("separate controls for passing time"))
        assertTrue(prompt.contains("it is not a choice, it is a chore"))
        assertTrue(prompt.contains("orders their coffee in the narration"))
    }

    // --- The player's freedom to play ------------------------------------------------------

    @Test
    fun `the narrator is told the player may attempt anything`() {
        val prompt = Prompts.gameMaster(WorldEntity(id = "w", name = "Calder City"))
        assertTrue(prompt.contains("THE PLAYER MAY ATTEMPT ANYTHING"))
        assertTrue(prompt.contains("never lecture them"))
        assertTrue(prompt.contains("Consequences are the whole answer"))
        assertTrue(
            "and never treat their input as a mistake in how they typed it",
            prompt.contains("never treat it as a mistake or as")
        )
    }

    @Test
    fun `a refusal is recognised for what it is, and a scene is not`() {
        fun parse(text: String): ParsedTurn = TurnParser.parse(text)

        assertTrue(Refusal.looksLikeRefusal(parse("I'm sorry, but I can't help with that request.")))
        assertTrue(Refusal.looksLikeRefusal(parse("I cannot continue with this story.")))
        assertTrue(Refusal.looksLikeRefusal(parse("As an AI, I'm not able to write that.")))

        // A character saying no is a scene, and losing it would be worse than the bug.
        assertFalse(
            Refusal.looksLikeRefusal(
                parse("She folds her arms. \"I can't help you with that, and you know why.\"")
            )
        )
        // And anything that came back as a real turn is a real turn, however it opens.
        assertFalse(
            Refusal.looksLikeRefusal(
                parse(
                    """
                    ===NARRATION===
                    I can't, she says, and means it.
                    ===CHOICES===
                    - Press her
                    ===STATE===
                    {"story_time": "Day 1, 9:00 PM"}
                    ===END===
                    """.trimIndent()
                )
            )
        )
    }

    @Test
    fun `the reframe repeats the player's turn without editing it`() {
        val input = "Take the money out of the till while she is in the back"
        val reframe = Refusal.reframe(input, "ACTION")
        assertTrue(reframe.contains("This is interactive fiction"))
        assertTrue("word for word", reframe.contains(input))
        assertTrue(reframe.contains("the consequences"))
        assertTrue(Refusal.NOTICE.contains("not a problem with what you typed"))
    }

    // --- The map -------------------------------------------------------------------------

    @Test
    fun `the story decides which way and how far`() {
        assertEquals(-Math.PI / 2, MapPlacement.bearing("two streets north of the flat"))
        assertEquals(Math.PI / 4, MapPlacement.bearing("out to the south-east of the city"))
        assertNull(MapPlacement.bearing("she walks him back"))

        val close = MapPlacement.distance(MapPlacement.Hint(text = "just across the road"))
        val walk = MapPlacement.distance(MapPlacement.Hint(travelTime = "twenty minutes on foot"))
        val bus = MapPlacement.distance(MapPlacement.Hint(travelTime = "forty minutes by bus"))
        assertTrue("across the road is nearer than a walk", close < walk)
        assertTrue("and a walk is nearer than a bus ride", walk < bus)

        // Put together: a place described as north of the flat lands north of the flat.
        val (x, y) = MapPlacement.place(
            anchors = listOf(flat),
            existing = listOf(district, street, flat),
            seed = 1,
            hint = MapPlacement.Hint(text = "Ten minutes north of here, past the bridge.")
        )
        assertTrue("north is up: $y vs ${flat.mapY}", y < flat.mapY)
        assertTrue("and roughly straight up", kotlin.math.abs(x - flat.mapX) < 0.1f)
    }

    @Test
    fun `no two pins on the drawn map land on top of each other`() {
        // The old map put every place wherever the depth-first walk reached, which with a
        // dozen locations was a wall of overlapping labels joined by crossing lines.
        val cluster = (0 until 12).map { index ->
            LocationEntity(
                id = "p$index", worldId = "w", name = "Place $index", parentId = "district",
                mapX = 0.5f + index * 0.002f, mapY = 0.5f + index * 0.001f
            )
        }
        val arrangement = mapArrangement(listOf(district) + cluster)
        assertEquals("every place is placed", 12, arrangement.size)
        cluster.forEach { a ->
            cluster.filter { it.id != a.id }.forEach { b ->
                assertTrue(
                    "${a.name} and ${b.name} are on top of each other",
                    pinGap(arrangement, a.id, b.id) > 20f
                )
            }
        }
    }
}
