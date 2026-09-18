package com.narrate.app.engine

import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Whether the scene is still going anywhere.
 *
 * A real playthrough spent nine turns at a café table: sit down, watch the sidewalk, say
 * nothing, read a page, check the time, check the time again, turn the page, turn the page
 * again, wait twenty minutes. Every turn was well written and nothing happened in any of them,
 * because nothing in the app could tell that the last five minutes of story had cost the player
 * five turns and given them nothing back.
 *
 * Slice of life is not the problem: ordinary life is full of things worth a turn. Dead time is
 * the problem, and dead time has a shape - the same room, the same people, the clock barely
 * moving, nothing written down. That shape is what this measures, so the narrator can be told
 * to move the story on instead of describing the same table again.
 */
object SceneMomentum {

    /** How many turns of nothing before the scene counts as stalled. */
    private const val STALL = 3

    /** Below this many minutes a turn has not really advanced the clock. */
    private const val CRAWLING_MINUTES = 4

    data class Reading(
        val idleTurns: Int,
        val place: String,
        val minutesPassed: Int
    ) {
        val stalled: Boolean get() = idleTurns >= STALL
    }

    /** Ways a turn can be worth having: something changed, somebody arrived, something was said. */
    private val idleInput = Regex(
        "\\b(wait|waits|waiting|sit|sits|sitting|stay|stays|staying|remain|remains|" +
            "watch|watches|watching|look|looks|looking|listen|listens|listening|" +
            "check the time|glance at (?:the |your )?(?:time|phone|clock)|" +
            "turn the page|keep reading|read (?:a|another) page|say nothing|" +
            "do nothing|nothing|linger|pause|breathe|think)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when what the player did was a way of passing time rather than doing something. */
    fun isIdleInput(input: String): Boolean {
        val text = input.trim()
        if (text.isBlank()) return true
        // Anything they actually said is not idling, however quietly they said it.
        if (text.contains('"') || text.contains('“')) return false
        if (text.length > 120) return false
        return idleInput.containsMatchIn(text)
    }

    fun read(snapshot: WorldSnapshot): Reading {
        val turns = snapshot.recentTurns.takeLast(6)
        if (turns.isEmpty()) return Reading(0, snapshot.currentLocation?.name.orEmpty(), 0)

        var idle = 0
        for (turn in turns.reversed()) {
            if (!wasIdle(turn, snapshot)) break
            idle++
        }

        val first = StoryClock.read(turns.first().storyTime)
        val last = StoryClock.read(snapshot.world.storyTime)
        val passed = if (first != null && last != null && last.day == first.day) {
            last.minutes - first.minutes
        } else {
            0
        }
        return Reading(idle, snapshot.currentLocation?.name.orEmpty(), passed)
    }

    /**
     * A turn counts as idle when the player passed the time, nobody new was there, and the
     * world wrote nothing down about it.
     */
    private fun wasIdle(turn: TurnEntity, snapshot: WorldSnapshot): Boolean {
        if (!isIdleInput(turn.playerInput)) return false
        val recorded = snapshot.memories.count { it.turnIndex == turn.index }
        return recorded == 0
    }

    /** What to tell the narrator when the scene has stopped moving. */
    fun render(snapshot: WorldSnapshot): String {
        val reading = read(snapshot)
        if (!reading.stalled) return ""
        val style = PlayStyle.from(snapshot.world.playStyle)
        return buildString {
            appendLine("## THIS SCENE HAS STOPPED MOVING")
            appendLine(
                "The last ${reading.idleTurns} turns have been the player passing time in " +
                    "${reading.place.ifBlank { "the same place" }} while nothing happened. " +
                    "Another turn of the same is a turn the player spent for nothing."
            )
            appendLine(
                "Do not write more of it. Do one of these instead, whichever the situation " +
                    "actually offers:"
            )
            appendLine(
                "- Let time move. If the player is waiting for something - a person, an hour, a " +
                    "shift - go there: cover the wait in a line or two and open the turn at the " +
                    "moment that matters. Hours may pass in a sentence."
            )
            appendLine(
                "- Let the place act. Somewhere real always has something going on: somebody " +
                    "recognises the player, the shop runs out of something, a neighbour needs a " +
                    "hand, a stranger asks the time, the weather turns, a phone goes off at the " +
                    "next table. Ordinary and specific, with somebody in it."
            )
            appendLine(
                "- Let the player's own life intrude. A message, a shift, a bill, a person they " +
                    "owe an answer to. Whatever the state file already has in motion."
            )
            if (style.quietWorld) {
                appendLine(
                    "This is a ${style.label} world, so none of that means drama. It means the " +
                        "next ordinary thing, arriving on its own, with somebody in it worth " +
                        "talking to. Quiet is not the same as empty."
                )
            }
            appendLine(
                "Whatever you choose, this turn ends somewhere different from where it began - a " +
                    "new person, a new place, a new piece of information, or a later hour."
            )
        }
    }

    /**
     * An option that lets the player out of a stalled scene, in case the narrator offers none.
     *
     * Without it the only way out of nine turns of waiting is for the player to think of one,
     * which is the app asking them to do its job.
     */
    fun skipSuggestion(snapshot: WorldSnapshot): Choice? {
        if (!read(snapshot).stalled) return null
        val waitingFor = snapshot.threads
            .filter { it.status == "ACTIVE" }
            .maxByOrNull { it.urgency }
            ?.title
            ?.takeIf { it.isNotBlank() }
        val label = when {
            waitingFor != null -> "Let the time pass until something happens with $waitingFor"
            else -> "Let the time pass until something actually happens"
        }
        return Choice(id = "skip", label = label.take(120), detail = "move the story on", kind = "ACTION")
    }
}
