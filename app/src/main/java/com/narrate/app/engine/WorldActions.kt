package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.repo.WorldSnapshot
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Moving time on purpose.
 *
 * "Let time pass" was a button with nothing behind it. The world had no idea what it was
 * passing time towards, so the narrator did what it could - wrote another minute of the same
 * evening - and the player pressed it again. In one playthrough it advanced the clock by one
 * minute and produced a turn about sitting on a step.
 *
 * Skipping time is only meaningful if the world knows what is next, which is what the calendar
 * is for. These controls are generated from it: the shift that ends at six, Friday at eight
 * with Liv, tomorrow morning. Where there is nothing scheduled there are still honest answers
 * - an hour, an evening, tomorrow - but they are offered as what they are rather than dressed
 * up as a story beat.
 */
object WorldActions {

    const val SKIP = "SKIP_TIME"
    const val HOME = "GO_HOME"
    const val SLEEP = "SLEEP"

    val kinds = setOf(SKIP, HOME, SLEEP)

    fun isWorldAction(kind: String): Boolean = kind in kinds

    /**
     * One thing the player can do with time, ready to put on a button.
     *
     * [targetMinute] is where the clock lands. Everything about the turn - how long it takes,
     * what happens in between, what the narrator is told to write - comes from that, so a
     * control can never quietly do nothing.
     */
    data class TimeOption(
        val id: String,
        val label: String,
        val detail: String,
        val targetMinute: Long,
        val kind: String = SKIP
    )

    /**
     * What is worth skipping to from here.
     *
     * Ordered by how soon it is, with the next real commitment first, because that is almost
     * always the thing the player pressed the button to reach.
     */
    fun options(snapshot: WorldSnapshot): List<TimeOption> {
        val world = snapshot.world
        val now = WorldClock.of(world)
        val options = mutableListOf<TimeOption>()

        // Whatever the player is in the middle of has an end, and that end is a real moment.
        Schedule.current(snapshot)?.let { running ->
            val ends = running.startMinute + running.event.durationMinutes
            if (ends > world.clockMinute) {
                options += TimeOption(
                    id = "until-end",
                    label = "Until ${verbFor(running.event.kind)} ends",
                    detail = WorldClock.stamp(ends, world).clock,
                    targetMinute = ends
                )
            }
        }

        // And everything the calendar says is coming.
        Schedule.upcoming(snapshot).take(3).forEach { occurrence ->
            val at = WorldClock.stamp(occurrence.startMinute, world)
            val sameDay = at.dayNumber == now.dayNumber
            val whenWord = when {
                sameDay -> at.clock
                at.dayNumber == now.dayNumber + 1 -> "tomorrow ${at.clock}"
                else -> "${at.weekday.getDisplayName(TextStyle.FULL, Locale.UK)} ${at.clock}"
            }
            options += TimeOption(
                id = "event-${occurrence.event.id}-${occurrence.startMinute}",
                label = "To $whenWord",
                detail = occurrence.event.title.truncate(48),
                // Arrive a little early rather than exactly on the hour.
                targetMinute = (occurrence.startMinute - 10).coerceAtLeast(world.clockMinute + 5)
            )
        }

        // Then the ordinary answers, which are still honest ones.
        val minuteOfDay = now.minuteOfDay
        if (minuteOfDay < 7 * 60 || minuteOfDay >= 22 * 60) {
            options += TimeOption("morning", "Until morning", "7:00 AM", now.next(7 * 60))
        } else {
            if (minuteOfDay < 12 * 60) options += TimeOption("midday", "Until midday", "12:00 PM", now.next(12 * 60))
            if (minuteOfDay < 18 * 60) options += TimeOption("evening", "Until the evening", "6:00 PM", now.next(18 * 60))
            options += TimeOption("tomorrow", "Until tomorrow morning", "7:00 AM", now.next(7 * 60))
        }
        options += TimeOption("hour", "An hour", WorldClock.stamp(world.clockMinute + 60, world).clock, world.clockMinute + 60)
        options += TimeOption(
            "three-hours", "Three hours",
            WorldClock.stamp(world.clockMinute + 180, world).clock, world.clockMinute + 180
        )

        return options
            .filter { it.targetMinute > world.clockMinute }
            .distinctBy { it.targetMinute / 15 }
            .sortedBy { it.targetMinute }
            .take(5)
    }

    private fun verbFor(kind: String): String = when (kind.uppercase()) {
        Schedule.SHIFT -> "the shift"
        Schedule.CLASS -> "the class"
        Schedule.MEETING -> "the meeting"
        Schedule.APPOINTMENT -> "the appointment"
        else -> "this"
    }

    /** What the feed shows as the player's move, so the log reads like the story. */
    fun playerInput(kind: String, snapshot: WorldSnapshot, option: TimeOption? = null): String = when (kind) {
        SKIP -> option?.let { "Let the time pass ${it.label.lowercase()}." } ?: "Let the time pass."
        HOME -> "Head home."
        SLEEP -> "Go to sleep."
        else -> ""
    }

    /** Where the clock lands. Never a guess, and never zero. */
    fun targetMinute(kind: String, snapshot: WorldSnapshot, option: TimeOption?): Long {
        val world = snapshot.world
        val now = WorldClock.of(world)
        return when (kind) {
            SKIP -> option?.targetMinute ?: (world.clockMinute + 60)
            HOME -> {
                val home = snapshot.locationById(snapshot.player?.homeLocationId)
                world.clockMinute + Geography.travelMinutes(snapshot.currentLocation, home).coerceAtLeast(5)
            }
            SLEEP -> {
                // A sensible night: to seven in the morning, or a proper stretch if they are
                // going to bed at four, which happens.
                val wake = now.next(7 * 60)
                val length = wake - world.clockMinute
                if (length < 4 * 60) world.clockMinute + 6 * 60 else wake
            }
            else -> world.clockMinute
        }
    }

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
     * The shape matters: not "narrate an hour of waiting" but "put the hour behind us and open
     * at the moment that matters". A skip that produces five paragraphs about a quiet room has
     * done the opposite of what the player pressed it for.
     */
    fun instruction(kind: String, snapshot: WorldSnapshot, option: TimeOption?): String {
        val world = snapshot.world
        val target = targetMinute(kind, snapshot, option)
        val minutes = target - world.clockMinute
        val landing = WorldClock.stamp(target, world)
        val player = snapshot.player?.name ?: "The player"
        val homeName = snapshot.locationName(snapshot.player?.homeLocationId)
        val next = Schedule.upcoming(snapshot).firstOrNull { it.startMinute <= target + 60 }

        return buildString {
            appendLine("## A TIME CONTROL - THIS TURN COVERS ${WorldClock.describeGap(minutes).uppercase()}")
            appendLine("It is ${WorldClock.of(world).full}. This turn ends at ${landing.full}.")
            appendLine()
            when (kind) {
                SKIP -> {
                    appendLine(
                        "$player is letting the time go by" +
                            (option?.detail?.takeIf { it.isNotBlank() }?.let { " to reach: $it" } ?: "") + "."
                    )
                    appendLine(
                        "Cover the whole interval in a short paragraph - what they did with it, " +
                            "in summary, and anything they would have noticed - and then open the " +
                            "scene at ${landing.clock}, where something is actually happening."
                    )
                }
                HOME -> {
                    appendLine("$player is going home${if (homeName != "unknown") " to $homeName" else ""}.")
                    appendLine(
                        "The journey takes ${WorldClock.describeGap(minutes)}. Give it a line or " +
                            "two unless something genuinely happens on the way, move them there " +
                            "in the state block, and write arriving: what the place is like at " +
                            "this hour, what is waiting, what is different."
                    )
                }
                SLEEP -> {
                    appendLine("$player is sleeping, and wakes at ${landing.full}.")
                    appendLine(
                        "Pass the night in a few sentences - how they slept, anything that woke " +
                            "them - and open on waking. They are not in yesterday's clothes: " +
                            "record what they change into in \"outfits\"."
                    )
                }
            }
            appendLine()
            appendLine(
                "Set \"scene\": {\"minutes\": $minutes}. That is the whole interval, and the app " +
                    "will land the clock on ${landing.clock} whatever you write."
            )
            appendLine(
                "The world ran while it passed. Everybody followed their routine, threads moved, " +
                    "things happened without the player. Move whoever would have moved, record " +
                    "what changed, and let the player find out through evidence rather than " +
                    "through being told."
            )
            next?.let {
                val at = WorldClock.stamp(it.startMinute, world)
                appendLine(
                    "Due around now: ${it.event.title} at ${at.clock}" +
                        it.event.locationName.takeIf { name -> name.isNotBlank() }?.let { name -> " ($name)" }.orEmpty() + "."
                )
            }
            appendLine("End somewhere with something in it. A skip that lands in an empty room has wasted the press.")
        }
    }

    /** Short label for a weekday, used when a control points at another day. */
    fun weekdayLabel(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.UK)
}
