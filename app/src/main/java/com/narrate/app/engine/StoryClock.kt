package com.narrate.app.engine

/**
 * The world's clock, read back from the words the narrator writes it in.
 *
 * Providers write the time differently - "Day 1, 2:16 AM" from one, "Day 1, 02:16" from
 * another - and the app has to hold the same invariant either way: within a day, time moves
 * forward. A world whose clock slips backwards cannot reason about a shift, a bus, an opening
 * hour or how long somebody has been asleep.
 */
object StoryClock {

    data class Reading(val day: Int, val minutes: Int)

    private val dayPattern = Regex("day\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val clockPattern = Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE)

    /** The day and the minute of that day, when the text says both. */
    fun read(storyTime: String): Reading? {
        if (storyTime.isBlank()) return null
        val day = dayPattern.find(storyTime)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val clock = clockPattern.find(storyTime) ?: return null
        var hour = clock.groupValues[1].toIntOrNull() ?: return null
        val minute = clock.groupValues[2].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        when (clock.groupValues[3].lowercase()) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
        }
        return Reading(day, hour * 60 + minute)
    }

    /**
     * True when the clock has plainly gone backwards.
     *
     * Only when both times can actually be read: a world that says "early evening" is not
     * wrong, it is merely vague, and vagueness is the narrator's business rather than a fault.
     */
    fun wentBackwards(previous: String, next: String): Boolean {
        val before = read(previous) ?: return false
        val after = read(next) ?: return false
        if (after.day < before.day) return true
        return after.day == before.day && after.minutes < before.minutes
    }
}
