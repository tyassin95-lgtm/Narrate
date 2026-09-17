package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.repo.WorldSnapshot

/**
 * The last few seconds of the story, stated plainly.
 *
 * The world digest describes everything that is true; this describes what is happening right
 * now. Suggested actions are written against this, which is the difference between "Talk to
 * her" and answering the question she just asked.
 */
object SceneBrief {

    /**
     * A question someone put to the player and is waiting on an answer to.
     *
     * Judged live by how little follows it rather than by where it falls, because a text
     * message is two lines long and a scene can be two pages. A question the narration has
     * already moved on from is not still hanging in the air.
     */
    fun openQuestion(narration: String): String? {
        if (narration.isBlank()) return null
        val plain = stripMarkup(narration)
        if (plain.isBlank()) return null

        // Something actually spoken beats something merely written.
        val spoken = Regex("[\"\u201c]([^\"\u201d]{3,300})[\"\u201d]")
            .findAll(plain)
            .lastOrNull { it.groupValues[1].trim().endsWith("?") }
            ?.let { it.groupValues[1].trim() to it.range.last }

        val (question, endsAt) = spoken ?: bareQuestion(plain) ?: return null
        val trailing = plain.length - endsAt
        return question.takeIf { trailing <= STILL_HANGING }
    }

    /** The last question in the prose when nobody bothered with quotation marks. */
    private fun bareQuestion(plain: String): Pair<String, Int>? {
        val mark = plain.lastIndexOf('?')
        if (mark < 0) return null
        val start = plain.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), mark - 1) + 1
        val question = plain.substring(start, mark + 1).trim()
        if (question.length !in 4..300) return null
        return question to mark
    }

    /** How much narration may follow a question before it counts as answered or dropped. */
    private const val STILL_HANGING = 200

    /** The closing stretch of the narration, as prose the model can react to. */
    fun closingMoment(narration: String, characters: Int = 900): String =
        stripMarkup(narration).takeLast(characters).trimStart()

    fun render(snapshot: WorldSnapshot, playerInput: String, inputKind: String): String {
        val player = snapshot.player ?: return ""
        val lastTurn = snapshot.recentTurns.lastOrNull()
        val present = snapshot.presentNpcs()

        return buildString {
            appendLine("# THIS MOMENT (what the suggested actions must answer to)")
            appendLine("The player is ${player.name}. Every suggested action is something ${player.name}")
            appendLine("could do or say next, written from their side of the scene.")
            appendLine()

            if (lastTurn != null && lastTurn.narration.isNotBlank()) {
                appendLine("## HOW THE LAST TURN ENDED")
                appendLine(closingMoment(lastTurn.narration))
                appendLine()
                openQuestion(lastTurn.narration)?.let { question ->
                    appendLine("## A QUESTION IS HANGING IN THE AIR")
                    appendLine("Someone asked: \"$question\"")
                    appendLine(
                        "One of the suggested actions must be ${player.name} actually answering it, " +
                            "written as the words they would say."
                    )
                    appendLine()
                }
            }

            if (playerInput.isNotBlank()) {
                appendLine("## WHAT THE PLAYER JUST DID")
                appendLine(
                    when (inputKind) {
                        "SPEECH" -> "${player.name} said: \"$playerInput\""
                        else -> "${player.name}: $playerInput"
                    }
                )
                appendLine()
            }

            if (present.isNotEmpty()) {
                appendLine("## WHO IS HERE TO ACT ON")
                present.take(6).forEach { npc ->
                    val notes = listOfNotNull(
                        npc.physicalState.takeIf { it.isNotBlank() }?.let { "condition: $it" },
                        npc.outfit.takeIf { it.isNotBlank() }?.let { "wearing: ${it.truncate(80)}" },
                        npc.relationshipToPlayer.takeIf { it.isNotBlank() }
                            ?.let { "toward ${player.name}: ${it.truncate(90)}" },
                        "warmth ${npc.affinity}"
                    )
                    appendLine("- ${npc.name}: ${notes.joinToString("; ")}")
                }
                appendLine()
            }

            val reachable = snapshot.npcs.filter { ContactChannels.canReach(it) }
            appendLine("## WHO ${player.name.uppercase()} CAN REACH FROM HERE")
            if (reachable.isEmpty()) {
                appendLine("- Nobody. No numbers, no addresses, no message threads. Do not suggest")
                appendLine("  texting, calling or emailing anyone, and do not suggest checking a thread")
                appendLine("  with someone. Asking someone present for their number is allowed.")
            } else {
                reachable.forEach { npc ->
                    appendLine(
                        "- ${npc.name}: by ${ContactChannels.parse(npc.playerContact)
                            .joinToString(", ") { ContactChannels.describe(it) }}."
                    )
                }
                appendLine("- Nobody else. Anyone not on this list cannot be contacted at all.")
            }
            appendLine()

            val carrying = snapshot.playerInventory()
            appendLine("## WHAT ${player.name.uppercase()} CAN ACTUALLY USE")
            if (carrying.isEmpty()) {
                appendLine("- Nothing in hand. Do not suggest giving, offering or using an object they")
                appendLine("  do not have.")
            } else {
                carrying.forEach { item ->
                    appendLine(
                        "- ${item.name}" +
                            (if (item.state.isNotBlank()) " (${item.state})" else "") +
                            " - theirs, in hand, available to use or offer."
                    )
                }
            }
            val lentOut = snapshot.items.filter {
                it.ownerId == player.id && it.holderId != null && it.holderId != player.id
            }
            lentOut.forEach { item ->
                val holder = snapshot.characterById(item.holderId)?.name ?: "someone"
                appendLine(
                    "- ${item.name} is ${player.name}'s but $holder has it. ${player.name} may ask " +
                        "for it back or tell them to keep it. Never write it as $holder's own " +
                        "property, and never have ${player.name} offer to return it to them."
                )
            }
        }
    }

    /** Narration markup is for the page, not for reasoning about the scene. */
    private fun stripMarkup(markup: String): String = markup
        .replace(Regex("\\[\\[/?\\s*\\w+[^\\]]*]]"), " ")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
