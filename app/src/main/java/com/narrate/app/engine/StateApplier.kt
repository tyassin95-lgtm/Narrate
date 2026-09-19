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
    val newImageWorthySubjects: List<String>,
    /**
     * Where the player ended the turn, named from the state as it is after applying.
     *
     * The snapshot the turn began with does not know about a place this turn invented, so
     * asking it produced "unknown" in the log and the codex for any turn that moved somewhere
     * new - which is most of the interesting ones.
     */
    val currentLocationName: String = ""
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
        narration: String,
        /** What the player did: an ordinary turn, the opening, or one of the time controls. */
        kind: String = "ACTION"
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
        /**
         * A place named in passing, written onto the map where it actually belongs.
         *
         * A location created with no parent and no route is a node floating on its own in the
         * corner of the map, and every arrival at it reads as an unexplained jump. What the
         * name says about it comes first - "the third floor of 118 Maple" is inside 118 Maple -
         * and failing that it goes inside whatever contains where the player is standing, with
         * a route from where they set out.
         */
        /**
         * The street a place stands on, created if the world has not drawn it yet.
         *
         * A town is made of streets, and until this the app had no such thing: "1247 Maple
         * Street" was a building with no road under it, so nothing could be two doors down
         * from anything. Naming an address now brings the road into being, once, with a fixed
         * line on the plane that every address on it is arranged along.
         */
        suspend fun ensureStreet(name: String, districtName: String?): LocationEntity? {
            val streetName = Geography.streetNameIn(name) ?: return null
            findLocation(streetName)?.takeIf { it.type == Geography.STREET }?.let { return it }
            locations.firstOrNull { it.name.equals(streetName, true) }?.let { existing ->
                if (existing.type == Geography.STREET) return existing
            }
            val geometry = Geography.streetGeometry(streetName, districtName)
            val district = districtName?.let { named -> locations.firstOrNull { it.name.equals(named, true) } }
            val street = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = streetName,
                type = Geography.STREET,
                parentId = district?.id,
                description = "A street in ${districtName ?: "the area"}.",
                discovered = false,
                firstSeenTurn = turnIndex,
                mapX = geometry.x,
                mapY = geometry.y,
                spanAngle = geometry.angle,
                spanLength = geometry.length
            )
            locations += street
            repo.saveLocation(street)
            return street
        }

        /**
         * Where a place actually is, decided by the town rather than by the screen.
         *
         * In order of authority: the address it carries, the street it is on, the building it
         * is a room in, and only then anything the narration said about direction and
         * distance. A place with none of those goes somewhere sensible in its district and
         * stays there.
         */
        suspend fun placeOnMap(
            name: String,
            type: String,
            parent: LocationEntity?,
            here: LocationEntity?,
            hintText: String
        ): Triple<Float, Float, String?> {
            // A room is not somewhere else: it is inside its building, at the same point.
            if (Geography.sharesPositionWithParent(type) && parent != null) {
                return Triple(parent.mapX, parent.mapY, parent.streetId)
            }
            val districtName = generateSequence(parent ?: here) { child ->
                child.parentId?.let { id -> locations.firstOrNull { it.id == id } }
            }.firstOrNull { it.type == "DISTRICT" || it.type == "SETTLEMENT" }?.name

            if (type == Geography.STREET) {
                val geometry = Geography.streetGeometry(name, districtName)
                return Triple(geometry.x, geometry.y, null)
            }
            ensureStreet(name, districtName)?.let { street ->
                val number = Geography.addressNumberIn(name) ?: 0
                val (x, y) = Geography.positionOnStreet(street, number, name)
                return Triple(x, y, street.id)
            }
            val anchors = listOfNotNull(parent, here).filter { it.mapX > 0f || it.mapY > 0f }
            if (anchors.isEmpty()) {
                val (x, y) = Geography.looseNear(districtName, name, locations)
                return Triple(x, y, null)
            }
            val (x, y) = MapPlacement.place(
                anchors = anchors,
                existing = locations,
                seed = locations.size,
                hint = MapPlacement.Hint(text = hintText)
            )
            return Triple(x, y, null)
        }

        suspend fun ensureLocation(reference: String?, originHint: String): LocationEntity? {
            if (reference.isNullOrBlank()) return null
            findLocation(reference)?.let { return it }

            val here = locations.firstOrNull { it.id == snapshot.currentLocation?.id }
            // Somewhere the name itself places inside another known location.
            // The most specific place the name mentions: "the third floor of 118 Maple Street"
            // belongs to 118 Maple Street, not to Maple Street.
            val namedWithin = locations.filter { candidate ->
                candidate.name.length >= 4 &&
                    candidate.name.lowercase() != reference.trim().lowercase() &&
                    reference.lowercase().contains(candidate.name.lowercase())
            }.maxByOrNull { it.name.length }

            // Unless the name says it stands outside that place, in which case it belongs
            // beside it: a landing outside a flat is in the building, not in the flat.
            val beside = PlaceIdentity.standsOutside(reference)?.let { outsideName ->
                locations.filter { it.name.length >= 3 && outsideName.lowercase().contains(it.name.lowercase()) }
                    .maxByOrNull { it.name.length }
            }
            val parent = when {
                beside != null -> locations.firstOrNull { it.id == beside.parentId }
                namedWithin != null -> namedWithin
                else -> here?.let { locations.firstOrNull { p -> p.id == it.parentId } }
            }

            val type = PlaceIdentity.typeFromName(reference, "BUILDING")
            val (x, y, streetId) = placeOnMap(
                name = reference,
                type = type,
                parent = parent ?: beside,
                here = here,
                hintText = narration.takeLast(1200)
            )
            val geometry = if (type == Geography.STREET) {
                Geography.streetGeometry(reference.trim(), null)
            } else {
                null
            }
            val created = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = reference.trim(),
                type = type,
                parentId = parent?.id?.takeIf { it != here?.id || namedWithin != null },
                description = "First referenced $originHint.",
                // Whether the player knows about it is decided by what they saw and were
                // told, not by the fact that the world now has a row for it.
                discovered = false,
                firstSeenTurn = turnIndex,
                mapX = x,
                mapY = y,
                spanAngle = geometry?.angle ?: 0f,
                spanLength = geometry?.length ?: 0f,
                streetId = streetId,
                addressNumber = Geography.addressNumberIn(reference) ?: 0
            )
            locations += created
            repo.saveLocation(created)

            // And a way to get there: from the place it stands outside of, and from wherever
            // the player set out, so arriving is never a jump.
            listOfNotNull(beside, here).distinct().forEach { from ->
                if (from.id != created.id && links.none { linkJoins(it, from.id, created.id) }) {
                    val link = LocationLinkEntity(
                        id = newId(), worldId = worldId, fromId = from.id, toId = created.id,
                        mode = "on foot", description = "Found on the way."
                    )
                    links += link
                    repo.saveLinks(listOf(link))
                }
            }
            report.add(
                ContinuityGuard.SEVERITY_INFO,
                "location",
                "Referenced a place that was never declared: ${created.name}.",
                "Created it" + (parent?.let { " inside ${it.name}" } ?: "") +
                    " and linked it to ${here?.name ?: "the map"}, so the reference resolves."
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
                    notableFeatures = incoming.notableFeatures.ifBlank { existing.notableFeatures }
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
            val newType = PlaceIdentity.typeFromName(incoming.name, incoming.type)
            val (x, y, streetId) = placeOnMap(
                name = incoming.name,
                type = newType,
                parent = parent,
                here = locations.firstOrNull { it.id == snapshot.currentLocation?.id },
                hintText = listOf(incoming.description, incoming.travelTime, narration.takeLast(800))
                    .joinToString(" ")
            )
            val geometry = if (newType == Geography.STREET) {
                Geography.streetGeometry(incoming.name.trim(), parent?.name)
            } else {
                null
            }
            val created = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                type = newType,
                parentId = parent?.id,
                description = incoming.description,
                atmosphere = incoming.atmosphere,
                notableFeatures = incoming.notableFeatures,
                controlledBy = incoming.controlledBy,
                discovered = false,
                firstSeenTurn = turnIndex,
                mapX = x,
                mapY = y,
                spanAngle = geometry?.angle ?: 0f,
                spanLength = geometry?.length ?: 0f,
                streetId = streetId,
                addressNumber = Geography.addressNumberIn(incoming.name) ?: 0
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
                // Discovery is the player's, not the world's: see the knowledge pass.
                discovered = location.discovered
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
                        val moved = Possession.settle(
                            item = existing,
                            newHolder = updatedPlayer,
                            newLocationId = null,
                            transfer = if (existing.ownerId == null || existing.ownerId == updatedPlayer.id) {
                                Possession.HELD
                            } else {
                                // Picking up something that is somebody else's is borrowing it,
                                // whatever the player intends by it.
                                Possession.BORROWED
                            },
                            declaredOwner = null,
                            turnIndex = turnIndex
                        ).copy(ownerId = existing.ownerId ?: updatedPlayer.id)
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
                    val dropped = Possession.settle(
                        item = existing,
                        newHolder = null,
                        newLocationId = updatedPlayer.currentLocationId,
                        transfer = Possession.DROPPED,
                        declaredOwner = null,
                        turnIndex = turnIndex
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
                possession = when {
                    holder == null -> Possession.STORED
                    findCharacter(incoming.owner)?.id.let { it != null && it != holder.id } -> Possession.LENT
                    else -> Possession.HELD
                },
                firstSeenTurn = turnIndex
            )
            items += created
            repo.saveItems(listOf(created))
        }
        delta.itemsUpdate.forEach { update ->
            val item = repo.resolveItem(items, update.name) ?: return@forEach
            // Owner, holder, place and the kind of move are settled together: they are one
            // fact, and working them out separately is how a lent jacket became a gift.
            val merged = Possession.settle(
                item = item,
                newHolder = findCharacter(update.heldBy),
                newLocationId = findLocation(update.location)?.id,
                transfer = update.transfer,
                declaredOwner = findCharacter(update.owner),
                turnIndex = turnIndex
            ).copy(state = update.state ?: item.state)
            items[items.indexOfFirst { it.id == item.id }] = merged
            repo.saveItems(listOf(merged))
            if (Possession.outOnLoan(merged, snapshot.player?.id)) {
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "item-ownership",
                    "${merged.name} is with ${findCharacter(update.heldBy)?.name ?: "somebody else"}, " +
                        "on loan from the player.",
                    "Recorded as lent, not given. It is still the player's, and they may ask for it back."
                )
            }
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

        // 8b. Contact details. A channel is a fact, and it starts the turn it is handed over.
        val contactMemories = mutableListOf<MemoryEntity>()
        delta.contacts.filter { it.character.isNotBlank() }.forEach { incoming ->
            val character = findCharacter(incoming.character) ?: run {
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "contact",
                    "Contact details were recorded for someone unknown: ${incoming.character}.",
                    "Ignored. Introduce the character before exchanging details with them."
                )
                return@forEach
            }
            if (character.isPlayer) return@forEach
            val channel = ContactChannels.normalise(incoming.channel)
            val updated = character.copy(
                playerContact = if (incoming.established) {
                    ContactChannels.add(character.playerContact, channel)
                } else {
                    ContactChannels.remove(character.playerContact, channel)
                }
            )
            characters[characters.indexOfFirst { it.id == character.id }] = updated
            repo.saveCharacter(updated)
            val description = if (incoming.established) {
                "The player and ${character.name} can now reach each other by " +
                    "${ContactChannels.describe(channel)}${incoming.note.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            } else {
                "The player can no longer reach ${character.name} by ${ContactChannels.describe(channel)}."
            }
            contactMemories += MemoryEntity(
                id = newId(), worldId = worldId, kind = "FACT", text = description.trim('.', ' ') + ".",
                importance = 4, subjectIds = character.id, subjectNames = character.name,
                keywords = MemoryIndex.keywords(description).joinToString(" "),
                storyTime = world.storyTime, turnIndex = turnIndex
            )
        }
        if (contactMemories.isNotEmpty()) repo.saveMemories(contactMemories)

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
                storyTime = world.storyTime,
                turnIndex = turnIndex,
                // Where it came from, and therefore what it is allowed to outrank. Only what
                // the player wrote and what the world was built on may be pinned; a detail the
                // narrator invented mid-scene is remembered, not promoted to canon.
                provenance = when (incoming.kind.uppercase()) {
                    "CANON" -> "PLAYER_CANON"
                    "RULE" -> "WORLD_CANON"
                    "FACT" -> "STATED"
                    "DISCOVERY" -> "OBSERVED"
                    else -> "OBSERVED"
                },
                pinned = false
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

        // 11. The clock: one number, moved by the app, derived from nowhere else.
        //
        // The narrator no longer writes a time. It says how long the beat took, and the clock
        // moves by that. Three copies of the time - a string, a day number and a word for the
        // part of the day - is how a world slept through a night and woke up on the same day.
        val scene = SceneDirector.readScene(delta)
        val advance = SceneDirector.minutesFor(scene, kind).toLong()
        world = WorldClock.applyTo(world, world.clockMinute + advance).copy(
            turnCount = turnIndex + 1,
            updatedAt = System.currentTimeMillis(),
            lastPlayedAt = System.currentTimeMillis()
        )
        repo.saveWorld(world)
        val nowMinute = world.clockMinute

        // 11b. The calendar. An arrangement without a date on it is one the world cannot keep.
        val newEvents = delta.events.filter { it.title.isNotBlank() }.mapNotNull { incoming ->
            val event = Schedule.fromDelta(
                worldId = worldId,
                title = incoming.title,
                description = incoming.description,
                kind = incoming.kind,
                whenText = incoming.whenText,
                durationMinutes = incoming.durationMinutes,
                locationName = incoming.location,
                withNames = incoming.withNames,
                recurrence = incoming.recurrence,
                forPlayer = incoming.forWhom.isBlank() || incoming.forWhom.equals("player", true),
                turnIndex = turnIndex,
                nowMinute = nowMinute,
                stamp = WorldClock.of(world)
            )
            if (event == null) {
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "calendar",
                    "\"${incoming.title}\" was arranged for \"${incoming.whenText}\", which is not a time.",
                    "Not put in the calendar. Give a day and a time - \"Friday 8 PM\" - so the " +
                        "world knows when it is."
                )
                return@mapNotNull null
            }
            val at = WorldClock.stamp(event.startMinute, world)
            report.add(
                ContinuityGuard.SEVERITY_INFO, "calendar",
                "Put in the calendar: ${event.title} on ${at.full}.",
                "The world now knows when this is, and time can be skipped to it."
            )
            event.copy(
                locationId = findLocation(incoming.location)?.id,
                status = incoming.status.uppercase().ifBlank { "CONFIRMED" }
            )
        }
        if (newEvents.isNotEmpty()) repo.saveEvents(newEvents)

        // 11c. Clothing, which is state with a time on it rather than a sentence.
        delta.outfits.filter { it.character.isNotBlank() }.forEach { incoming ->
            val character = findCharacter(incoming.character) ?: return@forEach
            var updated = Wardrobe.wearing(character, incoming.wearing, incoming.context, nowMinute)
            if (incoming.temporary.isNotBlank()) {
                updated = Wardrobe.withDetail(updated, incoming.temporary, incoming.temporaryHours, nowMinute)
            }
            characters[characters.indexOfFirst { it.id == character.id }] = updated
            repo.saveCharacter(updated)
            visualSubjects += updated.name
        }
        // Glitter does not last four days. Anything whose time is up comes off the record.
        characters.toList().forEach { character ->
            val pruned = Wardrobe.pruned(character, nowMinute)
            if (pruned !== character) {
                characters[characters.indexOfFirst { it.id == character.id }] = pruned
                repo.saveCharacter(pruned)
            }
        }

        // 11d. One person, one row. A title is not a different human being.
        EntityResolver.duplicates(characters).take(3).forEach { (keep, duplicate) ->
            val merged = EntityResolver.merge(keep, duplicate)
            characters[characters.indexOfFirst { it.id == keep.id }] = merged
            characters.removeAll { it.id == duplicate.id }
            repo.saveCharacter(merged)
            repo.deleteCharacter(duplicate.id)
            report.add(
                ContinuityGuard.SEVERITY_INFO, "duplicate",
                "${duplicate.name} and ${keep.name} were the same person under two names.",
                "Merged into ${merged.name}, keeping everything either of them knew."
            )
        }

        // 12. Read the prose back and flag anything the state block failed to mention.
        ContinuityGuard.auditNarration(snapshot, narration, movedNames, characters).forEach {
            report.add(it.severity, it.category, it.description, it.resolution)
        }
        if (report.issues.isNotEmpty()) {
            repo.saveIssues(report.toEntities(worldId, turnIndex))
        }

        // The player's own row and the world row must agree about where the camera is. Models
        // differ in which one they update, and a world that thinks the player is somewhere they
        // are not shows the wrong room, the wrong people and the wrong exits.
        val playerNow = characters.firstOrNull { it.isPlayer }
        if (playerNow?.currentLocationId != null && playerNow.currentLocationId != world.currentLocationId) {
            world = world.copy(currentLocationId = playerNow.currentLocationId)
            repo.saveWorld(world)
            report.add(
                ContinuityGuard.SEVERITY_INFO,
                "location",
                "The world and the player disagreed about where the player was standing.",
                "The player's own position won: they are at " +
                    "${locations.firstOrNull { it.id == playerNow.currentLocationId }?.name ?: "their recorded place"}."
            )
        }

        // Sweep the same invariant over anything an older turn left in two places at once.
        val doubled = items.filter { it.holderId != null && it.locationId != null }
        if (doubled.isNotEmpty()) {
            val fixed = doubled.map { it.copy(locationId = null) }
            fixed.forEach { item -> items[items.indexOfFirst { it.id == item.id }] = item }
            repo.saveItems(fixed)
        }

        // 13. What the player's character now knows, which is not the same as what happened.
        //
        // Everything above updates the world. This decides how much of it reaches the player:
        // the map, the cast list, the codex and the next prompt all read from here, so a
        // secret in a column stays a secret until something in the story hands it over.
        learn(
            snapshot = snapshot,
            delta = delta,
            narration = narration,
            characters = characters,
            locations = locations,
            items = items,
            world = world,
            turnIndex = turnIndex,
            report = report
        )

        val here = world.currentLocationId
        val present = characters.filter { it.currentLocationId == here && it.status == "ALIVE" }.map { it.id }
        val hereName = locations.firstOrNull { it.id == here }?.name
            ?: snapshot.locationName(here).takeIf { it != "unknown" }
            ?: ""
        return ApplyResult(report, world, present, visualSubjects.distinct(), hereName)
    }

    /**
     * Writes down what the turn taught the player, and only that.
     *
     * Three things are automatic, because they need no narrator to declare them: a person the
     * player is in the room with can be seen and heard; a place they are standing in is a
     * place they know; a place the narration names out loud is a place they have heard of.
     * Everything else - a job, a home, a plan, a fear, a secret - arrives only through the
     * "revealed" block, which is the narrator saying in as many words that it came up.
     */
    private suspend fun learn(
        snapshot: WorldSnapshot,
        delta: StateDelta,
        narration: String,
        characters: List<CharacterEntity>,
        locations: MutableList<LocationEntity>,
        items: List<ItemEntity>,
        world: WorldEntity,
        turnIndex: Int,
        report: ContinuityGuard.Report
    ) {
        val worldId = world.id
        val rows = mutableListOf<KnowledgeEntity>()
        val alreadyKnown = mutableMapOf<String, MutableSet<String>>()
        snapshot.knowledge.forEach {
            alreadyKnown.getOrPut(it.subjectId) { mutableSetOf() } += it.field
        }
        fun known(id: String) = alreadyKnown.getOrPut(id) { mutableSetOf() }
        fun add(row: KnowledgeEntity) {
            if (row.field in known(row.subjectId)) return
            known(row.subjectId) += row.field
            rows += row
        }

        val storyTime = world.storyTime
        val player = characters.firstOrNull { it.isPlayer }
        val hereId = world.currentLocationId

        // How much of somebody the player actually got.
        //
        // In the room and named on the page is a person they have met. In the room but never
        // named is a face without a name. A voice through a window is a voice through a
        // window - which is what Jenna was, before this turned her into a full dossier the
        // moment she shouted about the laundry.
        val prose = narration.lowercase()
        fun namedInProse(npc: CharacterEntity): Boolean {
            val first = npc.name.split(' ').firstOrNull()?.lowercase()?.takeIf { it.length >= 3 }
                ?: return false
            return Regex("\\b${Regex.escape(first)}\\b").containsMatchIn(prose)
        }

        characters.filter { !it.isPlayer && it.status != "DEAD" }.forEach { npc ->
            val inTheRoom = npc.currentLocationId == hereId
            val nearby = !inTheRoom && snapshot.withinEarshot(npc.currentLocationId)
            when {
                inTheRoom -> PlayerKnowledge.onMeeting(
                    worldId, npc, turnIndex, storyTime, known(npc.id), named = namedInProse(npc)
                ).forEach(::add)
                nearby && namedInProse(npc) -> PlayerKnowledge.onMeeting(
                    worldId, npc, turnIndex, storyTime, known(npc.id), named = true
                ).forEach(::add)
                nearby -> PlayerKnowledge.onHearing(
                    worldId, npc,
                    descriptor = "a voice from ${snapshot.locationName(npc.currentLocationId)}",
                    turnIndex, storyTime, known(npc.id)
                ).forEach(::add)
            }
        }

        // Where they are standing, and everything that contains it: you can see the street
        // you are on and the building you walked into.
        generateSequence(locations.firstOrNull { it.id == hereId }) { child ->
            child.parentId?.let { id -> locations.firstOrNull { it.id == id } }
        }.take(6).forEach { place ->
            PlayerKnowledge.onArriving(worldId, place, turnIndex, storyTime, known(place.id)).forEach(::add)
        }

        // A turn that says outright that a place has been discovered is saying the player
        // found out about it.
        delta.locationsUpdate.filter { it.discovered == true && it.name.isNotBlank() }.forEach { update ->
            val place = repo.resolveLocation(locations, update.name) ?: return@forEach
            PlayerKnowledge.onHearingOf(
                worldId, place, "found this turn", turnIndex, storyTime, known(place.id)
            ).forEach(::add)
        }

        // Somewhere the narration named out loud is somewhere they have now heard of, even
        // if they have never set foot in it. That is how a city becomes known: by being
        // talked about, one name at a time.
        if (narration.isNotBlank()) {
            val prose = narration.lowercase()
            locations.filter { it.name.length >= 4 && PlayerKnowledge.EXISTS !in known(it.id) }
                .filter { prose.contains(it.name.lowercase()) }
                .take(6)
                .forEach { place ->
                    PlayerKnowledge.onHearingOf(
                        worldId, place, "it came up in the scene", turnIndex, storyTime, known(place.id)
                    ).forEach(::add)
                }
        }

        // Anything the player is carrying, they plainly know about.
        items.filter { it.holderId != null && it.holderId == player?.id }.forEach { item ->
            add(
                PlayerKnowledge.row(
                    worldId, PlayerKnowledge.ITEM, item.id, item.name, PlayerKnowledge.EXISTS,
                    source = PlayerKnowledge.SEEN, sourceDetail = "in their hands",
                    turnIndex = turnIndex, storyTime = storyTime
                )
            )
        }

        // A number that changed hands is something they know they have.
        delta.contacts.filter { it.established && it.character.isNotBlank() }.forEach { incoming ->
            val npc = repo.resolveCharacter(characters, incoming.character) ?: return@forEach
            // Writing to somebody is knowing their name, not knowing their face.
            if (npc.currentLocationId != hereId) {
                PlayerKnowledge.onRemoteContact(
                    worldId, npc, incoming.note.ifBlank { "a message" },
                    turnIndex, storyTime, known(npc.id)
                ).forEach(::add)
            }
            add(
                PlayerKnowledge.row(
                    worldId, PlayerKnowledge.CHARACTER, npc.id, npc.name, PlayerKnowledge.CONTACT,
                    value = ContactChannels.describe(ContactChannels.normalise(incoming.channel)),
                    source = PlayerKnowledge.TOLD, sourceDetail = npc.name,
                    turnIndex = turnIndex, storyTime = storyTime
                )
            )
        }

        // And whatever the narrator says came up.
        delta.revealed.filter { it.about.isNotBlank() }.forEach { reveal ->
            val field = PlayerKnowledge.normaliseField(reveal.field)
            val source = PlayerKnowledge.normaliseSource(reveal.how)
            val type = reveal.subjectType.trim().uppercase()
            val subject: Pair<String, String>? = when {
                type.startsWith("LOC") || type.startsWith("PLACE") ->
                    repo.resolveLocation(locations, reveal.about)?.let { it.id to it.name }
                        ?.also { pair ->
                            // Hearing about a place puts it on the map, whatever else was said.
                            locations.firstOrNull { it.id == pair.first }?.let { place ->
                                PlayerKnowledge.onHearingOf(
                                    worldId, place, reveal.from.ifBlank { "mentioned" },
                                    turnIndex, storyTime, known(place.id)
                                ).forEach(::add)
                            }
                        }
                type.startsWith("ITEM") || type.startsWith("OBJ") ->
                    repo.resolveItem(items, reveal.about)?.let { it.id to it.name }
                else -> repo.resolveCharacter(characters, reveal.about)?.let { it.id to it.name }
            }
            if (subject == null) {
                report.add(
                    ContinuityGuard.SEVERITY_INFO, "knowledge",
                    "The player was said to have learned something about \"${reveal.about}\", " +
                        "who or which is not in the world.",
                    "Not recorded. Create them in the same turn they are learned about."
                )
                return@forEach
            }
            add(
                PlayerKnowledge.row(
                    worldId,
                    when {
                        type.startsWith("LOC") || type.startsWith("PLACE") -> PlayerKnowledge.LOCATION
                        type.startsWith("ITEM") || type.startsWith("OBJ") -> PlayerKnowledge.ITEM
                        else -> PlayerKnowledge.CHARACTER
                    },
                    subject.first, subject.second, field,
                    value = reveal.value,
                    source = source,
                    sourceDetail = reveal.from.ifBlank { reveal.how },
                    turnIndex = turnIndex, storyTime = storyTime
                )
            )
        }

        if (rows.isNotEmpty()) repo.saveKnowledge(rows)

        // The map flag is a cache of one question: does the player know this place exists?
        val knownPlaces = (snapshot.knowledge + rows)
            .filter { it.subjectType == PlayerKnowledge.LOCATION && it.field == PlayerKnowledge.EXISTS }
            .map { it.subjectId }
            .toSet()
        val corrected = locations.filter { it.discovered != (it.id in knownPlaces) }
            .map { it.copy(discovered = it.id in knownPlaces) }
        if (corrected.isNotEmpty()) {
            corrected.forEach { fixed -> locations[locations.indexOfFirst { it.id == fixed.id }] = fixed }
            repo.saveLocations(corrected)
        }
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
