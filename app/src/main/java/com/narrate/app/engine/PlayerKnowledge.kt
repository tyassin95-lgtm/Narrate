package com.narrate.app.engine

import com.narrate.app.core.newId
import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.KnowledgeEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * The difference between what the world is and what the player's character knows about it.
 *
 * Everything in this app is generated at once. A world arrives with twenty streets on its map,
 * and every person in it arrives with a backstory, a home address, a routine and a secret. That
 * is what makes the simulation possible - people have somewhere to be when nobody is watching -
 * and it is also what made the game feel like reading the answer key. The player met a woman
 * and the codex immediately told them where she lived, what she wanted and what she was hiding.
 * Nothing was left to find out, so there was nothing to play for.
 *
 * There are three layers, and they must stay apart:
 *
 *   the world knows    - every generated field, every place, every secret. The simulator uses it.
 *   the narrator knows - the same, because it has to write a consistent world.
 *   the player knows   - only what this table says, and that is what the screen may show.
 *
 * Knowledge is written down when it is learned, with where it came from, so the codex can say
 * "she told you" and a rewind can take it away again. Nothing leaks into the map, the cast, the
 * suggestions or the player's own thoughts because it happens to exist in a column.
 */
object PlayerKnowledge {

    // Subjects.
    const val CHARACTER = "CHARACTER"
    const val LOCATION = "LOCATION"
    const val ITEM = "ITEM"
    const val FACTION = "FACTION"

    // The one field that means "this is on the player's map, or in their cast".
    const val EXISTS = "exists"

    // What is visible the moment somebody is in the room, or a place is stood in.
    const val NAME = "name"
    const val APPEARANCE = "appearance"
    const val OUTFIT = "outfit"
    const val VOICE = "voice"

    // What has to be learned.
    const val ROLE = "role"
    const val SUMMARY = "summary"
    const val PERSONALITY = "personality"
    const val BACKSTORY = "backstory"
    const val GOALS = "goals"
    const val FEARS = "fears"
    const val SECRETS = "secrets"
    const val HOME = "home"
    const val ROUTINE = "routine"
    const val FACTION_FIELD = "faction"
    const val RELATIONSHIP = "relationship"
    const val CONTACT = "contact"

    /** Seen for oneself, so no hearsay is involved. */
    const val SEEN = "SEEN"
    const val VISITED = "VISITED"
    const val TOLD = "TOLD"
    const val OVERHEARD = "OVERHEARD"
    const val READ = "READ"
    const val DEDUCED = "DEDUCED"

    /** Everything a person gives away simply by being in the room. */
    private val onSight = listOf(NAME, APPEARANCE, OUTFIT, VOICE)

    /** Everything about a person that has to come from somewhere. */
    val hiddenCharacterFields = listOf(
        ROLE, SUMMARY, PERSONALITY, BACKSTORY, GOALS, FEARS, SECRETS, HOME, ROUTINE,
        FACTION_FIELD, RELATIONSHIP
    )

    /**
     * True when nothing has been recorded for this world, so the old flags have to answer.
     *
     * A save from an older build has been played for thirty turns with its whole map on
     * screen. Hiding it retroactively would be a worse lie than the one being fixed. Every
     * world created since carries seed rows from the moment it is built, so an empty table
     * means exactly one thing: this world predates the knowledge system, and what it has
     * already shown the player stands.
     */
    @Suppress("UNUSED_PARAMETER")
    fun legacy(knowledge: List<KnowledgeEntity>, turnCount: Int): Boolean = knowledge.isEmpty()

    fun legacy(snapshot: WorldSnapshot): Boolean =
        legacy(snapshot.knowledge, snapshot.world.turnCount)

    fun knows(snapshot: WorldSnapshot, subjectId: String?, field: String): Boolean {
        if (subjectId == null) return false
        if (legacy(snapshot)) return true
        return snapshot.knowledge.any { it.subjectId == subjectId && it.field == field }
    }

    fun knownFields(knowledge: List<KnowledgeEntity>, subjectId: String): Set<String> =
        knowledge.filter { it.subjectId == subjectId }.map { it.field }.toSet()

    fun knownFields(snapshot: WorldSnapshot, subjectId: String): Set<String> =
        knownFields(snapshot.knowledge, subjectId)

    /** The ids of everything of one kind the player knows to exist. */
    fun knownIds(knowledge: List<KnowledgeEntity>, subjectType: String): Set<String> =
        knowledge.filter { it.subjectType == subjectType && it.field == EXISTS }
            .map { it.subjectId }
            .toSet()

    /** What the player was told, in the words they were told it, if anything. */
    fun learned(snapshot: WorldSnapshot, subjectId: String, field: String): KnowledgeEntity? =
        snapshot.knowledge.lastOrNull { it.subjectId == subjectId && it.field == field }

    // --- Who and where the player knows about -------------------------------------------

    /** The places on the player's map: the ones their character has any business knowing. */
    fun knownLocations(snapshot: WorldSnapshot): List<LocationEntity> {
        if (legacy(snapshot)) return snapshot.locations.filter { it.discovered }
        val known = snapshot.knowledge
            .filter { it.subjectType == LOCATION && it.field == EXISTS }
            .map { it.subjectId }
            .toSet()
        return snapshot.locations.filter { it.id in known }
    }

    /** The people the player has actually met or been told about. */
    fun knownCharacters(snapshot: WorldSnapshot): List<CharacterEntity> {
        if (legacy(snapshot)) return snapshot.npcs
        val known = snapshot.knowledge
            .filter { it.subjectType == CHARACTER && it.field == EXISTS }
            .map { it.subjectId }
            .toSet()
        return snapshot.npcs.filter { it.id in known }
    }

    /**
     * A person as the player knows them: every field they have not learned is blank.
     *
     * The entity shape is kept so the codex, the suggestions and the prompt can all read one
     * thing. A blank field means "not known yet", never "this person has no goals".
     */
    fun asKnown(snapshot: WorldSnapshot, character: CharacterEntity): CharacterEntity =
        asKnown(snapshot.knowledge, snapshot.world.turnCount, character)

    fun asKnown(
        knowledge: List<KnowledgeEntity>,
        turnCount: Int,
        character: CharacterEntity
    ): CharacterEntity {
        if (character.isPlayer || legacy(knowledge, turnCount)) return character
        val fields = knownFields(knowledge, character.id)
        fun keep(field: String, value: String) = if (field in fields) value else ""
        return character.copy(
            role = keep(ROLE, character.role),
            summary = keep(SUMMARY, character.summary),
            personality = keep(PERSONALITY, character.personality),
            backstory = keep(BACKSTORY, character.backstory),
            appearance = keep(APPEARANCE, character.appearance),
            outfit = keep(OUTFIT, character.outfit),
            voice = keep(VOICE, character.voice),
            goals = keep(GOALS, character.goals),
            fears = keep(FEARS, character.fears),
            secrets = keep(SECRETS, character.secrets),
            homeLocationId = if (HOME in fields) character.homeLocationId else null,
            routine = keep(ROUTINE, character.routine),
            faction = keep(FACTION_FIELD, character.faction),
            relationshipToPlayer = keep(RELATIONSHIP, character.relationshipToPlayer)
        )
    }

    // --- Learning -----------------------------------------------------------------------

    fun row(
        worldId: String,
        subjectType: String,
        subjectId: String,
        subjectName: String,
        field: String,
        value: String = "",
        source: String = SEEN,
        sourceDetail: String = "",
        turnIndex: Int = 0,
        storyTime: String = ""
    ) = KnowledgeEntity(
        id = newId(),
        worldId = worldId,
        subjectType = subjectType,
        subjectId = subjectId,
        subjectName = subjectName,
        field = field,
        value = value.truncate(400),
        source = source,
        sourceDetail = sourceDetail.truncate(120),
        turnIndex = turnIndex,
        storyTime = storyTime
    )

    /**
     * What a person gives away by being in the room, and a place by being stood in.
     *
     * Deliberately short. Seeing somebody tells you what they look like and what they sound
     * like; it does not tell you their job, where they sleep or what they want. Those arrive
     * through the narrator saying they did.
     */
    fun onMeeting(
        worldId: String,
        character: CharacterEntity,
        turnIndex: Int,
        storyTime: String,
        already: Set<String>
    ): List<KnowledgeEntity> = (listOf(EXISTS) + onSight)
        .filter { it !in already }
        .map { field ->
            row(
                worldId, CHARACTER, character.id, character.name, field,
                value = when (field) {
                    APPEARANCE -> character.appearance
                    OUTFIT -> character.outfit
                    VOICE -> character.voice
                    NAME -> character.name
                    else -> ""
                },
                source = SEEN,
                sourceDetail = "in the scene",
                turnIndex = turnIndex,
                storyTime = storyTime
            )
        }

    fun onArriving(
        worldId: String,
        location: LocationEntity,
        turnIndex: Int,
        storyTime: String,
        already: Set<String>
    ): List<KnowledgeEntity> = if (EXISTS in already) emptyList() else listOf(
        row(
            worldId, LOCATION, location.id, location.name, EXISTS,
            source = VISITED, sourceDetail = "went there",
            turnIndex = turnIndex, storyTime = storyTime
        )
    )

    /**
     * A place the player has only heard of is still on their map, drawn as somewhere they have
     * not been. Knowing a cafe exists is not the same as knowing the way in.
     */
    fun onHearingOf(
        worldId: String,
        location: LocationEntity,
        from: String,
        turnIndex: Int,
        storyTime: String,
        already: Set<String>
    ): List<KnowledgeEntity> = if (EXISTS in already) emptyList() else listOf(
        row(
            worldId, LOCATION, location.id, location.name, EXISTS,
            source = TOLD, sourceDetail = from,
            turnIndex = turnIndex, storyTime = storyTime
        )
    )

    // --- What the narrator is told ------------------------------------------------------

    /**
     * The two lists the narrator gets: what the player knows about the people in front of
     * them, and what it must not let them act on.
     */
    fun render(snapshot: WorldSnapshot): String {
        if (legacy(snapshot)) return ""
        val inScene = (snapshot.presentNpcs() + snapshot.withinEarshotNpcs()).distinctBy { it.id }
        if (inScene.isEmpty() && snapshot.npcs.isEmpty()) return ""

        return buildString {
            appendLine("## WHAT THE PLAYER'S CHARACTER ACTUALLY KNOWS")
            appendLine(
                "You know everything about this world. Your protagonist does not, and writing " +
                    "them as though they did is the fastest way to ruin a scene. Below is the " +
                    "whole of what they have learned about the people around them."
            )
            inScene.forEach { npc ->
                val fields = knownFields(snapshot, npc.id)
                val known = fields.filter { it != EXISTS }.sorted()
                val unknown = hiddenCharacterFields.filter { it !in fields && fieldHasContent(npc, it) }
                appendLine("- ${npc.name}: knows ${if (known.isEmpty()) "nothing beyond the sight of them" else known.joinToString(", ")}.")
                if (unknown.isNotEmpty()) {
                    appendLine("  Does NOT know: ${unknown.joinToString(", ")}. Your protagonist cannot refer to any of it.")
                }
            }
            val unmet = snapshot.npcs.filter { !knows(snapshot, it.id, EXISTS) }
            if (unmet.isNotEmpty()) {
                appendLine(
                    "- Never met, never heard of: ${unmet.take(12).joinToString(", ") { it.name }}. " +
                        "The player does not know these people exist. If one of them turns up, " +
                        "they are a stranger."
                )
            }
            val unknownPlaces = snapshot.locations.filter { !knows(snapshot, it.id, EXISTS) }
            if (unknownPlaces.isNotEmpty()) {
                appendLine(
                    "- Places they have never heard of: ${unknownPlaces.take(12).joinToString(", ") { it.name }}. " +
                        "They cannot go somewhere they do not know about, and cannot name it."
                )
            }
            appendLine(
                "When something crosses from your knowledge into theirs - she mentions her " +
                    "sister, a sign gives the street a name, somebody says where they work - " +
                    "play it out and record it in \"revealed\". That is the only way it reaches " +
                    "the player's codex, and the only way they may use it later."
            )
        }
    }

    private fun fieldHasContent(character: CharacterEntity, field: String): Boolean = when (field) {
        ROLE -> character.role.isNotBlank()
        SUMMARY -> character.summary.isNotBlank()
        PERSONALITY -> character.personality.isNotBlank()
        BACKSTORY -> character.backstory.isNotBlank()
        GOALS -> character.goals.isNotBlank()
        FEARS -> character.fears.isNotBlank()
        SECRETS -> character.secrets.isNotBlank()
        HOME -> character.homeLocationId != null
        ROUTINE -> character.routine.isNotBlank()
        FACTION_FIELD -> character.faction.isNotBlank()
        RELATIONSHIP -> character.relationshipToPlayer.isNotBlank()
        else -> false
    }

    /** Normalises whatever the narrator wrote in "field" onto one of the fields above. */
    fun normaliseField(raw: String): String {
        val value = raw.trim().lowercase().replace(' ', '_')
        return when {
            value.isBlank() -> SUMMARY
            value.contains("exist") || value.contains("where_it_is") -> EXISTS
            value.contains("name") -> NAME
            value.contains("appear") || value.contains("look") -> APPEARANCE
            value.contains("outfit") || value.contains("wear") || value.contains("cloth") -> OUTFIT
            value.contains("voice") || value.contains("accent") -> VOICE
            value.contains("job") || value.contains("work") || value.contains("role") ||
                value.contains("occupation") || value.contains("profession") -> ROLE
            value.contains("personality") || value.contains("manner") || value.contains("temper") -> PERSONALITY
            value.contains("backstory") || value.contains("history") || value.contains("past") -> BACKSTORY
            value.contains("goal") || value.contains("want") || value.contains("plan") ||
                value.contains("ambition") -> GOALS
            value.contains("fear") || value.contains("afraid") -> FEARS
            value.contains("secret") || value.contains("hiding") -> SECRETS
            value.contains("home") || value.contains("address") || value.contains("live") ||
                value.contains("flat") || value.contains("apartment") -> HOME
            value.contains("routine") || value.contains("schedule") || value.contains("habit") -> ROUTINE
            value.contains("faction") || value.contains("affiliation") -> FACTION_FIELD
            value.contains("relationship") || value.contains("feel") || value.contains("toward") -> RELATIONSHIP
            value.contains("number") || value.contains("contact") || value.contains("email") -> CONTACT
            else -> SUMMARY
        }
    }

    fun normaliseSource(raw: String): String {
        val value = raw.trim().uppercase()
        return when {
            value.contains("TOLD") || value.contains("SAID") || value.contains("SAY") -> TOLD
            value.contains("OVERHEAR") || value.contains("HEARD") -> OVERHEARD
            value.contains("READ") || value.contains("SIGN") || value.contains("LETTER") ||
                value.contains("DOCUMENT") || value.contains("MESSAGE") -> READ
            value.contains("VISIT") || value.contains("WENT") -> VISITED
            value.contains("DEDUC") || value.contains("GUESS") || value.contains("WORK") -> DEDUCED
            else -> SEEN
        }
    }
}
