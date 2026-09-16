package com.narrate.app.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.narrate.app.data.entity.ImageEntity
import com.narrate.app.ui.codex.CodexViewModel
import com.narrate.app.ui.components.EmptyState
import com.narrate.app.ui.components.InfoRow
import com.narrate.app.ui.components.NarrateTopBar
import com.narrate.app.ui.components.Pill
import com.narrate.app.ui.theme.NarrateColors
import java.io.File

/** The world's visual history, filterable and labelled. */
@Composable
fun AlbumScreen(
    viewModel: CodexViewModel,
    onImage: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("ALL") }

    val types = listOf("ALL", "SCENE", "PORTRAIT", "LOCATION", "EVENT", "ITEM", "FAVOURITES")
    val visible = when (filter) {
        "ALL" -> state.images
        "FAVOURITES" -> state.images.filter { it.favorite }
        else -> state.images.filter { it.type == filter }
    }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = { NarrateTopBar("Album - ${state.world?.name.orEmpty()}", onBack = onBack) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(types) { type ->
                    Pill(type.lowercase(), filter == type) { filter = type }
                }
            }
            if (visible.isEmpty()) {
                EmptyState(
                    "No images yet",
                    "Use the image button while playing to capture a scene, a face or a place. " +
                        "Everything generated is saved here and becomes a visual reference for later."
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible) { image ->
                        AlbumTile(image, onClick = { onImage(image.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(image: ImageEntity, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(NarrateColors.SurfaceElevated)
        ) {
            if (File(image.filePath).exists()) {
                AsyncImage(
                    model = File(image.filePath),
                    contentDescription = image.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (image.favorite) {
                Icon(
                    Icons.Default.Favorite,
                    null,
                    tint = NarrateColors.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            image.label,
            style = MaterialTheme.typography.bodySmall,
            color = NarrateColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            listOf(image.storyTime, "turn ${image.turnIndex}").filter { it.isNotBlank() }.joinToString(" - "),
            style = MaterialTheme.typography.labelSmall,
            color = NarrateColors.TextMuted
        )
    }
}

/** A single image with everything the world knows about it. */
@Composable
fun ImageDetailScreen(
    viewModel: CodexViewModel,
    imageId: String,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val image = state.images.firstOrNull { it.id == imageId }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            NarrateTopBar(image?.label ?: "Image", onBack = onBack) {
                if (image != null) {
                    IconButton(onClick = { viewModel.toggleFavorite(image) }) {
                        Icon(
                            if (image.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favourite",
                            tint = NarrateColors.Accent
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (image == null) {
            EmptyState("Not found", "This image is no longer in the album.", Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (File(image.filePath).exists()) {
                AsyncImage(
                    model = File(image.filePath),
                    contentDescription = image.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(image.label, style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
            if (image.caption.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    image.caption,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = NarrateColors.TextSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
            InfoRow("Type", image.type.lowercase())
            InfoRow("Story point", listOf(image.storyTime, "turn ${image.turnIndex}").joinToString(" - "))
            InfoRow("Depicts", image.subjectNames)
            InfoRow("Location", image.locationName)
            InfoRow("Reference images used", image.usedReferences)
            InfoRow("Model", listOf(image.provider.lowercase(), image.model).filter { it.isNotBlank() }.joinToString(" - "))
            Spacer(Modifier.height(10.dp))
            var showPrompt by remember { mutableStateOf(false) }
            TextButton(onClick = { showPrompt = !showPrompt }) {
                Text(
                    if (showPrompt) "Hide the prompt" else "Show the prompt",
                    color = NarrateColors.Accent,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (showPrompt) {
                Text(
                    image.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
