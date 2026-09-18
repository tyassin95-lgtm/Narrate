package com.narrate.app.engine

import com.narrate.app.core.nameSimilarity
import com.narrate.app.core.newId
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ContinuityIssueEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * The safety net under the narrator.
 *
 * Models drift: they duplicate a character under a slightly different name, walk someone
 * across the world in one sentence, or let a dead man speak. The guard catches those before
 * they reach the save file, repairs what it can, and feeds whatever it cannot repair back
 * into the next prompt as an explicit correction.
 */
object ContinuityGuard {

    const val SEVERITY_INFO = "INFO"
    const val SEVERITY_WARNING = "WARNING"
    const val SEVERITY_BLOCKED = "BLOCKED"

    /**
     * Two different things get recorded here, and conflating them was a mistake.
     *
     * Something that happened in the story and contradicts the save file is a continuity
     * problem the player should know about. A suggestion the generator produced and the
     * validator threw away never reached the world at all - telling the player their world
     * broke, when what broke was a candidate nobody saw, is noise that will eventually make
     * the real warnings unreadable.
     */
    private val generationCategories = setOf(
        "suggested-action", "player-voice", "state-block", "unrecorded-person", "clock"
    )

    /** True when this was caught before it could touch the world. */
    fun isGenerationNote(category: String): Boolean = category in generationCategories

    data class Issue(
        val severity: String,
        val category: String,
        val description: String,
        val resolution: String
    )

    class Report {
        private val _issues = mutableListOf<Issue>()
        val issues: List<Issue> get() = _issues

        fun add(severity: String, category: String, description: String, resolution: String) {
            _issues += Issue(severity, category, description, resolution)
        }

        fun toEntities(worldId: String, turnIndex: Int): List<ContinuityIssueEntity> = _issues.map {
            ContinuityIssueEntity(
                id = newId(),
                worldId = worldId,
                turnIndex = turnIndex,
                severity = it.severity,
                category = it.category,
                description = it.description,
                resolution = it.resolution
            )
        }

        /** What the next turn needs to be told so the drift does not compound. */
        fun corrections(): List<String> = _issues
            .filter { it.severity != SEVERITY_INFO }
            .map { "${it.description} (${it.resolution})" }
    }

    /**
     * A character the narrator treats as new may just be an existing one renamed slightly.
     * Matching them prevents the roster filling with near-duplicate ghosts.
     */
    fun findExisting(characters: List<CharacterEntity>, name: String): CharacterEntity? {
        if (name.isBlank()) return null
        characters.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val alias = characters.firstOrNull { character ->
            character.aliases.split(",").any { it.trim().equals(name, ignoreCase = true) && it.isNotBlank() }
        }
        if (alias != null) return alias
        val best = characters.maxByOrNull { nameSimilarity(it.name, name) } ?: return null
        return best.takeIf { nameSimilarity(it.name, name) >= 0.75 }
    }

    /**
     * Movement plausibility. A character may only appear somewhere they could have reached:
     * an adjacent location, a parent or child of where they were, or anywhere at all if the
     * narrator explains how - in which case we trust the fiction but still record the move.
     */
    fun checkMovement(
        snapshot: WorldSnapshot,
        character: CharacterEntity,
        targetLocationId: String,
        reason: String?
    ): Issue? {
        val from = character.currentLocationId ?: return null
        if (from == targetLocationId) return null
        val adjacent = snapshot.links
            .filter { !it.blocked && (it.fromId == from || it.toId == from) }
            .map { if (it.fromId == from) it.toId else it.fromId }
            .toSet()
        val fromLocation = snapshot.locationById(from)
        val targetLocation = snapshot.locationById(targetLocationId)
        val hierarchical = targetLocation?.parentId == from ||
            fromLocation?.parentId == targetLocationId ||
            (targetLocation?.parentId != null && targetLocation.parentId == fromLocation?.parentId)
        if (targetLocationId in adjacent || hierarchical) return null
        if (!reason.isNullOrBlank()) {
            return Issue(
                SEVERITY_INFO,
                "movement",
                "${character.name} moved from ${snapshot.locationName(from)} to " +
                    "${snapshot.locationName(targetLocationId)} without a mapped route.",
                "Allowed: the narrator gave a reason ($reason). Route recorded."
            )
        }
        return Issue(
            SEVERITY_WARNING,
            "movement",
            "${character.name} jumped from ${snapshot.locationName(from)} to " +
                "${snapshot.locationName(targetLocationId)} with no connecting route and no explanation.",
            "Move applied and a route recorded, but explain how they travelled."
        )
    }

    /** The communication blocks in a turn: a text, a call, an email - someone not in the room. */
    private val commBlock = Regex(
        "\\[\\[\\s*(sms|email|call|letter|broadcast|document)([^\\]]*)]]([\\s\\S]*?)\\[\\[\\s*/\\s*\\1\\s*]]",
        RegexOption.IGNORE_CASE
    )
    private val commSender = Regex("(?:from|with|to)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /** Who took part in this turn's remote communication, and the prose with those blocks removed. */
    private data class Remote(val correspondents: Set<String>, val prose: String)

    private fun remoteTraffic(narration: String): Remote {
        val names = mutableSetOf<String>()
        var prose = narration
        commBlock.findAll(narration).forEach { match ->
            commSender.findAll(match.groupValues[2]).forEach { attribute ->
                names += attribute.groupValues[1].trim().lowercase()
            }
            // The body of a message mentions its sender constantly; none of it is presence.
            prose = prose.replace(match.value, " ")
        }
        return Remote(names.filter { it.isNotBlank() && it != "me" && it != "you" }.toSet(), prose)
    }

    /** "I'm going to turn the phone off for a while." Said in one turn; typing in the next. */
    private val wentQuiet = Regex(
        "\\b(turn(?:ing|ed|s)?|switch(?:ing|ed|es)?|power(?:ing|ed|s)?|shut(?:ting)?)\\s+" +
            "(?:the\\s+|my\\s+|her\\s+|his\\s+|their\\s+|it\\s+)?(?:phone\\s+)?off\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Who said, in the turns just gone, that they were going offline.
     *
     * A phone somebody switched off two minutes ago cannot show a typing indicator. The state
     * file has no field for a device, but the story said it out loud, and the story is where
     * the contradiction lives.
     */
    private fun silencedRecently(snapshot: WorldSnapshot): Set<String> {
        val recent = snapshot.recentTurns.takeLast(2)
        if (recent.isEmpty()) return emptySet()
        val silenced = mutableSetOf<String>()
        recent.forEach { turn ->
            val text = turn.narration
            wentQuiet.findAll(text).forEach { match ->
                // Whoever was speaking or acting nearest the phone going off. Failing that,
                // whoever is in the turn at all - people say "I'll turn it off" without their
                // own name attached, and "she" is not something a regex can resolve.
                val window = text.substring(
                    (match.range.first - 200).coerceAtLeast(0),
                    (match.range.last + 60).coerceAtMost(text.length)
                ).lowercase()
                fun named(haystack: String) = snapshot.npcs.map { it.name.split(' ').first().lowercase() }
                    .filter { it.length >= 3 && Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(haystack) }

                val nearby = named(window).ifEmpty { named(text.lowercase()) }
                val present = if (nearby.isNotEmpty()) nearby else {
                    val ids = turn.presentCharacterIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    snapshot.npcs.filter { it.id in ids }.map { it.name.split(' ').first().lowercase() }
                }
                silenced += present
            }
        }
        return silenced
    }

    /** Reading the prose back against the state file to catch what the state block omitted. */
    fun auditNarration(snapshot: WorldSnapshot, narration: String, movedNames: Set<String>): List<Issue> {
        if (narration.isBlank()) return emptyList()
        val issues = mutableListOf<Issue>()
        val here = snapshot.currentLocation?.id
        // A text message is not an arrival. Someone who wrote to the player from across town
        // has not walked into the room, and reading their message as presence is what turned
        // every phone call into a teleport warning.
        val remote = remoteTraffic(narration)
        val lower = remote.prose.lowercase()

        val silenced = silencedRecently(snapshot)

        // A message can only come from someone the player can actually exchange messages with.
        remote.correspondents.forEach { correspondent ->
            val npc = snapshot.npcs.firstOrNull {
                it.name.equals(correspondent, true) ||
                    it.name.split(' ').first().equals(correspondent, true)
            } ?: return@forEach
            val first = npc.name.split(' ').first().lowercase()
            if (first in silenced || correspondent.lowercase() in silenced) {
                issues += Issue(
                    SEVERITY_WARNING,
                    "device-state",
                    "${npc.name} said they were turning their phone off, and then sent a message " +
                        "anyway in the next breath.",
                    "The message was kept, but a phone that is off stays off until somebody turns " +
                        "it back on and you say so."
                )
            }
            if (!ContactChannels.canReach(npc)) {
                issues += Issue(
                    SEVERITY_WARNING,
                    "no-channel",
                    "${npc.name} communicates with the player remotely, but no contact details " +
                        "have ever been exchanged with them.",
                    "Left unrecorded. Either play out the exchange of contact details first, or " +
                        "record it in \"contacts\" in the same turn it happens."
                )
            }
        }

        snapshot.npcs.forEach { npc ->
            val name = npc.name.trim()
            if (name.length < 3) return@forEach
            val firstName = name.split(' ').first()
            // A whole-word match, so a short first name like Liv is still recognised without
            // "liv" matching inside "delivery".
            val mentioned = lower.contains(name.lowercase()) ||
                Regex("\\b${Regex.escape(firstName.lowercase())}\\b").containsMatchIn(lower)
            if (!mentioned) return@forEach
            if (npc.name in movedNames) return@forEach
            // Someone who only appeared inside a message block was never in the room.
            if (name.lowercase() in remote.correspondents ||
                firstName.lowercase() in remote.correspondents
            ) {
                return@forEach
            }

            if (npc.status == "DEAD" && speaksOrActs(lower, firstName)) {
                issues += Issue(
                    SEVERITY_WARNING,
                    "dead-character",
                    "${npc.name} is recorded as dead but appears to act in this turn.",
                    "State left unchanged. Do not use this character again unless the story explains it."
                )
            }
            if (here != null && npc.currentLocationId != here && npc.status == "ALIVE" && speaksOrActs(lower, firstName)) {
                // Somebody in the next room, or inside the house the player is standing outside,
                // can be heard and answered without having moved an inch.
                if (snapshot.withinEarshot(npc.currentLocationId)) {
                    issues += Issue(
                        SEVERITY_INFO,
                        "presence",
                        "${npc.name} took part from ${snapshot.locationName(npc.currentLocationId)}, " +
                            "which is within earshot of ${snapshot.locationName(here)}.",
                        "Allowed: they can be heard across the threshold. They are still recorded " +
                            "where they were, so move them only if they actually came through."
                    )
                } else {
                    issues += Issue(
                        SEVERITY_WARNING,
                        "presence",
                        "${npc.name} appears to act in the scene but is recorded at " +
                            "${snapshot.locationName(npc.currentLocationId)}, not ${snapshot.locationName(here)}.",
                        "Position left unchanged. Either move them explicitly with a reason, or keep them offstage."
                    )
                }
            }
        }
        issues += unrecordedPeople(snapshot, narration)
        return issues
    }

    /**
     * Somebody the story keeps talking about who is not in the world at all.
     *
     * The clearest case from a real playthrough: Liv's ex drove three quarters of the evening -
     * he had her number, knew where she lived, was sending the messages - and no such person
     * existed in the save file. A name that keeps coming back is a person, whether or not the
     * player has ever seen them, and a person the world does not know is a person the world
     * cannot keep straight.
     */
    private fun unrecordedPeople(snapshot: WorldSnapshot, narration: String): List<Issue> {
        val known = (
            snapshot.characters.flatMap { it.name.split(' ') } +
                snapshot.locations.flatMap { it.name.split(' ') } +
                snapshot.factions.flatMap { it.name.split(' ') }
            ).map { it.lowercase().trim(',', '.', '\'', 's') }.toSet()

        fun namesIn(text: String): Set<String> = properNouns.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.length >= 3 && it.lowercase() !in known && it.lowercase() !in notPeople }
            .toSet()

        val hereAndNow = namesIn(narration)
        if (hereAndNow.isEmpty()) return emptyList()
        // Only a name that has kept coming back, so a one-off mention is never dragged in.
        val earlier = snapshot.recentTurns.takeLast(4).map { namesIn(it.narration) }
        return hereAndNow
            .filter { name -> earlier.count { name in it } >= 2 }
            .take(2)
            .map { name ->
                Issue(
                    SEVERITY_WARNING,
                    "unrecorded-person",
                    "$name keeps coming up in the story but is not a character in this world.",
                    "Record them in characters_new, even though the player has not met them: a " +
                        "name that matters this much needs a location, a relationship and a place " +
                        "in the state file."
                )
            }
    }

    /** A capitalised word that is not the first word of a sentence. */
    private val properNouns = Regex("(?<=[a-z,;:\"'] )([A-Z][a-z]{2,15})\\b")

    /** Capitalised words that are never somebody's name. */
    private val notPeople = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "god", "christmas", "eastgate",
        "street", "avenue", "road", "hospital", "university", "college", "police", "yeah",
        "okay", "sorry", "thanks", "the", "and", "but", "not", "you", "her", "him"
    )

    /** Crude but effective: did the name appear next to speech or an action verb? */
    private fun speaksOrActs(narration: String, firstName: String): Boolean {
        val needle = firstName.lowercase()
        val pattern = Regex(
            "$needle\\s+(says|said|asks|asked|replies|replied|answers|answered|shouts|shouted|" +
                "calls|called|calling|" +
                "whispers|whispered|nods|nodded|steps|stepped|walks|walked|turns|turned|smiles|smiled|" +
                "laughs|laughed|leans|leaned|grabs|grabbed|hands|handed|looks|looked|stands|stood|sits|sat)"
        )
        if (pattern.containsMatchIn(narration)) return true
        return Regex("$needle[^.!?\\n]{0,40}\"").containsMatchIn(narration)
    }

    /** Renders outstanding corrections for injection into the next prompt. */
    fun renderCorrections(corrections: List<String>): String {
        if (corrections.isEmpty()) return ""
        return buildString {
            appendLine("## CONTINUITY CORRECTIONS (problems detected in your last turn - do not repeat them)")
            corrections.forEach { appendLine("- $it") }
            appendLine("Treat the state file above as correct and write this turn so that it stays correct.")
        }
    }
}
