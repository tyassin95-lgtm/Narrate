package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.KnowledgeEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.PlayerDelta
import com.narrate.app.engine.PlayerKnowledge
import com.narrate.app.engine.RevealDelta
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.WorldDigest
import com.narrate.app.ui.codex.CodexUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the world knows, what the narrator knows, and what the player's character knows.
 *
 * These were one thing for the whole life of this app, and it is the single biggest reason the
 * game stopped being worth playing. A world is generated whole - every street, every person's
 * history, every secret - because the simulation needs all of it. Handing all of it to the
 * player on turn one means there is nothing left to find out, and a world with nothing left to
 * find out is a document, not a place.
 *
 * So they are three layers now, and everything here is a way of checking that they stay apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerKnowledgeTest {

    private lateinit var repo: WorldRepository

    private val city = LocationEntity(id = "city", worldId = "w", name = "Calder City", type = "SETTLEMENT")
    private val street = LocationEntity(id = "street", worldId = "w", name = "Maple Street", type = "DISTRICT", parentId = "city")
    private val cafe = LocationEntity(id = "cafe", worldId = "w", name = "Rowan's", type = "BUILDING", parentId = "street")
    private val ninasFlat = LocationEntity(
        id = "nina-flat", worldId = "w", name = "Nina's Flat", type = "BUILDING", parentId = "city"
    )
    private val juniper = LocationEntity(
        id = "juniper", worldId = "w", name = "Juniper Cafe", type = "BUILDING", parentId = "street"
    )

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "cafe"
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv Carroway", currentLocationId = "cafe",
        role = "restorer at the museum", appearance = "Tall, dark coat, paint under her nails",
        outfit = "green scarf", goals = "Get the gallery submission in before Friday",
        secrets = "She has not told anyone the lease is up", backstory = "Grew up three towns over",
        homeLocationId = "nina-flat", routine = "At the studio most afternoons"
    )
    private val nina = CharacterEntity(
        id = "nina", worldId = "w", name = "Nina Alvarez", currentLocationId = "nina-flat",
        role = "night nurse", secrets = "Owes her brother money"
    )

    private fun knowledgeRow(subjectType: String, id: String, name: String, field: String) =
        PlayerKnowledge.row("w", subjectType, id, name, field)

    private fun snapshot(
        knowledge: List<KnowledgeEntity> = emptyList(),
        characters: List<CharacterEntity> = listOf(player, liv, nina),
        turnCount: Int = 3
    ) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe", turnCount = turnCount),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(city, street, cafe, ninasFlat, juniper),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = emptyList(),
        memories = emptyList(),
        visualIdentities = emptyList(),
        knowledge = knowledge
    )

    /** Having met Liv at the cafe, and nothing more. */
    private fun metLiv(): List<KnowledgeEntity> =
        PlayerKnowledge.onMeeting("w", liv, 1, "Day 1, 2:00 PM", emptySet()) +
            listOf(
                knowledgeRow(PlayerKnowledge.LOCATION, "city", "Calder City", PlayerKnowledge.EXISTS),
                knowledgeRow(PlayerKnowledge.LOCATION, "street", "Maple Street", PlayerKnowledge.EXISTS),
                knowledgeRow(PlayerKnowledge.LOCATION, "cafe", "Rowan's", PlayerKnowledge.EXISTS)
            )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.narrate.app.data.db.NarrateDatabase.get(context).clearAllTables()
        }
        repo = WorldRepository(context)
    }

    // --- Meeting somebody ----------------------------------------------------------------

    @Test
    fun `meeting somebody tells you what they look like and nothing else`() {
        val world = snapshot(metLiv())
        val known = PlayerKnowledge.asKnown(world, liv)

        assertEquals("Liv Carroway", known.name)
        assertEquals("Tall, dark coat, paint under her nails", known.appearance)
        assertEquals("green scarf", known.outfit)

        assertEquals("you cannot see somebody's job", "", known.role)
        assertEquals("or what they want", "", known.goals)
        assertEquals("or what they are hiding", "", known.secrets)
        assertEquals("or their history", "", known.backstory)
        assertEquals("or where they sleep", null, known.homeLocationId)
        assertEquals("or their week", "", known.routine)
    }

    @Test
    fun `what she tells you is what you know`() {
        val told = metLiv() + PlayerKnowledge.row(
            "w", PlayerKnowledge.CHARACTER, "liv", "Liv Carroway", PlayerKnowledge.ROLE,
            value = "restorer at the museum", source = PlayerKnowledge.TOLD, sourceDetail = "she said so"
        )
        val known = PlayerKnowledge.asKnown(snapshot(told), liv)
        assertEquals("restorer at the museum", known.role)
        assertEquals("and no more than that", "", known.secrets)
        assertEquals(
            PlayerKnowledge.TOLD,
            PlayerKnowledge.learned(snapshot(told), "liv", PlayerKnowledge.ROLE)?.source
        )
    }

    @Test
    fun `somebody never met is not in the cast at all`() {
        val world = snapshot(metLiv())
        assertEquals(listOf("Liv Carroway"), PlayerKnowledge.knownCharacters(world).map { it.name })
        assertTrue(
            "though the world still has her, with somewhere to be",
            world.npcs.any { it.name == "Nina Alvarez" && it.currentLocationId == "nina-flat" }
        )
    }

    // --- The map -------------------------------------------------------------------------

    @Test
    fun `the map is the places the player knows, not the places that exist`() {
        val world = snapshot(metLiv())
        assertEquals(
            listOf("Calder City", "Maple Street", "Rowan's"),
            PlayerKnowledge.knownLocations(world).map { it.name }
        )
        assertFalse(
            "an address nobody has given them is not on it",
            PlayerKnowledge.knows(world, "nina-flat", PlayerKnowledge.EXISTS)
        )
    }

    @Test
    fun `a world that predates all of this keeps the map it has already shown`() {
        // An empty knowledge table means one thing: a save from before this existed. Hiding a
        // city the player has walked around for thirty turns would be the worse lie.
        val old = snapshot(knowledge = emptyList(), turnCount = 30)
        assertTrue(PlayerKnowledge.legacy(old))
        assertEquals(5, PlayerKnowledge.knownLocations(old).size)
        assertEquals("and everybody they have met", 2, PlayerKnowledge.knownCharacters(old).size)
    }

    // --- Learning during play ------------------------------------------------------------

    @Test
    fun `being in a scene, and being told, are both written down with where they came from`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe", storyTime = "Day 1, 2:00 PM"))
        repo.saveLocations(listOf(city, street, cafe.copy(discovered = false), ninasFlat.copy(discovered = false)))
        repo.saveCharacter(player)
        repo.saveCharacter(liv)
        repo.saveCharacter(nina)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(
                revealed = listOf(
                    RevealDelta(
                        about = "Liv Carroway", subjectType = "CHARACTER", field = "job",
                        value = "restorer at the museum", how = "told", from = "Liv"
                    )
                )
            ),
            turnIndex = 1,
            narration = "She turns the cup round on the saucer. \"Museum. Restoration, mostly.\""
        )

        val learned = repo.knowledgeDao.all("w")
        val liv = learned.filter { it.subjectId == "liv" }
        assertTrue("seeing her is enough for what she looks like", liv.any { it.field == PlayerKnowledge.APPEARANCE })
        assertTrue("and being told is what settles her job", liv.any { it.field == PlayerKnowledge.ROLE })
        assertEquals(
            PlayerKnowledge.TOLD,
            liv.first { it.field == PlayerKnowledge.ROLE }.source
        )
        assertTrue("but not what she is hiding", liv.none { it.field == PlayerKnowledge.SECRETS })

        assertTrue(
            "the room they are standing in is on the map",
            learned.any { it.subjectId == "cafe" && it.field == PlayerKnowledge.EXISTS }
        )
        assertTrue(
            "and so is what contains it",
            learned.any { it.subjectId == "street" } && learned.any { it.subjectId == "city" }
        )
        assertTrue(
            "somewhere nobody mentioned is not",
            learned.none { it.subjectId == "nina-flat" }
        )
        assertTrue(
            "nor is the person who lives there",
            learned.none { it.subjectId == "nina" }
        )

        // And the map flag follows the knowledge, so nothing has to stay in step by hand.
        val places = repo.locationDao.all("w")
        assertTrue(places.first { it.id == "cafe" }.discovered)
        assertFalse(places.first { it.id == "nina-flat" }.discovered)
    }

    @Test
    fun `somewhere the narration names out loud is somewhere they have heard of`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe"))
        repo.saveLocations(listOf(city, street, cafe, ninasFlat.copy(discovered = false)))
        repo.saveCharacter(player)

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(),
            turnIndex = 2,
            narration = "\"She's staying at Nina's Flat until the lease is sorted,\" he says."
        )

        assertTrue(
            repo.knowledgeDao.all("w").any {
                it.subjectId == "nina-flat" && it.source == PlayerKnowledge.TOLD
            }
        )
        assertTrue(repo.locationDao.all("w").first { it.id == "nina-flat" }.discovered)
    }

    @Test
    fun `rewinding un-learns what those turns taught`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe", turnCount = 5))
        repo.saveKnowledge(
            listOf(
                PlayerKnowledge.row("w", PlayerKnowledge.CHARACTER, "liv", "Liv", PlayerKnowledge.EXISTS, turnIndex = 1),
                PlayerKnowledge.row("w", PlayerKnowledge.CHARACTER, "liv", "Liv", PlayerKnowledge.SECRETS, turnIndex = 4)
            )
        )

        repo.rewindTo("w", 3)

        val left = repo.knowledgeDao.all("w").map { it.field }
        assertEquals("the secret was never told, because that turn no longer happened", listOf("exists"), left)
    }

    // --- What the player is allowed to see and be offered --------------------------------

    @Test
    fun `the codex shows a stranger as a stranger`() {
        val state = CodexUiState(
            world = WorldEntity(id = "w", name = "Calder City", turnCount = 3),
            characters = listOf(player, liv, nina),
            locations = listOf(city, street, cafe, ninasFlat, juniper),
            knowledge = metLiv()
        )

        assertEquals(listOf("Liv Carroway"), state.knownNpcs.map { it.name })
        assertEquals(
            listOf("Calder City", "Maple Street", "Rowan's"),
            state.discoveredLocations.map { it.name }
        )
        val shown = state.asKnown(liv)
        assertEquals("", shown.secrets)
        assertEquals("", shown.goals)
        assertEquals("Tall, dark coat, paint under her nails", shown.appearance)
        assertTrue(state.tracksKnowledge)
    }

    @Test
    fun `a suggestion cannot name somebody or somewhere the player has never heard of`() {
        val world = snapshot(metLiv())
        val verdict = ChoiceGuard.vet(
            world,
            listOf(
                Choice("c0", "Ask Liv what she is working on", kind = "SPEECH"),
                Choice("c1", "Ask Liv about Nina Alvarez", kind = "SPEECH"),
                Choice("c2", "Walk over to the Juniper Cafe", kind = "ACTION")
            )
        )
        assertEquals(verdict.rejected.map { it.reason }.toString(), listOf("c0"), verdict.kept.map { it.id })
        assertEquals(setOf("unknown"), verdict.rejected.map { it.category }.toSet())
        assertTrue(verdict.rejected.any { it.reason.contains("never met") })
        assertTrue(verdict.rejected.any { it.reason.contains("never been") })
    }

    @Test
    fun `somebody the turn has just introduced is not a stranger to its own suggestions`() {
        // Vetting happens before the turn is applied, so the snapshot has never heard of the
        // woman who just walked in. Asking her what she wants is the obvious move, and
        // refusing it as "never met" would be the guard fighting the scene it is reading.
        val world = snapshot(metLiv())
        val turn = com.narrate.app.engine.TurnParser.parse(
            """
            ===NARRATION===
            The door goes. A woman in a wet coat looks round the room and finds him.
            ===CHOICES===
            - Ask Nina what she wants
            ===STATE===
            {"characters_update": [{"name": "Nina Alvarez", "location": "Rowan's", "movement_reason": "came in out of the rain"}]}
            ===END===
            """.trimIndent()
        )
        val verdict = ChoiceGuard.vet(world, listOf(Choice("c0", "Ask Nina what she wants", kind = "SPEECH")), turn)
        assertEquals(verdict.rejected.map { it.reason }.toString(), listOf("c0"), verdict.kept.map { it.id })

        // And without that turn behind it, the same option is still refused.
        assertTrue(
            ChoiceGuard.vet(world, listOf(Choice("c0", "Ask Nina what she wants", kind = "SPEECH")))
                .rejected.single().category == "unknown"
        )
    }

    @Test
    fun `the narrator is told the difference, in as many words`() {
        val world = snapshot(metLiv())
        val rendered = PlayerKnowledge.render(world)
        assertTrue(rendered.contains("WHAT THE PLAYER'S CHARACTER ACTUALLY KNOWS"))
        assertTrue(rendered.contains("Liv Carroway"))
        assertTrue("the hidden half is named", rendered.contains("Does NOT know"))
        assertTrue(rendered.contains("secrets"))
        assertTrue("and so is everybody they have not met", rendered.contains("Nina Alvarez"))
        assertTrue(rendered.contains("they are a stranger"))
        assertTrue("with the way to hand something over", rendered.contains("\"revealed\""))

        // And it is repeated right beside the facts themselves, because a section further up
        // the prompt was not enough to stop the protagonist knowing things he had not learned.
        val state = WorldDigest.currentState(world)
        assertTrue(state.contains("THE PLAYER DOES NOT KNOW"))
    }

    @Test
    fun `the world map handed to the narrator marks what the player has not seen`() {
        val map = WorldDigest.worldMap(snapshot(metLiv()))
        assertTrue(map, map.contains("Nina's Flat [BUILDING] (NOT KNOWN TO THE PLAYER)"))
        assertTrue(map.contains("yours to keep straight, not the player's to see"))
        assertFalse("Rowan's is theirs", map.contains("Rowan's [BUILDING] (NOT KNOWN"))
    }

    // --- Where it all starts -------------------------------------------------------------

    @Test
    fun `a new world opens with a map of one place, not a city`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Calder City", currentLocationId = "cafe"))
        repo.saveLocations(listOf(city, street, cafe.copy(discovered = false), ninasFlat.copy(discovered = false)))
        repo.saveCharacter(player.copy(homeLocationId = null))
        repo.saveCharacter(liv)
        repo.saveCharacter(nina)

        // The opening turn, with nobody having told the player anything yet.
        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(player = PlayerDelta(location = "Rowan's")),
            turnIndex = 0,
            narration = "The door sticks on the frame. Inside it is warm and half empty."
        )

        val onMap = repo.locationDao.all("w").filter { it.discovered }.map { it.name }.sorted()
        assertEquals(listOf("Calder City", "Maple Street", "Rowan's"), onMap)
        assertNotNull(repo.knowledgeDao.all("w").firstOrNull { it.subjectId == "cafe" })
    }
}
