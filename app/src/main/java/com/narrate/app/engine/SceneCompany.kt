package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Who the player is actually with, and who merely happens to be in the room.
 *
 * Standing in the same building is not the same as being in the conversation. A waiter took an
 * order and then joined a private conversation between two people at the table, because the
 * state file said "present" and nothing said in what capacity.
 *
 * Company is read from the story rather than declared: the person who has been in the scene
 * with the player is their company, and someone who works here, or turned up in the background
 * this turn, is staff and scenery until the story gives them a reason to speak.
 */
object SceneCompany {

    data class Company(
        /** In the conversation: the people this scene is between. */
        val with: List<CharacterEntity>,
        /** In earshot at most: staff, passersby, people at other tables. */
        val alsoHere: List<CharacterEntity>
    )

    /** Roles that are usually furniture in someone else's scene. */
    private val background = Regex(
        "\\b(waiter|waitress|server|barista|bartender|barman|barmaid|host|hostess|maitre|" +
            "cashier|clerk|shopkeeper|assistant|receptionist|porter|doorman|bouncer|usher|" +
            "driver|cabbie|conductor|steward|attendant|guard|security|orderly|janitor|cleaner|" +
            "passerby|passer-by|bystander|patron|customer|commuter|regular|crowd|staff)\\b",
        RegexOption.IGNORE_CASE
    )

    fun assess(snapshot: WorldSnapshot): Company {
        val present = snapshot.presentNpcs()
        if (present.isEmpty()) return Company(emptyList(), emptyList())

        val previous = snapshot.recentTurns.lastOrNull()
            ?.presentCharacterIds
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

        val with = mutableListOf<CharacterEntity>()
        val alsoHere = mutableListOf<CharacterEntity>()
        present.forEach { npc ->
            when {
                // Someone who was in the last scene with the player is in this one with them,
                // whatever their job is. A nurse you have spent three turns with is company.
                npc.id in previous -> with += npc
                isBackground(npc) -> alsoHere += npc
                npc.importance <= 2 -> alsoHere += npc
                else -> with += npc
            }
        }
        return Company(with, alsoHere)
    }

    private fun isBackground(npc: CharacterEntity): Boolean =
        background.containsMatchIn(npc.role) || background.containsMatchIn(npc.summary.take(120))

    /** How the scene's social shape is put to the narrator. */
    fun render(snapshot: WorldSnapshot): String {
        val player = snapshot.player ?: return ""
        val company = assess(snapshot)
        if (company.with.isEmpty() && company.alsoHere.isEmpty()) return ""

        return buildString {
            if (company.with.isNotEmpty()) {
                appendLine("## WHO ${player.name.uppercase()} IS WITH (this scene is between them)")
                company.with.forEach { appendLine("- ${it.name}${it.role.takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" }.orEmpty()}") }
            }
            if (company.alsoHere.isNotEmpty()) {
                appendLine("## ALSO IN THE ROOM (present, not in the conversation)")
                company.alsoHere.forEach {
                    appendLine(
                        "- ${it.name}${it.role.takeIf { r -> r.isNotBlank() }?.let { r -> " ($r)" }.orEmpty()}" +
                            it.goals.takeIf { g -> g.isNotBlank() }?.let { g -> ": ${g.truncate(80)}" }.orEmpty()
                    )
                }
                appendLine(
                    "These people are doing their own jobs and living their own evening. They do not " +
                        "join a private conversation because they are standing near it. A waiter takes " +
                        "the order and goes."
                )
            }
        }
    }
}
