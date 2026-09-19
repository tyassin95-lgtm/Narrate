package com.narrate.app.engine

import com.narrate.app.data.entity.WorldEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The world's clock. There is exactly one, and nothing else is allowed to keep time.
 *
 * Before this there were three: a `storyTime` string the narrator wrote in whatever shape it
 * felt like, a `dayNumber` parsed back out of that string, and a `timeOfDay` word parsed out
 * of it separately. They drifted, as three copies of one fact always do. A real playthrough
 * slept through a night and woke on "Day 1, 7:00 AM"; carried on through "Day 1, 3:15 PM" and
 * "Day 1, 5:38 PM"; and then jumped to Day 5 while the narration talked about Friday, which
 * was not a day the world had any way of locating.
 *
 * So time is one number - minutes since midnight on the world's first day - and everything
 * else is derived from it: the date, the weekday, the hour, how long until a shift starts.
 * The narrator never sets it. The narrator reports how long a scene took, and the app moves
 * the clock, which is the only way the two can never disagree.
 */
object WorldClock {

    const val DAY = 24 * 60

    /** A world with no epoch recorded runs on this one, chosen so day one is a Friday. */
    private val DEFAULT_EPOCH: LocalDate = LocalDate.of(2025, 9, 5)

    data class Stamp(val minute: Long, val epoch: LocalDate) {
        val dayNumber: Int get() = (Math.floorDiv(minute, DAY.toLong()) + 1).toInt()
        val minuteOfDay: Int get() = Math.floorMod(minute, DAY.toLong()).toInt()
        val date: LocalDate get() = epoch.plusDays(Math.floorDiv(minute, DAY.toLong()))
        val dateTime: LocalDateTime get() = date.atStartOfDay().plusMinutes(minuteOfDay.toLong())
        val weekday: DayOfWeek get() = date.dayOfWeek
        val hour: Int get() = minuteOfDay / 60
        val weekdayName: String
            get() = weekday.getDisplayName(TextStyle.FULL, Locale.UK)

        /** "Friday 5 September, 11:46 PM" - what a person would actually say. */
        val full: String
            get() = "$weekdayName ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.UK)}, $clock"

        /** "Fri, 11:46 PM" - the strip above a turn. */
        val short: String
            get() = "${weekday.getDisplayName(TextStyle.SHORT, Locale.UK)} $clock"

        val clock: String
            get() {
                val h = hour
                val m = minuteOfDay % 60
                val suffix = if (h < 12) "AM" else "PM"
                val display = when {
                    h == 0 -> 12
                    h > 12 -> h - 12
                    else -> h
                }
                return String.format(Locale.UK, "%d:%02d %s", display, m, suffix)
            }

        /** The word the world uses for this part of the day. Derived, never stored separately. */
        val timeOfDay: String
            get() = when (minuteOfDay) {
                in 0 until 5 * 60 -> "night"
                in 5 * 60 until 7 * 60 -> "dawn"
                in 7 * 60 until 12 * 60 -> "morning"
                in 12 * 60 until 14 * 60 -> "midday"
                in 14 * 60 until 17 * 60 -> "afternoon"
                in 17 * 60 until 19 * 60 -> "dusk"
                in 19 * 60 until 22 * 60 -> "evening"
                else -> "night"
            }

        val dark: Boolean get() = minuteOfDay < 6 * 60 || minuteOfDay >= 20 * 60

        fun plus(minutes: Long): Stamp = copy(minute = (minute + minutes).coerceAtLeast(0))

        /** The clock at a given time on a given day offset, for scheduling. */
        fun atTimeOnDay(dayOffset: Long, minuteOfDay: Int): Long =
            (Math.floorDiv(minute, DAY.toLong()) + dayOffset) * DAY + minuteOfDay

        /** The next time it will be [minuteOfDay], today if it has not passed yet. */
        fun next(minuteOfDay: Int): Long =
            if (minuteOfDay > this.minuteOfDay) atTimeOnDay(0, minuteOfDay) else atTimeOnDay(1, minuteOfDay)

        /** The next [day] at [minuteOfDay], never today unless today is that day and it is early. */
        fun nextWeekday(day: DayOfWeek, minuteOfDay: Int): Long {
            var ahead = (day.value - weekday.value + 7) % 7
            if (ahead == 0 && minuteOfDay <= this.minuteOfDay) ahead = 7
            return atTimeOnDay(ahead.toLong(), minuteOfDay)
        }
    }

    fun epochOf(world: WorldEntity): LocalDate =
        runCatching { LocalDate.parse(world.calendarEpoch) }.getOrDefault(DEFAULT_EPOCH)

    fun of(world: WorldEntity): Stamp = Stamp(world.clockMinute, epochOf(world))

    fun stamp(minute: Long, world: WorldEntity): Stamp = Stamp(minute, epochOf(world))

    /** A world's fields, all rewritten from the one number. Never set any of them by hand. */
    fun applyTo(world: WorldEntity, minute: Long): WorldEntity {
        val stamp = Stamp(minute.coerceAtLeast(0), epochOf(world))
        return world.copy(
            clockMinute = stamp.minute,
            calendarEpoch = world.calendarEpoch.ifBlank { DEFAULT_EPOCH.toString() },
            storyTime = "Day ${stamp.dayNumber}, ${stamp.weekdayName} ${stamp.clock}",
            dayNumber = stamp.dayNumber,
            timeOfDay = stamp.timeOfDay
        )
    }

    /** A starting date that makes a world's opening day fall on a sensible weekday. */
    fun startingEpoch(openingText: String): LocalDate {
        val lower = openingText.lowercase()
        val named = DayOfWeek.entries.firstOrNull {
            lower.contains(it.getDisplayName(TextStyle.FULL, Locale.UK).lowercase())
        } ?: return DEFAULT_EPOCH
        var date = DEFAULT_EPOCH
        while (date.dayOfWeek != named) date = date.plusDays(1)
        return date
    }

    private val durationWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "fifteen" to 15, "twenty" to 20, "twenty-five" to 25, "thirty" to 30,
        "forty" to 40, "forty-five" to 45, "fifty" to 50, "ninety" to 90, "a couple of" to 2,
        "a few" to 3, "several" to 4, "half an" to 30
    )

    private val durationPattern = Regex(
        "(\\d{1,4}|a couple of|a few|several|half an|an|a|one|two|three|four|five|six|seven|" +
            "eight|nine|ten|eleven|twelve|fifteen|twenty-five|twenty|thirty|forty-five|forty|" +
            "fifty|ninety)\\s*(minute|min|hour|hr|day|night|week)",
        RegexOption.IGNORE_CASE
    )

    /**
     * How long a phrase says something took, in minutes.
     *
     * The narrator writes "about twenty minutes" or "the better part of an hour", and the app
     * turns that into the number the clock actually moves by. Reading the phrase is the app's
     * job; doing arithmetic on it was never the narrator's.
     */
    fun minutesIn(phrase: String?): Int? {
        val text = phrase?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        text.toIntOrNull()?.let { return it }
        val match = durationPattern.find(text) ?: return when {
            text.contains("instant") || text.contains("no time") -> 0
            text.contains("moment") -> 2
            text.contains("overnight") -> 8 * 60
            text.contains("all day") -> 10 * 60
            else -> null
        }
        val word = match.groupValues[1].lowercase()
        val count = word.toIntOrNull() ?: durationWords[word] ?: return null
        val unit = match.groupValues[2].lowercase()
        return when {
            word == "half an" && unit.startsWith("hour") -> 30
            unit.startsWith("hour") || unit.startsWith("hr") -> count * 60
            unit.startsWith("day") -> count * DAY
            unit.startsWith("night") -> count * 8 * 60
            unit.startsWith("week") -> count * 7 * DAY
            else -> count
        }
    }

    /** Somewhere between a blink and a fortnight. Anything outside that is a mistake. */
    fun sane(minutes: Int): Int = minutes.coerceIn(0, 14 * DAY)

    fun describeGap(minutes: Long): String = when {
        minutes <= 0 -> "no time at all"
        minutes < 60 -> "$minutes minutes"
        minutes < DAY -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "$hours hour${if (hours == 1L) "" else "s"}"
            else "$hours hour${if (hours == 1L) "" else "s"} and $rest minutes"
        }
        else -> {
            val days = minutes / DAY
            "$days day${if (days == 1L) "" else "s"}"
        }
    }
}
