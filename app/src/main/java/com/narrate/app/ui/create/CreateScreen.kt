package com.narrate.app.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.WorldConcept
import com.narrate.app.ui.components.*
import com.narrate.app.ui.theme.NarrateColors

/**
 * World and character creation. Generated concepts are optional material at every step;
 * anything the player writes here is treated as canon the narrator may not overrule.
 */
@Composable
fun CreateScreen(
    viewModel: CreateViewModel,
    onFinished: (String) -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdWorldId) {
        state.createdWorldId?.let(onFinished)
    }

    Scaffold(
        containerColor = NarrateColors.Background,
        topBar = {
            NarrateTopBar(
                title = when (state.step) {
                    CreateStep.WORLD_DIRECTION -> "Create a world"
                    CreateStep.WORLD_DETAILS -> "Shape the world"
                    CreateStep.CHARACTER_DIRECTION -> "Create your character"
                    CreateStep.CHARACTER_DETAILS -> "Shape your character"
                    CreateStep.BUILDING -> "Building"
                },
                onBack = { if (state.step == CreateStep.WORLD_DIRECTION) onCancel() else viewModel.back() }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StepIndicator(state.step)
            ErrorBanner(state.error) { viewModel.dismissError() }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (state.step) {
                    CreateStep.WORLD_DIRECTION -> WorldDirectionStep(state, viewModel)
                    CreateStep.WORLD_DETAILS -> WorldDetailsStep(state, viewModel)
                    CreateStep.CHARACTER_DIRECTION -> CharacterDirectionStep(state, viewModel)
                    CreateStep.CHARACTER_DETAILS -> CharacterDetailsStep(state, viewModel)
                    CreateStep.BUILDING -> BuildingStep(state)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun StepIndicator(step: CreateStep) {
    val steps = listOf(
        CreateStep.WORLD_DIRECTION,
        CreateStep.WORLD_DETAILS,
        CreateStep.CHARACTER_DIRECTION,
        CreateStep.CHARACTER_DETAILS,
        CreateStep.BUILDING
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        steps.forEach { entry ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        if (steps.indexOf(entry) <= steps.indexOf(step)) NarrateColors.Accent
                        else NarrateColors.Divider,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun WorldDirectionStep(state: CreateUiState, viewModel: CreateViewModel) {
    Text(
        "Describe the world you want to live in.",
        style = MaterialTheme.typography.headlineMedium,
        color = NarrateColors.TextPrimary
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Write as much or as little as you like: setting, era, genre, tone, factions, magic or technology, " +
            "the rules it runs on, what you want it to feel like. Everything you write here becomes canon " +
            "the narrator is bound to.",
        style = MaterialTheme.typography.bodyMedium,
        color = NarrateColors.TextSecondary
    )
    Spacer(Modifier.height(16.dp))
    NarrateField(
        value = state.worldPrompt,
        onValueChange = viewModel::setWorldPrompt,
        label = "Your world, in your words",
        placeholder = "A drowned industrial city where the tides run on a timetable nobody set...",
        minLines = 6,
        maxLines = 20
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
            text = if (state.worldConcepts.isEmpty()) "Suggest worlds" else "Suggest again",
            loading = state.generating,
            modifier = Modifier.weight(1f)
        ) { viewModel.generateWorldConcepts() }
        SecondaryButton("Write it myself", Modifier.weight(1f)) { viewModel.authorWorldManually() }
    }
    Spacer(Modifier.height(20.dp))
    state.worldConcepts.forEach { concept ->
        ConceptCard(
            title = concept.name,
            tagline = concept.tagline,
            lines = listOfNotNull(
                concept.genre.takeIf { it.isNotBlank() }?.let { "Genre: $it" },
                concept.tone.takeIf { it.isNotBlank() }?.let { "Tone: $it" },
                concept.premise.takeIf { it.isNotBlank() }
            ),
            onSelect = { viewModel.chooseWorldConcept(concept) }
        )
    }
}

@Composable
private fun WorldDetailsStep(state: CreateUiState, viewModel: CreateViewModel) {
    val world = state.world
    Text("Every field is yours to rewrite.", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    NarrateField(world.name, { value -> viewModel.editWorld { it.copy(name = value) } }, "World name")
    Spacer(Modifier.height(10.dp))
    NarrateField(world.tagline, { value -> viewModel.editWorld { it.copy(tagline = value) } }, "Tagline")
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            NarrateField(world.genre, { value -> viewModel.editWorld { it.copy(genre = value) } }, "Genre")
        }
        Box(Modifier.weight(1f)) {
            NarrateField(world.tone, { value -> viewModel.editWorld { it.copy(tone = value) } }, "Tone")
        }
    }
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.premise, { value -> viewModel.editWorld { it.copy(premise = value) } },
        "Premise", minLines = 4, supporting = "The situation you are dropped into."
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.history, { value -> viewModel.editWorld { it.copy(history = value) } },
        "History", minLines = 3, supporting = "What already happened here."
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.rules, { value -> viewModel.editWorld { it.copy(rules = value) } },
        "Laws of the world", minLines = 3,
        supporting = "Physics, magic, technology, taboos. The narrator may never break these."
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.themes, { value -> viewModel.editWorld { it.copy(themes = value) } },
        "Themes", minLines = 2
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.artStyle, { value -> viewModel.editWorld { it.copy(artStyle = value) } },
        "Visual style", minLines = 2,
        supporting = "The look of every image generated in this world."
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(
        world.openingSituation, { value -> viewModel.editWorld { it.copy(openingSituation = value) } },
        "Opening situation", minLines = 3,
        supporting = "Where and how your first scene begins."
    )
    Spacer(Modifier.height(16.dp))
    Text("Narration length", style = MaterialTheme.typography.labelLarge, color = NarrateColors.TextSecondary)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("SHORT" to "Tight", "LONG" to "Full", "EPIC" to "Epic").forEach { (value, label) ->
            Pill(label, state.narrationLength == value) { viewModel.setNarrationLength(value) }
        }
    }
    Spacer(Modifier.height(14.dp))
    NarrateField(
        state.contentGuidelines, viewModel::setContentGuidelines,
        "Content direction (optional)", minLines = 2,
        supporting = "Anything you want emphasised or kept out of your story."
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Next: your character", Modifier.fillMaxWidth(), enabled = world.name.isNotBlank()) {
        viewModel.goTo(CreateStep.CHARACTER_DIRECTION)
    }
    if (world.name.isBlank()) {
        Spacer(Modifier.height(6.dp))
        Text("Give the world a name to continue.", style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
    }
}

@Composable
private fun CharacterDirectionStep(state: CreateUiState, viewModel: CreateViewModel) {
    Text("Who are you in ${state.world.name.ifBlank { "this world" }}?", style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
    Spacer(Modifier.height(6.dp))
    Text(
        "Describe your character however you want: their history, their work, their debts, their face. " +
            "What you write is permanent - the narrator cannot recast you.",
        style = MaterialTheme.typography.bodyMedium,
        color = NarrateColors.TextSecondary
    )
    Spacer(Modifier.height(16.dp))
    NarrateField(
        value = state.characterPrompt,
        onValueChange = viewModel::setCharacterPrompt,
        label = "Your character, in your words",
        placeholder = "A tidal engineer who signed something she did not read...",
        minLines = 6,
        maxLines = 20
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
            text = if (state.characterConcepts.isEmpty()) "Suggest characters" else "Suggest again",
            loading = state.generating,
            modifier = Modifier.weight(1f)
        ) { viewModel.generateCharacterConcepts() }
        SecondaryButton("Write it myself", Modifier.weight(1f)) { viewModel.authorCharacterManually() }
    }
    Spacer(Modifier.height(20.dp))
    state.characterConcepts.forEach { concept ->
        ConceptCard(
            title = concept.name,
            tagline = concept.role,
            lines = listOfNotNull(
                concept.summary.takeIf { it.isNotBlank() },
                concept.tiesToWorld.takeIf { it.isNotBlank() }?.let { "Ties: $it" }
            ),
            onSelect = { viewModel.chooseCharacterConcept(concept) }
        )
    }
}

@Composable
private fun CharacterDetailsStep(state: CreateUiState, viewModel: CreateViewModel) {
    val character = state.character
    Text("Your character", style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    NarrateField(character.name, { value -> viewModel.editCharacter { it.copy(name = value) } }, "Name")
    Spacer(Modifier.height(10.dp))
    NarrateField(character.role, { value -> viewModel.editCharacter { it.copy(role = value) } }, "Role in the world")
    Spacer(Modifier.height(10.dp))
    NarrateField(character.summary, { value -> viewModel.editCharacter { it.copy(summary = value) } }, "Summary", minLines = 3)
    Spacer(Modifier.height(10.dp))
    NarrateField(character.personality, { value -> viewModel.editCharacter { it.copy(personality = value) } }, "Personality", minLines = 3)
    Spacer(Modifier.height(10.dp))
    NarrateField(character.backstory, { value -> viewModel.editCharacter { it.copy(backstory = value) } }, "Backstory", minLines = 4)
    Spacer(Modifier.height(10.dp))
    NarrateField(
        character.appearance, { value -> viewModel.editCharacter { it.copy(appearance = value) } },
        "Appearance", minLines = 4,
        supporting = "This is locked in as your permanent visual identity and is used as the reference for every image of you."
    )
    Spacer(Modifier.height(10.dp))
    NarrateField(character.outfit, { value -> viewModel.editCharacter { it.copy(outfit = value) } }, "What you are wearing", minLines = 2)
    Spacer(Modifier.height(10.dp))
    NarrateField(character.voice, { value -> viewModel.editCharacter { it.copy(voice = value) } }, "How you speak", minLines = 2)
    Spacer(Modifier.height(10.dp))
    NarrateField(character.goals, { value -> viewModel.editCharacter { it.copy(goals = value) } }, "Goals", minLines = 2)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            NarrateField(character.fears, { value -> viewModel.editCharacter { it.copy(fears = value) } }, "Fears", minLines = 2)
        }
        Box(Modifier.weight(1f)) {
            NarrateField(character.secrets, { value -> viewModel.editCharacter { it.copy(secrets = value) } }, "Secrets", minLines = 2)
        }
    }
    Spacer(Modifier.height(10.dp))
    NarrateField(
        character.startingItems.joinToString(", "),
        { value -> viewModel.editCharacter { it.copy(startingItems = value.split(",").map { item -> item.trim() }.filter { item -> item.isNotBlank() }) } },
        "What you carry",
        supporting = "Separate with commas."
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        "Build this world",
        Modifier.fillMaxWidth(),
        enabled = character.name.isNotBlank()
    ) { viewModel.build() }
    if (character.name.isBlank()) {
        Spacer(Modifier.height(6.dp))
        Text("Give your character a name to continue.", style = MaterialTheme.typography.bodySmall, color = NarrateColors.TextMuted)
    }
}

@Composable
private fun BuildingStep(state: CreateUiState) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = NarrateColors.Accent)
        Spacer(Modifier.height(20.dp))
        Text(
            state.buildingStage.ifBlank { "Building the world..." },
            style = MaterialTheme.typography.titleLarge,
            color = NarrateColors.TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Drawing the map, placing the people who already live here, and setting in motion the things " +
                "that were happening before you arrived.",
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = NarrateColors.TextMuted
        )
    }
}

@Composable
private fun ConceptCard(title: String, tagline: String, lines: List<String>, onSelect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(NarrateColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, NarrateColors.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(14.dp)
    ) {
        Text(title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, color = NarrateColors.TextPrimary)
        if (tagline.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(tagline, style = MaterialTheme.typography.bodySmall, color = NarrateColors.Accent)
        }
        lines.forEach { line ->
            Spacer(Modifier.height(8.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Text("USE THIS AND EDIT IT", style = MaterialTheme.typography.labelSmall, color = NarrateColors.Accent)
    }
}
