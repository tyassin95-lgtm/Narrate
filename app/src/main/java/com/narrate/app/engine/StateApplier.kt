package com.narrate.app.engine

import com.narrate.app.core.nameSimilarity
import com.narrate.app.core.newId
import com.narrate.app.core.truncate
import com.narrate.app.data.entity.*
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot

data class ApplyResult(
    val report: ContinuityGuard.Report,
    val world: WorldEntity,
    val presentCharacterIds: List<String>,
    val newImageWorthySubjects: List<String>
)

/**
 * Turns a model's state block into durable world state.
 *
 * Every write is name-resolved against what already exists, checked by [ContinuityGuard],
 * and repaired where possible, so a sloppy or hallucinated delta cannot corrupt a save.
 */
class StateApplier(private val repo: WorldRepository) {

    suspend fun apply(
        snapshot: WorldSnapshot,
        delta: StateDelta,
        turnIndex: Int,
        narration: String
    ): ApplyResult {
        val report = ContinuityGuard.Report()
        val worldId = snapshot.world.id

        val characters = snapshot.characters.toMutableList()
        val locations = snapshot.locations.toMutableList()
        val links = snapshot.links.toMutableList()
        val items = snapshot.items.toMutableList()
        val factions = snapshot.factions.toMutableList()
        val movedNames = mutableSetOf<String>()
        val visualSubjects = mutableListOf<String>()

        fun findLocation(reference: String?): LocationEntity? = repo.resolveLocation(locations, reference)
        fun findCharacter(reference: String?): CharacterEntity? = repo.resolveCharacter(characters, reference)

        /** Places referenced but never declared still have to exist, or people vanish. */
        suspend fun ensureLocation(reference: String?, originHint: String): LocationEntity? {
            if (reference.isNullOrBlank()) return null
            findLocation(reference)?.let { return it }
            val created = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = reference.trim(),
                type = "BUILDING",
                description = "First referenced $originHint.",
                discovered = true,
                firstSeenTurn = turnIndex
            )
            locations += created
            repo.saveLocation(created)
            report.add(
                ContinuityGuard.SEVERITY_INFO,
                "location",
                "Referenced a place that was never declared: ${created.name}.",
                "Created it so the reference resolves. Describe it when the player arrives."
            )
            return created
        }

        // 1. New geography first: everything else may point at it.
        delta.locationsNew.filter { it.name.isNotBlank() }.forEach { incoming ->
            val existing = findLocation(incoming.name)
            if (existing != null) {
                val merged = existing.copy(
                    description = incoming.description.ifBlank { existing.description },
                    atmosphere = incoming.atmosphere.ifBlank { existing.atmosphere },
                    notableFeatures = incoming.notableFeatures.ifBlank { existing.notableFeatures },
                    discovered = true
                )
                locations[locations.indexOfFirst { it.id == existing.id }] = merged
                repo.saveLocation(merged)
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "location",
                    "Tried to create ${incoming.name}, which already exists.",
                    "Merged into the existing place instead of duplicating it."
                )
                return@forEach
            }
            val parent = findLocation(incoming.parent)
            val created = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                type = incoming.type.uppercase().ifBlank { "BUILDING" },
                parentId = parent?.id,
                description = incoming.description,
                atmosphere = incoming.atmosphere,
                notableFeatures = incoming.notableFeatures,
                controlledBy = incoming.controlledBy,
                discovered = true,
                firstSeenTurn = turnIndex,
                mapX = (locations.size % 7) * 0.14f + 0.08f,
                mapY = (locations.size / 7) * 0.16f + 0.1f
            )
            locations += created
            repo.saveLocation(created)
            incoming.connectsTo.forEach { target ->
                val other = findLocation(target) ?: return@forEach
                if (links.none { linkJoins(it, created.id, other.id) }) {
                    val link = LocationLinkEntity(
                        id = newId(), worldId = worldId, fromId = created.id, toId = other.id,
                        travelTime = incoming.travelTime, mode = "on foot"
                    )
                    links += link
                    repo.saveLinks(listOf(link))
                }
            }
        }

        delta.linksNew.forEach { incoming ->
            val from = findLocation(incoming.from) ?: return@forEach
            val to = findLocation(incoming.to) ?: return@forEach
            if (links.any { linkJoins(it, from.id, to.id) }) return@forEach
            val link = LocationLinkEntity(
                id = newId(), worldId = worldId, fromId = from.id, toId = to.id,
                travelTime = incoming.travelTime, mode = incoming.mode.ifBlank { "on foot" },
                description = incoming.description
            )
            links += link
            repo.saveLinks(listOf(link))
        }

        delta.locationsUpdate.forEach { update ->
            val location = findLocation(update.name) ?: run {
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "location",
                    "Update referenced an unknown place: ${update.name}.",
                    "Ignored. Declare places in locations_new before updating them."
                )
                return@forEach
            }
            val merged = location.copy(
                description = update.description ?: location.description,
                atmosphere = update.atmosphere ?: location.atmosphere,
                currentState = update.stateChange?.let { change ->
                    if (location.currentState.isBlank()) change
                    else (location.currentState + " " + change).truncate(600)
                } ?: location.currentState,
                controlledBy = update.controlledBy ?: location.controlledBy,
                discovered = update.discovered ?: location.discovered
            )
            locations[locations.indexOfFirst { it.id == location.id }] = merged
            repo.saveLocation(merged)
            if (update.stateChange?.isNotBlank() == true) visualSubjects += merged.name
        }

        // 2. New people, after deduplication against the existing roster.
        delta.charactersNew.filter { it.name.isNotBlank() }.forEach { incoming ->
            // The protagonist is the player's, and only theirs. A narrator that introduces
            // someone sharing their name is contradicting canon, not adding to the cast.
            val player = characters.firstOrNull { it.isPlayer }
            if (player != null && nameSimilarity(player.name, incoming.name) >= 0.85) {
                report.add(
                    ContinuityGuard.SEVERITY_WARNING, "player-identity",
                    "Tried to introduce ${incoming.name}, which is the player character's name.",
                    "Ignored. The player character is authored by the player and cannot be duplicated."
                )
                return@forEach
            }
            val duplicate = ContinuityGuard.findExisting(characters, incoming.name)
            if (duplicate != null) {
                report.add(
                    ContinuityGuard.SEVERITY_WARNING, "duplicate-character",
                    "Tried to introduce ${incoming.name}, who already exists as ${duplicate.name}.",
                    "Treated as the same person; their established profile was kept."
                )
                val where = ensureLocation(incoming.location, "as ${duplicate.name}'s position")
                val merged = duplicate.copy(
                    currentLocationId = where?.id ?: duplicate.currentLocationId,
                    lastSeenTurn = turnIndex
                )
                characters[characters.indexOfFirst { it.id == duplicate.id }] = merged
                repo.saveCharacter(merged)
                return@forEach
            }
            val where = ensureLocation(
                incoming.location.ifBlank { snapshot.currentLocation?.name },
                "as ${incoming.name}'s location"
            )
            val home = findLocation(incoming.homeLocation) ?: where
            val created = CharacterEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                role = incoming.role,
                summary = incoming.summary,
                personality = incoming.personality,
                appearance = incoming.appearance,
                outfit = incoming.outfit,
                voice = incoming.voice,
                goals = incoming.goals,
                secrets = incoming.secrets,
                faction = incoming.faction,
                relationshipToPlayer = incoming.relationshipToPlayer,
                currentLocationId = where?.id,
                homeLocationId = home?.id,
                routine = incoming.routine,
                importance = incoming.importance.coerceIn(1, 5),
                firstSeenTurn = turnIndex,
                lastSeenTurn = turnIndex
            )
            characters += created
            repo.saveCharacter(created)
            visualSubjects += created.name
            if (created.appearance.isNotBlank()) {
                repo.saveVisualIdentity(
                    VisualIdentityEntity(
                        id = newId(), worldId = worldId, subjectId = created.id, subjectType = "CHARACTER",
                        subjectName = created.name, canonicalDescription = created.appearance,
                        currentVariant = created.outfit, updatedTurn = turnIndex
                    )
                )
            }
        }

        // 3. Updates to existing people, including every movement.
        delta.charactersUpdate.filter { it.name.isNotBlank() }.forEach { update ->
            val character = findCharacter(update.name) ?: run {
                report.add(
                    ContinuityGuard.SEVERITY_WARNING, "unknown-character",
                    "Updated a character who does not exist: ${update.name}.",
                    "Ignored. Introduce characters through characters_new first."
                )
                return@forEach
            }
            var moved = character
            if (!update.location.isNullOrBlank()) {
                val target = ensureLocation(update.location, "as ${character.name}'s new position")
                if (target != null && target.id != character.currentLocationId) {
                    ContinuityGuard.checkMovement(snapshot, character, target.id, update.movementReason)?.let {
                        report.add(it.severity, it.category, it.description, it.resolution)
                    }
                    val from = character.currentLocationId
                    if (from != null && links.none { linkJoins(it, from, target.id) }) {
                        val link = LocationLinkEntity(
                            id = newId(), worldId = worldId, fromId = from, toId = target.id,
                            mode = "route", description = update.movementReason.orEmpty()
                        )
                        links += link
                        repo.saveLinks(listOf(link))
                    }
                    moved = moved.copy(currentLocationId = target.id)
                    movedNames += character.name
                }
            }
            val knowledge = appendKnowledge(moved.knowledge, update.knowledgeAdd)
            val merged = moved.copy(
                status = update.status?.uppercase() ?: moved.status,
                outfit = update.outfit ?: moved.outfit,
                physicalState = update.physicalState ?: moved.physicalState,
                goals = update.goals ?: moved.goals,
                affinity = (moved.affinity + update.affinityDelta).coerceIn(-100, 100),
                trust = (moved.trust + update.trustDelta).coerceIn(-100, 100),
                knowledge = knowledge,
                relationshipToPlayer = update.relationshipToPlayer ?: moved.relationshipToPlayer,
                summary = update.note?.takeIf { it.isNotBlank() && moved.summary.isBlank() } ?: moved.summary,
                lastSeenTurn = turnIndex
            )
            characters[characters.indexOfFirst { it.id == character.id }] = merged
            repo.saveCharacter(merged)
            if (update.outfit != null || update.physicalState != null) visualSubjects += merged.name
        }

        // 4. The player.
        var world = snapshot.world
        val player = snapshot.player
        if (player != null) {
            var updatedPlayer = characters.firstOrNull { it.id == player.id } ?: player
            delta.player?.let { playerDelta ->
                if (!playerDelta.location.isNullOrBlank()) {
                    val target = ensureLocation(playerDelta.location, "as the player's destination")
                    if (target != null) {
                        val from = updatedPlayer.currentLocationId
                        if (from != null && from != target.id && links.none { linkJoins(it, from, target.id) }) {
                            val link = LocationLinkEntity(
                                id = newId(), worldId = worldId, fromId = from, toId = target.id, mode = "travelled"
                            )
                            links += link
                            repo.saveLinks(listOf(link))
                        }
                        updatedPlayer = updatedPlayer.copy(currentLocationId = target.id)
                        world = world.copy(currentLocationId = target.id)
                        val visited = locations.firstOrNull { it.id == target.id }
                        if (visited != null && !visited.visited) {
                            val marked = visited.copy(visited = true, discovered = true)
                            locations[locations.indexOfFirst { it.id == visited.id }] = marked
                            repo.saveLocation(marked)
                        }
                    }
                }
                updatedPlayer = updatedPlayer.copy(
                    physicalState = playerDelta.condition ?: updatedPlayer.physicalState,
                    outfit = playerDelta.outfit ?: updatedPlayer.outfit,
                    appearance = playerDelta.appearanceChange?.let { change ->
                        (updatedPlayer.appearance + " " + change).trim().truncate(1200)
                    } ?: updatedPlayer.appearance,
                    knowledge = appendKnowledge(updatedPlayer.knowledge, playerDelta.knowledgeAdd),
                    lastSeenTurn = turnIndex
                )
                if (playerDelta.outfit != null || playerDelta.appearanceChange != null || playerDelta.condition != null) {
                    visualSubjects += updatedPlayer.name
                }
                playerDelta.itemsGained.filter { it.isNotBlank() }.forEach { name ->
                    val existing = repo.resolveItem(items, name)
                    if (existing != null) {
                        val moved = existing.copy(
                            holderId = updatedPlayer.id,
                            ownerId = existing.ownerId ?: updatedPlayer.id,
                            locationId = null
                        )
                        items[items.indexOfFirst { it.id == existing.id }] = moved
                        repo.saveItems(listOf(moved))
                    } else {
                        val created = ItemEntity(
                            id = newId(), worldId = worldId, name = name.trim(),
                            ownerId = updatedPlayer.id, holderId = updatedPlayer.id,
                            firstSeenTurn = turnIndex
                        )
                        items += created
                        repo.saveItems(listOf(created))
                    }
                }
                playerDelta.itemsLost.filter { it.isNotBlank() }.forEach { name ->
                    val existing = repo.resolveItem(items, name) ?: return@forEach
                    val dropped = existing.copy(
                        holderId = null,
                        locationId = updatedPlayer.currentLocationId
                    )
                    items[items.indexOfFirst { it.id == existing.id }] = dropped
                    repo.saveItems(listOf(dropped))
                }
            }
            characters[characters.indexOfFirst { it.id == player.id }] = updatedPlayer
            repo.saveCharacter(updatedPlayer)
        }

        // 5. Objects.
        delta.itemsNew.filter { it.name.isNotBlank() }.forEach { incoming ->
            if (repo.resolveItem(items, incoming.name) != null) return@forEach
            val holder = findCharacter(incoming.heldBy)
            val place = findLocation(incoming.location)
            val created = ItemEntity(
                id = newId(), worldId = worldId, name = incoming.name.trim(),
                description = incoming.description, appearance = incoming.appearance,
                significance = incoming.significance,
                ownerId = findCharacter(incoming.owner)?.id ?: holder?.id,
                holderId = holder?.id,
                locationId = if (holder == null) place?.id ?: snapshot.currentLocation?.id else null,
                firstSeenTurn = turnIndex
            )
            items += created
            repo.saveItems(listOf(created))
        }
        delta.itemsUpdate.forEach { update ->
            val item = repo.resolveItem(items, update.name) ?: return@forEach
            val holder = findCharacter(update.heldBy)
            val place = findLocation(update.location)
            val merged = item.copy(
                // Ownership only moves when the narrator says it has. Handing someone your
                // jacket makes them the holder, never the owner.
                ownerId = findCharacter(update.owner)?.id ?: item.ownerId ?: holder?.id,
                holderId = holder?.id ?: if (update.location != null) null else item.holderId,
                locationId = place?.id ?: if (holder != null) null else item.locationId,
                state = update.state ?: item.state,
                updatedAt = System.currentTimeMillis()
            )
            items[items.indexOfFirst { it.id == item.id }] = merged
            repo.saveItems(listOf(merged))
        }

        // 6. Factions.
        delta.factions.filter { it.name.isNotBlank() }.forEach { incoming ->
            val existing = factions.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
            if (existing == null) {
                val created = FactionEntity(
                    id = newId(), worldId = worldId, name = incoming.name.trim(),
                    description = incoming.description, goals = incoming.goals,
                    leaderId = findCharacter(incoming.leader)?.id, territory = incoming.territory,
                    standingWithPlayer = incoming.standingDelta.coerceIn(-100, 100),
                    status = incoming.status ?: "ACTIVE"
                )
                factions += created
                repo.saveFactions(listOf(created))
            } else {
                val merged = existing.copy(
                    description = incoming.description.ifBlank { existing.description },
                    goals = incoming.goals.ifBlank { existing.goals },
                    leaderId = findCharacter(incoming.leader)?.id ?: existing.leaderId,
                    territory = incoming.territory.ifBlank { existing.territory },
                    standingWithPlayer = (existing.standingWithPlayer + incoming.standingDelta).coerceIn(-100, 100),
                    status = incoming.status ?: existing.status,
                    updatedAt = System.currentTimeMillis()
                )
                factions[factions.indexOfFirst { it.id == existing.id }] = merged
                repo.saveFactions(listOf(merged))
            }
        }

        // 7. Relationships between everyone, player included.
        delta.relationships.forEach { incoming ->
            val from = findCharacter(incoming.from) ?: return@forEach
            val to = findCharacter(incoming.to) ?: return@forEach
            if (from.id == to.id) return@forEach
            val existing = repo.relationshipDao.between(worldId, from.id, to.id)
            val merged = (existing ?: RelationshipEntity(
                id = newId(), worldId = worldId, fromId = from.id, toId = to.id
            )).let { relationship ->
                relationship.copy(
                    type = incoming.type.ifBlank { relationship.type },
                    descriptor = incoming.descriptor.ifBlank { relationship.descriptor },
                    strength = (relationship.strength + incoming.strengthDelta).coerceIn(-100, 100),
                    history = if (incoming.note.isBlank()) relationship.history
                    else (relationship.history + "\n- [turn $turnIndex] ${incoming.note}").trim().truncate(2000),
                    updatedTurn = turnIndex,
                    updatedAt = System.currentTimeMillis()
                )
            }
            repo.saveRelationships(listOf(merged))
        }

        // 8. Threads that keep the world in motion.
        val existingThreads = snapshot.threads.toMutableList()
        delta.threads.filter { it.title.isNotBlank() }.forEach { incoming ->
            val existing = existingThreads.firstOrNull { it.title.equals(incoming.title, ignoreCase = true) }
            val merged = (existing ?: ThreadEntity(
                id = newId(), worldId = worldId, title = incoming.title.trim(), createdTurn = turnIndex
            )).copy(
                description = incoming.description.ifBlank { existing?.description.orEmpty() },
                status = incoming.status.uppercase().ifBlank { existing?.status ?: "ACTIVE" },
                urgency = incoming.urgency.coerceIn(1, 5),
                involvedNames = incoming.involved.joinToString(", ").ifBlank { existing?.involvedNames.orEmpty() },
                nextBeat = incoming.nextBeat.ifBlank { existing?.nextBeat.orEmpty() },
                deadline = incoming.deadline.ifBlank { existing?.deadline.orEmpty() },
                updatedTurn = turnIndex,
                updatedAt = System.currentTimeMillis()
            )
            if (existing != null) existingThreads[existingThreads.indexOfFirst { it.id == existing.id }] = merged
            else existingThreads += merged
            repo.saveThreads(listOf(merged))
        }

        // 9. Memory: the reason the world does not forget.
        val memories = delta.memories.filter { it.text.isNotBlank() }.map { incoming ->
            val subjectIds = incoming.subjects.mapNotNull { subject ->
                findCharacter(subject)?.id ?: findLocation(subject)?.id
            }
            MemoryEntity(
                id = newId(),
                worldId = worldId,
                kind = incoming.kind.uppercase().ifBlank { "EVENT" },
                text = incoming.text.trim(),
                importance = incoming.importance.coerceIn(1, 5),
                subjectIds = subjectIds.joinToString(","),
                subjectNames = incoming.subjects.joinToString(", "),
                keywords = MemoryIndex.keywords(incoming.text).joinToString(" "),
                storyTime = delta.storyTime ?: world.storyTime,
                turnIndex = turnIndex,
                pinned = incoming.importance >= 5
            )
        }
        if (memories.isNotEmpty()) repo.saveMemories(memories)

        // 10. Visual identity: how the world looks, kept in step with how it is.
        delta.visualUpdates.filter { it.subject.isNotBlank() }.forEach { update ->
            val character = findCharacter(update.subject)
            val location = if (character == null) findLocation(update.subject) else null
            val subjectId = character?.id ?: location?.id ?: return@forEach
            val subjectName = character?.name ?: location?.name ?: update.subject
            val existing = repo.visualForSubject(subjectId)
            val identity = (existing ?: VisualIdentityEntity(
                id = newId(), worldId = worldId, subjectId = subjectId,
                subjectType = if (character != null) "CHARACTER" else "LOCATION",
                subjectName = subjectName,
                canonicalDescription = character?.appearance ?: location?.description.orEmpty()
            )).let { identity ->
                identity.copy(
                    canonicalDescription = if (update.permanent) {
                        (identity.canonicalDescription + " " + update.change).trim().truncate(1200)
                    } else identity.canonicalDescription,
                    currentVariant = update.change.truncate(400),
                    evolutionLog = (identity.evolutionLog + "\n- [turn $turnIndex] ${update.change}").trim().truncate(3000),
                    updatedTurn = turnIndex
                )
            }
            repo.saveVisualIdentity(identity)
            visualSubjects += subjectName
        }

        // 11. The clock and the world row itself.
        val storyTime = delta.storyTime?.trim().orEmpty().ifBlank { world.storyTime }
        world = world.copy(
            storyTime = storyTime,
            dayNumber = parseDay(storyTime) ?: world.dayNumber,
            timeOfDay = parseTimeOfDay(storyTime) ?: world.timeOfDay,
            turnCount = turnIndex + 1,
            updatedAt = System.currentTimeMillis(),
            lastPlayedAt = System.currentTimeMillis()
        )
        repo.saveWorld(world)

        // 12. Read the prose back and flag anything the state block failed to mention.
        ContinuityGuard.auditNarration(snapshot, narration, movedNames).forEach {
            report.add(it.severity, it.category, it.description, it.resolution)
        }
        if (report.issues.isNotEmpty()) {
            repo.saveIssues(report.toEntities(worldId, turnIndex))
        }

        val here = world.currentLocationId
        val present = characters.filter { it.currentLocationId == here && it.status == "ALIVE" }.map { it.id }
        return ApplyResult(report, world, present, visualSubjects.distinct())
    }

    private fun linkJoins(link: LocationLinkEntity, a: String, b: String): Boolean =
        (link.fromId == a && link.toId == b) || (link.fromId == b && link.toId == a)

    private fun appendKnowledge(existing: String, additions: List<String>): String {
        val clean = additions.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return existing
        val lines = existing.split("\n").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        clean.forEach { addition ->
            val entry = "- " + addition.removePrefix("- ")
            if (lines.none { it.equals(entry, ignoreCase = true) }) lines += entry
        }
        return lines.joinToString("\n").truncate(4000)
    }

    private fun parseDay(storyTime: String): Int? =
        Regex("day\\s+(\\d+)", RegexOption.IGNORE_CASE).find(storyTime)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseTimeOfDay(storyTime: String): String? {
        val lower = storyTime.lowercase()
        return listOf(
            "dawn", "sunrise", "morning", "midday", "noon", "afternoon", "dusk", "sunset",
            "evening", "night", "midnight", "late night"
        ).firstOrNull { lower.contains(it) }
    }
}
