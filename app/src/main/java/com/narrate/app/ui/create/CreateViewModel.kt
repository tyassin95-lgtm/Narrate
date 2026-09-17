package com.narrate.app.ui.create

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.narrate.app.container
import com.narrate.app.engine.ConceptCompleteness
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.PlayStyle
import com.narrate.app.engine.WorldConcept
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CreateStep { WORLD_DIRECTION, WORLD_DETAILS, CHARACTER_DIRECTION, CHARACTER_DETAILS, BUILDING }

data class CreateUiState(
    val step: CreateStep = CreateStep.WORLD_DIRECTION,
    val worldPrompt: String = "",
    val playStyle: PlayStyle = PlayStyle.BALANCED,
    val worldConcepts: List<WorldConcept> = emptyList(),
    val world: WorldConcept = WorldConcept(),
    val narrationLength: String = "LONG",
    val contentGuidelines: String = "",
    val characterPrompt: String = "",
    val characterConcepts: List<CharacterConcept> = emptyList(),
    val character: CharacterConcept = CharacterConcept(),
    val generating: Boolean = false,
    val buildingStage: String = "",
    /** Seconds the current generation has been running, so a slow call never looks stuck. */
    val elapsedSeconds: Int = 0,
    val error: String? = null,
    val createdWorldId: String? = null
)

/**
 * Creation is a conversation, not a form. The player can type their whole world and skip
 * every generated suggestion, or take a concept and rewrite any field of it.
 */
class CreateViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    /** The generation currently in flight, so the player can give up on it. */
    private var runningJob: Job? = null
    private var tickerJob: Job? = null

    /**
     * Runs one generation call with a visible clock and a working cancel.
     *
     * A model that takes four minutes is not broken, but a screen that says nothing for four
     * minutes looks it. The elapsed count and the cancel button are what separate the two.
     */
    private fun generate(stage: String, block: suspend () -> Unit) {
        if (runningJob?.isActive == true) return
        update { it.copy(generating = true, error = null, buildingStage = stage, elapsedSeconds = 0) }
        tickerJob = viewModelScope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                seconds++
                update { if (it.generating) it.copy(elapsedSeconds = seconds) else it }
            }
        }
        runningJob = viewModelScope.launch {
            try {
                block()
            } finally {
                tickerJob?.cancel()
                update { it.copy(generating = false) }
            }
        }
    }

    /** Abandon a generation that is taking longer than the player is willing to wait. */
    fun cancelGeneration() {
        if (runningJob?.isActive != true) return
        runningJob?.cancel()
        tickerJob?.cancel()
        runningJob = null
        update {
            it.copy(
                generating = false,
                elapsedSeconds = 0,
                step = if (it.step == CreateStep.BUILDING) CreateStep.CHARACTER_DETAILS else it.step,
                error = "Generation cancelled."
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        runningJob?.cancel()
        tickerJob?.cancel()
    }

    fun setWorldPrompt(value: String) = update { it.copy(worldPrompt = value) }
    fun setPlayStyle(value: PlayStyle) = update { it.copy(playStyle = value) }
    fun setCharacterPrompt(value: String) = update { it.copy(characterPrompt = value) }
    fun setNarrationLength(value: String) = update { it.copy(narrationLength = value) }
    fun setContentGuidelines(value: String) = update { it.copy(contentGuidelines = value) }
    fun editWorld(block: (WorldConcept) -> WorldConcept) = update { it.copy(world = block(it.world)) }
    fun editCharacter(block: (CharacterConcept) -> CharacterConcept) = update { it.copy(character = block(it.character)) }
    fun dismissError() = update { it.copy(error = null) }

    fun goTo(step: CreateStep) = update { it.copy(step = step) }

    fun back() = update { current ->
        val previous = when (current.step) {
            CreateStep.WORLD_DIRECTION -> CreateStep.WORLD_DIRECTION
            CreateStep.WORLD_DETAILS -> CreateStep.WORLD_DIRECTION
            CreateStep.CHARACTER_DIRECTION -> CreateStep.WORLD_DETAILS
            CreateStep.CHARACTER_DETAILS -> CreateStep.CHARACTER_DIRECTION
            CreateStep.BUILDING -> CreateStep.CHARACTER_DETAILS
        }
        current.copy(step = previous)
    }

    /**
     * The default action whenever the player has written something: their text becomes the
     * world, organised into fields. Nothing they wrote is replaced.
     */
    fun useWhatIWroteForWorld() {
        generate("Shaping your world...") {
            val result = container.worldForge.expandWorld(_state.value.worldPrompt, _state.value.playStyle)
            update { current ->
                result.fold(
                    onSuccess = { current.copy(world = it, step = CreateStep.WORLD_DETAILS) },
                    onFailure = { current.copy(error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    /** The same for the character: expand what they wrote rather than propose someone else. */
    fun useWhatIWroteForCharacter() {
        generate("Shaping your character...") {
            val result = container.worldForge.expandCharacter(worldEntity(), _state.value.characterPrompt)
            update { current ->
                result.fold(
                    onSuccess = { current.copy(character = it, step = CreateStep.CHARACTER_DETAILS) },
                    onFailure = { current.copy(error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    private fun worldEntity(): com.narrate.app.data.entity.WorldEntity {
        val current = _state.value
        return com.narrate.app.data.entity.WorldEntity(
            name = current.world.name,
            genre = current.world.genre,
            tone = current.world.tone,
            premise = current.world.premise,
            history = current.world.history,
            rules = current.world.rules,
            customPrompt = current.worldPrompt,
            authoredCanon = current.worldPrompt,
            playStyle = current.playStyle.id
        )
    }

    fun generateWorldConcepts() {
        generate("Imagining worlds...") {
            val result = container.worldForge.worldConcepts(
                direction = _state.value.worldPrompt,
                playStyle = _state.value.playStyle
            )
            update { current ->
                result.fold(
                    onSuccess = { current.copy(worldConcepts = it) },
                    onFailure = { current.copy(error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    /**
     * Choosing a suggestion is the player deciding to use it instead of what they wrote, so
     * their earlier text stops being canon for this world. Keeping it would mean overriding
     * the choice they just made with the draft they abandoned.
     */
    fun chooseWorldConcept(concept: WorldConcept) = update {
        it.copy(world = concept, worldPrompt = "", step = CreateStep.WORLD_DETAILS)
    }

    /** Skip the suggestions entirely: the player's own text becomes the world. */
    fun authorWorldManually() = update { current ->
        current.copy(
            world = WorldConcept(
                name = current.world.name,
                premise = current.world.premise.ifBlank { current.worldPrompt }
            ),
            step = CreateStep.WORLD_DETAILS
        )
    }

    fun generateCharacterConcepts() {
        generate("Imagining characters...") {
            val result = container.worldForge.characterConcepts(worldEntity(), _state.value.characterPrompt)
            update { state ->
                result.fold(
                    onSuccess = { state.copy(characterConcepts = it) },
                    onFailure = { state.copy(error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    /** As above: a chosen suggestion replaces the draft rather than being overruled by it. */
    fun chooseCharacterConcept(concept: CharacterConcept) = update {
        it.copy(character = concept, characterPrompt = "", step = CreateStep.CHARACTER_DETAILS)
    }

    fun authorCharacterManually() = update { current ->
        current.copy(
            character = CharacterConcept(
                name = current.character.name,
                summary = current.character.summary.ifBlank { current.characterPrompt }
            ),
            step = CreateStep.CHARACTER_DETAILS
        )
    }

    /** Builds the world's starting state and writes the save. */
    fun build() {
        update { it.copy(step = CreateStep.BUILDING) }
        generate("Drawing the map...") {
            // Last chance to notice a field the generation dropped, or one the player skipped
            // past. Filling it now means the world is built on a complete sheet rather than on
            // whatever happened to come back, and anything already written is left alone.
            fillRemainingGaps()
            val current = _state.value
            val build = container.worldForge.buildWorld(
                concept = current.world,
                customPrompt = current.worldPrompt,
                character = current.character,
                playStyle = current.playStyle
            )
            if (build.isFailure) {
                update {
                    it.copy(
                        step = CreateStep.CHARACTER_DETAILS,
                        error = build.exceptionOrNull()?.message ?: "The world could not be built."
                    )
                }
                return@generate
            }
            update { it.copy(buildingStage = "Populating it with people...") }
            val world = runCatching {
                container.worldForge.persist(
                    concept = current.world,
                    customPrompt = current.worldPrompt,
                    narrationLength = current.narrationLength,
                    contentGuidelines = current.contentGuidelines,
                    character = current.character,
                    characterPrompt = current.characterPrompt,
                    build = build.getOrNull(),
                    playStyle = current.playStyle
                )
            }
            update { state ->
                world.fold(
                    onSuccess = { state.copy(createdWorldId = it.id, buildingStage = "Ready.") },
                    onFailure = {
                        state.copy(
                            step = CreateStep.CHARACTER_DETAILS,
                            error = it.message ?: "The world could not be saved."
                        )
                    }
                )
            }
        }
    }

    /**
     * Tops up any world or character field still empty when the player commits.
     *
     * This covers the paths that never went through an expansion at all: a suggestion the
     * player picked, or a sheet they filled in by hand and left parts of blank. Failure here
     * is not fatal - an incomplete world is still theirs to play.
     */
    private suspend fun fillRemainingGaps() {
        val current = _state.value
        val worldGaps = ConceptCompleteness.missingWorldFields(current.world)
        val characterGaps = ConceptCompleteness.missingCharacterFields(current.character)
        if (worldGaps.isEmpty() && characterGaps.isEmpty()) return
        update { it.copy(buildingStage = "Filling in the details...") }
        if (worldGaps.isNotEmpty()) {
            val filled = runCatching {
                container.worldForge.completeWorld(current.world, current.worldPrompt, current.playStyle)
            }.rethrowCancellation()
            if (filled != null) update { it.copy(world = filled) }
        }
        if (characterGaps.isNotEmpty()) {
            val filled = runCatching {
                container.worldForge.completeCharacter(
                    _state.value.character,
                    worldEntity(),
                    _state.value.characterPrompt
                )
            }.rethrowCancellation()
            if (filled != null) update { it.copy(character = filled) }
        }
    }

    private fun update(block: (CreateUiState) -> CreateUiState) {
        _state.value = block(_state.value)
    }
}

/** Swallowing a cancellation would keep a generation the player walked away from alive. */
private fun <T> Result<T>.rethrowCancellation(): T? {
    val failure = exceptionOrNull()
    if (failure is kotlinx.coroutines.CancellationException) throw failure
    return getOrNull()
}
