package com.narrate.app.engine

import com.narrate.app.data.entity.MemoryEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Long-term memory retrieval.
 *
 * Context windows are finite but a world is not, so memories are scored and only the ones
 * that bear on the present moment are injected - while pinned canon is always included.
 */
object MemoryIndex {

    private val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "have", "has", "was", "were",
        "you", "your", "they", "their", "them", "his", "her", "she", "him", "are", "but", "not",
        "what", "when", "where", "who", "how", "why", "will", "would", "could", "should", "about",
        "there", "here", "then", "than", "into", "onto", "over", "under", "just", "like", "said"
    )

    fun keywords(text: String): Set<String> = text.lowercase()
        .replace(Regex("[^a-z0-9' ]"), " ")
        .split(' ')
        .map { it.trim('\'') }
        .filter { it.length > 3 && it !in stopWords }
        .toSet()

    /**
     * Score = importance + recency + keyword overlap with the player's input and the
     * present scene + a bonus when the memory is about someone who is standing right here.
     */
    fun retrieve(
        snapshot: WorldSnapshot,
        playerInput: String,
        limit: Int
    ): List<MemoryEntity> {
        val all = snapshot.memories.filter { !it.superseded }
        if (all.isEmpty()) return emptyList()

        val pinned = all.filter { it.pinned }
        val pool = all.filter { !it.pinned }

        val presentNames = (snapshot.presentNpcs() + snapshot.nearbyNpcs()).map { it.name.lowercase() }
        val locationName = snapshot.currentLocation?.name?.lowercase().orEmpty()
        val queryTerms = keywords(playerInput) +
            keywords(snapshot.recentTurns.lastOrNull()?.summary.orEmpty()) +
            presentNames.flatMap { keywords(it) } +
            keywords(locationName)

        val currentTurn = snapshot.world.turnCount.coerceAtLeast(1)

        val scored = pool.map { memory ->
            val haystack = (memory.text + " " + memory.subjectNames + " " + memory.keywords).lowercase()
            val overlap = queryTerms.count { haystack.contains(it) }
            val recency = 1.0 - ((currentTurn - memory.turnIndex).toDouble() / (currentTurn + 8).toDouble())
            val presence = if (presentNames.any { haystack.contains(it) }) 3.0 else 0.0
            val placeBonus = if (locationName.isNotBlank() && haystack.contains(locationName)) 2.0 else 0.0
            val score = memory.importance * 1.6 + overlap * 1.4 + recency * 3.0 + presence + placeBonus
            memory to score
        }.sortedByDescending { it.second }

        val take = (limit - pinned.size).coerceAtLeast(4)
        return (pinned + scored.take(take).map { it.first })
            .distinctBy { it.id }
            .sortedBy { it.turnIndex }
    }

    fun render(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return ""
        return buildString {
            appendLine("## REMEMBERED CANON (these things definitely happened - honour every one)")
            memories.forEach { memory ->
                val pin = if (memory.pinned) "*" else ""
                appendLine("- [turn ${memory.turnIndex}, ${memory.storyTime}]$pin ${memory.text}")
            }
        }
    }

    /** Memories whose subject is a specific character, for their profile page and dialogue. */
    fun forSubject(memories: List<MemoryEntity>, subjectId: String, subjectName: String, limit: Int = 25): List<MemoryEntity> =
        memories.filter {
            it.subjectIds.contains(subjectId) || it.subjectNames.contains(subjectName, ignoreCase = true)
        }.sortedByDescending { it.turnIndex }.take(limit)
}
