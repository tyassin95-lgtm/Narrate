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
    /**
     * The world's clock, as minutes since midnight on [calendarEpoch].
     *
     * This is the only time in the app. Everything else - the day number, the weekday, the
     * hour shown on a turn, when a shift starts, how long somebody has been asleep - is
     * derived from it. Two playthroughs in a row had a world whose day number never advanced
     * through a night's sleep while the narration talked about Friday, because "day" and
     * "time of day" were separate strings that different parts of the app wrote independently.
     */
    val clockMinute: Long = 21 * 60,
    /** The real calendar date the world's first day falls on, ISO-8601. Gives weekdays. */
    val calendarEpoch: String = "",
    /** Kept as a rendered display string so old rows and exports still read correctly. */
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
    /**
     * When this outfit was put on, and what for.
     *
     * A party dress is a party dress for one evening. Four days later Liv was still described
     * in it, with the same glitter on the same cheek, because an outfit was a string with no
     * time attached and the narrator had nothing to tell it the night was over.
     */
    val outfitSetAt: Long = 0,
    /** PARTY, WORK, CLASS, HOME, SLEEP, GOING_OUT, OUTDOORS, CASUAL. */
    val outfitContext: String = "CASUAL",
    /**
     * Glitter, makeup, wet hair, a stamp on the back of a hand: things that are true for a
     * few hours and then are not. Stored as "detail|until=<clock minute>" per line.
     */
    val temporaryLook: String = "",
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
    /**
     * How the player can reach this person when they are not in the room, as a comma-separated
     * list of channels (PHONE, EMAIL, SOCIAL, RADIO...).
     *
     * Empty means exactly what it says: there is no way to contact them and no thread with
     * them to check. Knowing someone exists, hearing about them, even meeting them, does not
     * put their number in the player's phone - only an exchange that happened on the page does.
     */
    val playerContact: String = "",
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
    /**
     * Where this is on the city plane, in absolute coordinates the whole world shares.
     *
     * Not relative to a parent, and not a slot in a grid: a single plane, so "two blocks
     * north" means two blocks north of the same thing for everybody.
     */
    val mapX: Float = 0f,
    val mapY: Float = 0f,
    /** For a STREET: which way it runs, in degrees clockwise from east. */
    val spanAngle: Float = 0f,
    /** For a STREET: how long it is on the plane. Zero for anything that is not a street. */
    val spanLength: Float = 0f,
    /** The street this sits on, for a building with an address. */
    val streetId: String? = null,
    /** The number on the door, when there is one, so buildings sit in order along a street. */
    val addressNumber: Int = 0,
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
    /**
     * Who the object belongs to, which is not the same as who is holding it. A jacket lent to
     * someone shivering is still the lender's, and the world has to keep knowing that.
     */
    val ownerId: String? = null,
    /** Who physically has it right now. */
    val holderId: String? = null,
    val locationId: String? = null,
    /**
     * What kind of possession this is, which owner and holder alone cannot express.
     *
     * HELD (with its owner), LENT (the owner let somebody else have it for now), BORROWED
     * (the holder is not the owner and did not ask), STORED (put away somewhere on purpose),
     * DROPPED (left where it fell), LOST (nobody knows where), DESTROYED (gone for good).
     * A jacket lent to somebody shivering is LENT, and lending is not giving: "give it back"
     * only makes sense in one direction, and the world has to know which.
     */
    val possession: String = "HELD",
    /** Every hand it has passed through, one line per turn, newest last. */
    val history: String = "",
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
    /**
     * How this came to be true: PLAYER_CANON, WORLD_CANON, OBSERVED, STATED, INFERRED,
     * DOCUMENT, REMOTE, SPECULATIVE. Only the first two may be pinned, because only those
     * two are things the world actually promised.
     */
    val provenance: String = "OBSERVED",
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

/**
 * One thing the player's character actually knows, and how they came to know it.
 *
 * The world is generated all at once: every NPC arrives with a backstory, a home address, a
 * secret and a routine, and every street in the city is on the map before the player has
 * walked down any of them. That is how the world can be simulated - but handing all of it to
 * the player on turn one is what made the game feel like reading a wiki about a place instead
 * of being in it.
 *
 * So there are two layers now. The world knows everything. The player's character knows what
 * this table says they know, and nothing else: what they saw, what they were told, what they
 * read, what they worked out. The map, the codex, the narration, the suggestions and the
 * prompt all read from here, so a secret cannot leak simply because it exists in a column.
 */
@Entity(
    tableName = "player_knowledge",
    indices = [Index("worldId"), Index("subjectId"), Index("subjectType")]
)
data class KnowledgeEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    /** CHARACTER, LOCATION, ITEM, FACTION, THREAD */
    val subjectType: String,
    val subjectId: String,
    /** Kept alongside the id so the codex can show what was learned about whom. */
    val subjectName: String = "",
    /**
     * Which part of the subject this covers: "exists", "name", "appearance", "outfit",
     * "role", "goals", "secrets", "home", "routine", and so on. "exists" is the one that
     * puts a place on the map or a person in the cast.
     */
    val field: String,
    /** What the player learned, in the terms they learned it. Blank for a bare "exists". */
    val value: String = "",
    /** SEEN, VISITED, TOLD, OVERHEARD, READ, DEDUCED, SHOWN. */
    val source: String = "SEEN",
    /** Who or what it came from: a person's name, a document, a sign on a door. */
    val sourceDetail: String = "",
    val turnIndex: Int = 0,
    val storyTime: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Something that is going to happen, or is meant to.
 *
 * A world without a calendar cannot answer the only question that makes time worth skipping:
 * what is next. The playthrough that prompted this had Adrian agree to see Liv on Friday and
 * then had no idea when Friday was - the arrangement lived in a sentence in a thread summary,
 * so "let time pass" could only advance the clock by a minute and hope.
 *
 * A shift, a class, a deadline, a promise to meet somebody at eight: all the same shape, all
 * anchored to the world clock rather than to a phrase.
 */
@Entity(tableName = "events", indices = [Index("worldId"), Index("startMinute")])
data class EventEntity(
    @PrimaryKey val id: String = newId(),
    val worldId: String,
    val title: String,
    val description: String = "",
    /** SHIFT, CLASS, MEETING, APPOINTMENT, DEADLINE, PLAN, TRAVEL, OTHER. */
    val kind: String = "PLAN",
    /** On the world clock, in minutes since the calendar epoch. */
    val startMinute: Long = 0,
    val durationMinutes: Int = 60,
    /** Blank for a one-off; otherwise "WEEKLY:MON,WED" or "DAILY". */
    val recurrence: String = "",
    val locationId: String? = null,
    val locationName: String = "",
    /** Who it is with, by name, comma separated. */
    val withNames: String = "",
    /** SCHEDULED, CONFIRMED, TENTATIVE, DONE, MISSED, CANCELLED. */
    val status: String = "SCHEDULED",
    /** True when the player knows about it. An NPC's own shift may be none of their business. */
    val knownToPlayer: Boolean = true,
    /** Whose commitment it is: the player, an NPC, or the world. */
    val forPlayer: Boolean = true,
    val createdTurn: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
