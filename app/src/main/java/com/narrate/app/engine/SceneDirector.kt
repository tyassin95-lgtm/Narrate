package com.narrate.app.engine

import com.narrate.app.data.repo.WorldSnapshot

/**
 * How much of a scene one turn is.
 *
 * This is the change everything else in this release hangs off. The app used to treat a turn
 * as one exchange: the player says a thing, somebody answers, the world stops and asks what
 * they want to do next. A real playthrough spent thirteen turns and twenty-three story-minutes
 * on one pavement getting through a conversation, and another nine turns on a doorstep saying
 * goodnight. Every individual turn was well written. The experience was interminable, because
 * the player was being asked to hand-operate a conversation one sentence at a time.
 *
 * A turn is a beat of the story now: the player's move, played out to wherever it naturally
 * gets to. "Walk her home" is a walk home - the conversation on the way, the arrival, the
 * pause at the door - and it ends when something is genuinely being decided, not when
 * somebody has finished a sentence.
 *
 * The player can still take the wheel at any moment by typing. What they no longer have to do
 * is steer every step of an ordinary evening.
 */
object SceneDirector {

    /** What the narrator says it did with the beat. */
    data class Scene(
        val status: String = CONTINUING,
        val minutes: Int = 0,
        val endedBecause: String = ""
    ) {
        val resolved: Boolean get() = status == RESOLVED
    }

    const val CONTINUING = "CONTINUING"
    const val RESOLVED = "RESOLVED"

    /**
     * How long a beat is allowed to be worth, when the narrator does not say.
     *
     * A conversation beat is minutes; a walk across a district is longer. The floor matters
     * more than the ceiling: a turn that advanced the clock by one minute, forty times over,
     * is the shape of the problem.
     */
    const val MIN_BEAT_MINUTES = 3
    const val MAX_BEAT_MINUTES = 4 * 60

    fun readScene(delta: StateDelta): Scene {
        val raw = delta.scene ?: return Scene(
            minutes = WorldClock.minutesIn(delta.timePassed) ?: 0
        )
        val minutes: Int = raw.minutes.takeIf { it > 0 }
            ?: WorldClock.minutesIn(delta.timePassed)
            ?: 0
        return Scene(
            status = raw.status.trim().uppercase().ifBlank { CONTINUING },
            minutes = minutes,
            endedBecause = raw.endedBecause.trim()
        )
    }

    /**
     * How far the clock actually moves for this beat.
     *
     * The narrator's number is trusted inside sane bounds and floored otherwise, because the
     * failure this exists to stop is the clock standing still while a whole evening happens.
     */
    fun minutesFor(scene: Scene, kind: String): Int {
        if (WorldActions.isWorldAction(kind)) return scene.minutes
        val floor = if (kind == "OPENING") 0 else MIN_BEAT_MINUTES
        return scene.minutes.coerceIn(floor, MAX_BEAT_MINUTES)
    }

    /** The instruction that turns a turn into a scene. */
    fun render(snapshot: WorldSnapshot): String {
        val style = PlayStyle.from(snapshot.world.playStyle)
        val company = snapshot.presentNpcs().joinToString(", ") { it.name }
        return buildString {
            appendLine("## HOW MUCH OF THE STORY THIS TURN COVERS")
            appendLine(
                "A turn is a beat of the story, not a line of dialogue. Take what the player " +
                    "did and play it all the way to wherever it naturally arrives. If that is " +
                    "four exchanges of conversation, write four. If it is a walk across two " +
                    "streets with talk on the way and an arrival at the end, write the walk, " +
                    "the talk and the arrival."
            )
            appendLine(
                "Do NOT stop and hand the turn back merely because somebody finished speaking. " +
                    "Thirteen turns to get through one conversation on one pavement is the " +
                    "failure this rule exists to prevent."
            )
            appendLine()
            appendLine("Stop the beat at the first of these, and not before:")
            appendLine("- something is genuinely being decided, and it is the player's to decide")
            appendLine("- somebody asks them something only they can answer - an offer, an invitation, a question about what they want")
            appendLine("- an opportunity opens that they could take or let go")
            appendLine("- something interrupts: an arrival, a message, a change in the place")
            appendLine("- they find something out that changes what they might do next")
            appendLine("- the scene is over: they have arrived, said goodnight, finished the shift, gone to bed")
            appendLine()
            appendLine(
                "Everything in between belongs to you. Ordinary movement, small talk, ordering, " +
                    "sitting down, finishing a drink, crossing a road, the pause before somebody " +
                    "answers: write them as part of the beat. Never hand one back as a choice."
            )
            if (company.isNotBlank()) {
                appendLine(
                    "$company can speak more than once this turn, change the subject, start " +
                        "something, disagree, get up, or leave. They are not waiting for a cue."
                )
            }
            appendLine()
            appendLine(
                "Record what you did in \"scene\": \"status\" is RESOLVED when the scene is " +
                    "finished and CONTINUING when the player is still in it, \"minutes\" is how " +
                    "long the beat took on the clock, and \"ended_because\" is the one-line " +
                    "reason you stopped where you did."
            )
            appendLine(
                "\"minutes\" is the world's clock moving. A conversation beat is ten or twenty " +
                    "minutes. A walk is as long as the walk. An evening is hours. One minute a " +
                    "turn is the mistake this replaces."
            )
            if (style.quietWorld) {
                appendLine(
                    "This is a ${style.label} world, so a beat ends on something ordinary and " +
                        "real - an offer, a question, a decision about the evening - not on a " +
                        "crisis. Covering more ground is not the same as raising the stakes."
                )
            }
        }
    }
}
