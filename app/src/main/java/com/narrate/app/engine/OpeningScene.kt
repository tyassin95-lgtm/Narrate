package com.narrate.app.engine

/**
 * The scene the player said their world begins on.
 *
 * When the player writes an opening, that is where the story starts - not a hook to work
 * towards. Models have a habit of respectfully filing it away as "something that will happen
 * soon" and opening somewhere else entirely, which is the one thing it must never become.
 * Prompts ask for this; this is the part that does not depend on being obeyed.
 */
object OpeningScene {

    /** How much of a thread has to be the opening's own words before it is just a restatement. */
    private const val OVERLAP = 0.7

    /** True when a generated thread is the opening scene wearing a different hat. */
    fun restatesOpening(title: String, description: String, opening: String): Boolean {
        if (opening.isBlank()) return false
        val openingWords = MemoryIndex.keywords(opening)
        if (openingWords.isEmpty()) return false
        val threadWords = MemoryIndex.keywords("$title $description")
        if (threadWords.size < 3) return false
        val shared = threadWords.count { it in openingWords }.toDouble() / threadWords.size
        return shared >= OVERLAP
    }

    fun restatesOpening(thread: ThreadDelta, opening: String): Boolean =
        restatesOpening(thread.title, thread.description, opening)

    /**
     * The same delta with any thread that merely retells the opening removed.
     *
     * Used on the first turn only: later a thread may legitimately revisit how things began.
     */
    fun withoutOpeningThreads(delta: StateDelta, opening: String): StateDelta {
        if (opening.isBlank() || delta.threads.isEmpty()) return delta
        val kept = delta.threads.filterNot { restatesOpening(it, opening) }
        return if (kept.size == delta.threads.size) delta else delta.copy(threads = kept)
    }
}
