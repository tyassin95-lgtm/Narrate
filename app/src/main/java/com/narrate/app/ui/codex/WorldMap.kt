package com.narrate.app.ui.codex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.ui.theme.NarrateColors
import java.io.File

private const val NODE_WIDTH = 132
private const val NODE_HEIGHT = 62
private const val COLUMN_GAP = 40
private const val ROW_GAP = 34

private data class Placed(val location: LocationEntity, val x: Int, val y: Int)

/**
 * A drawn map of the world: containment runs down the page, routes are drawn between
 * places, and everyone currently standing somewhere is listed inside its node.
 */
@Composable
fun WorldMapCanvas(
    state: CodexUiState,
    onLocation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val placed = remember(state.locations) { layout(state.locations) }
    if (placed.isEmpty()) return

    val width = (placed.maxOf { it.x } + NODE_WIDTH + COLUMN_GAP).dp
    val height = (placed.maxOf { it.y } + NODE_HEIGHT + ROW_GAP).dp
    val positions = placed.associateBy { it.location.id }

    Box(
        modifier
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.size(width, height)) {
            Canvas(Modifier.matchParentSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                state.links.forEach { link ->
                    val from = positions[link.fromId] ?: return@forEach
                    val to = positions[link.toId] ?: return@forEach
                    drawLine(
                        color = if (link.blocked) NarrateColors.Accent.copy(alpha = 0.5f)
                        else NarrateColors.Divider,
                        start = Offset(
                            (from.x + NODE_WIDTH / 2).dp.toPx(),
                            (from.y + NODE_HEIGHT / 2).dp.toPx()
                        ),
                        end = Offset(
                            (to.x + NODE_WIDTH / 2).dp.toPx(),
                            (to.y + NODE_HEIGHT / 2).dp.toPx()
                        ),
                        strokeWidth = 2f,
                        pathEffect = if (link.blocked) dash else null
                    )
                }
                // Containment is drawn faintly so the hierarchy reads without shouting.
                state.locations.forEach { location ->
                    val parent = positions[location.parentId] ?: return@forEach
                    val child = positions[location.id] ?: return@forEach
                    drawLine(
                        color = NarrateColors.SurfaceHigh,
                        start = Offset(
                            (parent.x + NODE_WIDTH / 2).dp.toPx(),
                            (parent.y + NODE_HEIGHT).dp.toPx()
                        ),
                        end = Offset(
                            (child.x + NODE_WIDTH / 2).dp.toPx(),
                            child.y.dp.toPx()
                        ),
                        strokeWidth = 1.5f,
                        pathEffect = dash
                    )
                }
            }
            placed.forEach { node ->
                MapNode(
                    location = node.location,
                    state = state,
                    modifier = Modifier
                        .offset(node.x.dp, node.y.dp)
                        .size(NODE_WIDTH.dp, NODE_HEIGHT.dp),
                    onClick = { onLocation(node.location.id) }
                )
            }
        }
    }
}

@Composable
private fun MapNode(
    location: LocationEntity,
    state: CodexUiState,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val isHere = state.world?.currentLocationId == location.id
    val occupants = state.characters.filter { it.currentLocationId == location.id && it.status == "ALIVE" }
    val imagePath = state.imagePath(location.imageId)
    val hasImage = imagePath != null && File(imagePath).exists()

    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isHere) NarrateColors.Accent.copy(alpha = 0.18f) else NarrateColors.Surface
            )
            .border(
                1.dp,
                if (isHere) NarrateColors.Accent else if (location.discovered) NarrateColors.Divider
                else NarrateColors.SurfaceHigh,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // A place that has been drawn shows itself, dimmed enough to keep the label legible.
        if (hasImage) {
            AsyncImage(
                model = File(imagePath!!),
                contentDescription = location.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.82f)
                            )
                        )
                    )
            )
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                location.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (location.discovered) NarrateColors.TextPrimary else NarrateColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                location.type.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.TextMuted,
                maxLines = 1
            )
            if (occupants.isNotEmpty()) {
                Text(
                    occupants.joinToString(", ") { if (it.isPlayer) "you" else it.name.split(' ').first() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHere) NarrateColors.Accent else NarrateColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Depth-first placement: children sit under their parent, siblings spread across. */
private fun layout(locations: List<LocationEntity>): List<Placed> {
    if (locations.isEmpty()) return emptyList()
    val byParent = locations.groupBy { location ->
        location.parentId?.takeIf { parent -> locations.any { it.id == parent } }
    }
    val placed = mutableListOf<Placed>()
    var column = 0

    fun place(location: LocationEntity, depth: Int) {
        val children = byParent[location.id].orEmpty()
        val startColumn = column
        if (children.isEmpty()) {
            placed += Placed(location, column * (NODE_WIDTH + COLUMN_GAP), depth * (NODE_HEIGHT + ROW_GAP))
            column++
            return
        }
        children.forEach { place(it, depth + 1) }
        val centre = (startColumn + column - 1) / 2.0
        placed += Placed(
            location,
            (centre * (NODE_WIDTH + COLUMN_GAP)).toInt(),
            depth * (NODE_HEIGHT + ROW_GAP)
        )
    }

    byParent[null].orEmpty().forEach { place(it, 0) }
    // Anything orphaned by a broken parent link still gets a place on the page.
    locations.filter { location -> placed.none { it.location.id == location.id } }.forEach { orphan ->
        placed += Placed(orphan, column * (NODE_WIDTH + COLUMN_GAP), 0)
        column++
    }
    return placed
}

fun mapSize(locations: List<LocationEntity>): Pair<Dp, Dp> {
    val placed = layout(locations)
    if (placed.isEmpty()) return 0.dp to 0.dp
    return (placed.maxOf { it.x } + NODE_WIDTH).dp to (placed.maxOf { it.y } + NODE_HEIGHT).dp
}
