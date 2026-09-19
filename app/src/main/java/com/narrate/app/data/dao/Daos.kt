package com.narrate.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.narrate.app.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Upsert suspend fun upsert(world: WorldEntity)
    @Query("SELECT * FROM worlds ORDER BY lastPlayedAt DESC") fun observeAll(): Flow<List<WorldEntity>>
    @Query("SELECT * FROM worlds WHERE id = :id") fun observe(id: String): Flow<WorldEntity?>
    @Query("SELECT * FROM worlds WHERE id = :id") suspend fun get(id: String): WorldEntity?
    @Query("SELECT * FROM worlds ORDER BY lastPlayedAt DESC LIMIT 1") suspend fun mostRecent(): WorldEntity?
    @Query("DELETE FROM worlds WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE worlds SET lastPlayedAt = :at WHERE id = :id") suspend fun touch(id: String, at: Long = System.currentTimeMillis())
}

@Dao
interface CharacterDao {
    @Upsert suspend fun upsert(character: CharacterEntity)
    @Upsert suspend fun upsertAll(characters: List<CharacterEntity>)
    @Query("SELECT * FROM characters WHERE worldId = :worldId ORDER BY isPlayer DESC, importance DESC, name")
    fun observeAll(worldId: String): Flow<List<CharacterEntity>>
    @Query("SELECT * FROM characters WHERE worldId = :worldId") suspend fun all(worldId: String): List<CharacterEntity>
    @Query("SELECT * FROM characters WHERE id = :id") suspend fun get(id: String): CharacterEntity?
    @Query("SELECT * FROM characters WHERE id = :id") fun observe(id: String): Flow<CharacterEntity?>
    @Query("SELECT * FROM characters WHERE worldId = :worldId AND isPlayer = 1 LIMIT 1")
    suspend fun player(worldId: String): CharacterEntity?
    @Query("SELECT * FROM characters WHERE worldId = :worldId AND isPlayer = 1 LIMIT 1")
    fun observePlayer(worldId: String): Flow<CharacterEntity?>
    @Query("SELECT * FROM characters WHERE worldId = :worldId AND currentLocationId = :locationId AND isPlayer = 0")
    suspend fun atLocation(worldId: String, locationId: String): List<CharacterEntity>
    @Query("DELETE FROM characters WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM characters WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface LocationDao {
    @Upsert suspend fun upsert(location: LocationEntity)
    @Upsert suspend fun upsertAll(locations: List<LocationEntity>)
    @Query("SELECT * FROM locations WHERE worldId = :worldId ORDER BY type, name")
    fun observeAll(worldId: String): Flow<List<LocationEntity>>
    @Query("SELECT * FROM locations WHERE worldId = :worldId") suspend fun all(worldId: String): List<LocationEntity>
    @Query("SELECT * FROM locations WHERE id = :id") suspend fun get(id: String): LocationEntity?
    @Query("SELECT * FROM locations WHERE id = :id") fun observe(id: String): Flow<LocationEntity?>
    @Query("DELETE FROM locations WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM locations WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface LocationLinkDao {
    @Upsert suspend fun upsert(link: LocationLinkEntity)
    @Upsert suspend fun upsertAll(links: List<LocationLinkEntity>)
    @Query("SELECT * FROM location_links WHERE worldId = :worldId") suspend fun all(worldId: String): List<LocationLinkEntity>
    @Query("SELECT * FROM location_links WHERE worldId = :worldId") fun observeAll(worldId: String): Flow<List<LocationLinkEntity>>
    @Query("SELECT * FROM location_links WHERE fromId = :id OR toId = :id") suspend fun forLocation(id: String): List<LocationLinkEntity>
    @Query("DELETE FROM location_links WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface ItemDao {
    @Upsert suspend fun upsert(item: ItemEntity)
    @Upsert suspend fun upsertAll(items: List<ItemEntity>)
    @Query("SELECT * FROM items WHERE worldId = :worldId ORDER BY name") fun observeAll(worldId: String): Flow<List<ItemEntity>>
    @Query("SELECT * FROM items WHERE worldId = :worldId") suspend fun all(worldId: String): List<ItemEntity>
    @Query("SELECT * FROM items WHERE id = :id") suspend fun get(id: String): ItemEntity?
    @Query("SELECT * FROM items WHERE worldId = :worldId AND holderId = :holderId") suspend fun heldBy(worldId: String, holderId: String): List<ItemEntity>
    @Query("DELETE FROM items WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM items WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface KnowledgeDao {
    @Upsert suspend fun upsert(row: KnowledgeEntity)
    @Upsert suspend fun upsertAll(rows: List<KnowledgeEntity>)
    @Query("SELECT * FROM player_knowledge WHERE worldId = :worldId ORDER BY turnIndex ASC")
    fun observeAll(worldId: String): Flow<List<KnowledgeEntity>>
    @Query("SELECT * FROM player_knowledge WHERE worldId = :worldId ORDER BY turnIndex ASC")
    suspend fun all(worldId: String): List<KnowledgeEntity>
    @Query("SELECT * FROM player_knowledge WHERE worldId = :worldId AND subjectId = :subjectId")
    suspend fun forSubject(worldId: String, subjectId: String): List<KnowledgeEntity>
    @Query("DELETE FROM player_knowledge WHERE worldId = :worldId AND turnIndex >= :from")
    suspend fun deleteFrom(worldId: String, from: Int)
    @Query("DELETE FROM player_knowledge WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface FactionDao {
    @Upsert suspend fun upsert(faction: FactionEntity)
    @Upsert suspend fun upsertAll(factions: List<FactionEntity>)
    @Query("SELECT * FROM factions WHERE worldId = :worldId ORDER BY name") fun observeAll(worldId: String): Flow<List<FactionEntity>>
    @Query("SELECT * FROM factions WHERE worldId = :worldId") suspend fun all(worldId: String): List<FactionEntity>
    @Query("SELECT * FROM factions WHERE id = :id") suspend fun get(id: String): FactionEntity?
    @Query("DELETE FROM factions WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface RelationshipDao {
    @Upsert suspend fun upsert(relationship: RelationshipEntity)
    @Upsert suspend fun upsertAll(relationships: List<RelationshipEntity>)
    @Query("SELECT * FROM relationships WHERE worldId = :worldId") suspend fun all(worldId: String): List<RelationshipEntity>
    @Query("SELECT * FROM relationships WHERE worldId = :worldId") fun observeAll(worldId: String): Flow<List<RelationshipEntity>>
    @Query("SELECT * FROM relationships WHERE fromId = :id OR toId = :id") suspend fun forCharacter(id: String): List<RelationshipEntity>
    @Query("SELECT * FROM relationships WHERE worldId = :worldId AND fromId = :from AND toId = :to LIMIT 1")
    suspend fun between(worldId: String, from: String, to: String): RelationshipEntity?
    @Query("DELETE FROM relationships WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(memory: MemoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(memories: List<MemoryEntity>)
    @Update suspend fun update(memory: MemoryEntity)
    @Query("SELECT * FROM memories WHERE worldId = :worldId AND superseded = 0 ORDER BY turnIndex DESC")
    suspend fun all(worldId: String): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE worldId = :worldId ORDER BY turnIndex DESC")
    fun observeAll(worldId: String): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE worldId = :worldId AND pinned = 1 AND superseded = 0 ORDER BY importance DESC")
    suspend fun pinned(worldId: String): List<MemoryEntity>
    @Query("SELECT COUNT(*) FROM memories WHERE worldId = :worldId") suspend fun count(worldId: String): Int
    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
    @Query("DELETE FROM memories WHERE worldId = :worldId AND turnIndex >= :from")
    suspend fun deleteFrom(worldId: String, from: Int)
    @Delete suspend fun delete(memory: MemoryEntity)
    @Query("DELETE FROM memories WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface TurnDao {
    @Upsert suspend fun upsert(turn: TurnEntity)
    @Query("SELECT * FROM turns WHERE worldId = :worldId ORDER BY `index` ASC") fun observeAll(worldId: String): Flow<List<TurnEntity>>
    @Query("SELECT * FROM turns WHERE worldId = :worldId ORDER BY `index` ASC") suspend fun all(worldId: String): List<TurnEntity>
    @Query("SELECT * FROM turns WHERE worldId = :worldId ORDER BY `index` DESC LIMIT :limit") suspend fun recent(worldId: String, limit: Int): List<TurnEntity>
    @Query("SELECT * FROM turns WHERE worldId = :worldId ORDER BY `index` DESC LIMIT 1") suspend fun last(worldId: String): TurnEntity?
    @Query("SELECT * FROM turns WHERE worldId = :worldId AND `index` BETWEEN :from AND :to ORDER BY `index` ASC")
    suspend fun range(worldId: String, from: Int, to: Int): List<TurnEntity>
    @Query("SELECT COUNT(*) FROM turns WHERE worldId = :worldId") suspend fun count(worldId: String): Int
    @Query("DELETE FROM turns WHERE worldId = :worldId AND `index` >= :from") suspend fun deleteFrom(worldId: String, from: Int)
    @Query("DELETE FROM turns WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface ThreadDao {
    @Upsert suspend fun upsert(thread: ThreadEntity)
    @Upsert suspend fun upsertAll(threads: List<ThreadEntity>)
    @Query("SELECT * FROM threads WHERE worldId = :worldId ORDER BY urgency DESC, updatedTurn DESC")
    fun observeAll(worldId: String): Flow<List<ThreadEntity>>
    @Query("SELECT * FROM threads WHERE worldId = :worldId") suspend fun all(worldId: String): List<ThreadEntity>
    @Query("SELECT * FROM threads WHERE worldId = :worldId AND status = 'ACTIVE'") suspend fun active(worldId: String): List<ThreadEntity>
    @Query("DELETE FROM threads WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface ChapterDao {
    @Upsert suspend fun upsert(chapter: ChapterEntity)
    @Query("SELECT * FROM chapters WHERE worldId = :worldId ORDER BY fromTurn ASC") suspend fun all(worldId: String): List<ChapterEntity>
    @Query("SELECT * FROM chapters WHERE worldId = :worldId ORDER BY fromTurn ASC") fun observeAll(worldId: String): Flow<List<ChapterEntity>>
    @Query("SELECT MAX(toTurn) FROM chapters WHERE worldId = :worldId") suspend fun lastCompactedTurn(worldId: String): Int?
    @Query("DELETE FROM chapters WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface ImageDao {
    @Upsert suspend fun upsert(image: ImageEntity)
    @Query("SELECT * FROM images WHERE worldId = :worldId ORDER BY createdAt DESC") fun observeAll(worldId: String): Flow<List<ImageEntity>>
    @Query("SELECT * FROM images ORDER BY createdAt DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<ImageEntity>>
    @Query("SELECT * FROM images WHERE worldId = :worldId") suspend fun all(worldId: String): List<ImageEntity>
    @Query("SELECT * FROM images WHERE id = :id") suspend fun get(id: String): ImageEntity?
    @Query("SELECT * FROM images WHERE id = :id") fun observe(id: String): Flow<ImageEntity?>
    @Query("SELECT * FROM images WHERE worldId = :worldId AND turnIndex = :turnIndex ORDER BY createdAt DESC")
    suspend fun forTurn(worldId: String, turnIndex: Int): List<ImageEntity>
    @Query("UPDATE images SET favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: String, favorite: Boolean)
    @Query("DELETE FROM images WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM images WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface VisualIdentityDao {
    @Upsert suspend fun upsert(identity: VisualIdentityEntity)
    @Query("SELECT * FROM visual_identities WHERE worldId = :worldId") suspend fun all(worldId: String): List<VisualIdentityEntity>
    @Query("SELECT * FROM visual_identities WHERE worldId = :worldId") fun observeAll(worldId: String): Flow<List<VisualIdentityEntity>>
    @Query("SELECT * FROM visual_identities WHERE subjectId = :subjectId LIMIT 1") suspend fun forSubject(subjectId: String): VisualIdentityEntity?
    @Query("SELECT * FROM visual_identities WHERE subjectId = :subjectId LIMIT 1") fun observeForSubject(subjectId: String): Flow<VisualIdentityEntity?>
    @Query("DELETE FROM visual_identities WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface ContinuityIssueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(issue: ContinuityIssueEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(issues: List<ContinuityIssueEntity>)
    @Query("SELECT * FROM continuity_issues WHERE worldId = :worldId ORDER BY createdAt DESC LIMIT 200")
    fun observeAll(worldId: String): Flow<List<ContinuityIssueEntity>>
    @Query("SELECT * FROM continuity_issues WHERE worldId = :worldId AND turnIndex = :turnIndex")
    suspend fun forTurn(worldId: String, turnIndex: Int): List<ContinuityIssueEntity>
    @Query("SELECT * FROM continuity_issues WHERE worldId = :worldId ORDER BY turnIndex ASC, createdAt ASC")
    suspend fun all(worldId: String): List<ContinuityIssueEntity>
    @Query("DELETE FROM continuity_issues WHERE worldId = :worldId AND turnIndex >= :from")
    suspend fun deleteFrom(worldId: String, from: Int)
    @Query("DELETE FROM continuity_issues WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(usage: UsageEntity)

    @Query("SELECT * FROM usage_events WHERE worldId = :worldId ORDER BY createdAt DESC")
    fun observeForWorld(worldId: String): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 2000): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage_events WHERE worldId = :worldId")
    suspend fun forWorld(worldId: String): List<UsageEntity>

    @Query("DELETE FROM usage_events WHERE worldId = :worldId") suspend fun deleteByWorld(worldId: String)
}
