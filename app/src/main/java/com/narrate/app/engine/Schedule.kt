package com.narrate.app.engine

import com.narrate.app.core.newId
import com.narrate.app.core.truncate
import com.narrate.app.data.entity.EventEntity
import com.narrate.app.data.repo.WorldSnapshot
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The world's calendar, and the reason time is worth skipping.
 *
 * "Let time pass" could only ever mean "advance the clock a bit and hope", because the world
 * had no idea what it was passing time towards. Adrian agreed to see Liv on Friday and the
 * arrangement lived in a sentence inside a thread description; the app could not have told you
 * when Friday was, whether he had a shift first, or what he would be doing in the meantime.
 *
 * Once plans, shifts, classes and deadlines are records on the one clock, all of that is a
 * lookup: what is next, when, where, and with whom. That is what the time controls are built
 * out of, and it is what makes an evening skippable without the world losing track of itself.
 */
object Schedule {

    const val SHIFT = "SHIFT"
    const val CLASS = "CLASS"
    const val MEETING = "MEETING"
    const val APPOINTMENT = "APPOINTMENT"
    const val DEADLINE = "DEADLINE"
    const val PLAN = "PLAN"

    /** Every occurrence of every event between now and [horizonMinutes] from now, in order. */
    fun upcoming(
        snapshot: WorldSnapshot,
        horizonMinutes: Long = 14L * WorldClock.DAY,
        playerOnly: Boolean = true
    ): List<Occurrence> {
        val now = snapshot.world.clockMinute
        return snapshot.events
            .filter { it.status !in setOf("CANCELLED", "DONE", "MISSED") }
            .filter { !playerOnly || it.forPlayer }
            .filter { !playerOnly || it.knownToPlayer }
            .flatMap { event -> occurrencesOf(event, now, now + horizonMinutes) }
            .sortedBy { it.startMinute }
    }

    data class Occurrence(val event: EventEntity, val startMinute: Long) {
        val minutesAway: (Long) -> Long get() = { now -> startMinute - now }
    }

    /**
     * When this event actually happens between two moments.
     *
     * A weekly class is one record and forty occurrences; asking for them by window rather
     * than storing them is what keeps a term's worth of Tuesdays out of the database.
     */
    fun occurrencesOf(event: EventEntity, from: Long, until: Long): List<Occurrence> {
        if (event.recurrence.isBlank()) {
            return if (event.startMinute in from..until) listOf(Occurrence(event, event.startMinute)) else emptyList()
        }
        val rule = event.recurrence.uppercase()
        val minuteOfDay = Math.floorMod(event.startMinute, WorldClock.DAY.toLong()).toInt()
        val days = when {
            rule.startsWith("DAILY") -> DayOfWeek.entries.toList()
            rule.startsWith("WEEKLY") -> rule.substringAfter(":", "").split(",")
                .mapNotNull { token -> weekdayOf(token.trim()) }
            else -> emptyList()
        }
        if (days.isEmpty()) return emptyList()

        val result = mutableListOf<Occurrence>()
        var day = Math.floorDiv(from, WorldClock.DAY.toLong())
        val lastDay = Math.floorDiv(until, WorldClock.DAY.toLong())
        // The epoch is the same for every event in a world, so a day index maps to a weekday
        // by arithmetic rather than by asking the world for its calendar every step.
        while (day <= lastDay && result.size < 40) {
            val start = day * WorldClock.DAY + minuteOfDay
            if (start in from..until && weekdayOfDayIndex(day, event) in days) {
                result += Occurrence(event, start)
            }
            day++
        }
        return result
    }

    /**
     * Which weekday a day index falls on.
     *
     * The event carries its own anchor: whatever weekday its [EventEntity.startMinute] fell on
     * is day zero of its rhythm, so a Monday class stays on Mondays without the calendar epoch
     * having to be threaded through every call.
     */
    private fun weekdayOfDayIndex(day: Long, event: EventEntity): DayOfWeek {
        val anchorDay = Math.floorDiv(event.startMinute, WorldClock.DAY.toLong())
        val anchor = anchorWeekday(event)
        val delta = Math.floorMod(day - anchorDay, 7L).toInt()
        return DayOfWeek.of(((anchor.value - 1 + delta) % 7) + 1)
    }

    /** The weekday named in the rule, when it names one; otherwise the anchor's own. */
    private fun anchorWeekday(event: EventEntity): DayOfWeek {
        val named = event.recurrence.uppercase().substringAfter(":", "").split(",")
            .firstNotNullOfOrNull { weekdayOf(it.trim()) }
        return named ?: DayOfWeek.MONDAY
    }

    fun weekdayOf(token: String): DayOfWeek? {
        val value = token.trim().uppercase()
        if (value.isBlank()) return null
        return DayOfWeek.entries.firstOrNull {
            val full = it.name
            full.startsWith(value) || value.startsWith(full.take(3))
        }
    }

    /** The next thing that matters, if anything does inside the horizon. */
    fun nextMeaningful(snapshot: WorldSnapshot): Occurrence? = upcoming(snapshot).firstOrNull()

    /** Whatever the player is in the middle of right now. */
    fun current(snapshot: WorldSnapshot): Occurrence? {
        val now = snapshot.world.clockMinute
        return upcoming(snapshot, horizonMinutes = 0)
            .plus(
                snapshot.events.filter { it.forPlayer }.flatMap {
                    occurrencesOf(it, now - 2L * WorldClock.DAY, now)
                }
            )
            .filter { now in it.startMinute until (it.startMinute + it.event.durationMinutes) }
            .maxByOrNull { it.startMinute }
    }

    /**
     * Reads a plan out of the words people make plans in.
     *
     * "Friday" is not a date, and a world that stores it as one is a world that cannot tell
     * you when it is. This turns what the narrator wrote into a moment on the clock, relative
     * to now, so an arrangement becomes something the calendar and the time controls can both
     * point at.
     */
    fun resolveWhen(text: String, nowMinute: Long, epochStamp: WorldClock.Stamp): Long? {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return null
        val minuteOfDay = clockIn(lower) ?: defaultTimeFor(lower)

        WorldClock::class // keep the dependency explicit for readers of this file
        val stamp = epochStamp.copy(minute = nowMinute)
        weekdayOf(lower.split(Regex("[^a-z]")).firstOrNull { weekdayOf(it) != null } ?: "")?.let {
            return stamp.nextWeekday(it, minuteOfDay)
        }
        return when {
            lower.contains("tomorrow") -> stamp.atTimeOnDay(1, minuteOfDay)
            lower.contains("tonight") || lower.contains("this evening") -> stamp.atTimeOnDay(0, minuteOfDay)
            lower.contains("day after") -> stamp.atTimeOnDay(2, minuteOfDay)
            lower.contains("next week") -> stamp.atTimeOnDay(7, minuteOfDay)
            lower.contains("in an hour") -> nowMinute + 60
            clockIn(lower) != null -> stamp.next(minuteOfDay)
            else -> null
        }
    }

    private val clockPattern = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)

    private fun clockIn(text: String): Int? {
        val match = clockPattern.find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].lowercase()
        if (hour > 23 || minute > 59) return null
        when (meridiem) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
            // "meet at 8" in the evening means eight in the evening.
            else -> if (hour in 1..7) hour += 12
        }
        return hour * 60 + minute
    }

    private fun defaultTimeFor(text: String): Int = when {
        text.contains("morning") -> 9 * 60
        text.contains("lunch") || text.contains("midday") || text.contains("noon") -> 12 * 60
        text.contains("afternoon") -> 15 * 60
        text.contains("night") -> 21 * 60
        else -> 19 * 60
    }

    /** What the narrator is told about what is coming. */
    fun render(snapshot: WorldSnapshot): String {
        val now = WorldClock.of(snapshot.world)
        val soon = upcoming(snapshot, horizonMinutes = 10L * WorldClock.DAY).take(8)
        val running = current(snapshot)
        if (soon.isEmpty() && running == null) return ""
        return buildString {
            appendLine("## THE CALENDAR (dates and times are the world's, not yours to invent)")
            running?.let {
                appendLine(
                    "- HAPPENING NOW: ${it.event.title} until " +
                        WorldClock.stamp(it.startMinute + it.event.durationMinutes, snapshot.world).clock + "."
                )
            }
            soon.forEach { occurrence ->
                val at = WorldClock.stamp(occurrence.startMinute, snapshot.world)
                val away = WorldClock.describeGap(occurrence.startMinute - now.minute)
                appendLine(
                    "- ${at.full}: ${occurrence.event.title.truncate(70)}" +
                        occurrence.event.locationName.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty() +
                        occurrence.event.withNames.takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty() +
                        " (in $away)"
                )
            }
            appendLine(
                "When somebody agrees to something - a time, a day, a shift, a deadline - record " +
                    "it in \"events\" with a day and a time, not a word like \"Friday\". An " +
                    "arrangement with no date on it is one the world cannot keep."
            )
        }
    }

    /** Builds an event row from what the narrator wrote. */
    fun fromDelta(
        worldId: String,
        title: String,
        description: String,
        kind: String,
        whenText: String,
        durationMinutes: Int,
        locationName: String,
        withNames: String,
        recurrence: String,
        forPlayer: Boolean,
        turnIndex: Int,
        nowMinute: Long,
        stamp: WorldClock.Stamp
    ): EventEntity? {
        val start = resolveWhen(whenText, nowMinute, stamp) ?: return null
        return EventEntity(
            id = newId(),
            worldId = worldId,
            title = title.trim().truncate(90),
            description = description.truncate(300),
            kind = kind.trim().uppercase().ifBlank { PLAN },
            startMinute = start,
            durationMinutes = durationMinutes.coerceIn(5, 16 * 60),
            recurrence = recurrence.trim().uppercase(),
            locationName = locationName.trim(),
            withNames = withNames.trim(),
            forPlayer = forPlayer,
            createdTurn = turnIndex
        )
    }

    fun label(event: EventEntity, stamp: WorldClock.Stamp): String =
        "${stamp.weekday.getDisplayName(TextStyle.SHORT, Locale.UK)} ${stamp.clock} - ${event.title}"
}
