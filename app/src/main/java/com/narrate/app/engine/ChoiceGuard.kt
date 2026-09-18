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

    fun vet(snapshot: WorldSnapshot, choices: List<Choice>): Verdict {
        val player = snapshot.player ?: return Verdict(choices, emptyList())
        val kept = mutableListOf<Choice>()
        val rejected = mutableListOf<Rejection>()

        choices.forEach { choice ->
            val rejection = firstProblem(snapshot, player, choice)
            if (rejection == null) kept += choice else rejected += rejection
        }
        return Verdict(kept, rejected)
    }

    private fun firstProblem(
        snapshot: WorldSnapshot,
        player: CharacterEntity,
        choice: Choice
    ): Rejection? {
        val text = (choice.label + " " + choice.detail).lowercase()
        val npcs = snapshot.npcs

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

            val ownedByPlayer = item.ownerId == player.id
            val heldByPlayer = item.holderId == player.id

            if (ownedByPlayer && !heldByPlayer && returnsToOther(text, mentioned, snapshot)) {
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
