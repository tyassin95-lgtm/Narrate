package com.narrate.app.engine

import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Which questions the player's character can answer for himself.
 *
 * The rule that nothing is said on the player's behalf kept them mute: an NPC could ask three
 * questions in a turn and the character would stand there saying nothing while the app waited
 * for the player to type. But "where are you going?" when he is walking home, or "are you
 * always this quiet?" when quiet is written into his sheet, is not a decision - it is a line
 * of dialogue the character already has.
 *
 * So questions are sorted. Anything whose answer is already written down, or that costs
 * nothing, the character answers in his own voice. Anything that decides something - a choice,
 * a secret, how he feels about someone, what he will do next - stays the player's, untouched,
 * and it is the default for anything this cannot confidently place.
 */
object PlayerVoice {

    enum class Who {
        /** The character answers it himself, in the narration. */
        CHARACTER,

        /** The player answers it. Nobody speaks for them. */
        PLAYER
    }

    data class OpenQuestion(val text: String, val who: Who, val grounds: String)

    /** A decision, a commitment, an invitation: the player's to make, always. */
    private val decides = Regex(
        "\\b(will you|won't you|would you|are you going to|do you want|d'?you want|do you wanna|" +
            "shall we|should we|should i|can you|could you|can i|may i|do you mind|are you coming|" +
            "are you in|will we|are we doing|do you agree|are you sure|is that a yes|what do you say|" +
            "how much|are you staying|do you promise|can i trust you|will you help)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Feelings, loyalties and the state of a relationship are never answered for the player. */
    private val intimate = Regex(
        "\\b(do you (like|love|trust|hate|miss|forgive|blame|believe)|how do you feel|what do you think (of|about)|" +
            "are we |do you still|did you mean|what do you want|why did you|why didn't you|" +
            "what happened (to|between)|who did|what are you hiding|are you lying|do you regret)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Questions whose answer is written on the character sheet or in the state file. */
    private val routinePatterns = listOf(
        Routine(
            Regex("\\bwhere are you (going|headed|off to|walking|driving|heading)\\b", RegexOption.IGNORE_CASE),
            "destination"
        ),
        Routine(
            Regex(
                "\\b(where (do you live|are you staying|is your (place|flat|apartment))|" +
                    "do you live (around here|round here|near|nearby|close|far))\\b",
                RegexOption.IGNORE_CASE
            ),
            "home"
        ),
        Routine(
            Regex("\\b(what do you do|what's your job|where do you work|are you a |do you work (here|there|nights))\\b", RegexOption.IGNORE_CASE),
            "occupation"
        ),
        Routine(
            Regex("\\b(what's your name|who are you|what do they call you|your name)\\b", RegexOption.IGNORE_CASE),
            "name"
        ),
        Routine(
            Regex("\\b(are you (always )?(this )?(quiet|shy|serious|cheerful|blunt|careful|nervous)|" +
                "why are you so \\w+|do you (ever )?(talk|say) much|are you (a )?(listener|talker))\\b", RegexOption.IGNORE_CASE),
            "manner"
        ),
        Routine(
            Regex("\\b(are you (ok|okay|alright|all right|cold|tired|hurt|hungry|freezing|shivering)|" +
                "how are you|you look (tired|terrible|cold|awful)|did you sleep)\\b", RegexOption.IGNORE_CASE),
            "condition"
        ),
        Routine(
            Regex("\\b(how long have you (been|worked|lived)|how many years|since when)\\b", RegexOption.IGNORE_CASE),
            "history"
        )
    )

    private data class Routine(val pattern: Regex, val subject: String)

    /** Every question still hanging at the end of the narration, in the order they were asked. */
    fun questions(narration: String): List<String> {
        if (narration.isBlank()) return emptyList()
        val plain = SceneBrief.closingMoment(narration, characters = 700)
        val spoken = Regex("[\"“]([^\"”]{3,300})[\"”]")
            .findAll(plain)
            .map { it.groupValues[1].trim() }
            .filter { it.contains('?') }
            .flatMap { line -> line.split(Regex("(?<=\\?)\\s+")).map { it.trim() } }
            .filter { it.endsWith("?") && it.length in 4..300 }
            .toList()
        if (spoken.isNotEmpty()) return spoken.distinct().takeLast(4)
        return SceneBrief.openQuestion(narration)?.let { listOf(it) } ?: emptyList()
    }

    /**
     * Who answers this one.
     *
     * Reserved unless the answer is demonstrably already established: guessing wrong in this
     * direction only costs a line of small talk, and guessing wrong in the other takes a
     * decision out of the player's hands.
     */
    fun triage(question: String, snapshot: WorldSnapshot): OpenQuestion {
        val player = snapshot.player
            ?: return OpenQuestion(question, Who.PLAYER, "there is no character sheet to answer from")

        if (decides.containsMatchIn(question)) {
            return OpenQuestion(question, Who.PLAYER, "it asks the player to decide something")
        }
        if (intimate.containsMatchIn(question)) {
            return OpenQuestion(question, Who.PLAYER, "it asks how the player feels or what they want")
        }
        if (touchesASecret(question, player)) {
            return OpenQuestion(question, Who.PLAYER, "it reaches into something the character keeps to themselves")
        }

        val routine = routinePatterns.firstOrNull { it.pattern.containsMatchIn(question) }
            ?: return OpenQuestion(question, Who.PLAYER, "nothing on record settles it")

        val grounds = established(routine.subject, player, snapshot)
            ?: return OpenQuestion(
                question, Who.PLAYER,
                "the answer is not established anywhere, so it is not the narrator's to invent"
            )
        return OpenQuestion(question, Who.CHARACTER, grounds)
    }

    /** True when the question brushes against what this character is keeping quiet. */
    private fun touchesASecret(question: String, player: CharacterEntity): Boolean {
        val secrets = (player.secrets + " " + player.fears).trim()
        if (secrets.isBlank()) return false
        val secretWords = MemoryIndex.keywords(secrets).toSet()
        if (secretWords.isEmpty()) return false
        val asked = MemoryIndex.keywords(question)
        return asked.any { it in secretWords }
    }

    /** Where the answer already lives, phrased so the narrator can write from it. */
    private fun established(subject: String, player: CharacterEntity, snapshot: WorldSnapshot): String? = when (subject) {
        "destination" -> snapshot.locationById(player.homeLocationId)?.name
            ?.let { "where they are headed is on the map: $it" }
            ?: snapshot.currentLocation?.name?.let { "they are in $it and their route is part of the scene" }
        "home" -> snapshot.locationById(player.homeLocationId)?.name?.let { "they live at $it" }
        "occupation" -> player.role.takeIf { it.isNotBlank() }?.let { "their role is established: $it" }
            ?: player.summary.takeIf { it.isNotBlank() }?.let { "their dossier says: ${it.take(120)}" }
        "name" -> "their name is ${player.name}"
        "manner" -> listOf(player.personality, player.voice).firstOrNull { it.isNotBlank() }
            ?.let { "their manner is established: ${it.take(140)}" }
        "condition" -> player.physicalState.takeIf { it.isNotBlank() }
            ?.let { "their condition is recorded: ${it.take(120)}" }
            ?: "how they are is ordinary small talk and costs nothing"
        "history" -> listOf(player.backstory, player.summary).firstOrNull { it.isNotBlank() }
            ?.let { "their history is written down: ${it.take(140)}" }
        else -> null
    }

    /**
     * Ordinary questions the turn ended on without the character saying a word back.
     *
     * This is the shape of the complaint: an NPC asks his name, the turn stops, and the player
     * has to type "Adrian" to get past it. Nothing can rewrite the prose after the fact, but
     * naming it puts a correction in front of the narrator for the next turn, which is how
     * every other drift in this app gets pulled back.
     */
    fun unanswered(narration: String, snapshot: WorldSnapshot): List<String> {
        if (narration.isBlank()) return emptyList()
        val plain = SceneBrief.closingMoment(narration, characters = 700)
        // Every line anybody speaks, in order. A question is unanswered when nobody says
        // anything at all after it - the closing quotation mark of the question itself does
        // not count as somebody answering.
        val spoken = Regex("[\"\u201c]([^\"\u201d]{2,300})[\"\u201d]").findAll(plain).toList()
        return questions(narration)
            .map { triage(it, snapshot) }
            .filter { it.who == Who.CHARACTER }
            .filter { question ->
                val asked = spoken.lastOrNull { it.groupValues[1].contains(question.text) }
                    ?: return@filter false
                spoken.none { it.range.first > asked.range.last }
            }
            .map { it.text }
            .take(3)
    }

    /**
     * The instruction that goes with this turn: what the character answers, and what is left
     * standing for the player.
     */
    fun render(snapshot: WorldSnapshot, narration: String): String {
        val player = snapshot.player ?: return ""
        val open = questions(narration).map { triage(it, snapshot) }
        if (open.isEmpty()) return ""

        val mine = open.filter { it.who == Who.CHARACTER }
        val theirs = open.filter { it.who == Who.PLAYER }

        return buildString {
            appendLine("## QUESTIONS PUT TO ${player.name.uppercase()}")
            mine.forEach { question ->
                appendLine("- \"${question.text}\" - ${player.name} answers this himself in the narration.")
                appendLine("  The answer is already settled: ${question.grounds}. Write the line in their")
                appendLine("  own voice. Standing there saying nothing is not neutrality, it is a person")
                appendLine("  behaving strangely.")
            }
            theirs.forEach { question ->
                appendLine("- \"${question.text}\" - this one is the player's to answer. Do not answer it")
                appendLine("  for them (${question.grounds}). Leave it open, and make one of the suggested")
                appendLine("  actions the answer, written as the words they would say.")
            }
        }
    }
}
