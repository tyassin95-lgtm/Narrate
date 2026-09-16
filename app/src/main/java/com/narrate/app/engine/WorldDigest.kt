package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Renders the authoritative world state as text for the model.
 *
 * This is the heart of continuity: the model is never asked to remember anything. Each
 * turn it is handed the current, verified truth - who exists, where they are, what they
 * know, what has changed - and told that this file outranks its own recollection.
 */
object WorldDigest {

    fun worldBible(snapshot: WorldSnapshot): String = buildString {
        val world = snapshot.world
        appendLine("# WORLD BIBLE (immutable canon - never contradict)")
        appendLine("Title: ${world.name}")
        if (world.tagline.isNotBlank()) appendLine("Tagline: ${world.tagline}")
        if (world.genre.isNotBlank()) appendLine("Genre: ${world.genre}")
        if (world.tone.isNotBlank()) appendLine("Tone: ${world.tone}")
        if (world.premise.isNotBlank()) appendLine("Premise: ${world.premise}")
        if (world.history.isNotBlank()) appendLine("Established history: ${world.history}")
        if (world.rules.isNotBlank()) appendLine("Laws of this world (absolute): ${world.rules}")
        if (world.themes.isNotBlank()) appendLine("Themes: ${world.themes}")
        if (world.contentGuidelines.isNotBlank()) appendLine("Content guidance: ${world.contentGuidelines}")
        if (world.customPrompt.isNotBlank()) {
            appendLine()
            appendLine("## THE PLAYER'S OWN DIRECTION (highest authority after these instructions)")
            appendLine(world.customPrompt)
        }
    }

    fun playerDossier(snapshot: WorldSnapshot): String {
        val player = snapshot.player ?: return ""
        return buildString {
            appendLine("# PLAYER CHARACTER (the protagonist - written by the player, never rewrite them)")
            appendLine("Name: ${player.name}")
            if (player.role.isNotBlank()) appendLine("Role: ${player.role}")
            if (player.summary.isNotBlank()) appendLine("Summary: ${player.summary}")
            if (player.personality.isNotBlank()) appendLine("Personality: ${player.personality}")
            if (player.backstory.isNotBlank()) appendLine("Backstory: ${player.backstory}")
            if (player.appearance.isNotBlank()) appendLine("Appearance (locked): ${player.appearance}")
            if (player.outfit.isNotBlank()) appendLine("Currently wearing: ${player.outfit}")
            if (player.physicalState.isNotBlank()) appendLine("Physical condition: ${player.physicalState}")
            if (player.voice.isNotBlank()) appendLine("Manner of speech: ${player.voice}")
            if (player.goals.isNotBlank()) appendLine("Goals: ${player.goals}")
            if (player.fears.isNotBlank()) appendLine("Fears: ${player.fears}")
            if (player.secrets.isNotBlank()) appendLine("Secrets: ${player.secrets}")
            if (player.knowledge.isNotBlank()) appendLine("Knows: ${player.knowledge.truncate(1200)}")
            val inventory = snapshot.playerInventory()
            if (inventory.isNotEmpty()) {
                appendLine("Carrying: " + inventory.joinToString(", ") {
                    it.name + (if (it.state.isNotBlank()) " (${it.state})" else "")
                })
            }
        }
    }

    /** Where everything is. The single source of truth for geography and presence. */
    fun currentState(snapshot: WorldSnapshot): String = buildString {
        val world = snapshot.world
        appendLine("# CURRENT STATE (authoritative - outranks anything you remember)")
        appendLine("Story time: ${world.storyTime} (day ${world.dayNumber}, ${world.timeOfDay})")
        appendLine("Turns played: ${world.turnCount}")

        val here = snapshot.currentLocation
        if (here != null) {
            appendLine()
            appendLine("## THE PLAYER IS HERE: ${here.name} (${here.type})")
            if (here.description.isNotBlank()) appendLine("Description: ${here.description}")
            if (here.atmosphere.isNotBlank()) appendLine("Atmosphere: ${here.atmosphere}")
            if (here.notableFeatures.isNotBlank()) appendLine("Notable features: ${here.notableFeatures}")
            if (here.currentState.isNotBlank()) appendLine("Current condition: ${here.currentState}")
            if (here.controlledBy.isNotBlank()) appendLine("Controlled by: ${here.controlledBy}")
            snapshot.locationById(here.parentId)?.let { appendLine("Located within: ${it.name}") }
            val exits = snapshot.linksFrom(here.id).map { link ->
                val otherId = if (link.fromId == here.id) link.toId else link.fromId
                val other = snapshot.locationName(otherId)
                val detail = listOfNotNull(
                    link.travelTime.takeIf { it.isNotBlank() },
                    link.mode.takeIf { it.isNotBlank() },
                    if (link.blocked) "BLOCKED" else null
                ).joinToString(", ")
                if (detail.isBlank()) other else "$other ($detail)"
            }
            val children = snapshot.locations.filter { it.parentId == here.id }.map { it.name }
            if (exits.isNotEmpty()) appendLine("Exits from here: ${exits.joinToString("; ")}")
            if (children.isNotEmpty()) appendLine("Contains: ${children.joinToString("; ")}")
        }

        val present = snapshot.presentNpcs()
        appendLine()
        if (present.isEmpty()) {
            appendLine("## PRESENT: nobody else is in this location right now.")
            appendLine("Do not have any NPC speak or act here unless they plausibly arrive, and say how they arrive.")
        } else {
            appendLine("## PRESENT IN THIS LOCATION (only these characters can speak or act here right now)")
            present.forEach { appendLine(characterLine(it, snapshot)) }
        }

        val nearby = snapshot.nearbyNpcs()
        if (nearby.isNotEmpty()) {
            appendLine()
            appendLine("## NEARBY (one move away - could plausibly arrive)")
            nearby.forEach { appendLine("- ${it.name}: at ${snapshot.locationName(it.currentLocationId)}") }
        }
        appendLine()
        appendLine(worldMap(snapshot))
        appendLine()
        appendLine(npcRoster(snapshot))
        val factions = snapshot.factions.filter { it.status != "DISSOLVED" }
        if (factions.isNotEmpty()) {
            appendLine()
            appendLine("## FACTIONS")
            factions.forEach { faction ->
                appendLine(
                    "- ${faction.name}: ${faction.description.truncate(180)}" +
                        (if (faction.goals.isNotBlank()) " Goals: ${faction.goals.truncate(120)}." else "") +
                        " Standing with player: ${faction.standingWithPlayer}."
                )
            }
        }
        val significantItems = snapshot.items.filter { it.significance.isNotBlank() || it.holderId != null }
        if (significantItems.isNotEmpty()) {
            appendLine()
            appendLine("## TRACKED OBJECTS")
            significantItems.take(40).forEach { item ->
                val holder = snapshot.characterById(item.holderId)?.name
                val where = holder ?: snapshot.locationById(item.locationId)?.name ?: "unplaced"
                appendLine(
                    "- ${item.name}: with $where." +
                        (if (item.state.isNotBlank()) " State: ${item.state}." else "") +
                        (if (item.significance.isNotBlank()) " ${item.significance.truncate(140)}" else "")
                )
            }
        }
    }

    private fun characterLine(character: CharacterEntity, snapshot: WorldSnapshot): String = buildString {
        append("- ${character.name}")
        if (character.role.isNotBlank()) append(" (${character.role})")
        append(": ")
        if (character.summary.isNotBlank()) append(character.summary.truncate(200) + " ")
        if (character.personality.isNotBlank()) append("Personality: ${character.personality.truncate(160)}. ")
        if (character.voice.isNotBlank()) append("Speech: ${character.voice.truncate(120)}. ")
        if (character.goals.isNotBlank()) append("Wants: ${character.goals.truncate(140)}. ")
        if (character.relationshipToPlayer.isNotBlank()) {
            append("Toward the player: ${character.relationshipToPlayer.truncate(140)}. ")
        }
        append("Affinity ${character.affinity}, trust ${character.trust}. ")
        if (character.outfit.isNotBlank()) append("Wearing: ${character.outfit.truncate(120)}. ")
        if (character.physicalState.isNotBlank()) append("Condition: ${character.physicalState.truncate(120)}. ")
        if (character.knowledge.isNotBlank()) append("Knows: ${character.knowledge.truncate(320)}. ")
        if (character.secrets.isNotBlank()) append("Hiding: ${character.secrets.truncate(160)}.")
    }

    /** Full geography, hierarchy and routes. The GM may not invent or redraw this. */
    fun worldMap(snapshot: WorldSnapshot): String = buildString {
        appendLine("## WORLD MAP (the complete known geography - do not invent or move places)")
        if (snapshot.locations.isEmpty()) {
            appendLine("(no locations recorded yet)")
            return@buildString
        }
        val roots = snapshot.locations.filter { location ->
            location.parentId == null || snapshot.locations.none { it.id == location.parentId }
        }
        fun render(location: LocationEntity, depth: Int) {
            val indent = "  ".repeat(depth)
            val flags = buildList {
                if (!location.discovered) add("undiscovered")
                if (location.currentState.isNotBlank()) add(location.currentState.truncate(80))
            }
            appendLine(
                "$indent- ${location.name} [${location.type}]" +
                    (if (flags.isEmpty()) "" else " (${flags.joinToString("; ")})") +
                    (if (location.description.isNotBlank()) ": ${location.description.truncate(150)}" else "")
            )
            snapshot.locations.filter { it.parentId == location.id }.forEach { render(it, depth + 1) }
        }
        roots.forEach { render(it, 0) }
        if (snapshot.links.isNotEmpty()) {
            appendLine("Routes:")
            snapshot.links.take(80).forEach { link ->
                appendLine(
                    "  - ${snapshot.locationName(link.fromId)} <-> ${snapshot.locationName(link.toId)}" +
                        (if (link.travelTime.isNotBlank()) " (${link.travelTime} ${link.mode})" else "") +
                        (if (link.blocked) " [BLOCKED]" else "")
                )
            }
        }
    }

    /** Every living, relevant NPC with their current position. Prevents teleporting. */
    fun npcRoster(snapshot: WorldSnapshot): String = buildString {
        appendLine("## CHARACTER POSITIONS (every NPC is exactly where this says)")
        val npcs = snapshot.npcs.sortedWith(compareByDescending<CharacterEntity> { it.importance }.thenBy { it.name })
        if (npcs.isEmpty()) {
            appendLine("(no NPCs recorded yet)")
            return@buildString
        }
        npcs.take(60).forEach { npc ->
            val place = snapshot.locationName(npc.currentLocationId)
            val status = if (npc.status != "ALIVE") " [${npc.status}]" else ""
            appendLine(
                "- ${npc.name}$status: at $place." +
                    (if (npc.routine.isNotBlank()) " Routine: ${npc.routine.truncate(120)}." else "") +
                    " Last seen on turn ${npc.lastSeenTurn}."
            )
        }
        if (npcs.size > 60) appendLine("(+${npcs.size - 60} more minor characters on record)")
    }

    fun relationshipDigest(snapshot: WorldSnapshot): String {
        if (snapshot.relationships.isEmpty()) return ""
        return buildString {
            appendLine("## RELATIONSHIPS")
            snapshot.relationships.take(50).forEach { relationship ->
                val from = snapshot.characterById(relationship.fromId)?.name ?: return@forEach
                val to = snapshot.characterById(relationship.toId)?.name ?: return@forEach
                appendLine(
                    "- $from -> $to: ${relationship.type} (${relationship.strength}). " +
                        relationship.descriptor.truncate(140)
                )
            }
        }
    }

    fun threadDigest(snapshot: WorldSnapshot): String {
        val active = snapshot.threads.filter { it.status == "ACTIVE" || it.status == "DORMANT" }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("## ONGOING THREADS (these keep developing whether or not the player engages)")
            active.sortedByDescending { it.urgency }.take(20).forEach { thread ->
                appendLine(
                    "- [${thread.status}, urgency ${thread.urgency}] ${thread.title}: " +
                        thread.description.truncate(220) +
                        (if (thread.nextBeat.isNotBlank()) " Next development: ${thread.nextBeat.truncate(160)}." else "") +
                        (if (thread.deadline.isNotBlank()) " Deadline: ${thread.deadline}." else "")
                )
            }
        }
    }

    fun history(snapshot: WorldSnapshot): String = buildString {
        if (snapshot.chapters.isNotEmpty()) {
            appendLine("## THE STORY SO FAR (compacted history - all of it happened)")
            snapshot.chapters.forEach { chapter ->
                appendLine("### ${chapter.title} (turns ${chapter.fromTurn}-${chapter.toTurn}, ${chapter.storyTime})")
                appendLine(chapter.summary)
            }
        }
    }

    fun visualDigest(snapshot: WorldSnapshot): String {
        if (snapshot.visualIdentities.isEmpty()) return ""
        return buildString {
            appendLine("## ESTABLISHED APPEARANCES (already drawn - keep these consistent)")
            snapshot.visualIdentities.take(30).forEach { identity ->
                appendLine(
                    "- ${identity.subjectName}: ${identity.canonicalDescription.truncate(200)}" +
                        (if (identity.currentVariant.isNotBlank()) " Currently: ${identity.currentVariant.truncate(120)}." else "")
                )
            }
        }
    }
}
