package com.narrate.app.ui.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narrate.app.ai.ModelPricing
import com.narrate.app.data.entity.*
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.UsageRecorder
import com.narrate.app.ui.settings.UsageBreakdown
import com.narrate.app.ui.settings.UsageTotals
import com.narrate.app.ui.components.*
import com.narrate.app.ui.theme.NarrateColors

private enum class CodexTab(val label: String) {
    OVERVIEW("Overview"),
    CAST("Cast"),
    MAP("Map"),
    OBJECTS("Objects"),
    THREADS("Threads"),
    JOURNAL("Journal"),
    MEMORY("Memory"),
    USAGE("Usage"),
    CONTINUITY("Continuity")
}

/** The world's reference library: everything the save file knows, browsable. */
@Composable
fun CodexScreen(
    viewModel: CodexViewModel,
    onCharacter: (String) -> Unit,
    onLocation: (String) -> Unit,
    onAlbum: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(CodexTab.OVERVIEW) }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            Column {
                NarrateTopBar(state.world?.name ?: "Codex", onBack = onBack) {
                    TextButton(onClick = onAlbum) {
                        Text("Album", color = NarrateColors.Accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                ScrollableTabRow(
                    selectedTabIndex = CodexTab.entries.indexOf(tab),
                    containerColor = NarrateColors.Background,
                    contentColor = NarrateColors.Accent,
                    edgePadding = 12.dp,
                    divider = { HorizontalDivider(color = NarrateColors.Divider) }
                ) {
                    CodexTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tab == entry) NarrateColors.TextPrimary else NarrateColors.TextMuted
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ErrorBanner(state.message) { viewModel.clearMessage() }
            when (tab) {
                CodexTab.OVERVIEW -> OverviewTab(state, viewModel, onCharacter)
                CodexTab.CAST -> CastTab(state, onCharacter)
                CodexTab.MAP -> MapTab(state, onLocation)
                CodexTab.OBJECTS -> ObjectsTab(state, viewModel)
                CodexTab.THREADS -> ThreadsTab(state)
                CodexTab.JOURNAL -> JournalTab(state)
                CodexTab.MEMORY -> MemoryTab(state, viewModel)
                CodexTab.USAGE -> UsageTab(state)
                CodexTab.CONTINUITY -> ContinuityTab(state)
            }
        }
    }
}

@Composable
private fun OverviewTab(state: CodexUiState, viewModel: CodexViewModel, onCharacter: (String) -> Unit) {
    val world = state.world ?: return
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Turns", world.turnCount.toString())
                StatChip("Day", world.dayNumber.toString())
                StatChip("Cast", state.npcs.size.toString())
                StatChip("Places", state.locations.size.toString())
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        state.player?.let { player ->
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                        .clickable { onCharacter(player.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(player.name, state.imagePath(player.portraitImageId), 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("YOU", style = MaterialTheme.typography.labelSmall, color = NarrateColors.Accent)
                        Text(player.name, style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                        Text(
                            player.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = NarrateColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        item { InfoRow("Story time", world.storyTime) }
        item {
            val current = PlayStyle.from(world.playStyle)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("PACING", style = MaterialTheme.typography.labelSmall, color = NarrateColors.Accent)
                Spacer(Modifier.height(3.dp))
                Text(current.blurb, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PlayStyle.entries.toList()) { style ->
                        Pill(style.label, style == current) { viewModel.setPlayStyle(style) }
                    }
                }
            }
        }
        item { InfoRow("Currently", state.locationName(world.currentLocationId)) }
        item { InfoRow("Tagline", world.tagline) }
        item { InfoRow("Genre", world.genre) }
        item { InfoRow("Tone", world.tone) }
        item { InfoRow("Premise", world.premise) }
        item { InfoRow("History", world.history) }
        item { InfoRow("Laws of the world", world.rules) }
        item { InfoRow("Themes", world.themes) }
        item { InfoRow("Your direction", world.customPrompt) }
        item { InfoRow("Visual style", world.artStyle) }
        if (state.factions.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Factions", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
            }
            items(state.factions) { faction ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(faction.name, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary, modifier = Modifier.weight(1f))
                        Pill(standingLabel(faction.standingWithPlayer))
                    }
                    if (faction.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(faction.description, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextSecondary)
                    }
                    if (faction.goals.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Goals: ${faction.goals}", style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
                    }
                }
            }
        }
    }
}

private fun standingLabel(value: Int): String = when {
    value >= 50 -> "Allied ($value)"
    value >= 15 -> "Friendly ($value)"
    value <= -50 -> "Hostile ($value)"
    value <= -15 -> "Wary ($value)"
    else -> "Neutral ($value)"
}

@Composable
private fun CastTab(state: CodexUiState, onCharacter: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.npcs.sortedWith(compareByDescending<CharacterEntity> { it.importance }.thenBy { it.name })) { character ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                    .clickable { onCharacter(character.id) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(character.name, state.imagePath(character.portraitImageId), 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            character.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = NarrateColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (character.status != "ALIVE") {
                            Spacer(Modifier.width(6.dp))
                            Pill(character.status)
                        }
                    }
                    Text(
                        listOf(character.role, state.locationName(character.currentLocationId))
                            .filter { it.isNotBlank() }
                            .joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NarrateColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AffinityBadge(character.affinity)
            }
        }
        if (state.npcs.isEmpty()) {
            item { EmptyState("No one yet", "The cast fills in as you meet people.") }
        }
    }
}

@Composable
private fun AffinityBadge(affinity: Int) {
    val color = when {
        affinity >= 30 -> NarrateColors.CallAccent
        affinity <= -30 -> NarrateColors.Accent
        else -> NarrateColors.TextMuted
    }
    Text(
        (if (affinity > 0) "+" else "") + affinity,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun MapTab(state: CodexUiState, onLocation: (String) -> Unit) {
    val roots = state.locations.filter { location ->
        location.parentId == null || state.locations.none { it.id == location.parentId }
    }
    var drawn by remember { mutableStateOf(true) }
    if (drawn && state.locations.isNotEmpty()) {
        Column(Modifier.fillMaxSize()) {
            MapHeader(drawn) { drawn = it }
            WorldMapCanvas(state, onLocation, Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            MapHeader(drawn) { drawn = it }
            Text(
                "Every place the world has recorded, with who is standing in it right now.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            Spacer(Modifier.height(8.dp))
        }
        roots.forEach { root ->
            item { LocationNode(root, state, 0, onLocation) }
            descendants(root, state).forEach { (child, depth) ->
                item { LocationNode(child, state, depth, onLocation) }
            }
        }
        if (state.locations.isEmpty()) {
            item { EmptyState("No map yet", "Places are recorded as you discover them.") }
        }
    }
}

@Composable
private fun MapHeader(drawn: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "World map",
            style = MaterialTheme.typography.titleLarge,
            color = NarrateColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Pill("drawn", drawn) { onChange(true) }
        Spacer(Modifier.width(6.dp))
        Pill("list", !drawn) { onChange(false) }
    }
}

private fun descendants(
    parent: LocationEntity,
    state: CodexUiState,
    depth: Int = 1
): List<Pair<LocationEntity, Int>> =
    state.locations.filter { it.parentId == parent.id }.flatMap { child ->
        listOf(child to depth) + descendants(child, state, depth + 1)
    }

@Composable
private fun LocationNode(
    location: LocationEntity,
    state: CodexUiState,
    depth: Int,
    onLocation: (String) -> Unit
) {
    val occupants = state.characters.filter { it.currentLocationId == location.id && it.status == "ALIVE" }
    val isHere = state.world?.currentLocationId == location.id
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
            .background(
                if (isHere) NarrateColors.Accent.copy(alpha = 0.12f) else NarrateColors.Surface,
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (isHere) NarrateColors.Accent.copy(alpha = 0.5f) else NarrateColors.Divider,
                RoundedCornerShape(6.dp)
            )
            .clickable { onLocation(location.id) }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                location.name,
                style = MaterialTheme.typography.titleMedium,
                color = NarrateColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Pill(location.type.lowercase())
            if (isHere) {
                Spacer(Modifier.width(6.dp))
                Pill("you are here", selected = true)
            }
        }
        if (location.currentState.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(location.currentState, style = MaterialTheme.typography.bodySmall, color = NarrateColors.Gold)
        }
        if (occupants.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                occupants.joinToString(", ") { if (it.isPlayer) "${it.name} (you)" else it.name },
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextSecondary
            )
        }
        val exits = state.links
            .filter { it.fromId == location.id || it.toId == location.id }
            .map { state.locationName(if (it.fromId == location.id) it.toId else it.fromId) }
            .distinct()
        if (exits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Connects to: " + exits.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ObjectsTab(state: CodexUiState, viewModel: CodexViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.items) { item ->
            val holder = state.characters.firstOrNull { it.id == item.holderId }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
                    Text(
                        "With " + (holder?.let { if (it.isPlayer) "you" else it.name } ?: state.locationName(item.locationId)),
                        style = MaterialTheme.typography.bodySmall,
                        color = NarrateColors.TextMuted
                    )
                    if (item.significance.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(item.significance, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextSecondary)
                    }
                    if (item.state.isNotBlank()) {
                        Text("Condition: ${item.state}", style = MaterialTheme.typography.bodySmall, color = NarrateColors.Gold)
                    }
                }
                TextButton(
                    onClick = { viewModel.generateItemImage(item.id) },
                    enabled = !state.generating
                ) {
                    Text("Draw", color = NarrateColors.Accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (state.items.isEmpty()) {
            item { EmptyState("Nothing tracked yet", "Objects that matter are recorded as they appear.") }
        }
    }
}

@Composable
private fun ThreadsTab(state: CodexUiState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.threads.sortedWith(compareBy({ it.status != "ACTIVE" }, { -it.urgency }))) { thread ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NarrateColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Pill(thread.status.lowercase(), selected = thread.status == "ACTIVE")
                }
                if (thread.description.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(thread.description, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextSecondary)
                }
                if (thread.nextBeat.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "If you do nothing: ${thread.nextBeat}",
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = NarrateColors.Gold
                    )
                }
                if (thread.involvedNames.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text("Involves: ${thread.involvedNames}", style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
                }
            }
        }
        if (state.threads.isEmpty()) {
            item { EmptyState("Nothing in motion yet", "Plots appear here as the world sets them running.") }
        }
    }
}

@Composable
private fun JournalTab(state: CodexUiState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.chapters) { chapter ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text(chapter.title, style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
                Text(
                    "Turns ${chapter.fromTurn}-${chapter.toTurn} - ${chapter.storyTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.Accent
                )
                Spacer(Modifier.height(8.dp))
                Text(chapter.summary, style = MaterialTheme.typography.bodyLarge, color = NarrateColors.TextSecondary)
            }
        }
        item {
            Text("Turn log", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
        }
        items(state.turns.reversed()) { turn ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "Turn ${turn.index} - ${turn.storyTime} - ${turn.locationName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted
                )
                if (turn.playerInput.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        turn.playerInput,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = NarrateColors.Accent
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(turn.summary, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
            }
        }
        if (state.chapters.isEmpty() && state.turns.isEmpty()) {
            item { EmptyState("Nothing written yet", "The journal fills as you play.") }
        }
    }
}

@Composable
private fun MemoryTab(state: CodexUiState, viewModel: CodexViewModel) {
    val filter by viewModel.memoryFilter.collectAsStateWithLifecycle()
    val visible = state.memories.filter {
        filter.isBlank() || it.text.contains(filter, true) || it.subjectNames.contains(filter, true)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            NarrateField(filter, viewModel::setMemoryFilter, "Search the world's memory")
        }
        Text(
            "${state.memories.size} facts on record. Pinned ones are always in the narrator's context.",
            style = MaterialTheme.typography.bodySmall,
            color = NarrateColors.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible) { memory ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(memory.kind.lowercase())
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "turn ${memory.turnIndex} - ${memory.storyTime}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NarrateColors.TextMuted
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(memory.text, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
                    }
                    IconButton(onClick = { viewModel.togglePin(memory) }) {
                        Icon(
                            Icons.Default.PushPin,
                            "Pin",
                            tint = if (memory.pinned) NarrateColors.Accent else NarrateColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item { EmptyState("Nothing remembered yet", "Facts are recorded automatically as the world turns.") }
            }
        }
    }
}

/** What this particular world has cost to run. */
@Composable
private fun UsageTab(state: CodexUiState) {
    val total = remember(state.usage) { UsageRecorder.summarise(state.usage) }
    val byModel = remember(state.usage) { UsageRecorder.byModel(state.usage) }
    val byPurpose = remember(state.usage) { UsageRecorder.byPurpose(state.usage) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "What ${state.world?.name.orEmpty()} has used. Cost is estimated against list prices " +
                    "recorded on ${ModelPricing.AS_OF}; see Settings for the total across every world.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
        }
        if (state.usage.isEmpty()) {
            item { EmptyState("Nothing used yet", "Usage is recorded from the first turn you play.") }
            return@LazyColumn
        }
        item { UsageTotals(total) }
        item {
            val perTurn = if (state.turns.isEmpty()) 0.0 else total.cost / state.turns.size
            Text(
                "About ${ModelPricing.money(perTurn)} per turn across ${state.turns.size} turns.",
                style = MaterialTheme.typography.bodyMedium,
                color = NarrateColors.TextSecondary
            )
        }
        item { UsageBreakdown("By model", byModel) }
        item { UsageBreakdown("By activity", byPurpose) }
    }
}

@Composable
private fun ContinuityTab(state: CodexUiState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Every time the narrator contradicted the save file, the guard caught it here and told the " +
                    "narrator so it would not happen again.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            Spacer(Modifier.height(6.dp))
        }
        items(state.issues) { issue ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                    .border(
                        1.dp,
                        when (issue.severity) {
                            "BLOCKED" -> NarrateColors.Accent.copy(alpha = 0.5f)
                            "WARNING" -> NarrateColors.Gold.copy(alpha = 0.4f)
                            else -> NarrateColors.Divider
                        },
                        RoundedCornerShape(6.dp)
                    )
                    .padding(10.dp)
            ) {
                Row {
                    Pill(issue.severity.lowercase())
                    Spacer(Modifier.width(6.dp))
                    Pill(issue.category)
                    Spacer(Modifier.width(6.dp))
                    Text("turn ${issue.turnIndex}", style = MaterialTheme.typography.labelSmall, color = NarrateColors.TextMuted)
                }
                Spacer(Modifier.height(6.dp))
                Text(issue.description, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(issue.resolution, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
            }
        }
        if (state.issues.isEmpty()) {
            item { EmptyState("Nothing to report", "The world has stayed consistent so far.") }
        }
    }
}
