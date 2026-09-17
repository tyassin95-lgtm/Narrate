package com.narrate.app.engine

/**
 * Strips everything from a suggested action that is not the player's own words or intent.
 *
 * A suggestion is text the player is about to send as their turn. When the narrator appends
 * what it expects to happen - "...and offer your jacket, making her trust you and causing her
 * to open up" - that prediction is sent along with the action, and the player has effectively
 * instructed the narrator to force the outcome. Only the player's part survives.
 */
object ChoiceSanitizer {

    /**
     * Connectors that introduce a consequence rather than an intention.
     *
     * "making sure" and "so that you can" are the player's own purpose and stay; "making her
     * trust you" and "so that she opens up" decide what somebody else does, and go.
     */
    private val outcomeConnectors = listOf(
        "making", "causing", "prompting", "leading", "earning", "getting", "forcing",
        "convincing", "persuading", "revealing", "resulting", "ensuring", "allowing",
        "triggering", "sparking", "winning"
    )

    /** Words that mark the tail as the player's own purpose, not a promised result. */
    private val ownIntent = listOf("sure", "certain", "your way", "yourself", "time", "space")

    private val outcomeClause = Regex(
        ",\\s*(?:and\\s+)?(?:${outcomeConnectors.joinToString("|")})\\b[^\"\\u201d]*$",
        RegexOption.IGNORE_CASE
    )

    /** ", which will reveal" / ", so she opens up" / ", so that he agrees". */
    private val consequenceClause = Regex(
        ",\\s*(?:and\\s+)?(?:which|so(?:\\s+that)?|whereupon|after which)\\s+" +
            "(?:she|he|they|it|the\\s+\\w+|will|would|should|might|may|then|" +
            "reveals?|makes?|causes?|leads?|gets?|opens?|proves?)\\b[^\"\\u201d]*$",
        RegexOption.IGNORE_CASE
    )

    /** A whole trailing sentence of prediction: "This will make her trust you." */
    private val predictionSentence = Regex(
        "(?:(?<=\\.)|(?<=\\!)|(?<=\\?))\\s*(?:this|that|it|she|he|they)\\s+" +
            "(?:will|would|should|is going to|may|might|then)\\b[^.!?]*[.!?]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** Any formatting block marker, opening or closing, complete or not. */
    private val markupTag = Regex("\\[\\[/?\\s*\\w*[^\\]]*(?:]]|$)")

    /** Parenthetical stage directions aimed at the narrator rather than spoken aloud. */
    private val trailingAside = Regex("[\\s]*[(\\[][^)\\]]*[)\\]][\\s]*$")

    private val gmWords = Regex(
        "\\b(?:the (?:gm|narrator|ai)|prompting the|" +
            "(?:this|that|it|which)\\s+(?:will|would|should|might)\\b|" +
            "guarantee(?:s|d)?|this leads to|outcome|consequence)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns the action as the player would actually send it. Anything left unrecognised is
     * left alone: over-trimming a good suggestion is worse than letting an odd one through.
     */
    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        // Formatting markup in a suggestion is loaded straight into the player's input box,
        // where "[[sms from=\"me\"]] on my way" is not something anyone would type. The words
        // inside it are what the player meant to send, so they are kept and the tags are not.
        if (text.contains("[[")) {
            text = markupTag.replace(text, " ").replace(Regex("\\s+"), " ").trim()
        }

        // An aside in brackets at the end is never part of what the player says or does.
        var previous: String
        do {
            previous = text
            text = trailingAside.replace(text, "").trim()
        } while (text != previous && text.isNotEmpty())

        text = predictionSentence.replace(text, "").trim()
        text = consequenceClause.replace(text, "").trim()

        // Only cut an outcome connector when it really promises somebody else's reaction.
        outcomeClause.find(text)?.let { match ->
            val tail = match.value.lowercase()
            if (ownIntent.none { tail.contains(it) }) {
                text = text.removeRange(match.range).trim()
            }
        }

        text = text.trim().trimEnd(',', ';', '-', '—').trim()

        // A quoted line that lost its closing mark to the trimming gets it back.
        if (text.count { it == '"' } % 2 == 1) text = "$text\""
        val openCurly = text.count { it == '“' }
        val closeCurly = text.count { it == '”' }
        if (openCurly > closeCurly) text = "$text”"

        return text.ifBlank { raw.trim() }
    }

    /** True when the text still reads as a prediction or an instruction to the narrator. */
    fun looksLikeCommentary(text: String): Boolean = gmWords.containsMatchIn(text)
}
