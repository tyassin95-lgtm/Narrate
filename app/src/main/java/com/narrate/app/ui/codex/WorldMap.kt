package com.narrate.app.ui.codex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.ui.theme.NarrateColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The player's map of the world, drawn as a map.
 *
 * What used to be here was a family tree: a node per place, a line per route, containment drawn
 * as another line, and every node put wherever the depth-first walk happened to reach. It told
 * the player nothing true about where anything was, and with more than a dozen places it looked
 * like a wall of string in a detective film.
 *
 * A map answers different questions - what is near what, what is inside what, how far is it -
 * so this draws those instead. Places sit at their recorded coordinates. Containment is drawn
 * by enclosing a place and everything inside it in one soft area, the way a district encloses
 * its streets, rather than by a line pointing at it. Routes are not drawn at all: on a real map
 * you can see that two things are next to each other, and the ways between them belong in the
 * place's own entry, not scribbled across the city.
 *
 * Only what the player knows is here. That is decided upstream, by what their character has
 * actually seen, visited or been told.
 */

private const val PIN_WIDTH = 104
private const val PIN_HEIGHT = 46
private const val PANEL_PADDING = 22
private const val MIN_PANEL = 300

private data class Pin(val location: LocationEntity, val x: Float, val y: Float)

@Composable
fun WorldMapCanvas(
    state: CodexUiState,
    onLocation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val known = state.discoveredLocations
    if (known.isEmpty()) return
    val regions = remember(known) { regionsOf(known) }

    Column(
        modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        regions.forEach { region ->
            RegionPanel(region, state, onLocation)
        }
    }
}

private data class Region(
    val root: LocationEntity,
    val pins: List<Pin>,
    val side: Int
)

/**
 * One top-level place and everything the player knows to be inside it.
 *
 * A world normally has one or two of these - a city, and perhaps somewhere out of town - so
 * stacking them down the page reads correctly: these are separate places, not two halves of
 * one picture.
 */
private fun regionsOf(known: List<LocationEntity>): List<Region> {
    val roots = known.filter { location ->
        location.parentId == null || known.none { it.id == location.parentId }
    }
    return roots.map { root ->
        val inside = descendantsOf(root, known)
        val side = max(MIN_PANEL, (sqrt((inside.size + 1).toFloat()) * 150).toInt())
        Region(root, layout(inside, side), side)
    }
}

private fun descendantsOf(root: LocationEntity, known: List<LocationEntity>): List<LocationEntity> {
    val children = known.filter { it.parentId == root.id }
    return children + children.flatMap { descendantsOf(it, known) }
}

/**
 * Where each pin actually goes.
 *
 * The recorded coordinates are the truth - a place put beside what it is attached to when it
 * was discovered - but they are recorded in world space, where two streets may be a hundredth
 * apart. So they are stretched to fill the panel and then pushed apart until no two labels
 * overlap, which keeps the relative arrangement while making it readable.
 */
private fun layout(locations: List<LocationEntity>, side: Int): List<Pin> {
    if (locations.isEmpty()) return emptyList()
    val usableW = (side - PANEL_PADDING * 2 - PIN_WIDTH).toFloat().coerceAtLeast(1f)
    val usableH = (side - PANEL_PADDING * 2 - PIN_HEIGHT).toFloat().coerceAtLeast(1f)

    val minX = locations.minOf { it.mapX }
    val maxX = locations.maxOf { it.mapX }
    val minY = locations.minOf { it.mapY }
    val maxY = locations.maxOf { it.mapY }
    val spanX = (maxX - minX).takeIf { it > 0.0001f } ?: 1f
    val spanY = (maxY - minY).takeIf { it > 0.0001f } ?: 1f

    val points = locations.mapIndexed { index, location ->
        // A place with no coordinates at all still needs somewhere sensible to stand.
        val fallbackX = (index % 3) / 2f
        val fallbackY = (index / 3) / 3f
        val nx = if (maxX == minX) fallbackX else (location.mapX - minX) / spanX
        val ny = if (maxY == minY) fallbackY else (location.mapY - minY) / spanY
        floatArrayOf(
            PANEL_PADDING + nx * usableW,
            PANEL_PADDING + ny * usableH
        )
    }.toMutableList()

    // Relaxation: nudge overlapping pins apart, keeping everything inside the panel.
    val minGapX = (PIN_WIDTH + 12).toFloat()
    val minGapY = (PIN_HEIGHT + 12).toFloat()
    val leftEdge = PANEL_PADDING.toFloat()
    val rightEdge = (side - PANEL_PADDING - PIN_WIDTH).toFloat().coerceAtLeast(leftEdge)
    val topEdge = PANEL_PADDING.toFloat()
    val bottomEdge = (side - PANEL_PADDING - PIN_HEIGHT).toFloat().coerceAtLeast(topEdge)
    // Clamping every pass rather than once at the end. Clamping only at the end let a pin be
    // shoved past the edge by all its neighbours and then snapped back to the boundary - which
    // is where the neighbour that shoved it had also just been snapped, so they ended up in
    // exactly the same spot, which is the problem this loop exists to solve.
    repeat(120) {
        var moved = false
        for (a in points.indices) {
            for (b in a + 1 until points.size) {
                val dx = points[b][0] - points[a][0]
                val dy = points[b][1] - points[a][1]
                if (kotlin.math.abs(dx) >= minGapX || kotlin.math.abs(dy) >= minGapY) continue
                val pushX = if (dx >= 0) 5f else -5f
                val pushY = if (dy >= 0) 4f else -4f
                points[a][0] = (points[a][0] - pushX).coerceIn(leftEdge, rightEdge)
                points[a][1] = (points[a][1] - pushY).coerceIn(topEdge, bottomEdge)
                points[b][0] = (points[b][0] + pushX).coerceIn(leftEdge, rightEdge)
                points[b][1] = (points[b][1] + pushY).coerceIn(topEdge, bottomEdge)
                moved = true
            }
        }
        if (!moved) return@repeat
    }
    return locations.mapIndexed { index, location -> Pin(location, points[index][0], points[index][1]) }
}

@Composable
private fun RegionPanel(
    region: Region,
    state: CodexUiState,
    onLocation: (String) -> Unit
) {
    val isHere = state.world?.currentLocationId == region.root.id
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                region.root.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isHere) NarrateColors.Accent else NarrateColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                region.root.type.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.TextMuted
            )
        }
        Spacer(Modifier.height(6.dp))
        if (region.pins.isEmpty()) {
            Text(
                "Nothing inside it has been found yet.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            return@Column
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(
                Modifier
                    .size(region.side.dp, region.side.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NarrateColors.Surface)
            ) {
                // Containment, drawn as ground rather than as wires: everything inside a place
                // sits inside one soft area with that place.
                Canvas(Modifier.matchParentSize()) {
                    groups(region).forEach { group ->
                        val left = group.minOf { it.x } - 10f
                        val top = group.minOf { it.y } - 10f
                        val right = group.maxOf { it.x } + PIN_WIDTH + 10f
                        val bottom = group.maxOf { it.y } + PIN_HEIGHT + 10f
                        drawRoundRect(
                            color = NarrateColors.SurfaceHigh.copy(alpha = 0.55f),
                            topLeft = Offset(left.dp.toPx(), top.dp.toPx()),
                            size = Size((right - left).dp.toPx(), (bottom - top).dp.toPx()),
                            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                        )
                    }
                }
                region.pins.forEach { pin ->
                    MapPin(
                        pin = pin,
                        state = state,
                        modifier = Modifier
                            .offset(pin.x.dp, pin.y.dp)
                            .size(PIN_WIDTH.dp, PIN_HEIGHT.dp),
                        onClick = { onLocation(pin.location.id) }
                    )
                }
            }
        }
    }
}

/**
 * Each place that has known places inside it, together with them.
 *
 * Drawing the enclosing area is the whole of how hierarchy is shown here, so a building with
 * three rooms becomes one shaded patch containing four pins, and the player can see at a
 * glance that those rooms are in that building without a single line being drawn.
 */
private fun groups(region: Region): List<List<Pin>> {
    val byId = region.pins.associateBy { it.location.id }
    return region.pins.mapNotNull { parent ->
        val children = region.pins.filter { it.location.parentId == parent.location.id }
        if (children.isEmpty()) null else (children + byId.getValue(parent.location.id))
    }
}

@Composable
private fun MapPin(
    pin: Pin,
    state: CodexUiState,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val location = pin.location
    val isHere = state.world?.currentLocationId == location.id
    val occupants = state.characters.filter { it.currentLocationId == location.id && it.status == "ALIVE" }
    // Somewhere heard about but never entered is drawn faintly: the player knows it is there,
    // and that is all they know.
    val outline = when {
        isHere -> NarrateColors.Accent
        location.visited -> NarrateColors.Divider
        else -> NarrateColors.SurfaceHigh
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHere) NarrateColors.Accent.copy(alpha = 0.16f) else NarrateColors.SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(if (location.visited || isHere) outline else Color.Transparent)
                .then(
                    if (location.visited || isHere) Modifier
                    else Modifier.background(NarrateColors.SurfaceHigh)
                )
        )
        Spacer(Modifier.height(3.dp))
        Text(
            location.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (location.visited || isHere) NarrateColors.TextPrimary else NarrateColors.TextSecondary,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
        if (occupants.isNotEmpty()) {
            Text(
                occupants.joinToString(", ") { if (it.isPlayer) "you" else it.name.split(' ').first() },
                style = MaterialTheme.typography.labelSmall,
                color = if (isHere) NarrateColors.Accent else NarrateColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Kept for callers that need to know how much room the drawn map wants. */
fun mapSize(locations: List<LocationEntity>): Pair<Dp, Dp> {
    if (locations.isEmpty()) return 0.dp to 0.dp
    val side = max(MIN_PANEL, (sqrt(locations.size.toFloat()) * 150).toInt())
    return side.dp to side.dp
}

/**
 * The arrangement of one region's pins, as x/y pairs keyed by location id.
 *
 * Exposed so the layout can be tested without a screen: a map whose pins land on top of each
 * other is unreadable, and that is a property worth asserting rather than eyeballing.
 */
internal fun mapArrangement(known: List<LocationEntity>): Map<String, Pair<Float, Float>> =
    regionsOf(known).flatMap { it.pins }.associate { it.location.id to (it.x to it.y) }

/** How far apart two placed pins ended up. */
internal fun pinGap(arrangement: Map<String, Pair<Float, Float>>, a: String, b: String): Float {
    val first = arrangement[a] ?: return Float.MAX_VALUE
    val second = arrangement[b] ?: return Float.MAX_VALUE
    return hypot(first.first - second.first, first.second - second.second)
}
