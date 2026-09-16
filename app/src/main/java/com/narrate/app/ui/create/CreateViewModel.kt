package com.narrate.app.ui.create

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.narrate.app.container
import com.narrate.app.engine.CharacterConcept
import com.narrate.app.engine.WorldConcept
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CreateStep { WORLD_DIRECTION, WORLD_DETAILS, CHARACTER_DIRECTION, CHARACTER_DETAILS, BUILDING }

data class CreateUiState(
    val step: CreateStep = CreateStep.WORLD_DIRECTION,
    val worldPrompt: String = "",
    val worldConcepts: List<WorldConcept> = emptyList(),
    val world: WorldConcept = WorldConcept(),
    val narrationLength: String = "LONG",
    val contentGuidelines: String = "",
    val characterPrompt: String = "",
    val characterConcepts: List<CharacterConcept> = emptyList(),
    val character: CharacterConcept = CharacterConcept(),
    val generating: Boolean = false,
    val buildingStage: String = "",
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

    fun setWorldPrompt(value: String) = update { it.copy(worldPrompt = value) }
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

    fun generateWorldConcepts() {
        viewModelScope.launch {
            update { it.copy(generating = true, error = null) }
            val result = container.worldForge.worldConcepts(_state.value.worldPrompt)
            update { current ->
                result.fold(
                    onSuccess = { current.copy(generating = false, worldConcepts = it) },
                    onFailure = { current.copy(generating = false, error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    fun chooseWorldConcept(concept: WorldConcept) = update {
        it.copy(world = concept, step = CreateStep.WORLD_DETAILS)
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
        viewModelScope.launch {
            update { it.copy(generating = true, error = null) }
            val current = _state.value
            val worldEntity = com.narrate.app.data.entity.WorldEntity(
                name = current.world.name,
                genre = current.world.genre,
                tone = current.world.tone,
                premise = current.world.premise,
                history = current.world.history,
                rules = current.world.rules,
                customPrompt = current.worldPrompt
            )
            val result = container.worldForge.characterConcepts(worldEntity, current.characterPrompt)
            update { state ->
                result.fold(
                    onSuccess = { state.copy(generating = false, characterConcepts = it) },
                    onFailure = { state.copy(generating = false, error = it.message ?: "Could not reach the model.") }
                )
            }
        }
    }

    fun chooseCharacterConcept(concept: CharacterConcept) = update {
        it.copy(character = concept, step = CreateStep.CHARACTER_DETAILS)
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
        viewModelScope.launch {
            val current = _state.value
            update { it.copy(step = CreateStep.BUILDING, generating = true, error = null, buildingStage = "Drawing the map...") }
            val build = container.worldForge.buildWorld(current.world, current.worldPrompt, current.character)
            if (build.isFailure) {
                update {
                    it.copy(
                        generating = false,
                        step = CreateStep.CHARACTER_DETAILS,
                        error = build.exceptionOrNull()?.message ?: "The world could not be built."
                    )
                }
                return@launch
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
                    build = build.getOrNull()
                )
            }
            update { state ->
                world.fold(
                    onSuccess = { state.copy(generating = false, createdWorldId = it.id, buildingStage = "Ready.") },
                    onFailure = {
                        state.copy(
                            generating = false,
                            step = CreateStep.CHARACTER_DETAILS,
                            error = it.message ?: "The world could not be saved."
                        )
                    }
                )
            }
        }
    }

    private fun update(block: (CreateUiState) -> CreateUiState) {
        _state.value = block(_state.value)
    }
}
