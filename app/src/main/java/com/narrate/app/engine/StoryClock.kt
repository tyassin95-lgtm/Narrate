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

    /** Minutes from [previous] to [next], when both can be read. Negative if it went backwards. */
    fun elapsed(previous: String, next: String): Int? {
        val before = read(previous) ?: return null
        val after = read(next) ?: return null
        return (after.day - before.day) * DAY_MINUTES + (after.minutes - before.minutes)
    }

    private const val DAY_MINUTES = 24 * 60

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty-five" to 45, "fortyfive" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90
    )

    private val elapsedPhrase = Regex(
        "\\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|" +
            "twenty|thirty|forty-five|fortyfive|forty|fifty|sixty|ninety|half an|\\d{1,3})\\s+" +
            "(minute|minutes|hour|hours)\\s+(?:later|after|pass|passed|passes|go by|went by|gone by)",
        RegexOption.IGNORE_CASE
    )

    /**
     * How long the prose says has gone by, when it says so in plain words.
     *
     * A playthrough had the narrator write "twenty minutes later" three turns running while the
     * clock moved four minutes each time, and then tell the player that forty minutes remained
     * before an appointment that was an hour and a half away. Prose that states an interval is
     * making a claim about the clock, and the clock is the thing that has to agree with it.
     */
    fun statedElapsed(narration: String): Int? {
        val match = elapsedPhrase.find(narration) ?: return null
        val word = match.groupValues[1].lowercase()
        val count = when {
            word == "half an" -> return if (match.groupValues[2].startsWith("hour", true)) 30 else null
            word.toIntOrNull() != null -> word.toInt()
            else -> numberWords[word] ?: return null
        }
        return if (match.groupValues[2].startsWith("hour", true)) count * 60 else count
    }

    /**
     * The same clock, moved forward, written the way the world already writes it.
     *
     * Used when the player presses a time control and the narrator moves the clock by four
     * minutes anyway. The button is a promise that time passes; the app keeps it rather than
     * asking again.
     */
    fun advance(storyTime: String, minutes: Int): String {
        val reading = read(storyTime) ?: return storyTime
        val total = reading.minutes + minutes
        val day = reading.day + total / DAY_MINUTES
        val within = ((total % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        val twentyFour = storyTime.contains(Regex("\\d{1,2}[:.]\\d{2}\\s*(?!am|pm)", RegexOption.IGNORE_CASE)) &&
            !storyTime.contains(Regex("(am|pm)", RegexOption.IGNORE_CASE))
        val hour = within / 60
        val minute = within % 60
        val clock = if (twentyFour) {
            String.format("%02d:%02d", hour, minute)
        } else {
            val suffix = if (hour < 12) "AM" else "PM"
            val display = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            String.format("%d:%02d %s", display, minute, suffix)
        }
        return "Day $day, $clock"
    }

    /** Hours when it is dark wherever you stand, and hours when it plainly is not. */
    fun definitelyDark(minutes: Int): Boolean = minutes in 0 until 4 * 60
    fun definitelyLight(minutes: Int): Boolean = minutes in 10 * 60 until 15 * 60

    private val darkCue = Regex(
        "\\b(sunlight|sunshine|midday sun|afternoon sun|broad daylight|noon light|" +
            "morning light|bright (?:sun|daylight))\\b",
        RegexOption.IGNORE_CASE
    )
    private val lightCue = Regex(
        "\\b(pitch dark|darkness outside|middle of the night|moonlight|starlight|" +
            "dead of night|night sky)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * A phrase in the prose that the clock says cannot be true.
     *
     * Only the two ends of the day are checked - four in the morning and the middle of the
     * afternoon - because everything between them depends on the season, the latitude and
     * whatever sun the world has, and none of those are the app's business.
     */
    fun lightContradiction(storyTime: String, narration: String): String? {
        val reading = read(storyTime) ?: return null
        if (definitelyDark(reading.minutes)) return darkCue.find(narration)?.value
        if (definitelyLight(reading.minutes)) return lightCue.find(narration)?.value
        return null
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
