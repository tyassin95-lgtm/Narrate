package com.narrate.app.engine

import com.narrate.app.data.entity.CharacterEntity

/**
 * Who the player can actually reach, and how.
 *
 * Knowing that someone exists is not the same as being able to text them. The player heard
 * about Liv's ex for several turns without ever meeting him, and then the phone in the
 * narration showed "no new messages in Liv's or Evan's threads" - a thread that could not
 * exist, with a man whose number nobody had ever given anyone.
 *
 * So a channel is a fact like any other: it begins in a scene where it is handed over, it is
 * recorded, and until then there is no thread, no inbox entry and no way to call.
 */
object ContactChannels {

    const val PHONE = "PHONE"
    const val EMAIL = "EMAIL"
    const val SOCIAL = "SOCIAL"

    /** The channels this app understands, and what each one means in the fiction. */
    private val known = mapOf(
        PHONE to "calls and texts",
        EMAIL to "email",
        SOCIAL to "messages on a social account",
        "RADIO" to "radio",
        "LETTER" to "letters to a known address"
    )

    fun parse(stored: String): List<String> = stored.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .distinct()

    fun store(channels: List<String>): String = channels
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(",")

    /** Normalises what a model wrote - "phone number", "text", "cell" - into one channel. */
    fun normalise(raw: String): String {
        val value = raw.trim().uppercase()
        if (value.isBlank()) return PHONE
        known.keys.firstOrNull { value.contains(it) }?.let { return it }
        return when {
            value.contains("NUMBER") || value.contains("TEXT") || value.contains("SMS") ||
                value.contains("CELL") || value.contains("MOBILE") || value.contains("CALL") -> PHONE
            value.contains("MAIL") && !value.contains("LETTER") -> EMAIL
            value.contains("INSTAGRAM") || value.contains("MESSENGER") || value.contains("HANDLE") ||
                value.contains("PROFILE") || value.contains("ACCOUNT") -> SOCIAL
            value.contains("ADDRESS") -> "LETTER"
            else -> PHONE
        }
    }

    fun describe(channel: String): String = known[channel.uppercase()] ?: channel.lowercase()

    fun canReach(character: CharacterEntity): Boolean = character.playerContact.isNotBlank()

    fun canReachBy(character: CharacterEntity, channel: String): Boolean =
        normalise(channel) in parse(character.playerContact)

    /** Adds a channel without disturbing the ones already established. */
    fun add(existing: String, channel: String): String =
        store(parse(existing) + normalise(channel))

    fun remove(existing: String, channel: String): String =
        store(parse(existing) - normalise(channel))

    /** The verbs that mean reaching someone who is not in the room. */
    private val remoteVerbs = Regex(
        "\\b(?:text|texts|texting|message|messages|messaging|call|calls|calling|ring|rings|" +
            "phone|phones|phoning|email|emails|emailing|dm|dms|write to|writes to)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when a line of intent is about contacting someone remotely rather than speaking to them. */
    fun isRemoteContact(text: String): Boolean = remoteVerbs.containsMatchIn(text)

    /**
     * How the player's phone looks from inside the fiction: the only threads that exist.
     */
    fun render(characters: List<CharacterEntity>): String = buildString {
        val reachable = characters.filter { !it.isPlayer && canReach(it) }
        appendLine("# HOW THE PLAYER CAN REACH PEOPLE")
        if (reachable.isEmpty()) {
            appendLine(
                "Nobody. The player has no one's number, no one's email and no message thread " +
                    "with anyone. Their phone has no conversations in it."
            )
        } else {
            reachable.forEach { character ->
                appendLine(
                    "- ${character.name}: ${parse(character.playerContact).joinToString(", ") { describe(it) }}"
                )
            }
            appendLine(
                "That is the whole list. Nobody else is contactable, and no thread, inbox entry " +
                    "or call history exists with anyone not named here."
            )
        }
        appendLine(
            "A person can only be contacted once the contact details were handed over in a scene " +
                "that was actually played. If someone wants to be reachable, that exchange has to " +
                "happen on the page first, and be recorded in \"contacts\"."
        )
    }
}
