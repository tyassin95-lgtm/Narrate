package com.narrate.app.engine

import com.narrate.app.core.nameSimilarity
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.entity.ItemEntity

/**
 * Whether the thing the narrator just named is already in the world.
 *
 * A playthrough finished with two people in it called Dr. Elena Ruiz and Elena Ruiz. They were
 * the same attending physician at the same hospital: one had been created when she texted
 * about a staffing gap, the other when she appeared in a corridor with her title attached. The
 * old check compared names as strings and a title was enough to make them strangers.
 *
 * The right question is not "is this the same string" but "could this plausibly be a second
 * person". A title is not a different person. A first name is not a different person when the
 * surname, the job and the workplace all line up. Getting it wrong in the other direction
 * matters too - two people named Chen are two people - so a match needs agreement on
 * something beyond the name before it will merge on a partial one.
 */
object EntityResolver {

    private val titles = Regex(
        "^(dr|doctor|mr|mrs|ms|miss|prof|professor|sgt|sergeant|officer|nurse|sister|" +
            "father|fr|sir|lady|lord|detective|det|capt|captain|lt|lieutenant)\\.?\\s+",
        RegexOption.IGNORE_CASE
    )

    /** A name with its honorifics taken off, which is the name the person actually has. */
    fun bareName(name: String): String {
        var text = name.trim()
        while (titles.containsMatchIn(text)) text = titles.replace(text, "")
        return text.trim()
    }

    private fun words(name: String): List<String> =
        bareName(name).lowercase().split(Regex("[^a-z']+")).filter { it.length > 1 }

    /**
     * The existing character this reference means, if it means one of them.
     *
     * [context] is whatever else the turn said about them - a role, a workplace, a faction -
     * which is what lets a bare first name resolve to somebody already on the books without
     * letting two different Elenas collapse into one.
     */
    fun findCharacter(
        characters: List<CharacterEntity>,
        name: String,
        context: String = ""
    ): CharacterEntity? {
        val reference = name.trim()
        if (reference.isBlank()) return null
        val bare = bareName(reference)

        characters.firstOrNull { it.name.equals(reference, true) }?.let { return it }
        // A title is not a different person.
        characters.firstOrNull { bareName(it.name).equals(bare, true) }?.let { return it }
        // Nor is an alias they were introduced under.
        characters.firstOrNull { character ->
            character.aliases.split(',').any { it.trim().equals(bare, true) && it.isNotBlank() }
        }?.let { return it }

        val referenceWords = words(reference)
        if (referenceWords.isEmpty()) return null

        val candidates = characters.filter { character ->
            val existing = words(character.name)
            // They share a surname, or one name is contained in the other.
            existing.isNotEmpty() && (
                (existing.size > 1 && referenceWords.size > 1 && existing.last() == referenceWords.last()) ||
                    existing.containsAll(referenceWords) ||
                    referenceWords.containsAll(existing)
                )
        }
        if (candidates.isEmpty()) {
            // Nothing structural: fall back to the old similarity, at the old threshold.
            return characters.maxByOrNull { nameSimilarity(bareName(it.name), bare) }
                ?.takeIf { nameSimilarity(bareName(it.name), bare) >= 0.85 }
        }
        if (candidates.size == 1 && candidates.first().let { words(it.name).size > 1 && referenceWords.size > 1 }) {
            return candidates.first()
        }
        // A partial name needs something else to agree before it merges anybody.
        val clues = (context + " " + reference).lowercase()
        return candidates.firstOrNull { character ->
            val about = listOf(character.role, character.faction, character.summary)
                .joinToString(" ").lowercase()
            agrees(about, clues)
        } ?: candidates.singleOrNull()
    }

    /** Whether two descriptions of somebody are talking about the same job in the same place. */
    private fun agrees(about: String, clues: String): Boolean {
        if (about.isBlank() || clues.isBlank()) return false
        val aboutWords = about.split(Regex("[^a-z]+")).filter { it.length > 4 }.toSet()
        val clueWords = clues.split(Regex("[^a-z]+")).filter { it.length > 4 }.toSet()
        return aboutWords.intersect(clueWords).isNotEmpty()
    }

    /**
     * The same question for objects.
     *
     * "the jacket", "your wool jacket" and "Adrian's jacket" are one coat, and creating a
     * second row for it is how an object ends up in two places.
     */
    fun findItem(items: List<ItemEntity>, name: String, ownerId: String? = null): ItemEntity? {
        val reference = name.trim().lowercase()
        if (reference.isBlank()) return null
        items.firstOrNull { it.name.equals(reference, true) }?.let { return it }

        val head = reference.split(Regex("[^a-z]+")).lastOrNull { it.length > 2 } ?: return null
        val sameHead = items.filter { item ->
            item.name.lowercase().split(Regex("[^a-z]+")).lastOrNull { it.length > 2 } == head
        }
        if (sameHead.size == 1) return sameHead.first()
        if (ownerId != null) sameHead.firstOrNull { it.ownerId == ownerId }?.let { return it }
        return sameHead.maxByOrNull { nameSimilarity(it.name, reference) }
            ?.takeIf { nameSimilarity(it.name, reference) >= 0.6 }
    }

    /**
     * Two characters the world is holding that are plainly the same person.
     *
     * Run over the whole cast rather than at creation, because the duplicate in the report was
     * created several turns before the evidence that it was one arrived.
     */
    fun duplicates(characters: List<CharacterEntity>): List<Pair<CharacterEntity, CharacterEntity>> {
        val pairs = mutableListOf<Pair<CharacterEntity, CharacterEntity>>()
        val npcs = characters.filter { !it.isPlayer }
        for (index in npcs.indices) {
            for (other in index + 1 until npcs.size) {
                val a = npcs[index]
                val b = npcs[other]
                if (samePerson(a, b)) pairs += a to b
            }
        }
        return pairs
    }

    fun samePerson(a: CharacterEntity, b: CharacterEntity): Boolean {
        val nameA = words(a.name)
        val nameB = words(b.name)
        if (nameA.isEmpty() || nameB.isEmpty()) return false
        if (bareName(a.name).equals(bareName(b.name), true)) return true
        val overlap = nameA.intersect(nameB.toSet())
        if (overlap.isEmpty()) return false
        // Sharing a surname is not enough on its own; sharing it and a workplace is.
        val sharedSurname = nameA.size > 1 && nameB.size > 1 && nameA.last() == nameB.last()
        val sameJob = agrees(
            listOf(a.role, a.summary, a.faction).joinToString(" ").lowercase(),
            listOf(b.role, b.summary, b.faction).joinToString(" ").lowercase()
        )
        val samePlace = a.currentLocationId != null && a.currentLocationId == b.currentLocationId
        return sharedSurname && (sameJob || samePlace)
    }

    /**
     * Folds [duplicate] into [keep], so nothing the world learned about either is lost.
     *
     * The surviving row keeps the longer name - a title is information - and takes whichever
     * field is filled in on either side, plus the higher of the two standings.
     */
    fun merge(keep: CharacterEntity, duplicate: CharacterEntity): CharacterEntity {
        fun pick(a: String, b: String) = if (a.isNotBlank()) a else b
        val aliases = (keep.aliases.split(',') + duplicate.name + duplicate.aliases.split(','))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(keep.name, true) }
            .distinct()
            .joinToString(", ")
        return keep.copy(
            name = if (keep.name.length >= duplicate.name.length) keep.name else duplicate.name,
            aliases = aliases,
            role = pick(keep.role, duplicate.role),
            summary = pick(keep.summary, duplicate.summary),
            personality = pick(keep.personality, duplicate.personality),
            backstory = pick(keep.backstory, duplicate.backstory),
            appearance = pick(keep.appearance, duplicate.appearance),
            outfit = pick(keep.outfit, duplicate.outfit),
            voice = pick(keep.voice, duplicate.voice),
            goals = pick(keep.goals, duplicate.goals),
            fears = pick(keep.fears, duplicate.fears),
            secrets = pick(keep.secrets, duplicate.secrets),
            knowledge = listOf(keep.knowledge, duplicate.knowledge).filter { it.isNotBlank() }.joinToString("\n"),
            currentLocationId = keep.currentLocationId ?: duplicate.currentLocationId,
            homeLocationId = keep.homeLocationId ?: duplicate.homeLocationId,
            routine = pick(keep.routine, duplicate.routine),
            playerContact = ContactChannels.store(
                ContactChannels.parse(keep.playerContact) + ContactChannels.parse(duplicate.playerContact)
            ),
            faction = pick(keep.faction, duplicate.faction),
            relationshipToPlayer = pick(keep.relationshipToPlayer, duplicate.relationshipToPlayer),
            affinity = if (kotlin.math.abs(keep.affinity) >= kotlin.math.abs(duplicate.affinity)) keep.affinity else duplicate.affinity,
            trust = if (kotlin.math.abs(keep.trust) >= kotlin.math.abs(duplicate.trust)) keep.trust else duplicate.trust,
            importance = maxOf(keep.importance, duplicate.importance),
            portraitImageId = keep.portraitImageId ?: duplicate.portraitImageId,
            firstSeenTurn = minOf(keep.firstSeenTurn, duplicate.firstSeenTurn),
            lastSeenTurn = maxOf(keep.lastSeenTurn, duplicate.lastSeenTurn)
        )
    }
}
