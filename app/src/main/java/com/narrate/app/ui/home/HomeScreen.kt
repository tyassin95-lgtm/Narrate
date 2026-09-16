package com.narrate.app.ui.home

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.narrate.app.container
import com.narrate.app.data.entity.ImageEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.ui.components.*
import com.narrate.app.ui.theme.NarrateColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class HomeUiState(
    val worlds: List<WorldEntity> = emptyList(),
    val images: List<ImageEntity> = emptyList(),
    val hasProviderKey: Boolean = false,
    val hasNarrationModel: Boolean = false
) {
    val featured: WorldEntity? get() = worlds.firstOrNull()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.container
    private val repo = container.repository

    val state: StateFlow<HomeUiState> = combine(
        repo.observeWorlds(),
        repo.observeRecentImages(40),
        container.settings.settings
    ) { worlds, images, settings ->
        HomeUiState(
            worlds = worlds,
            images = images,
            hasProviderKey = settings.configuredProviders.isNotEmpty(),
            hasNarrationModel = settings.hasNarrationModel
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun deleteWorld(worldId: String) {
        viewModelScope.launch { repo.deleteWorld(worldId) }
    }

    fun coverFor(world: WorldEntity, images: List<ImageEntity>): String? =
        images.firstOrNull { it.id == world.coverImageId }?.filePath
            ?: images.firstOrNull { it.worldId == world.id && it.type == "SCENE" }?.filePath
            ?: images.firstOrNull { it.worldId == world.id }?.filePath
}

/** The browse experience: one hero, then rows. Everything is one tap from here. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlay: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onSettings: () -> Unit,
    onAlbum: (String) -> Unit,
    onCodex: (String) -> Unit,
    onImage: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<WorldEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(NarrateColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "NARRATE",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                ),
                color = NarrateColors.Accent,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = NarrateColors.TextPrimary)
            }
        }

        if (!state.hasProviderKey || !state.hasNarrationModel) {
            SetupBanner(onSettings)
        }

        val featured = state.featured
        if (featured != null) {
            HeroPanel(
                world = featured,
                coverPath = viewModel.coverFor(featured, state.images),
                onPlay = { onPlay(featured.id) },
                onCodex = { onCodex(featured.id) }
            )
        } else {
            EmptyState(
                title = "No worlds yet",
                body = "Create a world and a character, then step into it. Everything that happens there will be remembered.",
                actionLabel = "Create your first world",
                onAction = onCreateWorld,
                modifier = Modifier.padding(top = 40.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PrimaryButton("New world", Modifier.weight(1f), onClick = onCreateWorld)
            if (featured != null) {
                SecondaryButton("Album", Modifier.weight(1f)) { onAlbum(featured.id) }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (state.worlds.isNotEmpty()) {
            CardRow("Continue", state.worlds) { world ->
                Box {
                    PosterCard(
                        title = world.name,
                        subtitle = "${world.turnCount} turns - ${world.storyTime}",
                        imagePath = viewModel.coverFor(world, state.images),
                        badge = if (world.id == featured?.id) "Continue" else null,
                        onClick = { onPlay(world.id) }
                    )
                    IconButton(
                        onClick = { pendingDelete = world },
                        modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "Delete world",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        val worldsWithImages = state.worlds.filter { world -> state.images.any { it.worldId == world.id } }
        if (state.images.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            CardRow(
                title = "Recently seen",
                items = state.images.take(20),
                action = if (worldsWithImages.isNotEmpty()) "All" else null,
                onAction = { worldsWithImages.firstOrNull()?.let { onAlbum(it.id) } }
            ) { image ->
                Column(
                    Modifier.width(200.dp).clickable { onImage(image.id) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(112.dp)
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
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        image.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = NarrateColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (state.worlds.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionHeader("Your worlds")
            state.worlds.forEach { world ->
                WorldRow(
                    world = world,
                    coverPath = viewModel.coverFor(world, state.images),
                    onPlay = { onPlay(world.id) },
                    onCodex = { onCodex(world.id) }
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    pendingDelete?.let { world ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = NarrateColors.SurfaceElevated,
            title = { Text("Delete ${world.name}?", color = NarrateColors.TextPrimary) },
            text = {
                Text(
                    "This erases the world permanently: its story, its people, its map, its memories and " +
                        "every image generated in it. This cannot be undone.",
                    color = NarrateColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorld(world.id)
                    pendingDelete = null
                }) { Text("Delete", color = NarrateColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = NarrateColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SetupBanner(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NarrateColors.SurfaceElevated)
            .clickable(onClick = onSettings)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Settings, null, tint = NarrateColors.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Connect a model", style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
            Text(
                "Add your OpenAI, Claude, Gemini or Grok key and pick which models narrate, simulate and draw.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
        }
    }
}

@Composable
private fun HeroPanel(
    world: WorldEntity,
    coverPath: String?,
    onPlay: () -> Unit,
    onCodex: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(430.dp)) {
        if (coverPath != null && File(coverPath).exists()) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = world.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MonogramArt(world.name, Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to NarrateColors.Background.copy(alpha = 0.55f),
                    1f to NarrateColors.Background
                )
            )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Text(
                world.genre.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = NarrateColors.Accent
            )
            Spacer(Modifier.height(6.dp))
            Text(
                world.name,
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (world.tagline.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    world.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NarrateColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (world.turnCount == 0) "Begin" else "Continue", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onCodex,
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NarrateColors.SurfaceHigh.copy(alpha = 0.9f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("World info")
                }
            }
        }
    }
}

@Composable
private fun WorldRow(world: WorldEntity, coverPath: String?, onPlay: () -> Unit, onCodex: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NarrateColors.Surface)
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))) {
            if (coverPath != null && File(coverPath).exists()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = world.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MonogramArt(world.name, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(world.name, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${world.turnCount} turns - ${world.storyTime}",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            if (world.tone.isNotBlank()) {
                Text(
                    world.tone,
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onCodex) {
            Text("Codex", color = NarrateColors.Accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = NarrateColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp)
    )
}
