package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
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
        if (world.authoredCanon.isNotBlank()) {
            appendLine()
            appendLine("## WRITTEN BY THE PLAYER - VERBATIM, AND ABSOLUTE")
            appendLine("This is the player's own text. It outranks every other line in this file, every")
            appendLine("summary derived from it, and anything you believe you remember. Never rename,")
            appendLine("revise, contradict or quietly improve any part of it.")
            appendLine("<<<")
            appendLine(world.authoredCanon)
            appendLine(">>>")
        } else if (world.customPrompt.isNotBlank()) {
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
            if (player.authoredCanon.isNotBlank()) {
                appendLine()
                appendLine("## THIS CHARACTER IN THE PLAYER'S OWN WORDS - VERBATIM, AND ABSOLUTE")
                appendLine("Their name, their face, their history and their manner are exactly as written")
                appendLine("here. Where the fields below and this text differ, this text is correct.")
                appendLine("<<<")
                appendLine(player.authoredCanon)
                appendLine(">>>")
                appendLine()
            }
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
                appendLine("Carrying right now: " + inventory.joinToString(", ") {
                    it.name + (if (it.state.isNotBlank()) " (${it.state})" else "")
                })
            }
            val lentOut = snapshot.items.filter {
                it.ownerId == player.id && it.holderId != null && it.holderId != player.id
            }
            if (lentOut.isNotEmpty()) {
                appendLine(
                    "Theirs, but currently with someone else: " + lentOut.joinToString(", ") { item ->
                        "${item.name} (with ${snapshot.characterById(item.holderId)?.name ?: "someone"})"
                    }
                )
                appendLine(
                    "Those remain the player's property. Never write the player asking for them back " +
                        "as though they were borrowed from the other person."
                )
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
            SceneCompany.render(snapshot).takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        }

        val earshot = snapshot.withinEarshotNpcs()
        if (earshot.isNotEmpty()) {
            appendLine()
            appendLine("## WITHIN EARSHOT (not in the room, but close enough to be heard)")
            earshot.forEach {
                appendLine("- ${it.name}: in ${snapshot.locationName(it.currentLocationId)}")
            }
            appendLine(
                "They can call through a door, be heard from a hallway, answer from the next room " +
                    "or watch from a window without moving. If one of them actually comes through, " +
                    "that is a move and you record it."
            )
        }

        val nearby = snapshot.nearbyNpcs().filterNot { it in earshot }
        if (nearby.isNotEmpty()) {
            appendLine()
            appendLine("## NEARBY (one move away - could plausibly arrive)")
            nearby.forEach { appendLine("- ${it.name}: at ${snapshot.locationName(it.currentLocationId)}") }
        }
        appendLine()
        appendLine(worldMap(snapshot))
        appendLine()
        appendLine(npcRoster(snapshot))
        appendLine()
        appendLine(ContactChannels.render(snapshot.characters))
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
        val significantItems = snapshot.items
            .filter { it.significance.isNotBlank() || it.holderId != null || Possession.gone(it) }
        if (significantItems.isNotEmpty()) {
            appendLine()
            appendLine("## TRACKED OBJECTS (owner and holder are different questions)")
            significantItems.take(40).forEach { item ->
                appendLine(
                    "- ${item.name}: ${whereabouts(snapshot, item)}" +
                        (if (item.state.isNotBlank()) " State: ${item.state}." else "") +
                        (if (item.significance.isNotBlank()) " ${item.significance.truncate(140)}" else "")
                )
            }
            appendLine(
                "Never move an object's ownership without saying so. Handing something over, " +
                    "putting it down, lending it and giving it away are four different events, " +
                    "and each one goes in \"items_update\" with the right \"transfer\"."
            )
        }
    }

    /**
     * Where an object is and whose it is.
     *
     * These are different questions, and conflating them is how a jacket lent to someone
     * shivering became hers, with the narrator then offering to give it back to her.
     */
    private fun whereabouts(snapshot: WorldSnapshot, item: ItemEntity): String = Possession.describe(
        item = item,
        owner = snapshot.characterById(item.ownerId),
        holder = snapshot.characterById(item.holderId),
        placeName = snapshot.locationById(item.locationId)?.name
    )

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
        // Right beside the facts, the half of them the player has not been given. Keeping this
        // in a separate section further up the prompt was not enough: the narrator read the
        // profile and wrote the protagonist as though he had read it too.
        val hidden = PlayerKnowledge.hiddenCharacterFields
            .filterNot { PlayerKnowledge.knows(snapshot, character.id, it) }
            .filter { field ->
                when (field) {
                    PlayerKnowledge.ROLE -> character.role.isNotBlank()
                    PlayerKnowledge.GOALS -> character.goals.isNotBlank()
                    PlayerKnowledge.SECRETS -> character.secrets.isNotBlank()
                    PlayerKnowledge.HOME -> character.homeLocationId != null
                    PlayerKnowledge.ROUTINE -> character.routine.isNotBlank()
                    PlayerKnowledge.BACKSTORY -> character.backstory.isNotBlank()
                    PlayerKnowledge.PERSONALITY -> character.personality.isNotBlank()
                    PlayerKnowledge.RELATIONSHIP -> character.relationshipToPlayer.isNotBlank()
                    else -> false
                }
            }
        if (hidden.isNotEmpty() && !PlayerKnowledge.legacy(snapshot)) {
            append(" THE PLAYER DOES NOT KNOW: ${hidden.joinToString(", ")}.")
        }
    }

    /** Full geography, hierarchy and routes. The GM may not invent or redraw this. */
    fun worldMap(snapshot: WorldSnapshot): String = buildString {
        appendLine("## WORLD MAP (everything that exists - yours to keep straight, not the player's to see)")
        appendLine(
            "Places marked [NOT KNOWN TO THE PLAYER] exist, and people go about their lives in " +
                "them, but the player's character has never been there and has never heard of " +
                "them. They cannot name one, walk to one, or be assumed to know the way. When " +
                "one of them comes up in the story, record it in \"revealed\"."
        )
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
                // Asked of the knowledge table rather than the flag: the flag is a cache, and
                // the whole point of this section is that the narrator gets the truth.
                if (!PlayerKnowledge.knows(snapshot, location.id, PlayerKnowledge.EXISTS)) {
                    add("NOT KNOWN TO THE PLAYER")
                }
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
            val reach = if (ContactChannels.canReach(npc)) {
                " Reachable by ${ContactChannels.parse(npc.playerContact).joinToString(", ") { ContactChannels.describe(it) }}."
            } else {
                " The player has no way to contact them."
            }
            appendLine(
                "- ${npc.name}$status: at $place." +
                    (if (npc.routine.isNotBlank()) " Routine: ${npc.routine.truncate(120)}." else "") +
                    reach +
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

    /**
     * Everything that happened before the turns replayed verbatim.
     *
     * Chapters cover the oldest stretch, but a chapter is only written once enough turns have
     * gone by, and the turns in between - too old to be replayed, too recent to be compacted -
     * used to appear nowhere at all. Their own one-line summaries fill that gap, so no part of
     * the story is ever invisible to the narrator.
     */
    fun history(snapshot: WorldSnapshot): String = buildString {
        if (snapshot.chapters.isNotEmpty()) {
            appendLine("## THE STORY SO FAR (compacted history - all of it happened)")
            snapshot.chapters.forEach { chapter ->
                appendLine("### ${chapter.title} (turns ${chapter.fromTurn}-${chapter.toTurn}, ${chapter.storyTime})")
                appendLine(chapter.summary)
            }
        }
        val between = snapshot.earlierTurns
        if (between.isNotEmpty()) {
            appendLine()
            appendLine("## SINCE THEN (turns not yet gathered into a chapter - all of it happened)")
            between.forEach { turn ->
                appendLine(
                    "- Turn ${turn.index} (${turn.storyTime}, ${turn.locationName}): " +
                        turn.summary.ifBlank { turn.narration.truncate(200) }
                )
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
