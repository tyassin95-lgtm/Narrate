package com.narrate.app

import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.PlayerVoice
import com.narrate.app.engine.Prompts
import com.narrate.app.engine.SceneBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protagonist is a person, not a mute.
 *
 * Being asked three questions in a row and answering none of them is not player agency, it is
 * a character behaving strangely. But agency is the whole point, so the line is drawn at
 * decisions: what is already written down, the character says himself; what decides something,
 * the player says.
 */
class PlayerVoiceTest {

    private val street = LocationEntity(id = "loc-walk", worldId = "w", name = "Harker Street")
    private val flat = LocationEntity(id = "loc-home", worldId = "w", name = "Eastgate Rentals")

    private val adrian = CharacterEntity(
        id = "pc", worldId = "w", name = "Adrian Voss", isPlayer = true,
        role = "emergency medicine resident",
        personality = "Dry, watchful, slow to trust. Says less than he thinks.",
        backstory = "Raised in group homes; twelve years in medicine.",
        physicalState = "soaked to the skin",
        secrets = "He has never told anyone that Marguerite paid for his final year.",
        currentLocationId = "loc-walk", homeLocationId = "loc-home"
    )
    private val liv = CharacterEntity(id = "liv", worldId = "w", name = "Liv Carrow", currentLocationId = "loc-walk")

    private fun snapshot(
        player: CharacterEntity = adrian,
        turns: List<TurnEntity> = emptyList()
    ) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Eastgate", currentLocationId = "loc-walk", turnCount = 6),
        player = player,
        characters = listOf(player, liv),
        locations = listOf(street, flat),
        links = emptyList(),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = turns,
        memories = emptyList(),
        visualIdentities = emptyList()
    )

    private fun turn(narration: String) = TurnEntity(
        id = "t", worldId = "w", index = 5, narration = narration, summary = "a walk"
    )

    private fun who(question: String, player: CharacterEntity = adrian) =
        PlayerVoice.triage(question, snapshot(player)).who

    @Test
    fun `the questions from the report are answered by the character himself`() {
        assertEquals(PlayerVoice.Who.CHARACTER, who("Where are you going?"))
        assertEquals(
            PlayerVoice.Who.CHARACTER,
            who("Are you quiet because you like listening to people, or do you just enjoy it?")
        )
    }

    @Test
    fun `small talk whose answer is on the sheet is the character's to answer`() {
        listOf(
            "Where are you headed?",
            "Do you live around here?",
            "What do you do?",
            "What's your name?",
            "How long have you worked there?",
            "Are you cold?",
            "Are you always this quiet?"
        ).forEach { assertEquals(it, PlayerVoice.Who.CHARACTER, who(it)) }
    }

    @Test
    fun `anything that decides something stays the player's`() {
        listOf(
            "Do you want to come up for a coffee?",
            "Will you help me?",
            "Should we tell him?",
            "Can you keep this to yourself?",
            "Are you coming with me or not?",
            "Do you trust me?",
            "How do you feel about all this?",
            "What do you think of Marcus?",
            "Why did you lie to her?"
        ).forEach { assertEquals(it, PlayerVoice.Who.PLAYER, who(it)) }
    }

    @Test
    fun `a question that brushes a secret is never answered for the player`() {
        // "How long have you..." is ordinarily small talk. Not when it walks into this.
        assertEquals(PlayerVoice.Who.PLAYER, who("How long have you known Marguerite?"))
        val triaged = PlayerVoice.triage("How long have you known Marguerite?", snapshot())
        assertTrue(triaged.grounds.contains("keeps to themselves"))
    }

    @Test
    fun `nothing is invented to answer with`() {
        // A blank sheet means the answer is not established, so the question is the player's.
        val blank = adrian.copy(role = "", summary = "", personality = "", voice = "", backstory = "")
        assertEquals(PlayerVoice.Who.PLAYER, who("What do you do?", blank))
        assertEquals(PlayerVoice.Who.PLAYER, who("Are you always this quiet?", blank))
        assertTrue(
            PlayerVoice.triage("What do you do?", snapshot(blank)).grounds.contains("not established")
        )
    }

    @Test
    fun `being asked three things at once answers the easy two and leaves the third`() {
        val narration = """
            She pulls the jacket tighter and looks sideways at him as they walk.

            "Where are you going? Do you live around here? And would you stay for a drink if I asked?"
        """.trimIndent()

        val triaged = PlayerVoice.questions(narration).map { PlayerVoice.triage(it, snapshot()) }
        assertEquals(3, triaged.size)
        assertEquals(PlayerVoice.Who.CHARACTER, triaged[0].who)
        assertEquals(PlayerVoice.Who.CHARACTER, triaged[1].who)
        assertEquals("the invitation is a decision", PlayerVoice.Who.PLAYER, triaged[2].who)
    }

    @Test
    fun `the brief tells the narrator which to answer and which to leave standing`() {
        val brief = SceneBrief.render(
            snapshot(turns = listOf(turn("\"Where are you going? Would you come up for a coffee?\""))),
            "", "ACTION"
        )
        assertTrue(brief.contains("QUESTIONS PUT TO ADRIAN VOSS"))
        assertTrue(brief.contains("Adrian Voss answers this himself in the narration"))
        assertTrue(brief.contains("Eastgate Rentals"))
        assertTrue(brief.contains("the player's to answer"))
        assertTrue(brief.contains("Do not answer it"))
    }

    @Test
    fun `a scene with no questions in it is left alone`() {
        assertTrue(PlayerVoice.questions("He walked her as far as the corner and stopped.").isEmpty())
        assertEquals("", PlayerVoice.render(snapshot(), "He walked her as far as the corner."))
    }

    @Test
    fun `a turn that ends by asking his name is caught as the character saying nothing`() {
        // Straight from the report: Liv introduces herself, asks his name, and the turn stops
        // so the player can type "Adrian".
        val narration = """
            "I'm Liv, by the way." Her fingers touch the silver chain at her throat.
            "What's your name?"
        """.trimIndent()

        assertEquals(listOf("What's your name?"), PlayerVoice.unanswered(narration, snapshot()))
    }

    @Test
    fun `nothing is flagged when he actually answered`() {
        val narration = """
            "I'm Liv, by the way. What's your name?"

            "Adrian." He shifts the bag to his other shoulder. "Adrian Voss."
        """.trimIndent()

        assertTrue(PlayerVoice.unanswered(narration, snapshot()).isEmpty())
    }

    @Test
    fun `a question that is the player's is allowed to end the turn`() {
        val narration = "\"Would you come up for a coffee?\" She is already looking for her key."
        assertTrue(
            "leaving a decision open is the whole point",
            PlayerVoice.unanswered(narration, snapshot()).isEmpty()
        )
    }

    @Test
    fun `the craft rules put him in the conversation rather than beside it`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("IS IN THE CONVERSATION, NOT WATCHING IT"))
        assertTrue(prompt.contains("a monologue with a witness"))
        assertTrue(prompt.contains("same scene it was asked, not next turn"))
        assertTrue(prompt.contains("never their own name"))
        assertTrue(prompt.contains("Ending on"))
    }

    @Test
    fun `the law itself draws the line between a decision and a line of dialogue`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("THEIR CHARACTER IS A PERSON"))
        assertTrue(prompt.contains("That is not the same as leaving them mute"))
        assertTrue(prompt.contains("When you cannot tell which kind it is, it is the"))
        assertTrue("and the player keeps every decision", prompt.contains("Never decide what the player wants"))
    }

    @Test
    fun `the craft rules show both halves of it`() {
        val prompt = Prompts.gameMaster(WorldEntity(name = "Eastgate"))
        assertTrue(prompt.contains("\"Home. Ten minutes that way"))
        assertTrue(prompt.contains("Listening, mostly. You learn more."))
        assertTrue(prompt.contains("That one is a decision, and it is the player's."))
    }
}
