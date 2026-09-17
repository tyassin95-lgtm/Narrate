package com.narrate.app.engine

/**
 * What kind of thing an object is, decided in code rather than asked of the image model.
 *
 * Image models do not honour conditionals. A prompt that says "if this object bears its
 * owner's likeness, show Adrian Voss - dark hair, green eyes" simply puts that face in the
 * picture, which is how a medical textbook ended up with a portrait on the cover. So the
 * decision is made here, and the prompt only ever states things that are true of this object.
 */
object ObjectTraits {

    /** Things whose whole purpose is to carry a picture of their owner. */
    private val likenessBearing = listOf(
        "id badge", "identity card", "identification", "id card", "photo id", "badge",
        "passport", "licence", "license", "warrant card", "press pass", "security pass",
        "employee card", "student card", "visa", "photograph", "photo of", "portrait",
        "locket", "headshot", "mugshot", "selfie", "picture of", "miniature of"
    )

    /** Things that are made of writing, and are not themselves without it. */
    private val textBearing = likenessBearing + listOf(
        "book", "notebook", "journal", "diary", "ledger", "manual", "reference", "textbook",
        "document", "file", "folder", "dossier", "report", "chart", "record", "letter",
        "note", "envelope", "newspaper", "magazine", "pamphlet", "flyer", "poster", "leaflet",
        "map", "ticket", "receipt", "prescription", "label", "tag", "sign", "certificate",
        "form", "contract", "script", "menu", "timetable", "roster", "invoice", "card"
    )

    private fun matches(patterns: List<String>, vararg fields: String?): Boolean {
        val haystack = fields.filterNotNull().joinToString(" ").lowercase()
        if (haystack.isBlank()) return false
        return patterns.any { haystack.contains(it) }
    }

    /**
     * True when the object would genuinely show its owner's face, so the picture on it can be
     * the right person instead of a stranger.
     */
    fun bearsOwnerLikeness(name: String, description: String? = null, appearance: String? = null): Boolean =
        matches(likenessBearing, name, description, appearance)

    /**
     * True when writing belongs on the object. A blanket "no text" rule turns a hospital ID
     * badge into a blank plastic sleeve and a book into a featureless block.
     */
    fun bearsText(name: String, description: String? = null, appearance: String? = null): Boolean =
        matches(textBearing, name, description, appearance)
}
