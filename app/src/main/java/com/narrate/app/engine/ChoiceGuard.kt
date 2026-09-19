package com.narrate.app.engine

import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity
import com.narrate.app.data.repo.WorldSnapshot

/**
 * Suggested actions are part of the continuity system, not decoration on the end of it.
 *
 * A suggestion that offers an object the player is not holding, hands someone back a coat that
 * was never theirs, or speaks in an NPC's voice is a continuity error the player is invited to
 * commit. This drops those before they reach the screen, and says why, so the narrator can be
 * told and asked again.
 */
object ChoiceGuard {

    data class Rejection(val choice: Choice, val category: String, val reason: String)

    data class Verdict(val kept: List<Choice>, val rejected: List<Rejection>) {
        val hasProblems: Boolean get() = rejected.isNotEmpty()

        /** What to tell the narrator when asking for better ones. */
        fun problems(): List<String> = rejected.map { "\"${it.choice.label.take(90)}\" - ${it.reason}" }
    }

    /**
     * [turn] is the reply these choices came with, when there is one.
     *
     * Suggestions are checked against the world as it was before the turn was applied, which
     * is the right base for almost everything - but not for somebody the turn has just
     * introduced. On the turn a stranger walks in, "ask her what she is doing here" is the
     * obvious move and the snapshot has never heard of her, so the narration and the state
     * block are read for whoever and wherever this turn has just put in front of the player.
     */
    fun vet(snapshot: WorldSnapshot, choices: List<Choice>, turn: ParsedTurn? = null): Verdict {
        val player = snapshot.player ?: return Verdict(choices, emptyList())
        val introduced = introducedThisTurn(snapshot, turn)
        val kept = mutableListOf<Choice>()
        val rejected = mutableListOf<Rejection>()

        // What the player has just done or said. Offering it back is offering them nothing:
        // one real turn came back with "Yeah, overthinking sounds familiar." and an intent tag
        // that read, in as many words, "already said".
        val alreadyDone = snapshot.recentTurns.takeLast(2)
            .map { it.playerInput }
            .filter { it.isNotBlank() }
            .map { normalise(it) }

        // How much of the menu is a way of passing time. One such option is a real choice;
        // four of them is the app asking the player to pick which kind of nothing to do.
        val stalled = SceneMomentum.read(snapshot).stalled
        var idleKept = 0

        choices.forEach { choice ->
            val label = normalise(choice.label)
            val repeat = label.takeIf { it.length >= 8 }
                ?.let { text -> alreadyDone.any { it == text || (it.length >= 12 && it.contains(text)) } }
                ?: false
            val duplicate = kept.any { sameOutcome(normalise(it.label), label) }
            val idle = isFiller(choice.label)

            val rejection = when {
                repeat -> Rejection(
                    choice, "already-done",
                    "the player has just done this - it was their last turn, word for word"
                )
                duplicate -> Rejection(
                    choice, "duplicate",
                    "it is another way of writing an option already on the list"
                )
                idle && stalled -> Rejection(
                    choice, "filler",
                    "the scene has already stalled, and this is another turn of waiting in it"
                )
                idle && idleKept >= 1 -> Rejection(
                    choice, "filler",
                    "there is already an option for doing nothing, and two of them is not a choice"
                )
                else -> firstProblem(snapshot, player, choice, introduced)
            }
            if (rejection == null) {
                if (idle) idleKept++
                kept += choice
            } else {
                rejected += rejection
            }
        }
        return Verdict(kept, rejected)
    }

    /**
     * Housekeeping the player should never have to spend a move on.
     *
     * The app has buttons for time now - skip, go home, sleep - and everything else on this
     * list is something the prose should simply do. An option that reads "take a sip of your
     * coffee" is not a small choice, it is the absence of one, and a menu of them told the
     * player their situation had nothing in it. This is deliberately broad: the cost of losing
     * a borderline option is one line of a menu, and the cost of keeping it is the turn.
     */
    private val filler = Regex(
        // Time, and ways of not spending it.
        "^(?:check|checks|glance at|look at|consult)\\s+(?:the\\s+|your\\s+|his\\s+|her\\s+)?" +
            "(?:time|clock|phone|watch)\\b|" +
            "^(?:keep|continue|carry on|go on)\\s+(?:watching|reading|waiting|sitting|standing|listening)|" +
            "^(?:say|do)\\s+nothing\\b|^wait(?:s|ing)?\\b|^(?:stay|remain|sit|stand|linger|pause|hover)\\b|" +
            "^let (?:the )?(?:time|hours?|minutes?) pass\\b|^(?:head|go|walk) home\\b|" +
            "^(?:go to|head to) (?:bed|sleep)\\b|^(?:sleep|nap|doze)\\b|" +
            // Looking at nothing in particular.
            "^(?:look|glance|gaze|stare|peer)\\s+(?:around|about|out|ahead|down|up|away|at the (?:room|street|sidewalk|window|ceiling|floor))\\b|" +
            "^(?:take in|survey|observe|study)\\s+(?:the\\s+)?(?:room|surroundings|scene|street|place|bar|cafe)\\b|" +
            "^(?:people[- ]watch|watch the (?:sidewalk|street|traffic|door|room|people|crowd))\\b|" +
            // Eating, drinking and ordering, which are prose, not decisions.
            "^(?:drink|sip|take a sip|have a sip|swallow|finish)\\b|" +
            "^(?:order|buy|get)\\s+(?:a|an|another|some|yourself)?\\s*" +
            "(?:coffee|tea|drink|beer|water|food|something to (?:eat|drink)|refill)\\b|" +
            "^(?:eat|nibble|pick at|chew)\\b|" +
            // Fidgeting.
            "^(?:breathe|take a breath|sigh|shrug|nod|blink|shift|fidget|stretch|yawn|rub your)\\b|" +
            "^(?:think|reflect|consider|mull|ponder|wonder)\\s+(?:about it|it over|on it|quietly)?\\s*$|" +
            "^(?:adjust|straighten|smooth)\\s+(?:your|his|her|the)\\b|" +
            // Anywhere in the label.
            "\\bturn the page\\b|\\bread (?:a|another|the next) (?:page|paragraph|section|chapter)\\b|" +
            "\\bagain in a few minutes\\b|\\bnurse (?:your|the) (?:drink|coffee|beer)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Things that are housekeeping wherever in the line they appear. */
    private val fillerAnywhere = Regex(
        "\\bturn the page\\b|\\bread (?:a|another|the next) (?:page|paragraph|section|chapter)\\b|" +
            "\\bagain in a few minutes\\b|\\bnurse (?:your|the) (?:drink|coffee|beer)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when the whole option is housekeeping.
     *
     * "Wait" is housekeeping. "Wait until he leaves, then go through the desk" is a plan, and
     * the opening word is the least interesting thing about it - so the anchored patterns only
     * condemn a line that is short and has nothing after the verb.
     */
    fun isFiller(label: String): Boolean {
        val text = label.trim()
        if (fillerAnywhere.containsMatchIn(text)) return true
        val compound = text.contains(", then", true) || text.contains(" then ", true) ||
            text.contains(" until ", true) || text.contains(" so that ", true) ||
            text.contains(" while she", true) || text.contains(" while he", true) ||
            text.contains(" and ask", true) || text.contains(" and tell", true)
        val words = text.split(Regex("\\s+")).size
        if (compound || words > 9) return false
        return filler.containsMatchIn(text)
    }

    /**
     * Two options that come to the same thing.
     *
     * "Keep watching the sidewalk" and "Continue watching the sidewalk traffic without moving"
     * are one option written twice, and a menu of four of those is not a decision.
     */
    private fun sameOutcome(a: String, b: String): Boolean {
        if (a == b) return true
        val wordsA = a.split(' ').filter { it.length > 3 }.toSet()
        val wordsB = b.split(' ').filter { it.length > 3 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val shared = wordsA.intersect(wordsB).size.toDouble()
        return shared / minOf(wordsA.size, wordsB.size) >= 0.75
    }

    /** Comparable form of a line: what was said, without the punctuation or the casing. */
    private fun normalise(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Who and what this turn has just put in front of the player.
     *
     * Two different questions, and conflating them would undo one guard to fix another: a name
     * the turn mentions is a name the player now knows of, but only somebody the turn actually
     * puts in the room is somebody they can speak to.
     */
    private data class Arrivals(
        val named: Set<String> = emptySet(),
        val here: Set<String> = emptySet(),
        val prose: String = ""
    ) {
        fun knowsOf(name: String): Boolean {
            val lower = name.trim().lowercase()
            // Anybody the turn walked into the room is somebody the player has now met, so
            // the two questions answer each other in this direction.
            return lower.isNotEmpty() &&
                (named.any { it == lower } || here.any { it == lower } || prose.contains(lower))
        }

        fun inTheRoom(name: String): Boolean {
            val lower = name.trim().lowercase()
            return lower.isNotEmpty() && here.any { it == lower }
        }
    }

    private fun introducedThisTurn(snapshot: WorldSnapshot, turn: ParsedTurn?): Arrivals {
        if (turn == null) return Arrivals()
        fun clean(values: List<String>) = values.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

        val hereName = snapshot.currentLocation?.name?.trim()?.lowercase()
        // Somebody created with no location is created in the scene; somebody moved to where
        // the player is standing has walked in.
        val arriving = turn.delta.charactersNew
            .filter { it.location.isBlank() || it.location.trim().lowercase() == hereName }
            .map { it.name } +
            turn.delta.charactersUpdate
                .filter { it.location?.trim()?.lowercase() == hereName }
                .map { it.name }

        return Arrivals(
            named = clean(
                turn.delta.charactersNew.map { it.name } +
                    turn.delta.locationsNew.map { it.name } +
                    turn.delta.revealed.map { it.about }
            ),
            here = clean(arriving),
            prose = turn.narration.lowercase()
        )
    }

    private fun firstProblem(
        snapshot: WorldSnapshot,
        player: CharacterEntity,
        choice: Choice,
        introduced: Arrivals
    ): Rejection? {
        val text = (choice.label + " " + choice.detail).lowercase()
        val npcs = snapshot.npcs

        // An option the player's character has no way of having thought of.
        //
        // A suggestion is written by something that can see the whole world, and it kept
        // offering the player a walk to a bar they had never heard of and a question about a
        // sister nobody had mentioned. Tapping one of those does not just break continuity: it
        // hands the player information through the menu, which is the least interesting door
        // in the game for a secret to come through.
        unknownToThePlayer(snapshot, text, introduced)?.let { return Rejection(choice, "unknown", it) }

        // The player is the one acting. An option cannot be addressed to them or spoken by
        // someone else - that is the narrator losing track of whose turn it is.
        firstName(player.name)?.let { name ->
            val addressed = Regex("\\b(ask|tell|answer|reply to|thank|greet|call|warn|follow)\\s+(mr\\.?|ms\\.?|dr\\.?\\s+)?$name\\b")
            if (addressed.containsMatchIn(text)) {
                return Rejection(
                    choice, "player-identity",
                    "it tells the player to address ${player.name}, who is the player"
                )
            }
            if (Regex("^$name\\b\\s+(says|asks|replies|answers|nods|turns|walks|smiles|hands)").containsMatchIn(text)) {
                return Rejection(
                    choice, "player-identity",
                    "it is written about ${player.name} from outside, not as something they do"
                )
            }
        }

        npcs.forEach { npc ->
            firstName(npc.name)?.let { name ->
                if (Regex("^$name\\b\\s+(says|asks|replies|answers|offers|hands|gives|tells)").containsMatchIn(text)) {
                    return Rejection(
                        choice, "npc-perspective",
                        "it is written as something ${npc.name} does, not something the player does"
                    )
                }
            }
        }

        // Objects: what the player can offer, and what is already theirs.
        snapshot.items.forEach { item ->
            val mentioned = mentions(text, item.name) ?: return@forEach

            val ownedByPlayer = Possession.ownedBy(item, player.id)
            val heldByPlayer = Possession.heldBy(item, player.id)

            // Something burned, broken or lost is not an option, whoever used to own it.
            if (Possession.gone(item)) {
                return Rejection(
                    choice, "item-possession",
                    "the ${item.name} is ${item.possession.lowercase()} - it is not there to be used"
                )
            }

            if (ownedByPlayer && thanksForOwnItem(text, mentioned)) {
                return Rejection(
                    choice, "item-ownership",
                    "the ${item.name} is the player's own - thanking somebody for it turns it " +
                        "into theirs"
                )
            }

            if (Possession.outOnLoan(item, player.id) && returnsToOther(text, mentioned, snapshot)) {
                val holder = snapshot.characterById(item.holderId)?.name ?: "someone else"
                return Rejection(
                    choice, "item-ownership",
                    "the ${item.name} belongs to the player and $holder is only borrowing it, " +
                        "so the player cannot give it back to them"
                )
            }

            // Something of theirs that somebody is borrowing is still theirs to talk about:
            // telling her to keep the coat, or asking for it back, are both real moves. Only an
            // object that is not the player's at all is one they cannot offer.
            if (!heldByPlayer && !ownedByPlayer && offersItem(text, mentioned)) {
                val where = snapshot.characterById(item.holderId)?.name
                    ?: snapshot.locationById(item.locationId)?.name
                    ?: "elsewhere"
                return Rejection(
                    choice, "item-possession",
                    "it offers the ${item.name}, which the player is not carrying - it is with $where"
                )
            }
        }

        // Talking to someone who is not here, without any means of reaching them.
        val here = snapshot.currentLocation?.id
        npcs.filter { it.currentLocationId != here && !snapshot.withinEarshot(it.currentLocationId) }.forEach { npc ->
            // The snapshot is from before this turn was applied. Somebody the turn has just
            // walked into the room is in the room, and refusing to let the player speak to
            // them is the guard arguing with the scene it is reading.
            if (introduced.inTheRoom(npc.name) || introduced.inTheRoom(npc.name.split(' ').first())) {
                return@forEach
            }
            firstName(npc.name)?.let { name ->
                val talksTo = Regex("\\b(ask|tell|say to|talk to|answer|reply to|thank|greet)\\s+$name\\b")
                val remotely = Regex("\\b(call|phone|ring|text|message|write|email|radio)\\b")
                if (talksTo.containsMatchIn(text) && !remotely.containsMatchIn(text)) {
                    return Rejection(
                        choice, "presence",
                        "it speaks to ${npc.name}, who is not here - they are at " +
                            snapshot.locationName(npc.currentLocationId)
                    )
                }
            }
        }

        // Reaching someone remotely requires a way to reach them. Asking for their number is
        // how that begins and stays perfectly allowed; texting a stranger does not.
        npcs.filter { !ContactChannels.canReach(it) }.forEach { npc ->
            firstName(npc.name)?.let { name ->
                val reaches = Regex(
                    "\\b(call|calls|phone|phones|ring|rings|text|texts|message|messages|email|emails|dm)\\s+" +
                        "(?:up\\s+|back\\s+)?$name\\b"
                )
                val theirThread = Regex("$name'?s?\\s+(number|phone|email|thread|messages|inbox|chat)")
                // Asking for a number is how a channel begins, and stays allowed. "Ask what he
                // wanted" is not that: the ask has to be about the contact details themselves.
                val asksForIt = Regex(
                    "\\b(ask|asks|asking|get|gets|getting|swap|swaps|exchange|exchanges|trade|give|gives)\\b" +
                        "[^.?!]{0,40}\\b(number|numbers|email|address|handle|details|contact|phone)\\b"
                )
                if ((reaches.containsMatchIn(text) || theirThread.containsMatchIn(text)) &&
                    !asksForIt.containsMatchIn(text)
                ) {
                    return Rejection(
                        choice, "no-channel",
                        "the player has no way to contact ${npc.name}: no number, address or " +
                            "thread with them has been exchanged"
                    )
                }
            }
        }

        return null
    }

    /**
     * A person or a place named in an option that the player has never heard of.
     *
     * Only names are checked, and only whole words: the option may perfectly well be about a
     * bar the player is standing outside without naming it, and that is the narrator's job to
     * get right. What it may not do is put a name in the player's mouth that nothing has told
     * them.
     */
    private fun unknownToThePlayer(
        snapshot: WorldSnapshot,
        text: String,
        introduced: Arrivals
    ): String? {
        if (PlayerKnowledge.legacy(snapshot)) return null
        fun justArrived(name: String) = introduced.knowsOf(name)

        snapshot.npcs.forEach { npc ->
            if (PlayerKnowledge.knows(snapshot, npc.id, PlayerKnowledge.EXISTS)) return@forEach
            if (justArrived(npc.name) || justArrived(npc.name.split(' ').first())) return@forEach
            val name = npc.name.split(' ').firstOrNull()?.takeIf { it.length >= 4 } ?: return@forEach
            if (Regex("\\b${Regex.escape(name.lowercase())}\\b").containsMatchIn(text)) {
                return "it names ${npc.name}, who the player has never met or heard of"
            }
        }
        snapshot.locations.forEach { place ->
            if (PlayerKnowledge.knows(snapshot, place.id, PlayerKnowledge.EXISTS)) return@forEach
            if (justArrived(place.name)) return@forEach
            val name = place.name.takeIf { it.length >= 5 } ?: return@forEach
            if (Regex("\\b${Regex.escape(name.lowercase())}\\b").containsMatchIn(text)) {
                return "it names ${place.name}, somewhere the player has never been and never heard of"
            }
        }
        return null
    }

    /** Where an item is named in the text, if it is. */
    private fun mentions(text: String, itemName: String): IntRange? {
        val name = itemName.trim().lowercase()
        if (name.length < 3) return null
        val match = Regex("\\b${Regex.escape(name)}\\b").find(text)
        if (match != null) return match.range
        // "the jacket" should match an item recorded as "Adrian's wool jacket".
        val head = name.split(' ').lastOrNull()?.takeIf { it.length > 3 } ?: return null
        return Regex("\\b${Regex.escape(head)}\\b").find(text)?.range
    }

    /** "Ask if she wants her jacket back" - handing the player's own property away. */
    private fun returnsToOther(text: String, mention: IntRange, snapshot: WorldSnapshot): Boolean {
        val window = text.substring(
            (mention.first - 60).coerceAtLeast(0),
            (mention.last + 60).coerceAtMost(text.length)
        )
        val theirs = Regex("\\b(her|his|their|hers|theirs)\\b").containsMatchIn(window) ||
            snapshot.npcs.any { npc ->
                firstName(npc.name)?.let { Regex("\\b$it's\\b").containsMatchIn(window) } == true
            }
        // "Return her jacket" says it without the word "back", and handing the player's own
        // property to the person borrowing it is the same mistake either way.
        val returning = Regex("\\b(return|returns|returning)\\b").containsMatchIn(window)
        val givingBack = returning ||
            (
                Regex("\\b(give|gives|giving|hand|hands|handing|offer|offers)\\b").containsMatchIn(window) &&
                    Regex("\\b(back|returned)\\b").containsMatchIn(window)
                )
        val askingIfTheyWantIt = Regex("\\bwants?\\b[^.]{0,30}\\bback\\b").containsMatchIn(window)
        return theirs && (givingBack || askingIfTheyWantIt)
    }

    /** "Thank you for the jacket" - about the jacket the player lent out. */
    private fun thanksForOwnItem(text: String, mention: IntRange): Boolean {
        val window = text.substring(
            (mention.first - 40).coerceAtLeast(0),
            (mention.last + 10).coerceAtMost(text.length)
        )
        return Regex("\\b(thank|thanks|thanking|grateful)\\b[^.?!]{0,25}\\bfor\\b").containsMatchIn(window)
    }

    /** Offering, giving or using an object. */
    private fun offersItem(text: String, mention: IntRange): Boolean {
        val window = text.substring(
            (mention.first - 45).coerceAtLeast(0),
            (mention.last + 20).coerceAtMost(text.length)
        )
        return Regex("\\b(offer|offers|give|gives|hand|hands|lend|lends|pass|show|shows|use|uses|wrap|drape|put)\\b")
            .containsMatchIn(window)
    }

    private fun firstName(full: String): String? =
        full.trim().split(' ').firstOrNull()
            ?.takeIf { it.length >= 3 }
            ?.lowercase()
            ?.let { Regex.escape(it) }
}
