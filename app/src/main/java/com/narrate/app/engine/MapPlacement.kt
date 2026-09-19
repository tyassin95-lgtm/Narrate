package com.narrate.app.engine

import com.narrate.app.data.entity.LocationEntity
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where a newly discovered place goes on the map.
 *
 * Until now a new location took the next free cell of a seven-column grid, so the café two
 * doors down from the flat could land in the opposite corner of the map from it, and the map -
 * which the player reads as a picture of the town - said nothing true about where anything was.
 * A place that belongs to something, or that connects to something, belongs next to it.
 *
 * This is deliberately crude: it is a sketch map of relationships, not a survey. All it
 * guarantees is that a new place appears near what it is attached to, and not on top of
 * anything already drawn.
 */
object MapPlacement {

    /** How far apart two pins have to be before the map is readable. */
    private const val MIN_GAP = 0.07f

    /** How far from its anchor a new place starts looking for room. */
    private const val FIRST_RING = 0.09f

    private const val GOLDEN_ANGLE = 2.39996

    private const val EDGE = 0.06f

    /**
     * What the story said about where this place is.
     *
     * The narration is the only survey this world will ever get: "two streets north of the
     * flat", "a forty minute bus ride out of town", "just across the road". Reading it turns a
     * pin dropped anywhere near the anchor into a pin dropped in roughly the right direction,
     * roughly the right distance away, which is the whole difference between a map and a
     * diagram.
     */
    data class Hint(val text: String = "", val travelTime: String = "") {
        val blank: Boolean get() = text.isBlank() && travelTime.isBlank()
    }

    private val bearings = listOf(
        // Checked longest first, so "north east" does not match as "north".
        listOf("north-east", "northeast", "north east") to -Math.PI / 4,
        listOf("north-west", "northwest", "north west") to -3 * Math.PI / 4,
        listOf("south-east", "southeast", "south east") to Math.PI / 4,
        listOf("south-west", "southwest", "south west") to 3 * Math.PI / 4,
        listOf("north", "uptown", "upriver") to -Math.PI / 2,
        listOf("south", "downtown", "downriver") to Math.PI / 2,
        listOf("east") to 0.0,
        listOf("west") to Math.PI
    )

    /** Which way from the anchor, in radians, when the text says. Screen y grows downward. */
    fun bearing(text: String): Double? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        return bearings.firstOrNull { (words, _) -> words.any { lower.contains(it) } }?.second
    }

    /**
     * How far, in map units, where the whole world is one unit across.
     *
     * The numbers are a feel, not a survey: what matters is that "across the road" lands
     * closer than "twenty minutes away", and that an hour on a bus puts something at the
     * other end of the map rather than next door.
     */
    private val spelled = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "ten" to 10, "fifteen" to 15, "twenty" to 20, "twenty-five" to 25, "thirty" to 30,
        "forty" to 40, "forty-five" to 45, "fifty" to 50
    )

    fun distance(hint: Hint): Float {
        val text = (hint.text + " " + hint.travelTime).lowercase()
        if (text.isBlank()) return FIRST_RING

        Regex(
            "(\\d{1,3}|five|ten|fifteen|twenty|twenty-five|thirty|forty|forty-five|fifty|" +
                "an|a|one|two|three|four|half an)\\s*(minute|min|hour|hr)"
        ).find(text)?.let { match ->
            val word = match.groupValues[1].lowercase()
            val count = word.toIntOrNull() ?: spelled[word] ?: return@let
            val minutes = if (match.groupValues[2].startsWith("h")) count * 60 else count
            val driving = Regex("\\b(bus|car|drive|driving|train|tram|taxi|cab|ride)\\b").containsMatchIn(text)
            val perMinute = if (driving) 0.010f else 0.006f
            return (minutes * perMinute).coerceIn(0.05f, 0.62f)
        }
        return when {
            Regex("\\b(next door|across the (?:road|street|hall)|opposite|adjoining|upstairs|downstairs)\\b")
                .containsMatchIn(text) -> 0.06f
            Regex("\\b(?:a few|two|three|couple of) (?:doors|streets|blocks)\\b").containsMatchIn(text) -> 0.11f
            Regex("\\b(?:round|around) the corner|\\bup the (?:road|street)\\b|\\bdown the (?:road|street)\\b")
                .containsMatchIn(text) -> 0.09f
            Regex("\\b(other side of (?:town|the city)|across town|outskirts|edge of town|out of town)\\b")
                .containsMatchIn(text) -> 0.45f
            Regex("\\b(?:half an hour|an hour|hours) (?:away|from)\\b").containsMatchIn(text) -> 0.4f
            else -> FIRST_RING
        }
    }

    /**
     * A point near [anchors] that is clear of [existing], or a grid cell when there is nothing
     * to anchor to. [seed] only decides which direction the search sets off in, so two places
     * added on the same turn do not both take the same spot, and [hint] overrides that guess
     * whenever the story actually said which way and how far.
     */
    fun place(
        anchors: List<LocationEntity>,
        existing: List<LocationEntity>,
        seed: Int,
        hint: Hint = Hint()
    ): Pair<Float, Float> {
        val useful = anchors.filter { it.mapX > 0f || it.mapY > 0f }
        if (useful.isEmpty()) return grid(existing.size)

        val originX = useful.map { it.mapX }.average().toFloat()
        val originY = useful.map { it.mapY }.average().toFloat()
        val told = bearing(hint.text)
        val reach = distance(hint)

        for (step in 0 until 48) {
            // When the story named a direction, hold it and only creep outwards for room.
            val angle = told?.plus(if (step == 0) 0.0 else (step % 2 * 2 - 1) * (step / 2) * 0.16)
                ?: ((seed + step) * GOLDEN_ANGLE)
            val radius = if (told != null) reach + step * 0.008f else reach + step * 0.012f
            val x = clamp(originX + (radius * cos(angle)).toFloat())
            val y = clamp(originY + (radius * sin(angle)).toFloat())
            if (existing.none { hypot(it.mapX - x, it.mapY - y) < MIN_GAP }) return x to y
        }
        return clamp(originX) to clamp(originY)
    }

    /** The old behaviour, still the right answer for a place with nothing to hang off. */
    fun grid(index: Int): Pair<Float, Float> =
        ((index % 7) * 0.14f + 0.08f) to ((index / 7) * 0.16f + 0.1f)

    private fun clamp(value: Float): Float = value.coerceIn(EDGE, 1f - EDGE)
}
