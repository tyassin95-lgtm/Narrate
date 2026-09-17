package com.narrate.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.narrate.app.core.newId

/**
 * A world is the root aggregate: everything else hangs off [WorldEntity.id].
 * Deleting a world deletes its whole universe.
 */
@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val tagline: String = "",
    val genre: String = "",
    val tone: String = "",
    /** The player-authored pitch. Treated as immutable canon by the GM. */
    val premise: String = "",
    val history: String = "",
    /** Physics, magic, technology, taboos: the rules the GM may never violate. */
    val rules: String = "",
    val themes: String = "",
    /** Raw custom instructions from the player, injected verbatim into every prompt. */
    val customPrompt: String = "",
    /**
     * Exactly what the player typed when they created this world, kept word for word.
     * This outranks every generated field and every recollection the narrator may have.
     */
    val authoredCanon: String = "",
    val narrationStyle: String = "Cinematic third-person limited, present tense",
    val narrationLength: String = "LONG",
    /**
     * How much pressure the world applies: SANDBOX, GENTLE, BALANCED or DRAMATIC.
     * Persistent configuration that shapes the narrator, the simulation and world creation.
     */
    val playStyle: String = "BALANCED",
    val contentGuidelines: String = "",
    val artStyle: String = "Cinematic, film still, natural lighting, high detail",
    val storyTime: String = "Day 1, morning",
    val dayNumber: Int = 1,
    val timeOfDay: String = "morning",
    val playerCharacterId: String? = null,
    val currentLocationId: String? = null,
    val coverImageId: String? = null,
    val turnCount: Int = 0,
    val openingNarration: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = System.currentTimeMillis()
)

/**
 * Both the player character and every NPC live here. `isPlayer` distinguishes them so
 * that all continuity machinery (location tracking, visual identity, memory) is shared.
 */
@Entity(
    tableName = "characters",
    indices = [Index("worldId"), Index("currentLocationId")]
)
data class CharacterEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val name: String,
    val aliases: String = "",
    val isPlayer: Boolean = false,
    val role: String = "",
    val summary: String = "",
    val personality: String = "",
    val backstory: String = "",
    /** Canonical, slow-changing physical description. The anchor for visual continuity. */
    val appearance: String = "",
    /** What they are wearing right now. Changes freely; feeds image generation. */
    val outfit: String = "",
    /** Injuries, exhaustion, transformations. Also feeds image generation. */
    val physicalState: String = "",
    val voice: String = "",
    val goals: String = "",
    val fears: String = "",
    val secrets: String = "",
    /** For the player character: exactly what they wrote about themselves, word for word. */
    val authoredCanon: String = "",
    /** What this character knows, as a running list. Prevents NPCs knowing things they cannot. */
    val knowledge: String = "",
    val currentLocationId: String? = null,
    val homeLocationId: String? = null,
    val routine: String = "",
    val status: String = "ALIVE",
    val affinity: Int = 0,
    val trust: Int = 0,
    val relationshipToPlayer: String = "",
    val faction: String = "",
    val portraitImageId: String? = null,
    val tags: String = "",
    val firstSeenTurn: Int = 0,
    val lastSeenTurn: Int = 0,
    val importance: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "locations",
    indices = [Index("worldId"), Index("parentId")]
)
data class LocationEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val name: String,
    /** REGION, SETTLEMENT, DISTRICT, BUILDING, ROOM, LANDMARK, WILDERNESS, VEHICLE */
    val type: String = "BUILDING",
    val parentId: String? = null,
    val description: String = "",
    val atmosphere: String = "",
    val notableFeatures: String = "",
    /** Damage, renovations, occupation: how the place has changed since it was introduced. */
    val currentState: String = "",
    val secrets: String = "",
    val controlledBy: String = "",
    val mapX: Float = 0f,
    val mapY: Float = 0f,
    val discovered: Boolean = true,
    val visited: Boolean = false,
    val imageId: String? = null,
    val tags: String = "",
    val firstSeenTurn: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A directed edge on the map. Travel is only plausible along these unless the GM explains otherwise. */
@Entity(
    tableName = "location_links",
    indices = [Index("worldId"), Index("fromId"), Index("toId")]
)
data class LocationLinkEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val fromId: String,
    val toId: String,
    val travelTime: String = "",
    val mode: String = "on foot",
    val description: String = "",
    val blocked: Boolean = false
)

@Entity(tableName = "items", indices = [Index("worldId")])
data class ItemEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val name: String,
    val description: String = "",
    val appearance: String = "",
    val significance: String = "",
    val holderId: String? = null,
    val locationId: String? = null,
    val state: String = "",
    val imageId: String? = null,
    val tags: String = "",
    val firstSeenTurn: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "factions", indices = [Index("worldId")])
data class FactionEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val name: String,
    val description: String = "",
    val goals: String = "",
    val leaderId: String? = null,
    val territory: String = "",
    val standingWithPlayer: Int = 0,
    val status: String = "ACTIVE",
    val imageId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "relationships",
    indices = [Index("worldId"), Index("fromId"), Index("toId")]
)
data class RelationshipEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val fromId: String,
    val toId: String,
    val type: String = "",
    val descriptor: String = "",
    val strength: Int = 0,
    val history: String = "",
    val updatedTurn: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * The long-term memory store. Every meaningful fact the world must not forget becomes a row here,
 * scored for retrieval and optionally pinned so it is always in context.
 */
@Entity(tableName = "memories", indices = [Index("worldId"), Index("turnIndex")])
data class MemoryEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    /** EVENT, FACT, DECISION, PROMISE, DISCOVERY, RELATIONSHIP, CONSEQUENCE, RULE, KNOWLEDGE */
    val kind: String = "EVENT",
    val text: String,
    val importance: Int = 3,
    /** Entity ids this memory is about, used for subject-scoped retrieval. */
    val subjectIds: String = "",
    val subjectNames: String = "",
    val keywords: String = "",
    val storyTime: String = "",
    val turnIndex: Int = 0,
    val pinned: Boolean = false,
    val superseded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** One exchange: what the player did and how the world answered. The verbatim story log. */
@Entity(tableName = "turns", indices = [Index("worldId"), Index("index")])
data class TurnEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val index: Int,
    /** ACTION, SPEECH, CHOICE, OPENING, SYSTEM */
    val inputType: String = "ACTION",
    val playerInput: String = "",
    val narration: String = "",
    val summary: String = "",
    val choicesJson: String = "[]",
    val storyTime: String = "",
    val locationId: String? = null,
    val locationName: String = "",
    val presentCharacterIds: String = "",
    val imageIds: String = "",
    val model: String = "",
    val provider: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** An ongoing plot: a quest, a threat, a slow-burning relationship. Keeps the world moving. */
@Entity(tableName = "threads", indices = [Index("worldId")])
data class ThreadEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val title: String,
    val description: String = "",
    /** ACTIVE, DORMANT, RESOLVED, FAILED */
    val status: String = "ACTIVE",
    val urgency: Int = 2,
    val involvedNames: String = "",
    val nextBeat: String = "",
    val deadline: String = "",
    val createdTurn: Int = 0,
    val updatedTurn: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** A compacted chapter summary. Older turns fold into these so history never falls out of context. */
@Entity(tableName = "chapters", indices = [Index("worldId")])
data class ChapterEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val title: String,
    val summary: String,
    val fromTurn: Int,
    val toTurn: Int,
    val storyTime: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "images", indices = [Index("worldId"), Index("turnIndex")])
data class ImageEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val filePath: String,
    val label: String,
    val caption: String = "",
    /** SCENE, PORTRAIT, LOCATION, ITEM, EVENT, CREATURE, MAP, COVER */
    val type: String = "SCENE",
    val subjectIds: String = "",
    val subjectNames: String = "",
    val locationName: String = "",
    val storyTime: String = "",
    val turnIndex: Int = 0,
    val prompt: String = "",
    val model: String = "",
    val provider: String = "",
    val usedReferences: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * The persistent look of a recurring subject. Holds the locked description plus the
 * reference images that later generations are seeded from.
 */
@Entity(tableName = "visual_identities", indices = [Index("worldId"), Index("subjectId")])
data class VisualIdentityEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val subjectId: String,
    /** CHARACTER, LOCATION, ITEM, CREATURE, FACTION */
    val subjectType: String = "CHARACTER",
    val subjectName: String = "",
    /** Identity that must never drift: face, build, distinguishing marks, architecture. */
    val canonicalDescription: String = "",
    /** Mutable surface: clothing, injuries, weather, season, damage. */
    val currentVariant: String = "",
    val primaryImageId: String? = null,
    val referenceImageIds: String = "",
    val evolutionLog: String = "",
    val updatedTurn: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Anything the continuity guard rejected or repaired, kept so the player can audit the world. */
@Entity(tableName = "continuity_issues", indices = [Index("worldId")])
data class ContinuityIssueEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val turnIndex: Int,
    /** INFO, WARNING, BLOCKED */
    val severity: String = "WARNING",
    val category: String = "",
    val description: String = "",
    val resolution: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One billable call to a provider. Written for every turn, repair, chapter summary,
 * creation step and image, so the player can see where their money went.
 */
@Entity(tableName = "usage_events", indices = [Index("worldId"), Index("createdAt")])
data class UsageEntity(
    @PrimaryKey val id: String = newId(),
    /** Blank for work done before a world exists, such as generating world concepts. */
    val worldId: String = "",
    val turnIndex: Int = -1,
    /** NARRATION, REPAIR, CHAPTER, CREATION, IMAGE */
    val purpose: String = "NARRATION",
    val provider: String = "",
    val model: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val images: Int = 0,
    val estimatedCost: Double = 0.0,
    /** False when the model has no price on record, so totals can say so honestly. */
    val costKnown: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
