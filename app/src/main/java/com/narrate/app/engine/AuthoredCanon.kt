package com.narrate.app.engine

/**
 * What the player wrote themselves, treated as fact.
 *
 * Generated material is a suggestion; text the player typed is not. This finds the concrete
 * things they declared - above all a name - so the rest of the app can guarantee those
 * survive generation unchanged, rather than hoping the model respected them.
 */
object AuthoredCanon {

    /** Words that look like a name to a regex but never are. */
    private val notNames = setOf(
        "the", "a", "an", "my", "his", "her", "their", "he", "she", "it", "they", "this",
        "that", "there", "here", "i", "you", "we", "and", "but", "so", "then", "name",
        "character", "world", "setting", "story", "genre", "tone", "premise", "appearance",
        "personality", "background", "backstory", "history", "role", "occupation", "job",
        "age", "gender", "goals", "fears", "secrets", "notes", "rules", "themes"
    )

    private const val NAME_WORD = "[A-Z][\\p{L}'\\u2019-]+"

    /**
     * Patterns for a character's name, strongest first. A leading "Adrian Voss:" is the
     * commonest way people open, followed by "named"/"called", then an explicit field.
     */
    private val characterNamePatterns = listOf(
        Regex("^\\s*($NAME_WORD(?:\\s+$NAME_WORD){0,3})\\s*[:\\u2014\\u2013-]"),
        Regex("^\\s*(?:name)\\s*[:\\-]\\s*(.{1,60})$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("\\b(?:named|called)\\s+($NAME_WORD(?:\\s+$NAME_WORD){0,3})"),
        Regex("\\bmy character(?:'s name)?\\s+is\\s+($NAME_WORD(?:\\s+$NAME_WORD){0,3})", RegexOption.IGNORE_CASE),
        Regex("\\bI(?:'m|\\u2019m| am)\\s+($NAME_WORD(?:\\s+$NAME_WORD){0,3})"),
        Regex("\\bplay(?:ing)?(?: as)?\\s+($NAME_WORD(?:\\s+$NAME_WORD){0,3})", RegexOption.IGNORE_CASE)
    )

    private val worldNamePatterns = listOf(
        Regex(
            "^\\s*(?:world name|world|title|setting|name)\\s*[:\\-]\\s*(.{1,70})$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        ),
        Regex(
            "\\b(?:world|city|town|village|station|realm|kingdom|colony|ship|school|hospital)\\s+" +
                "(?:is\\s+)?(?:called|named)\\s+[\"'\\u201c]?($NAME_WORD(?:\\s+$NAME_WORD){0,4})"
        ),
        Regex("\\b(?:called|named)\\s+[\"'\\u201c]([^\"'\\u201d\\n]{2,60})[\"'\\u201d]"),
        Regex("^\\s*($NAME_WORD(?:\\s+$NAME_WORD){0,4})\\s*[:\\u2014\\u2013-]")
    )

    /** The name the player gave their character, or null if they did not name one. */
    fun characterName(authored: String): String? = firstMatch(authored, characterNamePatterns)

    /** The name the player gave their world, or null if they did not name one. */
    fun worldName(authored: String): String? = firstMatch(authored, worldNamePatterns)

    private fun firstMatch(authored: String, patterns: List<Regex>): String? {
        if (authored.isBlank()) return null
        patterns.forEach { pattern ->
            val candidate = pattern.find(authored)?.groupValues?.getOrNull(1)?.let(::tidy)
            if (candidate != null && isPlausibleName(candidate)) return candidate
        }
        return null
    }

    /**
     * Words people use to label a field rather than to name a person.
     *
     * Someone writing "Adrian Voss Apperance: dark hair, green eyes" has named their character
     * Adrian Voss and then headed a section. Taking the label as part of the name leaves it
     * baked into their world for good, so it is trimmed off. Misspellings are included because
     * people type quickly and the name is canon either way.
     *
     * Only words that are never surnames belong here. "Story", "Notes" and "History" are real
     * family names, and trimming one of those would be the same mistake in the other direction.
     */
    private val fieldLabels = setOf(
        "appearance", "apperance", "appearence", "backstory", "personality", "biography",
        "bio", "description", "desc", "profile", "traits", "stats", "overview", "background"
    )

    /** Drops a trailing field label so a heading never becomes part of someone's name. */
    private fun stripFieldLabel(candidate: String): String {
        var words = candidate.split(' ').filter { it.isNotBlank() }
        while (words.size > 1 && words.last().lowercase().trim(':', '-') in fieldLabels) {
            words = words.dropLast(1)
        }
        return words.joinToString(" ")
    }

    private fun tidy(raw: String): String = raw.trim()
        .trim('"', '\'', '\u201c', '\u201d', ',', '.', ';', '-', '\u2014')
        .replace(Regex("\\s+"), " ")
        .trim()
        .let(::stripFieldLabel)

    private fun isPlausibleName(candidate: String): Boolean {
        if (candidate.length !in 2..70) return false
        val words = candidate.split(' ')
        if (words.size > 5) return false
        if (words.all { it.lowercase() in notNames }) return false
        if (words.first().lowercase() in notNames && words.size == 1) return false
        return candidate.any { it.isLetter() }
    }

    /** Names the player used anywhere, so generated characters cannot quietly reuse them. */
    fun mentionedNames(authored: String): Set<String> {
        if (authored.isBlank()) return emptySet()
        return Regex("$NAME_WORD(?:\\s+$NAME_WORD)*").findAll(authored)
            .map { it.value.trim() }
            .filter { value -> value.split(' ').none { it.lowercase() in notNames } }
            .filter { it.length in 3..70 }
            .toSet()
    }

    /** Something generated that contradicts what the player wrote. */
    data class Violation(val subject: String, val authored: String, val generated: String) {
        val description: String
            get() = "The player wrote $subject \"$authored\" but generation returned \"$generated\"."
    }

    /**
     * Restores a character's declared name. Everything else the model may elaborate on, but
     * a name the player chose is not the model's to change.
     */
    fun enforceCharacter(authored: String, concept: CharacterConcept): Pair<CharacterConcept, List<Violation>> {
        val declared = characterName(authored) ?: return concept to emptyList()
        if (concept.name.equals(declared, ignoreCase = true)) return concept to emptyList()
        val violation = Violation("their character's name", declared, concept.name)
        return concept.copy(name = declared) to listOf(violation)
    }

    /** The same for a world's title. */
    fun enforceWorld(authored: String, concept: WorldConcept): Pair<WorldConcept, List<Violation>> {
        val declared = worldName(authored) ?: return concept to emptyList()
        if (concept.name.equals(declared, ignoreCase = true)) return concept to emptyList()
        val violation = Violation("the world's name", declared, concept.name)
        return concept.copy(name = declared) to listOf(violation)
    }

    /**
     * The block handed to every generation call. The player's words go in verbatim, because
     * summarising them is already a way of changing them.
     */
    fun brief(label: String, authored: String): String {
        if (authored.isBlank()) return ""
        return """
            $label - WRITTEN BY THE PLAYER, AND THEREFORE FACT:
            <<<
            $authored
            >>>

            Everything between those markers is established truth about this world and is not
            yours to revise. You may build on it, organise it, and add detail that fits around
            it, but you may not:
              - change or replace any name the player gave
              - contradict, soften or reinterpret any fact, trait, relationship or event stated
              - drop details they bothered to write down
              - reassign something they said about one subject to a different subject
            Where their text and your own instincts disagree, their text wins. Where their text
            is silent, you are free to invent something compatible.
        """.trimIndent()
    }
}
