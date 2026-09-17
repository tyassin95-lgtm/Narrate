package com.narrate.app.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.narrate.app.data.entity.ImageEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ImageSubject
import com.narrate.app.ui.components.Avatar
import com.narrate.app.ui.components.ErrorBanner
import com.narrate.app.ui.markup.RichNarration
import com.narrate.app.ui.theme.NarrateColors
import java.io.File

/**
 * The gameplay screen: narration first, everything else a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    onOpenCodex: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val issues by viewModel.lastIssues.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var speaking by remember { mutableStateOf(false) }
    // The suggestion currently sitting in the box, so an unedited one is still recorded as a
    // choice while anything the player changes counts as their own words.
    var pendingChoice by remember { mutableStateOf<Choice?>(null) }
    var suggestionsOpen by remember { mutableStateOf(true) }
    var showImageSheet by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { viewModel.ensureOpening() }
    LaunchedEffect(state.turns.size, state.loading) {
        val target = (state.turns.size + if (state.loading) 1 else 0) - 1
        if (target >= 0) listState.animateScrollToItem(target.coerceAtLeast(0))
    }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            PlayTopBar(
                title = state.world?.name.orEmpty(),
                storyTime = state.world?.storyTime.orEmpty(),
                place = state.currentLocation?.name.orEmpty(),
                issueCount = issues.size,
                onBack = onBack,
                onCodex = onOpenCodex,
                onAlbum = onOpenAlbum,
                onIssues = { showIssues = true }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            ErrorBanner(state.error ?: state.notice) { viewModel.dismissError() }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                itemsIndexed(state.turns, key = { _, turn -> turn.id }) { index, turn ->
                    TurnBlock(
                        turn = turn,
                        images = state.images.filter { it.turnIndex == turn.index },
                        showSeparator = index > 0,
                        onOpenImage = onOpenImage
                    )
                }
                if (state.loading) {
                    item { ThinkingIndicator() }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            PresenceBar(
                names = state.presentCharacters.map { it.id to it.name },
                portraits = state.presentCharacters.associate { character ->
                    character.id to state.images.firstOrNull { it.id == character.portraitImageId }?.filePath
                },
                place = state.currentLocation?.name.orEmpty(),
                onCharacter = onOpenCharacter
            )

            AnimatedVisibility(visible = state.currentChoices.isNotEmpty() && !state.loading) {
                SuggestedActions(
                    choices = state.currentChoices,
                    expanded = suggestionsOpen,
                    onToggle = { suggestionsOpen = !suggestionsOpen },
                    onChoose = { choice ->
                        // Load it for editing rather than sending it. The player decides.
                        input = viewModel.choiceText(choice)
                        pendingChoice = choice
                        speaking = choice.kind == "SPEECH"
                        runCatching { focusRequester.requestFocus() }
                    }
                )
            }

            InputBar(
                value = input,
                onValueChange = {
                    input = it
                    if (pendingChoice != null && it != viewModel.choiceText(pendingChoice!!)) {
                        pendingChoice = null
                    }
                },
                speaking = speaking,
                onSpeakingChange = { speaking = it },
                enabled = !state.loading,
                generatingImage = state.generatingImage,
                focusRequester = focusRequester,
                onSend = {
                    viewModel.submit(input, viewModel.kindFor(input, pendingChoice, speaking))
                    input = ""
                    pendingChoice = null
                },
                onImage = { showImageSheet = true }
            )
        }
    }

    if (showImageSheet) {
        ImageSheet(
            state = state,
            onDismiss = { showImageSheet = false },
            onGenerate = { subject, direction ->
                viewModel.generateImage(subject, direction)
                showImageSheet = false
            },
            onIllustrateMoment = {
                viewModel.illustrateLastMoment()
                showImageSheet = false
            }
        )
    }

    if (showIssues) {
        ContinuityDialog(issues) { showIssues = false }
    }
}

@Composable
private fun PlayTopBar(
    title: String,
    storyTime: String,
    place: String,
    issueCount: Int,
    onBack: () -> Unit,
    onCodex: () -> Unit,
    onAlbum: () -> Unit,
    onIssues: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(NarrateColors.Background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, "Leave world", tint = NarrateColors.TextSecondary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NarrateColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(storyTime, place).filter { it.isNotBlank() }.joinToString("  -  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (issueCount > 0) {
                IconButton(onClick = onIssues) {
                    BadgedBox(badge = { Badge(containerColor = NarrateColors.Gold) { Text("$issueCount") } }) {
                        Icon(Icons.Default.Warning, "Continuity notes", tint = NarrateColors.Gold)
                    }
                }
            }
            IconButton(onClick = onAlbum) {
                Icon(Icons.Default.Image, "Album", tint = NarrateColors.TextSecondary)
            }
            IconButton(onClick = onCodex) {
                Icon(Icons.Default.Menu, "World information", tint = NarrateColors.TextSecondary)
            }
        }
        HorizontalDivider(color = NarrateColors.Divider)
    }
}

@Composable
private fun TurnBlock(
    turn: TurnEntity,
    images: List<ImageEntity>,
    showSeparator: Boolean,
    onOpenImage: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (showSeparator && turn.playerInput.isBlank()) {
            HorizontalDivider(
                Modifier.padding(bottom = 24.dp),
                color = NarrateColors.Divider.copy(alpha = 0.5f)
            )
        }
        if (turn.playerInput.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 22.dp)
                    .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
                    .border(1.dp, NarrateColors.Divider, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .heightIn(min = 18.dp)
                        .background(NarrateColors.Accent)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        when (turn.inputType) {
                            "SPEECH" -> "YOU SAY"
                            "CHOICE" -> "YOU CHOOSE"
                            else -> "YOU"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NarrateColors.Accent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        turn.playerInput,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = NarrateColors.TextSecondary
                    )
                }
            }
        }
        RichNarration(turn.narration, Modifier.fillMaxWidth())
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images) { image ->
                    Column(
                        Modifier
                            .width(220.dp)
                            .clickable { onOpenImage(image.id) }
                    ) {
                        if (File(image.filePath).exists()) {
                            AsyncImage(
                                model = File(image.filePath),
                                contentDescription = image.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
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
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = NarrateColors.Accent
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "The world moves...",
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = NarrateColors.TextMuted
        )
    }
}

@Composable
private fun PresenceBar(
    names: List<Pair<String, String>>,
    portraits: Map<String, String?>,
    place: String,
    onCharacter: (String) -> Unit
) {
    if (names.isEmpty() && place.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Place, null, tint = NarrateColors.TextMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            place.ifBlank { "Nowhere yet" },
            style = MaterialTheme.typography.labelSmall,
            color = NarrateColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp)
        )
        Spacer(Modifier.width(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(names) { (id, name) ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NarrateColors.SurfaceHigh)
                        .clickable { onCharacter(id) }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(name, portraits[id], size = 24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, color = NarrateColors.TextSecondary)
                }
            }
        }
    }
}

/**
 * The suggestions for this turn.
 *
 * Tapping one writes it into the input box instead of sending it, so the player can read the
 * whole thing, rewrite as much of it as they like, and send when they mean to. Long options
 * are clamped here and shown in full once they are in the box.
 */
@Composable
private fun SuggestedActions(
    choices: List<Choice>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChoose: (Choice) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SUGGESTED ACTIONS - ${choices.size}",
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.TextMuted,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "HIDE" else "SHOW",
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.Accent
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NarrateColors.SurfaceElevated)
                            .border(1.dp, NarrateColors.Divider, RoundedCornerShape(8.dp))
                            .clickable { onChoose(choice) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when (choice.kind) {
                                        "SPEECH" -> NarrateColors.Gold
                                        "OBSERVE" -> NarrateColors.SystemAccent
                                        else -> NarrateColors.Accent
                                    }
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                choice.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NarrateColors.TextPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (choice.detail.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    choice.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NarrateColors.TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Edit,
                            "Put this in the box to edit",
                            tint = NarrateColors.TextMuted,
                            modifier = Modifier.size(15.dp).padding(top = 2.dp)
                        )
                    }
                }
                Text(
                    "Tap one to load it for editing. Nothing is sent until you press send.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    speaking: Boolean,
    onSpeakingChange: (Boolean) -> Unit,
    enabled: Boolean,
    generatingImage: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onImage: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeToggle("ACT", !speaking) { onSpeakingChange(false) }
            Spacer(Modifier.width(6.dp))
            ModeToggle("SPEAK", speaking) { onSpeakingChange(true) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onImage, enabled = !generatingImage) {
                if (generatingImage) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NarrateColors.Accent)
                } else {
                    Icon(Icons.Default.Image, "Generate an image", tint = NarrateColors.Accent)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = {
                    Text(
                        if (speaking) "Say anything..." else "Do anything...",
                        color = NarrateColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                maxLines = 8,
                enabled = enabled,
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NarrateColors.Accent,
                    unfocusedBorderColor = NarrateColors.Divider,
                    focusedContainerColor = NarrateColors.SurfaceElevated,
                    unfocusedContainerColor = NarrateColors.SurfaceElevated,
                    focusedTextColor = NarrateColors.TextPrimary,
                    unfocusedTextColor = NarrateColors.TextPrimary,
                    cursorColor = NarrateColors.Accent
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { onSend() },
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = NarrateColors.Accent,
                    disabledContainerColor = NarrateColors.SurfaceHigh
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ModeToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) Color.White else NarrateColors.TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) NarrateColors.Accent else NarrateColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSheet(
    state: PlayUiState,
    onDismiss: () -> Unit,
    onGenerate: (ImageSubject, String) -> Unit,
    onIllustrateMoment: () -> Unit
) {
    var direction by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NarrateColors.Surface,
        contentColor = NarrateColors.TextPrimary
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Generate an image", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Built from the current state of the world, seeded with what has already been drawn.",
                style = MaterialTheme.typography.bodySmall,
                color = NarrateColors.TextMuted
            )
            Spacer(Modifier.height(16.dp))
            SheetAction(Icons.Default.AutoStories, "This scene", "Everyone and everything in frame right now") {
                onGenerate(ImageSubject.CurrentScene, direction)
            }
            SheetAction(Icons.Default.Image, "This moment", "The beat the narrator just described") {
                onIllustrateMoment()
            }
            state.player?.let { player ->
                SheetAction(Icons.Default.Person, player.name, "Your character as they are now") {
                    onGenerate(ImageSubject.Character(player.id), direction)
                }
            }
            state.currentLocation?.let { location ->
                SheetAction(Icons.Default.Place, location.name, "This place as it stands") {
                    onGenerate(ImageSubject.Location(location.id), direction)
                }
            }
            state.presentCharacters.forEach { character ->
                SheetAction(Icons.Default.Person, character.name, character.role.ifBlank { "Present here" }) {
                    onGenerate(ImageSubject.Character(character.id), direction)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = direction,
                onValueChange = { direction = it },
                label = { Text("Extra direction (optional)") },
                placeholder = { Text("close on her hands, rain on the window", color = NarrateColors.TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NarrateColors.Accent,
                    unfocusedBorderColor = NarrateColors.Divider,
                    focusedContainerColor = NarrateColors.SurfaceElevated,
                    unfocusedContainerColor = NarrateColors.SurfaceElevated,
                    focusedTextColor = NarrateColors.TextPrimary,
                    unfocusedTextColor = NarrateColors.TextPrimary,
                    cursorColor = NarrateColors.Accent
                )
            )
            if (direction.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onGenerate(ImageSubject.Custom(direction), "") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NarrateColors.Accent)
                ) {
                    Text("Generate from this direction")
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = NarrateColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
        }
    }
}

@Composable
private fun ContinuityDialog(issues: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NarrateColors.SurfaceElevated,
        title = { Text("Continuity watch", color = NarrateColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "The guard caught these on the last turn and corrected the world where it could. " +
                        "The narrator has been told about them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NarrateColors.TextMuted
                )
                Spacer(Modifier.height(10.dp))
                issues.forEach {
                    Text("- $it", style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = NarrateColors.Accent) }
        }
    )
}
