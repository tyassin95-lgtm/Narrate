package com.narrate.app.ui.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.narrate.app.ui.components.*
import com.narrate.app.engine.ContactChannels
import com.narrate.app.ui.theme.NarrateColors
import java.io.File

/** A character's full dossier, including every image ever generated of them. */
@Composable
fun CharacterDetailScreen(
    viewModel: CodexViewModel,
    characterId: String,
    onImage: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val character = state.characters.firstOrNull { it.id == characterId }
    val images = state.imagesFor(characterId)
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            NarrateTopBar(character?.name ?: "Character", onBack = onBack) {
                if (character?.isPlayer == true) {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, "Correct this name", tint = NarrateColors.TextSecondary)
                    }
                }
                DrawButton(
                    drawing = state.isDrawing(characterId),
                    enabled = !state.generating
                ) { viewModel.generatePortrait(characterId) }
            }
        }
    ) { padding ->
        if (character == null) {
            EmptyState("Not found", "This character is no longer part of the world.", Modifier.padding(padding))
            return@Scaffold
        }
        if (renaming) {
            RenameDialog(
                current = character.name,
                onDismiss = { renaming = false },
                onConfirm = {
                    renaming = false
                    viewModel.renameCharacter(characterId, it)
                }
            )
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ErrorBanner(state.message) { viewModel.clearMessage() }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                Avatar(character.name, state.imagePath(character.portraitImageId), 88.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(character.name, style = MaterialTheme.typography.headlineLarge, color = NarrateColors.TextPrimary)
                    if (character.role.isNotBlank()) {
                        Text(character.role, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.Accent)
                    }
                    Text(
                        "At ${state.locationName(character.currentLocationId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NarrateColors.TextMuted
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Status", character.status.lowercase())
                if (!character.isPlayer) {
                    StatChip("Affinity", character.affinity.toString())
                    StatChip("Trust", character.trust.toString())
                }
                StatChip("Seen", "turn ${character.lastSeenTurn}")
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Visual history", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                ImageStrip(images, onImage)
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("In your own words - kept as canon", character.authoredCanon)
            InfoRow("Summary", character.summary)
            InfoRow("Personality", character.personality)
            InfoRow("Backstory", character.backstory)
            InfoRow("Appearance (locked)", character.appearance)
            InfoRow("Currently wearing", character.outfit)
            InfoRow("Condition", character.physicalState)
            InfoRow("Voice", character.voice)
            InfoRow("Goals", character.goals)
            InfoRow("Fears", character.fears)
            InfoRow("Secrets", character.secrets)
            InfoRow("Toward you", character.relationshipToPlayer)
            if (!character.isPlayer) {
                // Visible because it is world state, not a UI detail: whether this person can
                // be reached at all is the difference between a thread existing and not.
                InfoRow(
                    "How you can reach them",
                    if (character.playerContact.isBlank()) {
                        "No contact details exchanged - you have no way to contact them."
                    } else {
                        ContactChannels.parse(character.playerContact)
                            .joinToString(", ") { ContactChannels.describe(it) }
                            .replaceFirstChar { it.uppercase() }
                    }
                )
            }
            InfoRow("Faction", character.faction)
            InfoRow("Routine", character.routine)
            InfoRow("Knows", character.knowledge)

            val memories = viewModel.memoriesFor(character, state.memories)
            if (memories.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("What the world remembers", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                memories.forEach { memory ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            "turn ${memory.turnIndex} - ${memory.storyTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NarrateColors.TextMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(memory.text, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Your protagonist's name is yours to correct. */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NarrateColors.SurfaceElevated,
        title = { Text("Your character's name", color = NarrateColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "This is the name the narrator uses from here on, and the name written on " +
                        "anything of yours that carries one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted
                )
                Spacer(Modifier.height(12.dp))
                NarrateField(value, { value = it }, "Name")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Save", color = NarrateColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = NarrateColors.TextSecondary) }
        }
    )
}

/** A place, its condition, who is in it, and where you can get to from there. */
@Composable
fun LocationDetailScreen(
    viewModel: CodexViewModel,
    locationId: String,
    onImage: (String) -> Unit,
    onCharacter: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val location = state.locations.firstOrNull { it.id == locationId }
    val images = state.imagesFor(locationId)
    val occupants = state.characters.filter { it.currentLocationId == locationId }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            NarrateTopBar(location?.name ?: "Location", onBack = onBack) {
                DrawButton(
                    drawing = state.isDrawing(locationId),
                    enabled = !state.generating
                ) { viewModel.generateLocationImage(locationId) }
            }
        }
    ) { padding ->
        if (location == null) {
            EmptyState("Not found", "This place is no longer part of the world.", Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ErrorBanner(state.message) { viewModel.clearMessage() }
            state.imagePath(location.imageId)?.let { path ->
                if (File(path).exists()) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = location.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Type", location.type.lowercase())
                if (state.world?.currentLocationId == location.id) StatChip("You", "are here")
                state.locations.firstOrNull { it.id == location.parentId }?.let { StatChip("Within", it.name) }
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Visual history", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                ImageStrip(images, onImage)
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Description", location.description)
            InfoRow("Atmosphere", location.atmosphere)
            InfoRow("Notable features", location.notableFeatures)
            InfoRow("Current condition", location.currentState)
            InfoRow("Controlled by", location.controlledBy)

            if (occupants.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Who is here", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                occupants.forEach { person ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                            .clickable { onCharacter(person.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(person.name, state.imagePath(person.portraitImageId), 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (person.isPlayer) "${person.name} (you)" else person.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = NarrateColors.TextPrimary
                        )
                    }
                }
            }

            val exits = state.links.filter { it.fromId == locationId || it.toId == locationId }
            if (exits.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Routes from here", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                exits.forEach { link ->
                    val otherId = if (link.fromId == locationId) link.toId else link.fromId
                    Text(
                        "- " + state.locationName(otherId) +
                            (if (link.travelTime.isNotBlank()) " (${link.travelTime} ${link.mode})" else "") +
                            (if (link.blocked) " [blocked]" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NarrateColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 3.dp).clickable { onBack() }
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ImageStrip(images: List<ImageEntity>, onImage: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images) { image ->
            Column(Modifier.width(150.dp).clickable { onImage(image.id) }) {
                if (File(image.filePath).exists()) {
                    AsyncImage(
                        model = File(image.filePath),
                        contentDescription = image.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    image.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    image.storyTime,
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    color = NarrateColors.TextMuted
                )
            }
        }
    }
}
