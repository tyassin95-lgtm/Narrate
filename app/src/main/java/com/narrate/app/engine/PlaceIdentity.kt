package com.narrate.app.engine

import com.narrate.app.core.normalizeName

/**
 * Whether two names are the same place.
 *
 * Plain name similarity is the wrong tool here. "Liv's apartment" and "Adrian's apartment"
 * share two words out of three - the possessive and the word "apartment" - and a generic
 * similarity score reads that as a two-thirds match, which is how the player was walked to
 * Liv's door and recorded as standing in Adrian's flat.
 *
 * A place is identified by the words that are peculiar to it. The kind of thing it is - flat,
 * apartment, room, street, cafe - is shared by half the map and identifies nothing. So the
 * kind words are set aside, and what remains has to agree: when both names carry distinctive
 * words and none of them match, they are different places however alike they read.
 */
object PlaceIdentity {

    /** Words that say what kind of place something is, not which place it is. */
    private val kindWords = setOf(
        "apartment", "apartments", "flat", "house", "home", "place", "building", "block",
        "room", "rooms", "suite", "unit", "studio", "loft", "residence", "quarters",
        "office", "offices", "shop", "store", "bar", "pub", "cafe", "café", "restaurant",
        "street", "st", "road", "rd", "avenue", "ave", "lane", "alley", "square", "court",
        "district", "quarter", "area", "ward", "wing", "floor", "level", "landing",
        "entrance", "lobby", "hall", "hallway", "corridor", "stairs", "stairwell", "door",
        "gate", "yard", "garden", "park", "station", "stop", "the", "a", "an", "of", "at",
        "in", "on", "s", "his", "her", "their", "its", "my", "your", "old", "new", "upper",
        "lower", "back", "front", "inside", "outside", "main"
    )

    /** The words that actually pick this place out from every other place. */
    fun distinctive(name: String): Set<String> {
        val words = name.normalizeName().split(' ').filter { it.isNotBlank() }
        val kept = words.filterNot { it in kindWords }
        // A place called nothing but its kind - "the kitchen" - is identified by that kind.
        return (if (kept.isEmpty()) words else kept).toSet()
    }

    /**
     * True when two names refer to the same place.
     *
     * The rule is deliberately conservative: creating a second place by mistake is a tidying
     * job, while merging two real places loses one of them and moves whoever was standing in it.
     */
    fun samePlace(a: String, b: String): Boolean {
        val x = a.normalizeName()
        val y = b.normalizeName()
        if (x.isBlank() || y.isBlank()) return false
        if (x == y) return true

        val dx = distinctive(a)
        val dy = distinctive(b)
        if (dx.isEmpty() || dy.isEmpty()) return false

        val shared = dx.intersect(dy)
        // Whoever or whatever a place belongs to is the whole of its identity: if the
        // distinctive words disagree, no amount of shared furniture makes it one place.
        if (shared.isEmpty()) return false

        // One name being the other plus more detail is the same place named longer:
        // "Night Ward" and "the Night Ward", "Liv's flat" and "Liv's flat, kitchen".
        return shared == dx || shared == dy
    }

    /** Words that say a place is a room inside something larger. */
    private val roomWords = setOf(
        "room", "bedroom", "kitchen", "bathroom", "hallway", "corridor", "landing", "ward",
        "office", "lobby", "stairwell", "cell", "cabin", "attic", "basement", "cellar", "study"
    )

    /** Words that say a place is a whole building. */
    private val buildingWords = setOf(
        "house", "apartment", "flat", "hospital", "hotel", "block", "tower", "cafe", "bar",
        "pub", "restaurant", "shop", "store", "church", "school", "library", "station", "clinic"
    )

    /**
     * The type a place's own name says it is.
     *
     * "Liv's Rented Room" recorded as a BUILDING is how one address came to mean the house, the
     * upstairs hallway and her bedroom at the same time. The name is the better evidence.
     */
    fun typeFromName(name: String, declared: String): String {
        val words = name.normalizeName().split(' ')
        val type = declared.uppercase().ifBlank { "BUILDING" }
        return when {
            words.any { it in roomWords } && type in setOf("BUILDING", "", "LANDMARK") -> "ROOM"
            words.any { it in buildingWords } && type == "ROOM" -> "BUILDING"
            else -> type
        }
    }

    /**
     * The best match for a name among places that already exist, or null when it is somewhere new.
     */
    fun <T> match(name: String?, candidates: List<T>, nameOf: (T) -> String): T? {
        if (name.isNullOrBlank()) return null
        candidates.firstOrNull { nameOf(it).equals(name.trim(), ignoreCase = true) }?.let { return it }
        val matches = candidates.filter { samePlace(nameOf(it), name) }
        if (matches.isEmpty()) return null
        // The most specific name wins, so a room inside a building beats the building.
        return matches.maxByOrNull { distinctive(nameOf(it)).size * 100 + nameOf(it).length }
    }
}
