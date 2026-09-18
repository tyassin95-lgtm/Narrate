package com.narrate.app.ui.codex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.narrate.app.container
import com.narrate.app.data.entity.*
import com.narrate.app.engine.ImageSubject
import com.narrate.app.engine.MemoryIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CodexUiState(
    val world: WorldEntity? = null,
    val characters: List<CharacterEntity> = emptyList(),
    val locations: List<LocationEntity> = emptyList(),
    val links: List<LocationLinkEntity> = emptyList(),
    val items: List<ItemEntity> = emptyList(),
    val factions: List<FactionEntity> = emptyList(),
    val threads: List<ThreadEntity> = emptyList(),
    val memories: List<MemoryEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val images: List<ImageEntity> = emptyList(),
    val issues: List<ContinuityIssueEntity> = emptyList(),
    val turns: List<TurnEntity> = emptyList(),
    val usage: List<UsageEntity> = emptyList(),
    /** The entity currently being drawn, so its own row can show it is working. */
    val generatingSubjectId: String? = null,
    val message: String? = null
) {
    val generating: Boolean get() = generatingSubjectId != null

    fun isDrawing(subjectId: String): Boolean = generatingSubjectId == subjectId

    /**
     * The places the player knows about.
     *
     * A world is built with more geography than the player has seen - where each NPC sleeps,
     * the cafe nobody has walked into yet - and those exist so people have somewhere to be.
     * Showing them on the map would hand the player an address their character never learned.
     */
    val discoveredLocations: List<LocationEntity> get() = locations.filter { it.discovered }

    /** Things that actually happened in the world and contradicted it. */
    val worldIssues: List<ContinuityIssueEntity>
        get() = issues.filterNot { com.narrate.app.engine.ContinuityGuard.isGenerationNote(it.category) }

    /** Things the narrator proposed that were thrown away before the player saw them. */
    val generationNotes: List<ContinuityIssueEntity>
        get() = issues.filter { com.narrate.app.engine.ContinuityGuard.isGenerationNote(it.category) }

    val player: CharacterEntity? get() = characters.firstOrNull { it.isPlayer }
    val npcs: List<CharacterEntity> get() = characters.filter { !it.isPlayer }
    fun imagePath(id: String?): String? = images.firstOrNull { it.id == id }?.filePath
    fun imagesFor(subjectId: String): List<ImageEntity> = images.filter { it.subjectIds.contains(subjectId) }
    fun locationName(id: String?): String = locations.firstOrNull { it.id == id }?.name ?: "unknown"
}

/** Backing state for everything the player can browse about a world. */
class CodexViewModel(application: Application, private val worldId: String) : AndroidViewModel(application) {

    private val container = application.container
    private val repo = container.repository
    /** Which subject is being drawn, and anything the last attempt has to say. */
    private data class DrawState(val subjectId: String? = null, val message: String? = null)

    private val transient = MutableStateFlow(DrawState())

    val state: StateFlow<CodexUiState> = combine(
        combine(
            repo.observeWorld(worldId),
            repo.observeCharacters(worldId),
            repo.observeLocations(worldId),
            repo.observeLinks(worldId)
        ) { world, characters, locations, links -> listOf(world, characters, locations, links) },
        combine(
            repo.observeItems(worldId),
            repo.observeFactions(worldId),
            repo.observeThreads(worldId),
            repo.observeMemories(worldId)
        ) { items, factions, threads, memories -> listOf(items, factions, threads, memories) },
        combine(
            repo.observeChapters(worldId),
            repo.observeImages(worldId),
            repo.observeIssues(worldId),
            repo.observeTurns(worldId),
            repo.observeUsage(worldId)
        ) { chapters, images, issues, turns, usage -> listOf(chapters, images, issues, turns, usage) },
        transient
    ) { first, second, third, flags ->
        @Suppress("UNCHECKED_CAST")
        CodexUiState(
            world = first[0] as WorldEntity?,
            characters = first[1] as List<CharacterEntity>,
            locations = first[2] as List<LocationEntity>,
            links = first[3] as List<LocationLinkEntity>,
            items = second[0] as List<ItemEntity>,
            factions = second[1] as List<FactionEntity>,
            threads = second[2] as List<ThreadEntity>,
            memories = second[3] as List<MemoryEntity>,
            chapters = third[0] as List<ChapterEntity>,
            images = third[1] as List<ImageEntity>,
            issues = third[2] as List<ContinuityIssueEntity>,
            turns = third[3] as List<TurnEntity>,
            usage = third[4] as List<UsageEntity>,
            generatingSubjectId = flags.subjectId,
            message = flags.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodexUiState())

    private val _memoryFilter = MutableStateFlow("")
    val memoryFilter: StateFlow<String> = _memoryFilter.asStateFlow()

    fun setMemoryFilter(value: String) { _memoryFilter.value = value }

    fun memoriesFor(character: CharacterEntity, memories: List<MemoryEntity>): List<MemoryEntity> =
        MemoryIndex.forSubject(memories, character.id, character.name)

    /** Pacing is part of the world's configuration, and can be changed as it goes on. */
    fun setPlayStyle(style: com.narrate.app.engine.PlayStyle) {
        viewModelScope.launch {
            val world = repo.world(worldId) ?: return@launch
            repo.saveWorld(world.copy(playStyle = style.id))
            transient.value = transient.value.copy(message = "Pacing set to ${style.label.lowercase()}.")
        }
    }

    /**
     * Corrects the player character's name.
     *
     * The protagonist's name is the player's own canon, so they get to fix it - whether they
     * mistyped it, changed their mind, or the app read a heading they wrote as part of it.
     */
    fun renameCharacter(characterId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val character = repo.character(characterId) ?: return@launch
            if (character.name == trimmed) return@launch
            repo.saveCharacter(character.copy(name = trimmed))
            repo.visualForSubject(characterId)?.let { identity ->
                repo.saveVisualIdentity(identity.copy(subjectName = trimmed))
            }
            transient.value = transient.value.copy(message = "Renamed to $trimmed.")
        }
    }

    fun togglePin(memory: MemoryEntity) {
        viewModelScope.launch { repo.setMemoryPinned(memory.id, !memory.pinned) }
    }

    fun generatePortrait(characterId: String) = generate(ImageSubject.Character(characterId), characterId)
    fun generateLocationImage(locationId: String) = generate(ImageSubject.Location(locationId), locationId)
    fun generateItemImage(itemId: String) = generate(ImageSubject.Item(itemId), itemId)

    /**
     * One drawing at a time, and the row that asked for it says so. Without visible progress
     * the button looks dead and gets pressed again, which is both confusing and expensive.
     */
    private fun generate(subject: ImageSubject, subjectId: String) {
        if (transient.value.subjectId != null) return
        transient.value = DrawState(subjectId = subjectId)
        viewModelScope.launch {
            val snapshot = repo.snapshot(worldId, container.settings.current.recentTurnWindow)
            if (snapshot == null) {
                transient.value = DrawState(message = "World not found.")
                return@launch
            }
            val result = container.imageDirector.generate(snapshot, subject)
            transient.value = DrawState(
                message = result.fold(
                    onSuccess = { "Saved: ${it.label}" },
                    onFailure = { it.message ?: "The image could not be generated." }
                )
            )
        }
    }

    fun clearMessage() { transient.value = transient.value.copy(message = null) }

    fun toggleFavorite(image: ImageEntity) {
        viewModelScope.launch { repo.setImageFavorite(image.id, !image.favorite) }
    }

    /** Removes an image, and everything that pointed at it, from the world. */
    fun deleteImage(image: ImageEntity) {
        viewModelScope.launch {
            repo.deleteImage(image)
            transient.value = transient.value.copy(message = "Deleted: ${image.label}")
        }
    }
}
