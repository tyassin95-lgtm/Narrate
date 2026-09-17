package com.narrate.app

import androidx.test.core.app.ApplicationProvider
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ChoiceGuard
import com.narrate.app.engine.ContactChannels
import com.narrate.app.engine.ContactDelta
import com.narrate.app.engine.ContinuityGuard
import com.narrate.app.engine.SceneBrief
import com.narrate.app.engine.StateApplier
import com.narrate.app.engine.StateDelta
import com.narrate.app.engine.WorldDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Being in the world is not the same as being in the player's phone.
 *
 * Two reported failures live here. NPCs asked to be contacted moments after meeting, with no
 * number ever exchanged; and the player's phone showed "no new messages in Liv's or Evan's
 * threads" for a man he had never met, whose name he only knew because Liv talked about him.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommunicationTest {

    private lateinit var repo: WorldRepository

    private val ward = LocationEntity(id = "loc-ward", worldId = "w", name = "Night Ward", type = "ROOM")
    private val flat = LocationEntity(id = "loc-flat", worldId = "w", name = "Liv's apartment", type = "BUILDING")

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true, currentLocationId = "loc-ward"
    )
    private val liv = CharacterEntity(
        id = "liv", worldId = "w", name = "Liv Carrow", currentLocationId = "loc-flat"
    )
    /** Discussed for several turns, never met, never contactable. */
    private val evan = CharacterEntity(
        id = "evan", worldId = "w", name = "Evan Mercer", currentLocationId = "loc-flat"
    )

    private fun snapshot(characters: List<CharacterEntity> = listOf(player, liv, evan)) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Eastgate", currentLocationId = "loc-ward", turnCount = 8),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(ward, flat),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = emptyList(),
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

    @Test
    fun `nobody is reachable until they have been`() {
        assertFalse(ContactChannels.canReach(liv))
        val digest = ContactChannels.render(listOf(player, liv, evan))
        assertTrue(digest.contains("Nobody"))
        assertTrue(digest.contains("no message thread"))
    }

    @Test
    fun `a suggestion to text someone whose number nobody gave is dropped`() {
        val verdict = ChoiceGuard.vet(
            snapshot(),
            listOf(
                Choice("c0", "Text Liv that you got home safe", kind = "ACTION"),
                Choice("c1", "Check Evan's thread for anything new", kind = "ACTION"),
                Choice("c2", "\"Can I have your number, in case something changes?\"", kind = "SPEECH"),
                Choice("c3", "Head down to the ward and start the round", kind = "ACTION")
            )
        )

        assertEquals(
            "asking for a number and doing something ordinary both stand",
            listOf("c2", "c3"),
            verdict.kept.map { it.id }
        )
        assertTrue(verdict.rejected.all { it.category == "no-channel" })
        assertTrue(verdict.problems().any { it.contains("no number, address or thread") })
    }

    @Test
    fun `once a number is exchanged the same suggestion is allowed`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Eastgate", currentLocationId = "loc-ward"))
        repo.saveLocations(listOf(ward, flat))
        repo.saveCharacters(listOf(player, liv, evan))

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(contacts = listOf(ContactDelta(character = "Liv Carrow", channel = "phone number", note = "she typed it into his phone"))),
            turnIndex = 3,
            narration = "She took his phone out of his hand and typed it in herself."
        )

        val saved = repo.characterDao.get("liv")!!
        assertTrue(ContactChannels.canReach(saved))
        assertTrue(ContactChannels.canReachBy(saved, "text"))

        val verdict = ChoiceGuard.vet(
            snapshot(listOf(player, saved, evan)),
            listOf(
                Choice("c0", "Text Liv that you got home safe", kind = "ACTION"),
                Choice("c1", "Text Evan and ask what he wanted", kind = "ACTION")
            )
        )
        assertEquals(listOf("c0"), verdict.kept.map { it.id })
        assertTrue("the man he has never met is still unreachable", verdict.rejected.single().choice.id == "c1")

        // And the world remembers how it happened.
        val memory = repo.memoryDao.all("w").map { it.text }
        assertTrue(memory.any { it.contains("Liv Carrow") && it.contains("calls and texts") })
    }

    @Test
    fun `a number can be lost again`() = runBlocking {
        repo.saveWorld(WorldEntity(id = "w", name = "Eastgate", currentLocationId = "loc-ward"))
        repo.saveLocations(listOf(ward))
        repo.saveCharacters(listOf(player, liv.copy(playerContact = "PHONE")))

        StateApplier(repo).apply(
            repo.snapshot("w")!!,
            StateDelta(contacts = listOf(ContactDelta(character = "Liv Carrow", channel = "PHONE", established = false))),
            turnIndex = 9,
            narration = "The call did not connect. It had not connected all week."
        )
        assertFalse(ContactChannels.canReach(repo.characterDao.get("liv")!!))
    }

    @Test
    fun `a text message from across town is not someone walking in`() {
        // The reported warning: Liv texts from her flat and the guard reports her as having
        // appeared at the hospital.
        val narration = """
            The ward settles into its three-in-the-morning quiet.

            [[sms from="Liv"]]I got in ok. Thank you for walking me back.[[/sms]]

            He reads it twice and puts the phone face down on the desk.
        """.trimIndent()

        val issues = ContinuityGuard.auditNarration(
            snapshot(listOf(player, liv.copy(playerContact = "PHONE"), evan)),
            narration,
            movedNames = emptySet()
        )
        assertTrue(
            "a message is not an arrival: ${issues.map { it.description }}",
            issues.none { it.category == "presence" }
        )
    }

    @Test
    fun `a message from someone with no channel is flagged instead of accepted`() {
        val narration = """
            He checks his phone out of habit.

            [[sms from="Evan"]]We need to talk about Liv.[[/sms]]
        """.trimIndent()

        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = emptySet())
        val flagged = issues.single { it.category == "no-channel" }
        assertTrue(flagged.description.contains("Evan Mercer"))
        assertTrue(flagged.resolution.contains("contacts"))
    }

    @Test
    fun `someone standing in the room is still held to being in the room`() {
        // The remote exemption must not become a way to smuggle absent people into a scene.
        val narration = "Liv leans against the doorframe. \"You look terrible,\" she says."
        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = emptySet())
        assertTrue(
            "she is recorded at her flat, so acting in the ward is still a warning",
            issues.any { it.category == "presence" && it.description.contains("Liv") }
        )
    }

    @Test
    fun `the scene brief tells the narrator who can and cannot be reached`() {
        val withPhone = liv.copy(playerContact = "PHONE")
        val brief = SceneBrief.render(snapshot(listOf(player, withPhone, evan)), "", "ACTION")
        assertTrue(brief.contains("WHO ADRIAN VOSS CAN REACH FROM HERE"))
        assertTrue(brief.contains("Liv Carrow: by calls and texts"))
        assertTrue("and that the list is closed", brief.contains("Nobody else"))

        val none = SceneBrief.render(snapshot(), "", "ACTION")
        assertTrue(none.contains("Nobody. No numbers"))
        assertTrue(none.contains("Asking someone present for their number is allowed"))
    }

    @Test
    fun `the roster says of every person whether they can be reached`() {
        val roster = WorldDigest.npcRoster(snapshot(listOf(player, liv.copy(playerContact = "PHONE,EMAIL"), evan)))
        assertTrue(roster.contains("Liv Carrow"))
        assertTrue(roster.contains("Reachable by calls and texts, email"))
        assertTrue(roster.contains("Evan Mercer"))
        assertTrue(roster.contains("The player has no way to contact them"))
    }

    @Test
    fun `the words a model uses for a channel all mean the same thing`() {
        listOf("phone", "phone number", "her number", "cell", "mobile", "text", "SMS", "call")
            .forEach { assertEquals(it, ContactChannels.PHONE, ContactChannels.normalise(it)) }
        assertEquals(ContactChannels.EMAIL, ContactChannels.normalise("email address"))
        assertEquals(ContactChannels.SOCIAL, ContactChannels.normalise("instagram handle"))
        assertEquals("adding one channel never drops another", "PHONE,EMAIL", ContactChannels.add("PHONE", "email"))
    }
}
