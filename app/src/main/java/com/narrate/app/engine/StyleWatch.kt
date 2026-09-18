package com.narrate.app.engine

import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.ui.markup.MarkupParser

/**
 * The images the narrator has already used.
 *
 * Across nineteen turns of one real playthrough the radiator ticked, the phone buzzed, the
 * glitter caught the light and the cold found somebody's hands over and over. Each detail was
 * good once. Repeated every turn they stop being observation and become wallpaper, and the
 * prose starts to feel like length being padded rather than a scene being written.
 *
 * A model cannot notice its own tics from inside one turn, because it only sees the last few
 * turns as prose and has no sense of what it keeps reaching for. Counting is something code is
 * good at, so the count is done here and handed back as a short list of things to stop using.
 */
object StyleWatch {

    /** How many of the recent turns a word has to appear in before it counts as a tic. */
    private const val REPEATS = 3

    /** How many turns back to look. */
    private const val WINDOW = 5

    /** Words that carry no image and so can never be a tic. */
    private val ordinary = setOf(
        "the", "and", "but", "for", "with", "that", "this", "then", "than", "into", "onto",
        "from", "over", "under", "about", "again", "still", "only", "just", "back", "away",
        "down", "out", "off", "not", "now", "his", "her", "him", "she", "they", "them", "their",
        "you", "your", "its", "was", "were", "been", "have", "has", "had", "does", "did",
        "says", "said", "say", "asks", "asked", "ask", "looks", "looked", "look", "one", "two",
        "something", "someone", "anything", "nothing", "there", "here", "where", "when", "what",
        "who", "how", "why", "which", "would", "could", "should", "will", "can", "may", "might",
        "does", "like", "does", "very", "more", "most", "less", "much", "some", "any", "all",
        "moment", "second", "minute", "time", "way", "thing", "man", "woman", "people", "face",
        "eyes", "hand", "hands", "voice", "head", "door", "room", "night", "day", "street"
    )

    private val word = Regex("[a-z]{4,}")

    /**
     * Nouns and images that have turned up in at least [REPEATS] of the last [WINDOW] turns.
     *
     * Names of people and places are excluded: a story about Liv is supposed to keep saying Liv.
     */
    fun overusedImages(snapshot: WorldSnapshot): List<String> {
        val turns = snapshot.recentTurns.takeLast(WINDOW)
        if (turns.size < REPEATS) return emptyList()

        val names = (
            snapshot.characters.map { it.name } +
                snapshot.locations.map { it.name } +
                listOf(snapshot.world.name)
            )
            .flatMap { it.lowercase().split(' ', '\'') }
            .filter { it.isNotBlank() }
            .toSet()

        val counts = mutableMapOf<String, Int>()
        turns.forEach { turn ->
            val seen = word.findAll(MarkupParser.stripMarkup(turn.narration).lowercase())
                .map { it.value }
                .filterNot { it in ordinary || it in names }
                .toSet()
            seen.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }

        return counts.filterValues { it >= REPEATS }
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key }
    }

    /** What to tell the narrator about its own habits. */
    fun render(snapshot: WorldSnapshot): String {
        val overused = overusedImages(snapshot)
        if (overused.isEmpty()) return ""
        return buildString {
            appendLine("## IMAGES YOU HAVE ALREADY USED (do not reach for them again)")
            appendLine(overused.joinToString(", "))
            appendLine(
                "Each of these has appeared in at least three of the last few turns. Find what is " +
                    "different about this moment instead: what has changed since the last turn, what " +
                    "the player has not been shown yet, what somebody does that they have not done " +
                    "before. If nothing physical has changed, write less rather than describing the " +
                    "same room again."
            )
        }
    }
}
