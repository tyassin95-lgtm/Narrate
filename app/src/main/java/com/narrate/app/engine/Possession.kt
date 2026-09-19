package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity

/**
 * Who owns a thing, who has it, and how it got there.
 *
 * Three columns were never enough. An owner, a holder and a location cannot tell you whether
 * Liv is wearing Adrian's jacket because he lent it to her on a cold street, because she took
 * it, or because he gave it to her for good - and those are three different worlds. The one
 * the app kept producing was the wrong one: the jacket became hers, and the next turn offered
 * the player the option of asking whether she wanted her jacket back.
 *
 * So possession is its own fact, recorded when the object moves, with a line of history for
 * every hand it passes through. Everything downstream - the digest, the suggestions, the
 * inventory, what an NPC believes about it - reads this one answer.
 */
object Possession {

    /** With whoever owns it. The ordinary case. */
    const val HELD = "HELD"

    /** The owner let somebody else have it for now. It is still the owner's. */
    const val LENT = "LENT"

    /** Somebody else has it and the owner did not offer. Still the owner's, and they know it. */
    const val BORROWED = "BORROWED"

    /** Put somewhere on purpose: a drawer, a locker, a coat hook, home. */
    const val STORED = "STORED"

    /** Left where it fell. Anyone could pick it up. */
    const val DROPPED = "DROPPED"

    /** Nobody knows where it is, including its owner. */
    const val LOST = "LOST"

    /** Gone. Burned, broken past use, thrown in the river. */
    const val DESTROYED = "DESTROYED"

    /** Given away for good. Owner and holder are the same person again. */
    const val GIVEN = "GIVEN"

    private val all = setOf(HELD, LENT, BORROWED, STORED, DROPPED, LOST, DESTROYED, GIVEN)

    /** What the narrator wrote in "transfer", turned into one of the states above. */
    fun readTransfer(raw: String?): String? {
        val value = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        if (value in all) return value
        return when {
            value.contains("LEND") || value.contains("LENT") || value.contains("LOAN") ||
                value.contains("BORROW TO") -> LENT
            value.contains("BORROW") || value.contains("TOOK") || value.contains("TAKE") ||
                value.contains("STOLE") || value.contains("STEAL") -> BORROWED
            value.contains("GIVE") || value.contains("GAVE") || value.contains("GIFT") ||
                value.contains("SOLD") || value.contains("SELL") || value.contains("HAND OVER") ||
                value.contains("TRANSFER") -> GIVEN
            value.contains("RETURN") || value.contains("BACK") || value.contains("KEEP") -> HELD
            value.contains("STORE") || value.contains("STOW") || value.contains("PUT AWAY") ||
                value.contains("STASH") || value.contains("LEFT AT") -> STORED
            value.contains("DROP") -> DROPPED
            value.contains("LOSE") || value.contains("LOST") || value.contains("MISPLACE") -> LOST
            value.contains("DESTROY") || value.contains("BROKE") || value.contains("BURN") ||
                value.contains("RUIN") -> DESTROYED
            else -> null
        }
    }

    /**
     * The whole object state after a move, worked out once so nothing downstream has to guess.
     *
     * [transfer] is what the narrator said happened, when it said anything. When it said
     * nothing, the change is inferred from where the object ended up, and the inference
     * deliberately favours lending: assuming a gift is how the player loses their own coat.
     */
    fun settle(
        item: ItemEntity,
        newHolder: CharacterEntity?,
        newLocationId: String?,
        transfer: String?,
        declaredOwner: CharacterEntity?,
        turnIndex: Int
    ): ItemEntity {
        val kind = readTransfer(transfer)
        val ownerBefore = item.ownerId

        // Where it ends up.
        val holderId = when (kind) {
            DROPPED, STORED, LOST, DESTROYED -> null
            else -> newHolder?.id ?: if (newLocationId != null) null else item.holderId
        }
        val locationId = when {
            holderId != null -> null
            kind == DESTROYED -> null
            else -> newLocationId ?: item.locationId
        }

        // Whose it is. Ownership only moves when somebody says it moved.
        val ownerId = when {
            declaredOwner != null -> declaredOwner.id
            kind == GIVEN && newHolder != null -> newHolder.id
            ownerBefore != null -> ownerBefore
            else -> newHolder?.id
        }

        val state = when {
            kind == DESTROYED -> DESTROYED
            kind == LOST -> LOST
            kind == DROPPED -> DROPPED
            kind == STORED -> STORED
            holderId == null && locationId != null -> if (item.possession == STORED) STORED else DROPPED
            holderId == null -> item.possession
            ownerId == null || holderId == ownerId -> HELD
            kind == BORROWED -> BORROWED
            // No declaration, and it is in somebody else's hands: lent, not given.
            else -> if (kind == GIVEN) HELD else LENT
        }

        val note = describeMove(item, holderId, locationId, state, turnIndex)
        return item.copy(
            ownerId = ownerId,
            holderId = holderId,
            locationId = locationId,
            possession = state,
            history = if (note == null) item.history else (item.history + "\n" + note).trim().truncate(1200),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun describeMove(
        before: ItemEntity,
        holderId: String?,
        locationId: String?,
        state: String,
        turnIndex: Int
    ): String? {
        if (before.holderId == holderId && before.locationId == locationId && before.possession == state) {
            return null
        }
        return "- [turn $turnIndex] ${state.lowercase()}"
    }

    /** True when the player owns it, whoever is carrying it right now. */
    fun ownedBy(item: ItemEntity, characterId: String?): Boolean =
        characterId != null && item.ownerId == characterId

    fun heldBy(item: ItemEntity, characterId: String?): Boolean =
        characterId != null && item.holderId == characterId

    /**
     * Somebody else has it, and it is still the owner's to ask for.
     *
     * Decided by owner and holder rather than by the recorded state, because that is what
     * makes it true: if it is yours and somebody else has it, it is out, whether the turn
     * that moved it called it lending, borrowing or nothing at all. Saves written before
     * possession existed all read HELD, and they are the ones this matters most for.
     */
    fun outOnLoan(item: ItemEntity, ownerId: String?): Boolean =
        ownedBy(item, ownerId) && item.holderId != null && item.holderId != ownerId &&
            item.possession !in setOf(DESTROYED, LOST)

    fun gone(item: ItemEntity): Boolean = item.possession in setOf(DESTROYED, LOST)

    /**
     * One sentence the narrator cannot misread.
     *
     * The wording matters more than it looks: every version of this that said "Liv has the
     * jacket" produced a turn in which the jacket was Liv's.
     */
    fun describe(
        item: ItemEntity,
        owner: CharacterEntity?,
        holder: CharacterEntity?,
        placeName: String?
    ): String {
        fun who(character: CharacterEntity?) =
            character?.let { if (it.isPlayer) "${it.name} (the player)" else it.name }

        val ownerName = who(owner)
        val holderName = who(holder)
        // Somebody else holding something that is not theirs is a loan, whatever the recorded
        // state says. Saves written before possession existed all read HELD, and reading them
        // literally is what produced "carried by Liv, whose it is" about Adrian's coat.
        val state = if (
            item.possession == HELD && item.holderId != null && item.ownerId != null &&
            item.holderId != item.ownerId
        ) LENT else item.possession
        return when (state) {
            DESTROYED -> "destroyed. It no longer exists and cannot appear again."
            LOST -> "lost. Nobody knows where it is, including ${ownerName ?: "its owner"}."
            STORED -> "put away at ${placeName ?: "somewhere unrecorded"}" +
                (ownerName?.let { ". It is $it's." } ?: ".")
            DROPPED -> "left at ${placeName ?: "somewhere unrecorded"}" +
                (ownerName?.let { ". It is still $it's." } ?: ".")
            LENT -> "owned by ${ownerName ?: "someone unrecorded"}, LENT to ${holderName ?: "someone"}. " +
                "Lending is not giving: it is still ${ownerName ?: "the owner"}'s, and " +
                "${ownerName ?: "the owner"} may ask for it back at any time. " +
                "${holderName ?: "The holder"} has no claim on it."
            BORROWED -> "owned by ${ownerName ?: "someone unrecorded"} and currently with " +
                "${holderName ?: "someone else"}, who did not ask. It is still " +
                "${ownerName ?: "the owner"}'s."
            else -> when {
                holderName != null && ownerName != null && item.holderId != item.ownerId ->
                    "owned by $ownerName, carried by $holderName."
                holderName != null -> "carried by $holderName, whose it is."
                ownerName != null && placeName != null -> "$ownerName's, at $placeName."
                placeName != null -> "at $placeName."
                ownerName != null -> "$ownerName's."
                else -> "whereabouts unrecorded."
            }
        }
    }

    /** How the codex labels it in one or two words. */
    fun label(item: ItemEntity, playerId: String?): String = when {
        item.possession == DESTROYED -> "destroyed"
        item.possession == LOST -> "lost"
        heldBy(item, playerId) -> "carried"
        outOnLoan(item, playerId) -> "lent out"
        ownedBy(item, playerId) && item.possession == STORED -> "put away"
        ownedBy(item, playerId) -> "yours"
        item.possession == DROPPED -> "left behind"
        item.holderId != null -> "someone else's"
        else -> item.possession.lowercase()
    }
}
