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
     * A point near [anchors] that is clear of [existing], or a grid cell when there is nothing
     * to anchor to. [seed] only decides which direction the search sets off in, so two places
     * added on the same turn do not both take the same spot.
     */
    fun place(
        anchors: List<LocationEntity>,
        existing: List<LocationEntity>,
        seed: Int
    ): Pair<Float, Float> {
        val useful = anchors.filter { it.mapX > 0f || it.mapY > 0f }
        if (useful.isEmpty()) return grid(existing.size)

        val originX = useful.map { it.mapX }.average().toFloat()
        val originY = useful.map { it.mapY }.average().toFloat()

        for (step in 0 until 48) {
            val angle = (seed + step) * GOLDEN_ANGLE
            val radius = FIRST_RING + step * 0.012f
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
