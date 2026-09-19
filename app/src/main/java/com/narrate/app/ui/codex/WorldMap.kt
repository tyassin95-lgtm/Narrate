package com.narrate.app.ui.codex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.engine.Geography
import com.narrate.app.ui.theme.NarrateColors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A map of the town, drawn as a map.
 *
 * Two attempts got this wrong in the same way. The first drew the containment column as a
 * family tree with routes as crossing lines. The second drew it as nested translucent boxes,
 * which looked less like string on a corkboard and no more like a place: the player was
 * looking at a picture of the database's shape.
 *
 * A town has streets. Buildings stand on them, with numbers, in order. Districts are wherever
 * their streets happen to be rather than boxes drawn around them. Rooms are inside buildings
 * and do not appear separately at all, because a map of a city does not have a pin for
 * somebody's kitchen.
 *
 * So this draws streets as lines, buildings as marks along them, landmarks where they are, and
 * the player where they are standing. The coordinates are real, shared, and decided when a
 * place is discovered - not by this file, which only paints what the world already knows.
 */

private const val CANVAS_DP = 720
private const val EDGE_DP = 28

@Composable
fun WorldMapCanvas(
    state: CodexUiState,
    onLocation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val known = state.discoveredLocations
    if (known.isEmpty()) return
    val plan = remember(known) { townPlan(known) }
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(CANVAS_DP.dp, CANVAS_DP.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NarrateColors.Surface)
                .pointerInput(plan) {
                    detectTapGestures { tap ->
                        val hit = plan.pins.minByOrNull { pin ->
                            hypot(pin.x * size.width - tap.x, pin.y * size.height - tap.y)
                        }
                        if (hit != null &&
                            hypot(hit.x * size.width - tap.x, hit.y * size.height - tap.y) < 90f
                        ) {
                            onLocation(hit.location.id)
                        }
                    }
                }
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawTown(plan, state, labelPaint)
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(plan)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Legend(plan: TownPlan) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            "${plan.streets.size} street${if (plan.streets.size == 1) "" else "s"}, " +
                "${plan.pins.count { it.visited }} place${if (plan.pins.count { it.visited } == 1) "" else "s"} " +
                "you have been, ${plan.pins.count { !it.visited }} you have only heard of.",
            style = MaterialTheme.typography.labelSmall,
            color = NarrateColors.TextMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a place to open it. Everywhere you have not found yet is missing on purpose.",
            style = MaterialTheme.typography.labelSmall,
            color = NarrateColors.TextMuted
        )
    }
}

internal data class MapStreet(val location: LocationEntity, val from: Offset, val to: Offset)
internal data class MapPin(
    val location: LocationEntity,
    val x: Float,
    val y: Float,
    val visited: Boolean,
    val here: Boolean
)

internal data class TownPlan(
    val streets: List<MapStreet>,
    val pins: List<MapPin>,
    val districts: List<Pair<String, Offset>>
)

/**
 * The drawing, worked out from the world's own coordinates.
 *
 * Everything is in a 0..1 plane so the picture scales; the only thing done here that the world
 * has not already decided is spreading labels apart enough to read, and only labels - the
 * marks stay exactly where the town put them.
 */
internal fun townPlan(known: List<LocationEntity>, currentLocationId: String? = null): TownPlan {
    val streets = known.filter { it.type == Geography.STREET }.map { street ->
        val angle = Math.toRadians(street.spanAngle.toDouble())
        val half = (street.spanLength.takeIf { it > 0.01f } ?: 0.3f) / 2f
        val dx = (half * cos(angle)).toFloat()
        val dy = (half * sin(angle)).toFloat()
        MapStreet(
            street,
            Offset(street.mapX - dx, street.mapY - dy),
            Offset(street.mapX + dx, street.mapY + dy)
        )
    }

    // A room is drawn as part of its building, not as a place of its own.
    val drawable = known.filter {
        it.type != Geography.STREET && it.type != "ROOM" &&
            it.type != "REGION" && it.type != "DISTRICT" && it.type != "SETTLEMENT"
    }
    val pins = drawable.map { location ->
        MapPin(
            location = location,
            x = location.mapX.coerceIn(0.03f, 0.97f),
            y = location.mapY.coerceIn(0.03f, 0.97f),
            visited = location.visited,
            here = location.id == currentLocationId
        )
    }

    // A district is a label sitting over the middle of its own places, not a box round them.
    val districts = known.filter { it.type == "DISTRICT" || it.type == "SETTLEMENT" }
        .mapNotNull { district ->
            val members = known.filter { member ->
                member.id != district.id && belongsTo(member, district, known)
            }.filter { it.mapX > 0f || it.mapY > 0f }
            if (members.isEmpty()) null
            else district.name to Offset(
                members.map { it.mapX }.average().toFloat(),
                members.map { it.mapY }.average().toFloat()
            )
        }
    return TownPlan(streets, pins, districts)
}

private fun belongsTo(member: LocationEntity, district: LocationEntity, all: List<LocationEntity>): Boolean {
    var current: LocationEntity? = member
    var steps = 0
    while (current != null && steps < 6) {
        if (current.parentId == district.id) return true
        current = current.parentId?.let { id -> all.firstOrNull { it.id == id } }
        steps++
    }
    return false
}

private fun DrawScope.drawTown(
    plan: TownPlan,
    state: CodexUiState,
    labelPaint: android.graphics.Paint
) {
    val here = state.world?.currentLocationId
    fun point(x: Float, y: Float) = Offset(
        EDGE_DP.dp.toPx() + x * (size.width - 2 * EDGE_DP.dp.toPx()),
        EDGE_DP.dp.toPx() + y * (size.height - 2 * EDGE_DP.dp.toPx())
    )

    // District names first, underneath everything, the way they are printed on a real map.
    plan.districts.forEach { (name, centre) ->
        val at = point(centre.x, centre.y)
        labelPaint.color = NarrateColors.SurfaceHigh.copy(alpha = 0.9f).toArgb()
        labelPaint.textSize = 34f
        labelPaint.letterSpacing = 0.18f
        drawContext.canvas.nativeCanvas.drawText(
            name.uppercase(), at.x - name.length * 9f, at.y, labelPaint
        )
        labelPaint.letterSpacing = 0f
    }

    // The roads.
    plan.streets.forEach { street ->
        val from = point(street.from.x, street.from.y)
        val to = point(street.to.x, street.to.y)
        drawLine(
            color = NarrateColors.SurfaceHigh,
            start = from,
            end = to,
            strokeWidth = 13f
        )
        drawLine(
            color = NarrateColors.Surface,
            start = from,
            end = to,
            strokeWidth = 7f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 14f))
        )
        labelPaint.color = NarrateColors.TextMuted.toArgb()
        labelPaint.textSize = 22f
        val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        drawContext.canvas.nativeCanvas.drawText(
            street.location.name, mid.x + 8f, mid.y - 8f, labelPaint
        )
    }

    // The places on them.
    plan.pins.forEach { pin ->
        val at = point(pin.x, pin.y)
        val isHere = pin.location.id == here
        val colour = when {
            isHere -> NarrateColors.Accent
            pin.visited -> NarrateColors.TextSecondary
            else -> NarrateColors.TextMuted
        }
        if (isHere) {
            drawCircle(colour.copy(alpha = 0.18f), radius = 26f, center = at)
        }
        if (pin.visited || isHere) {
            drawCircle(colour, radius = 9f, center = at)
        } else {
            // Somewhere heard of and never entered: an outline, because that is all they have.
            drawCircle(colour, radius = 8f, center = at, style = Stroke(width = 3f))
        }
        labelPaint.color = (if (isHere) NarrateColors.Accent else NarrateColors.TextPrimary).toArgb()
        labelPaint.textSize = 25f
        drawContext.canvas.nativeCanvas.drawText(
            pin.location.name.take(26), at.x + 16f, at.y + 9f, labelPaint
        )

        val occupants = state.characters.filter {
            it.currentLocationId == pin.location.id && it.status == "ALIVE"
        }
        if (occupants.isNotEmpty()) {
            labelPaint.color = (if (isHere) NarrateColors.Accent else NarrateColors.TextMuted).toArgb()
            labelPaint.textSize = 20f
            drawContext.canvas.nativeCanvas.drawText(
                occupants.joinToString(", ") { if (it.isPlayer) "you" else it.name.split(' ').first() }.take(30),
                at.x + 16f,
                at.y + 32f,
                labelPaint
            )
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** Kept for callers that need to know how much room the drawn map wants. */
fun mapSize(locations: List<LocationEntity>): Pair<Dp, Dp> {
    if (locations.isEmpty()) return 0.dp to 0.dp
    return CANVAS_DP.dp to CANVAS_DP.dp
}
