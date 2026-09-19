package com.narrate.app.data.repo

import com.narrate.app.data.entity.*

/**
 * An immutable read of everything the engine needs to reason about a world on one turn.
 * Loading it in one shot keeps the prompt builder, simulator and continuity guard
 * looking at exactly the same state.
 */
data class WorldSnapshot(
    val world: WorldEntity,
    val player: CharacterEntity?,
    val characters: List<CharacterEntity>,
    val locations: List<LocationEntity>,
    val links: List<LocationLinkEntity>,
    val items: List<ItemEntity>,
    val factions: List<FactionEntity>,
    val relationships: List<RelationshipEntity>,
    val threads: List<ThreadEntity>,
    val chapters: List<ChapterEntity>,
    val recentTurns: List<TurnEntity>,
    /**
     * Turns too old to be replayed word for word and too recent to be in a chapter yet.
     *
     * Without these there is a hole in the middle of the story: a world with a twelve-turn
     * replay window and a chapter covering turns 0-3 simply had nothing to say about turns
     * four to six.
     */
    val earlierTurns: List<TurnEntity> = emptyList(),
    val memories: List<MemoryEntity>,
    val visualIdentities: List<VisualIdentityEntity>,
    /**
     * Everything the player's character has actually learned.
     *
     * Empty on a world built before this existed, which is why [PlayerKnowledge] treats an
     * empty table as "no knowledge system yet" rather than "the player knows nothing".
     */
    val knowledge: List<KnowledgeEntity> = emptyList(),
    /** The world's calendar: shifts, classes, plans, deadlines. */
    val events: List<EventEntity> = emptyList()
) {
    val npcs: List<CharacterEntity> get() = characters.filter { !it.isPlayer }

    val currentLocation: LocationEntity?
        get() = locations.firstOrNull { it.id == (player?.currentLocationId ?: world.currentLocationId) }

    fun locationById(id: String?): LocationEntity? = id?.let { key -> locations.firstOrNull { it.id == key } }

    fun characterById(id: String?): CharacterEntity? = id?.let { key -> characters.firstOrNull { it.id == key } }

    fun locationName(id: String?): String = locationById(id)?.name ?: "unknown"

    /** NPCs sharing the player's location: the ones who can actually speak this turn. */
    fun presentNpcs(): List<CharacterEntity> {
        val here = currentLocation?.id ?: return emptyList()
        return npcs.filter { it.currentLocationId == here && it.status == "ALIVE" }
    }

    /**
     * Places close enough that somebody there can be heard from where the player is standing.
     *
     * A man inside the house can call through the door to two people on the pavement without
     * teleporting: the pavement is outside the house, and a threshold is not a wall. Location
     * and presence are different questions, and treating them as one flagged a neighbour
     * shouting from his own hallway as a continuity failure.
     */
    fun withinEarshot(locationId: String?): Boolean {
        val here = currentLocation?.id ?: return false
        val there = locationId ?: return false
        if (there == here) return true
        val inside = locationById(here)
        val other = locationById(there)
        // One contains the other: a room and its building, a building and its street.
        if (inside?.parentId == there || other?.parentId == here) return true
        // Or they share a parent and one is the doorway onto the other - either because a
        // route joins them, or because one is named after the other: the pavement called
        // "Maple Street Outside 118 Maple Street" is the front step of 118 Maple Street.
        if (inside?.parentId != null && inside.parentId == other?.parentId) {
            if (links.any { !it.blocked && linkJoins(it, here, there) }) return true
            val a = inside.name.lowercase()
            val b = other.name.lowercase()
            return (a.length >= 4 && b.contains(a)) || (b.length >= 4 && a.contains(b))
        }
        return false
    }

    private fun linkJoins(link: LocationLinkEntity, a: String, b: String): Boolean =
        (link.fromId == a && link.toId == b) || (link.fromId == b && link.toId == a)

    /** Who is near enough to speak across a threshold without moving. */
    fun withinEarshotNpcs(): List<CharacterEntity> {
        val here = currentLocation?.id ?: return emptyList()
        return npcs.filter {
            it.status == "ALIVE" && it.currentLocationId != here && withinEarshot(it.currentLocationId)
        }
    }

    /** NPCs one link away: close enough to walk in, which the GM should know about. */
    fun nearbyNpcs(): List<CharacterEntity> {
        val here = currentLocation?.id ?: return emptyList()
        val adjacent = links.filter { !it.blocked && (it.fromId == here || it.toId == here) }
            .map { if (it.fromId == here) it.toId else it.fromId }
            .toSet()
        val childIds = locations.filter { it.parentId == here }.map { it.id }.toSet()
        val reachable = adjacent + childIds
        return npcs.filter { it.currentLocationId in reachable && it.status == "ALIVE" }
    }

    fun linksFrom(locationId: String): List<LocationLinkEntity> =
        links.filter { it.fromId == locationId || it.toId == locationId }

    fun playerInventory(): List<ItemEntity> =
        player?.let { pc -> items.filter { it.holderId == pc.id } } ?: emptyList()
}
