package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.repo.WorldSnapshot

/**
 * The things a player does constantly and should never have to spend a suggestion on.
 *
 * Waiting, going home and sleeping are not decisions. They are the punctuation between
 * decisions, and for most of this app's life they were the only way to get from one scene to
 * the next: the narrator offered "wait a little longer" and the player pressed it, and pressed
 * it again, because otherwise nothing moved. That is the interaction model that made the game
 * tedious, and no amount of better prose fixes it.
 *
 * So they are buttons. They take a turn, they advance the clock by a real amount, they let
 * every routine in the world run while they do it, and they open on the next thing worth
 * reading. The suggested actions are then free to be about things worth choosing.
 */
object WorldActions {

    const val SKIP = "SKIP_TIME"
    const val HOME = "GO_HOME"
    const val SLEEP = "SLEEP"

    val kinds = setOf(SKIP, HOME, SLEEP)

    fun isWorldAction(kind: String): Boolean = kind in kinds

    /** What the feed shows as the player's move, so the log reads like the story. */
    fun playerInput(kind: String, snapshot: WorldSnapshot): String = when (kind) {
        SKIP -> "Let time pass."
        HOME -> "Head home."
        SLEEP -> if (nightAlready(snapshot)) "Go to sleep." else "Rest until the next part of the day."
        else -> ""
    }

    /**
     * How long the world is allowed to move. The narrator may take more, never less: a skip
     * that advances the clock four minutes is the thing being replaced.
     */
    fun minimumMinutes(kind: String, snapshot: WorldSnapshot): Int = when (kind) {
        SKIP -> 60
        HOME -> 20
        SLEEP -> minutesUntilMorning(snapshot)
        else -> 0
    }

    private fun nightAlready(snapshot: WorldSnapshot): Boolean {
        val reading = StoryClock.read(snapshot.world.storyTime) ?: return true
        return reading.minutes >= 21 * 60 || reading.minutes < 5 * 60
    }

    private fun minutesUntilMorning(snapshot: WorldSnapshot): Int {
        val reading = StoryClock.read(snapshot.world.storyTime) ?: return 8 * 60
        val morning = 7 * 60
        return if (reading.minutes < morning) morning - reading.minutes
        else (24 * 60 - reading.minutes) + morning
    }

    /** Whether the button is worth offering at all, given where the player is standing. */
    fun available(kind: String, snapshot: WorldSnapshot): Boolean = when (kind) {
        HOME -> {
            val home = snapshot.player?.homeLocationId
            home != null && home != snapshot.world.currentLocationId
        }
        else -> true
    }

    /**
     * The instruction for one of these turns.
     *
     * The shape matters: it is not "narrate an hour of waiting", it is "put the hour behind us
     * in a line and open on what is different". A skip that produces five paragraphs about a
     * quiet room has done the opposite of what the player pressed it for.
     */
    fun instruction(kind: String, snapshot: WorldSnapshot): String {
        val minutes = minimumMinutes(kind, snapshot)
        val player = snapshot.player?.name ?: "The player"
        val homeName = snapshot.locationName(snapshot.player?.homeLocationId)
        return buildString {
            appendLine("## THE PLAYER USED A TIME CONTROL - THIS IS NOT AN ORDINARY TURN")
            when (kind) {
                SKIP -> {
                    appendLine(
                        "$player is letting time pass. They are not interested in the next five " +
                            "minutes; they want the next thing that is actually worth their attention."
                    )
                    appendLine(
                        "Move story_time forward by at least ${describe(minutes)}. Cover the gap " +
                            "in one or two sentences at most - what they did with it, in summary - " +
                            "and then open the scene at the moment something is different."
                    )
                }
                HOME -> {
                    appendLine("$player is going home${if (homeName != "unknown") " to $homeName" else ""}.")
                    appendLine(
                        "Cover the journey in a line or two unless something genuinely happens on " +
                            "the way, move them there in the state block, and advance story_time by " +
                            "however long the trip actually takes (at least ${describe(minutes)})."
                    )
                    appendLine(
                        "Then write arriving: what the place is like at this hour, what is waiting, " +
                            "what they notice that was not there this morning."
                    )
                }
                SLEEP -> {
                    appendLine("$player is sleeping.")
                    appendLine(
                        "Pass the night in a few sentences - how they slept, anything that woke " +
                            "them, a dream only if it earns its place - and advance story_time to " +
                            "the morning (at least ${describe(minutes)} on, with the day number " +
                            "incremented if it crosses midnight)."
                    )
                    appendLine(
                        "Open the turn on waking: the new day, what is already different, what is " +
                            "on today. Everybody else has had the night too."
                    )
                }
            }
            appendLine()
            appendLine(
                "While that time passed, the world ran. Every NPC followed their routine, threads " +
                    "moved, and things happened without the player. Apply the ones that make sense " +
                    "from the offscreen list, move the people who would have moved, and record it " +
                    "all in the state block - people who are no longer where they were, anything " +
                    "that changed, anything waiting."
            )
            appendLine(
                "The player learns about it only through evidence: an empty chair, a message, a " +
                    "shop shut, somebody who was not there before."
            )
            appendLine(
                "End this turn somewhere with something in it. A time control that lands the " +
                    "player in another empty room has wasted the press."
            )
            snapshot.threads.filter { it.status == "ACTIVE" }.maxByOrNull { it.urgency }?.let { thread ->
                appendLine(
                    "If anything was due to happen, it was this: ${thread.title.truncate(80)}."
                )
            }
        }
    }

    private fun describe(minutes: Int): String = when {
        minutes >= 120 -> "${minutes / 60} hours"
        minutes >= 60 -> "an hour"
        else -> "$minutes minutes"
    }
}
