package com.narrate.app.ui.play

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.narrate.app.container
import com.narrate.app.core.AppJson
import com.narrate.app.data.entity.*
import com.narrate.app.engine.Choice
import com.narrate.app.engine.ImageSubject
import com.narrate.app.ui.markup.MarkupParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class PlayUiState(
    val world: WorldEntity? = null,
    val player: CharacterEntity? = null,
    val turns: List<TurnEntity> = emptyList(),
    val characters: List<CharacterEntity> = emptyList(),
    val locations: List<LocationEntity> = emptyList(),
    val images: List<ImageEntity> = emptyList(),
    val threads: List<ThreadEntity> = emptyList(),
    val loading: Boolean = false,
    val generatingImage: Boolean = false,
    val error: String? = null,
    val notice: String? = null
) {
    val currentChoices: List<Choice>
        get() = turns.lastOrNull()?.let { turn ->
            runCatching {
                AppJson.decodeFromString(ListSerializer(Choice.serializer()), turn.choicesJson)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    val currentLocation: LocationEntity?
        get() = locations.firstOrNull { it.id == world?.currentLocationId }

    val presentCharacters: List<CharacterEntity>
        get() = characters.filter {
            !it.isPlayer && it.currentLocationId == world?.currentLocationId && it.status == "ALIVE"
        }
}

/** Drives one world's play session: the feed, the turn loop, and on-demand imagery. */
class PlayViewModel(application: Application, private val worldId: String) : AndroidViewModel(application) {

    private val container = application.container
    private val repo = container.repository

    private val _transient = MutableStateFlow(
        PlayUiState(loading = false)
    )

    val state: StateFlow<PlayUiState> = combine(
        repo.observeWorld(worldId),
        repo.observeTurns(worldId),
        repo.observeCharacters(worldId),
        repo.observeLocations(worldId),
        repo.observeImages(worldId)
    ) { world, turns, characters, locations, images ->
        Triple(world, turns, characters) to (locations to images)
    }.combine(repo.observeThreads(worldId)) { (main, extra), threads ->
        val (world, turns, characters) = main
        val (locations, images) = extra
        PlayUiState(
            world = world,
            player = characters.firstOrNull { it.isPlayer },
            turns = turns,
            characters = characters,
            locations = locations,
            images = images,
            threads = threads
        )
    }.combine(_transient) { data, transient ->
        data.copy(
            loading = transient.loading,
            generatingImage = transient.generatingImage,
            error = transient.error,
            notice = transient.notice
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayUiState())

    private val _lastIssues = MutableStateFlow<List<String>>(emptyList())
    val lastIssues: StateFlow<List<String>> = _lastIssues.asStateFlow()

    /** Starts the world if it has never been played; otherwise does nothing. */
    fun ensureOpening() {
        viewModelScope.launch {
            val world = repo.world(worldId) ?: return@launch
            if (world.turnCount > 0) return@launch
            if (repo.turnDao.count(worldId) > 0) return@launch
            runTurn { container.turnDirector.opening(worldId) }
        }
    }

    fun submit(input: String, kind: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            runTurn { container.turnDirector.take(worldId, input.trim(), kind) }
        }
    }

    fun chooseOption(choice: Choice) {
        val text = if (choice.detail.isBlank()) choice.label else "${choice.label} - ${choice.detail}"
        submit(text, "CHOICE")
    }

    private suspend fun runTurn(block: suspend () -> Result<com.narrate.app.engine.TurnResult>) {
        if (_transient.value.loading) return
        _transient.value = _transient.value.copy(loading = true, error = null, notice = null)
        val result = block()
        _transient.value = result.fold(
            onSuccess = { turnResult ->
                _lastIssues.value = turnResult.issues
                    .filter { it.severity != com.narrate.app.engine.ContinuityGuard.SEVERITY_INFO }
                    .map { "${it.description} ${it.resolution}" }
                val notice = if (!turnResult.parsed.stateParsed && turnResult.parsed.parseNotes.isNotEmpty()) {
                    "The narrator did not return a world update this turn, so nothing was recorded. The prose is kept."
                } else null
                _transient.value.copy(loading = false, notice = notice)
            },
            onFailure = { throwable ->
                _transient.value.copy(loading = false, error = throwable.message ?: "The world could not answer.")
            }
        )

        if (container.settings.current.autoGenerateSceneImages && result.isSuccess) {
            generateImage(ImageSubject.CurrentScene)
        }
    }

    fun generateImage(subject: ImageSubject, direction: String = "") {
        viewModelScope.launch {
            if (_transient.value.generatingImage) return@launch
            _transient.value = _transient.value.copy(generatingImage = true, error = null)
            val snapshot = repo.snapshot(worldId, container.settings.current.recentTurnWindow)
            if (snapshot == null) {
                _transient.value = _transient.value.copy(generatingImage = false, error = "World not found.")
                return@launch
            }
            val result = container.imageDirector.generate(snapshot, subject, direction)
            _transient.value = result.fold(
                onSuccess = { image ->
                    _transient.value.copy(generatingImage = false, notice = "Saved to the album: ${image.label}")
                },
                onFailure = { throwable ->
                    _transient.value.copy(
                        generatingImage = false,
                        error = throwable.message ?: "The image could not be generated."
                    )
                }
            )
        }
    }

    /** Generate an image of the moment the narrator itself suggested. */
    fun illustrateLastMoment() {
        viewModelScope.launch {
            val last = repo.turnDao.last(worldId)
            val hint = last?.summary?.ifBlank { MarkupParser.stripMarkup(last.narration).take(400) }.orEmpty()
            generateImage(if (hint.isBlank()) ImageSubject.CurrentScene else ImageSubject.Moment(hint))
        }
    }

    fun rewind(toTurnIndex: Int) {
        viewModelScope.launch {
            repo.rewindTo(worldId, toTurnIndex)
            _transient.value = _transient.value.copy(notice = "Rewound to turn $toTurnIndex.")
        }
    }

    fun dismissError() {
        _transient.value = _transient.value.copy(error = null, notice = null)
    }
}
