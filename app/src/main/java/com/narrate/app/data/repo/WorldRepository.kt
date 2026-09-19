package com.narrate.app.data.repo

import android.content.Context
import com.narrate.app.core.newId
import com.narrate.app.core.nameSimilarity
import com.narrate.app.data.db.NarrateDatabase
import com.narrate.app.data.entity.*
import com.narrate.app.engine.PlaceIdentity
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The single door to persistence. Every write the world makes goes through here so that
 * saves stay coherent and recoverable across sessions.
 */
class WorldRepository(context: Context) {

    private val app = context.applicationContext
    private val db = NarrateDatabase.get(app)

    val worldDao = db.worldDao()
    val characterDao = db.characterDao()
    val locationDao = db.locationDao()
    val linkDao = db.locationLinkDao()
    val itemDao = db.itemDao()
    val factionDao = db.factionDao()
    val relationshipDao = db.relationshipDao()
    val memoryDao = db.memoryDao()
    val turnDao = db.turnDao()
    val threadDao = db.threadDao()
    val chapterDao = db.chapterDao()
    val imageDao = db.imageDao()
    val visualDao = db.visualIdentityDao()
    val issueDao = db.continuityIssueDao()
    val usageDao = db.usageDao()
    val knowledgeDao = db.knowledgeDao()

    fun observeWorlds(): Flow<List<WorldEntity>> = worldDao.observeAll()
    fun observeWorld(id: String): Flow<WorldEntity?> = worldDao.observe(id)
    fun observeCharacters(worldId: String): Flow<List<CharacterEntity>> = characterDao.observeAll(worldId)
    fun observeLocations(worldId: String): Flow<List<LocationEntity>> = locationDao.observeAll(worldId)
    fun observeLinks(worldId: String): Flow<List<LocationLinkEntity>> = linkDao.observeAll(worldId)
    fun observeItems(worldId: String): Flow<List<ItemEntity>> = itemDao.observeAll(worldId)
    fun observeFactions(worldId: String): Flow<List<FactionEntity>> = factionDao.observeAll(worldId)
    fun observeThreads(worldId: String): Flow<List<ThreadEntity>> = threadDao.observeAll(worldId)
    fun observeTurns(worldId: String): Flow<List<TurnEntity>> = turnDao.observeAll(worldId)
    fun observeMemories(worldId: String): Flow<List<MemoryEntity>> = memoryDao.observeAll(worldId)
    fun observeImages(worldId: String): Flow<List<ImageEntity>> = imageDao.observeAll(worldId)
    fun observeRecentImages(limit: Int = 30): Flow<List<ImageEntity>> = imageDao.observeRecent(limit)
    fun observeChapters(worldId: String): Flow<List<ChapterEntity>> = chapterDao.observeAll(worldId)
    fun observeIssues(worldId: String): Flow<List<ContinuityIssueEntity>> = issueDao.observeAll(worldId)
    fun observeVisualIdentities(worldId: String): Flow<List<VisualIdentityEntity>> = visualDao.observeAll(worldId)
    fun observeCharacter(id: String): Flow<CharacterEntity?> = characterDao.observe(id)
    fun observeLocation(id: String): Flow<LocationEntity?> = locationDao.observe(id)
    fun observeImage(id: String): Flow<ImageEntity?> = imageDao.observe(id)
    fun observePlayer(worldId: String): Flow<CharacterEntity?> = characterDao.observePlayer(worldId)
    fun observeVisualForSubject(subjectId: String): Flow<VisualIdentityEntity?> = visualDao.observeForSubject(subjectId)
    fun observeUsage(worldId: String): Flow<List<UsageEntity>> = usageDao.observeForWorld(worldId)
    fun observeAllUsage(): Flow<List<UsageEntity>> = usageDao.observeAll()
    fun observeKnowledge(worldId: String): Flow<List<KnowledgeEntity>> = knowledgeDao.observeAll(worldId)

    suspend fun world(id: String): WorldEntity? = worldDao.get(id)
    suspend fun mostRecentWorld(): WorldEntity? = worldDao.mostRecent()

    /** Loads the complete working set for a turn. */
    suspend fun snapshot(worldId: String, recentTurnWindow: Int = 8): WorldSnapshot? {
        val world = worldDao.get(worldId) ?: return null
        val characters = characterDao.all(worldId)
        val recent = turnDao.recent(worldId, recentTurnWindow).sortedBy { it.index }
        val chapters = chapterDao.all(worldId)
        // Everything between the end of the last chapter and the first turn replayed in full.
        val compactedTo = chapters.maxOfOrNull { it.toTurn } ?: -1
        val replayedFrom = recent.minOfOrNull { it.index } ?: world.turnCount
        val between = if (replayedFrom - 1 >= compactedTo + 1) {
            turnDao.range(worldId, compactedTo + 1, replayedFrom - 1)
        } else {
            emptyList()
        }
        return WorldSnapshot(
            world = world,
            player = characters.firstOrNull { it.isPlayer },
            characters = characters,
            locations = locationDao.all(worldId),
            links = linkDao.all(worldId),
            items = itemDao.all(worldId),
            factions = factionDao.all(worldId),
            relationships = relationshipDao.all(worldId),
            threads = threadDao.all(worldId),
            chapters = chapters,
            recentTurns = recent,
            earlierTurns = between,
            memories = memoryDao.all(worldId),
            visualIdentities = visualDao.all(worldId),
            knowledge = knowledgeDao.all(worldId)
        )
    }

    suspend fun saveWorld(world: WorldEntity) = worldDao.upsert(world.copy(updatedAt = System.currentTimeMillis()))

    suspend fun touchWorld(worldId: String) = worldDao.touch(worldId)

    /** Deleting a world removes its entire universe: every row, and every image on disk. */
    suspend fun deleteWorld(worldId: String) {
        imageDao.all(worldId).forEach { runCatching { File(it.filePath).delete() } }
        characterDao.deleteByWorld(worldId)
        locationDao.deleteByWorld(worldId)
        linkDao.deleteByWorld(worldId)
        itemDao.deleteByWorld(worldId)
        factionDao.deleteByWorld(worldId)
        relationshipDao.deleteByWorld(worldId)
        memoryDao.deleteByWorld(worldId)
        knowledgeDao.deleteByWorld(worldId)
        turnDao.deleteByWorld(worldId)
        threadDao.deleteByWorld(worldId)
        chapterDao.deleteByWorld(worldId)
        imageDao.deleteByWorld(worldId)
        visualDao.deleteByWorld(worldId)
        issueDao.deleteByWorld(worldId)
        usageDao.deleteByWorld(worldId)
        worldDao.delete(worldId)
        worldImageDir(worldId).deleteRecursively()
    }

    suspend fun saveCharacter(character: CharacterEntity) =
        characterDao.upsert(character.copy(updatedAt = System.currentTimeMillis()))

    suspend fun saveCharacters(characters: List<CharacterEntity>) = characterDao.upsertAll(characters)
    suspend fun saveLocation(location: LocationEntity) =
        locationDao.upsert(location.copy(updatedAt = System.currentTimeMillis()))

    suspend fun saveLocations(locations: List<LocationEntity>) = locationDao.upsertAll(locations)
    suspend fun saveLinks(links: List<LocationLinkEntity>) = linkDao.upsertAll(links)
    suspend fun saveItems(items: List<ItemEntity>) = itemDao.upsertAll(items)
    suspend fun saveFactions(factions: List<FactionEntity>) = factionDao.upsertAll(factions)
    suspend fun saveRelationships(relationships: List<RelationshipEntity>) = relationshipDao.upsertAll(relationships)
    suspend fun saveThreads(threads: List<ThreadEntity>) = threadDao.upsertAll(threads)
    suspend fun saveKnowledge(rows: List<KnowledgeEntity>) {
        if (rows.isNotEmpty()) knowledgeDao.upsertAll(rows)
    }

    suspend fun saveMemories(memories: List<MemoryEntity>) = memoryDao.insertAll(memories)
    suspend fun saveTurn(turn: TurnEntity) = turnDao.upsert(turn)
    suspend fun saveChapter(chapter: ChapterEntity) = chapterDao.upsert(chapter)
    suspend fun saveImage(image: ImageEntity) = imageDao.upsert(image)
    suspend fun saveVisualIdentity(identity: VisualIdentityEntity) =
        visualDao.upsert(identity.copy(updatedAt = System.currentTimeMillis()))

    suspend fun saveIssues(issues: List<ContinuityIssueEntity>) = issueDao.insertAll(issues)
    suspend fun recordUsage(usage: UsageEntity) = usageDao.insert(usage)
    suspend fun usageForWorld(worldId: String): List<UsageEntity> = usageDao.forWorld(worldId)
    suspend fun visualForSubject(subjectId: String): VisualIdentityEntity? = visualDao.forSubject(subjectId)
    suspend fun image(id: String): ImageEntity? = imageDao.get(id)
    suspend fun character(id: String): CharacterEntity? = characterDao.get(id)
    suspend fun location(id: String): LocationEntity? = locationDao.get(id)
    suspend fun lastCompactedTurn(worldId: String): Int = chapterDao.lastCompactedTurn(worldId) ?: -1
    suspend fun turnsBetween(worldId: String, from: Int, to: Int): List<TurnEntity> = turnDao.range(worldId, from, to)
    suspend fun setImageFavorite(id: String, favorite: Boolean) = imageDao.setFavorite(id, favorite)
    suspend fun setMemoryPinned(id: String, pinned: Boolean) = memoryDao.setPinned(id, pinned)

    /**
     * Removes an image from the world for good.
     *
     * An image is not only a file: it may be a character's portrait, a place's icon, a world's
     * cover, or the visual reference future generations are seeded from. All of those have to
     * let go of it, or the album would show a picture the world still quietly depends on.
     */
    suspend fun deleteImage(image: ImageEntity) {
        val worldId = image.worldId

        // Each subject that used this image falls back to its next surviving picture, so
        // deleting the newest portrait of someone does not leave them with no face at all.
        val fallbacks = mutableMapOf<String, String?>()
        visualDao.all(worldId).forEach { identity ->
            val allRefs = identity.referenceImageIds.split(",").filter { it.isNotBlank() }
            val remaining = allRefs.filter { it != image.id }
            val wasUsed = identity.primaryImageId == image.id || remaining.size != allRefs.size
            if (!wasUsed) return@forEach
            val fallback = if (identity.primaryImageId == image.id) {
                remaining.firstOrNull()
            } else {
                identity.primaryImageId
            }
            fallbacks[identity.subjectId] = fallback
            saveVisualIdentity(
                identity.copy(
                    primaryImageId = fallback,
                    referenceImageIds = remaining.joinToString(",")
                )
            )
        }

        characterDao.all(worldId).filter { it.portraitImageId == image.id }.forEach {
            saveCharacter(it.copy(portraitImageId = fallbacks[it.id]))
        }
        locationDao.all(worldId).filter { it.imageId == image.id }.forEach {
            saveLocation(it.copy(imageId = fallbacks[it.id]))
        }
        itemDao.all(worldId).filter { it.imageId == image.id }.forEach {
            saveItems(listOf(it.copy(imageId = fallbacks[it.id])))
        }
        worldDao.get(worldId)?.takeIf { it.coverImageId == image.id }?.let { world ->
            val nextCover = imageDao.all(worldId)
                .filter { it.id != image.id && (it.type == "SCENE" || it.type == "LOCATION") }
                .maxByOrNull { it.createdAt }
            saveWorld(world.copy(coverImageId = nextCover?.id))
        }

        runCatching { File(image.filePath).delete() }
        imageDao.delete(image.id)
    }

    /**
     * Rewind: drop every turn from [fromIndex] on so the player can retry a moment.
     *
     * What those turns recorded goes with them. A memory of something that no longer happened
     * would be quoted back to the narrator as established fact, which is precisely the kind of
     * contradiction the rest of the app exists to prevent.
     */
    suspend fun rewindTo(worldId: String, fromIndex: Int) {
        turnDao.deleteFrom(worldId, fromIndex)
        memoryDao.deleteFrom(worldId, fromIndex)
        // Rewinding un-learns what those turns taught the player, or the codex would
        // keep a secret the story has not told yet.
        knowledgeDao.deleteFrom(worldId, fromIndex)
        issueDao.deleteFrom(worldId, fromIndex)
        val world = worldDao.get(worldId) ?: return
        worldDao.upsert(world.copy(turnCount = fromIndex, updatedAt = System.currentTimeMillis()))
    }

    /** Resolve an id, a name, or something close to a name. The GM is not required to use ids. */
    fun resolveCharacter(characters: List<CharacterEntity>, reference: String?): CharacterEntity? {
        if (reference.isNullOrBlank()) return null
        characters.firstOrNull { it.id == reference }?.let { return it }
        characters.firstOrNull { it.name.equals(reference, ignoreCase = true) }?.let { return it }
        return characters.maxByOrNull { nameSimilarity(it.name, reference) }
            ?.takeIf { nameSimilarity(it.name, reference) >= 0.6 }
    }

    /**
     * Resolve a place by id or by name.
     *
     * Places are matched on the words that identify them rather than on general similarity:
     * "Liv's apartment" and "Adrian's apartment" are two thirds alike and are not the same
     * address, and treating them as one merged a real place away and moved whoever was in it.
     */
    fun resolveLocation(locations: List<LocationEntity>, reference: String?): LocationEntity? {
        if (reference.isNullOrBlank()) return null
        locations.firstOrNull { it.id == reference }?.let { return it }
        return PlaceIdentity.match(reference, locations) { it.name }
    }

    fun resolveItem(items: List<ItemEntity>, reference: String?): ItemEntity? {
        if (reference.isNullOrBlank()) return null
        items.firstOrNull { it.id == reference }?.let { return it }
        return items.firstOrNull { it.name.equals(reference, ignoreCase = true) }
            ?: items.maxByOrNull { nameSimilarity(it.name, reference) }
                ?.takeIf { nameSimilarity(it.name, reference) >= 0.65 }
    }

    fun worldImageDir(worldId: String): File =
        File(app.filesDir, "worlds/$worldId/images").apply { mkdirs() }

    /** Writes image bytes into the world's own folder and returns the stored file. */
    fun writeImageFile(worldId: String, bytes: ByteArray, extension: String = "png"): File {
        val file = File(worldImageDir(worldId), "img_${System.currentTimeMillis()}_${newId().take(8)}.$extension")
        file.outputStream().use { it.write(bytes) }
        return file
    }

    fun readImageBytes(path: String): ByteArray? =
        runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
}
