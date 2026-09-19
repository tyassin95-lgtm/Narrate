package com.narrate.app.engine

import com.narrate.app.data.entity.LocationEntity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where things are, as geography rather than as a tree.
 *
 * The map was a rendering of the containment column: a district drew a box, the buildings
 * inside it drew smaller boxes, and a place's position on screen came from the order it had
 * been discovered in. It told the player nothing true about the town, because the app had
 * never been asked to know anything true about the town.
 *
 * A city has a shape, and the shape is streets. A street is a line. A building has a number on
 * a street and sits along it in order. A room is inside its building and has no separate place
 * of its own. A district is not a box - it is wherever its streets happen to be. Once the
 * model works that way, "two blocks north" and "the third house on the left" have somewhere to
 * land, and the picture on screen is a map instead of a diagram of a database.
 *
 * Everything here is deterministic from names and numbers, so the same world always draws the
 * same town, and a place discovered on turn thirty appears where the story put it rather than
 * wherever the screen had room.
 */
object Geography {

    const val STREET = "STREET"

    private val streetWords = listOf(
        "street", "st", "road", "rd", "avenue", "ave", "lane", "ln", "drive", "dr", "way",
        "boulevard", "blvd", "row", "terrace", "crescent", "close", "court", "alley", "mews",
        "parade", "walk", "hill", "grid"
    )

    /** True when this name is the name of a street rather than of something on one. */
    fun isStreet(name: String): Boolean {
        val words = name.trim().lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        // "1247 Maple Street" is a building; "Maple Street" is the street it stands on.
        if (addressNumberIn(name) != null) return false
        if (words.first() in setOf("the")) return words.getOrNull(words.size - 1) in streetWords
        return words.last() in streetWords
    }

    private val addressPattern = Regex("(?:^|\\b)(\\d{1,5})\\s+(?=[A-Za-z])")

    /** The number on the door, when the name carries one. */
    fun addressNumberIn(name: String): Int? =
        addressPattern.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..99999 }

    /**
     * The street a name sits on: "1247 Maple Street" and "the sidewalk outside 1247 Maple
     * Street" are both on Maple Street.
     */
    fun streetNameIn(name: String): String? {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val suffixAt = words.indexOfLast { it.trim(',', '.').lowercase() in streetWords }
        if (suffixAt <= 0) return null
        // Walk back over the words that name the street, stopping at a number, a preposition,
        // or a word that is plainly not part of a name.
        var start = suffixAt
        while (start > 0) {
            val previous = words[start - 1].trim(',', '.')
            val lower = previous.lowercase()
            if (previous.isEmpty()) break
            if (lower.toIntOrNull() != null) break
            if (lower in setOf("outside", "on", "at", "near", "of", "along", "off", "by", "the")) break
            if (!previous.first().isUpperCase() && lower !in streetWords) break
            start--
        }
        val street = words.subList(start, suffixAt + 1).joinToString(" ").trim(',', '.')
        return street.takeIf { it.split(Regex("\\s+")).size in 1..4 && it.length >= 3 }
    }

    /** A number that is the same every time this world is drawn. */
    private fun stableHash(text: String): Int {
        var hash = 7
        text.lowercase().trim().forEach { hash = hash * 31 + it.code }
        return abs(hash)
    }

    /**
     * The patch of the plane a district occupies.
     *
     * Districts are spread around the middle of the map rather than tiled, because a world
     * usually has one district that matters and several that are names in the distance.
     */
    fun districtCentre(districtName: String?): Pair<Float, Float> {
        if (districtName.isNullOrBlank()) return 0.5f to 0.5f
        val hash = stableHash(districtName)
        val ring = 0.26f
        val angle = (hash % 360) * Math.PI / 180.0
        return (0.5f + (ring * cos(angle)).toFloat()) to (0.5f + (ring * sin(angle)).toFloat())
    }

    private const val DISTRICT_SPAN = 0.34f

    data class Street(val x: Float, val y: Float, val angle: Float, val length: Float)

    /**
     * Where a street runs.
     *
     * Streets alternate between two bearings and sit on a lattice, which is what makes the
     * result read as a grid of blocks rather than as scattered lines. A world whose streets
     * are not a grid still gets a coherent, stable arrangement out of it.
     */
    fun streetGeometry(streetName: String, districtName: String?): Street {
        val hash = stableHash(streetName)
        val (cx, cy) = districtCentre(districtName)
        val acrossTheGrain = hash % 2 == 0
        val lane = ((hash / 2) % 5) - 2
        val shift = lane * (DISTRICT_SPAN / 5f)
        val jitter = (((hash / 11) % 7) - 3) * 0.006f
        return if (acrossTheGrain) {
            Street(x = cx + shift + jitter, y = cy, angle = 90f, length = DISTRICT_SPAN)
        } else {
            Street(x = cx, y = cy + shift + jitter, angle = 0f, length = DISTRICT_SPAN)
        }
    }

    /**
     * A point along a street, put in order by its house number.
     *
     * Numbers run up the street and odds sit on one side, which is what makes "the third house
     * on the left" and "two doors down" mean something on the picture.
     */
    fun positionOnStreet(street: LocationEntity, addressNumber: Int, fallbackSeed: String): Pair<Float, Float> {
        val angle = street.spanAngle * Math.PI / 180.0
        val length = street.spanLength.takeIf { it > 0.01f } ?: DISTRICT_SPAN
        val seed = if (addressNumber > 0) addressNumber else stableHash(fallbackSeed) % 2000
        // Numbers wrap every couple of thousand so a long road still fits on the plane.
        val along = ((seed % 2000) / 2000f - 0.5f) * length
        val side = if (addressNumber % 2 == 1) -1f else 1f
        val offset = 0.018f * side
        val x = street.mapX + (along * cos(angle)).toFloat() - (offset * sin(angle)).toFloat()
        val y = street.mapY + (along * sin(angle)).toFloat() + (offset * cos(angle)).toFloat()
        return clamp(x) to clamp(y)
    }

    /** A place inside a building has no position of its own: it is where the building is. */
    fun sharesPositionWithParent(type: String): Boolean = type.uppercase() == "ROOM"

    /** Spread a handful of places that have nothing to hang off around their district. */
    fun looseNear(districtName: String?, seedName: String, taken: List<LocationEntity>): Pair<Float, Float> {
        val (cx, cy) = districtCentre(districtName)
        val hash = stableHash(seedName)
        for (step in 0 until 24) {
            val angle = ((hash + step * 47) % 360) * Math.PI / 180.0
            val radius = 0.07f + ((hash / 360 + step) % 5) * 0.03f
            val x = clamp(cx + (radius * cos(angle)).toFloat())
            val y = clamp(cy + (radius * sin(angle)).toFloat())
            if (taken.none { abs(it.mapX - x) < 0.02f && abs(it.mapY - y) < 0.02f }) return x to y
        }
        return clamp(cx) to clamp(cy)
    }

    private fun clamp(value: Float) = value.coerceIn(0.04f, 0.96f)

    /**
     * The travel between two places, in minutes, from where they actually are.
     *
     * The plane is a town: corner to corner is about an hour on foot. That is arbitrary, but
     * it is consistent, which is the property a world needs so that "twenty minutes away"
     * means the same distance every time somebody says it.
     */
    fun travelMinutes(from: LocationEntity?, to: LocationEntity?, onFoot: Boolean = true): Int {
        if (from == null || to == null) return if (onFoot) 15 else 8
        if (from.id == to.id) return 0
        val dx = from.mapX - to.mapX
        val dy = from.mapY - to.mapY
        val distance = kotlin.math.hypot(dx, dy)
        val minutes = (distance * (if (onFoot) 70f else 22f)).toInt()
        return minutes.coerceIn(if (onFoot) 2 else 1, 240)
    }

    /** Which way [to] lies from [from], in the words a person would use. */
    fun bearingWord(from: LocationEntity, to: LocationEntity): String {
        val dx = to.mapX - from.mapX
        val dy = to.mapY - from.mapY
        if (kotlin.math.hypot(dx, dy) < 0.02f) return "right here"
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
        return when {
            angle < -157.5 || angle >= 157.5 -> "west"
            angle < -112.5 -> "north-west"
            angle < -67.5 -> "north"
            angle < -22.5 -> "north-east"
            angle < 22.5 -> "east"
            angle < 67.5 -> "south-east"
            angle < 112.5 -> "south"
            else -> "south-west"
        }
    }
}
