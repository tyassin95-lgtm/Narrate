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
    val memories: List<MemoryEntity>,
    val visualIdentities: List<VisualIdentityEntity>
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
