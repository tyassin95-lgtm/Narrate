package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity

/**
 * What people are wearing, and when they stopped wearing it.
 *
 * An outfit was a sentence in a column with no time attached to it, so it never went out of
 * date. Four days after a house party, a real playthrough was still describing Liv in the
 * party dress with glitter on one cheek - through a night's sleep, a day of classes and a
 * shift at a diner - because nothing in the app knew the party had been on Friday.
 *
 * Clothing is state now: what it is, when it went on, what occasion it is for, and which parts
 * of it are temporary. Glitter expires. A work uniform comes off after the shift. Nobody gets
 * a brand new outfit every turn either, which was the other half of the same problem.
 */
object Wardrobe {

    const val PARTY = "PARTY"
    const val WORK = "WORK"
    const val CLASS = "CLASS"
    const val HOME = "HOME"
    const val SLEEP = "SLEEP"
    const val GOING_OUT = "GOING_OUT"
    const val OUTDOORS = "OUTDOORS"
    const val CASUAL = "CASUAL"

    private val contexts = setOf(PARTY, WORK, CLASS, HOME, SLEEP, GOING_OUT, OUTDOORS, CASUAL)

    fun normaliseContext(raw: String): String {
        val value = raw.trim().uppercase().replace(' ', '_')
        if (value in contexts) return value
        return when {
            value.contains("PARTY") || value.contains("NIGHT_OUT") -> PARTY
            value.contains("WORK") || value.contains("SHIFT") || value.contains("SCRUB") ||
                value.contains("UNIFORM") -> WORK
            value.contains("CLASS") || value.contains("LECTURE") || value.contains("UNI") ||
                value.contains("SCHOOL") -> CLASS
            value.contains("SLEEP") || value.contains("BED") || value.contains("PYJAMA") ||
                value.contains("PAJAMA") -> SLEEP
            value.contains("HOME") || value.contains("INDOOR") -> HOME
            value.contains("DATE") || value.contains("OUT") -> GOING_OUT
            value.contains("COAT") || value.contains("COLD") || value.contains("RAIN") -> OUTDOORS
            else -> CASUAL
        }
    }

    /** A temporary detail and the moment it stops being true. */
    data class Detail(val text: String, val until: Long)

    fun parse(stored: String): List<Detail> = stored.split('\n')
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val text = trimmed.substringBefore("|until=").trim()
            val until = trimmed.substringAfter("|until=", "").trim().toLongOrNull() ?: 0L
            if (text.isBlank()) null else Detail(text, until)
        }

    fun store(details: List<Detail>): String = details
        .distinctBy { it.text.lowercase() }
        .take(4)
        .joinToString("\n") { "${it.text.truncate(90)}|until=${it.until}" }

    /** What is still true about how somebody looks, at this moment on the clock. */
    fun current(character: CharacterEntity, nowMinute: Long): List<Detail> =
        parse(character.temporaryLook).filter { it.until > nowMinute }

    /** What has stopped being true since last time, so the narrator can be told to drop it. */
    fun expired(character: CharacterEntity, nowMinute: Long): List<Detail> =
        parse(character.temporaryLook).filter { it.until in 1..nowMinute }

    fun withDetail(character: CharacterEntity, text: String, hours: Int, nowMinute: Long): CharacterEntity {
        if (text.isBlank()) return character
        val kept = current(character, nowMinute) + Detail(text.trim(), nowMinute + hours.coerceIn(1, 72) * 60L)
        return character.copy(temporaryLook = store(kept))
    }

    /** Clears anything that has run out, so the column does not carry last week's glitter. */
    fun pruned(character: CharacterEntity, nowMinute: Long): CharacterEntity {
        val kept = current(character, nowMinute)
        val stored = store(kept)
        return if (stored == character.temporaryLook) character else character.copy(temporaryLook = stored)
    }

    fun wearing(
        character: CharacterEntity,
        outfit: String,
        context: String,
        nowMinute: Long
    ): CharacterEntity = character.copy(
        outfit = outfit.trim().truncate(220).ifBlank { character.outfit },
        outfitContext = normaliseContext(context),
        outfitSetAt = nowMinute
    )

    /**
     * Whether what somebody has on has stopped making sense.
     *
     * Three questions, all of which the old model could not ask: has it been long enough that
     * they would have changed, is the occasion over, and is this the wrong thing for what they
     * are doing now.
     */
    fun stale(character: CharacterEntity, nowMinute: Long): String? {
        if (character.outfit.isBlank()) return null
        val worn = nowMinute - character.outfitSetAt
        if (character.outfitSetAt <= 0L) return null
        return when {
            character.outfitContext == PARTY && worn > 8 * 60 ->
                "still in what they wore to a party ${WorldClock.describeGap(worn)} ago"
            character.outfitContext == SLEEP && worn > 10 * 60 ->
                "still in what they slept in ${WorldClock.describeGap(worn)} ago"
            character.outfitContext == WORK && worn > 14 * 60 ->
                "still in work clothes ${WorldClock.describeGap(worn)} into the day"
            worn > 20 * 60 ->
                "wearing the same clothes they put on ${WorldClock.describeGap(worn)} ago"
            else -> null
        }
    }

    /** What the occasion calls for, so a change lands on something sensible. */
    fun expectedContext(activity: String, timeOfDay: String): String {
        val text = activity.lowercase()
        return when {
            text.contains("shift") || text.contains("hospital") || text.contains("ward") ||
                text.contains("work") -> WORK
            text.contains("class") || text.contains("lecture") || text.contains("campus") -> CLASS
            text.contains("party") || text.contains("club") || text.contains("bar") -> PARTY
            text.contains("date") || text.contains("dinner") || text.contains("meet") -> GOING_OUT
            timeOfDay == "night" -> SLEEP
            else -> CASUAL
        }
    }

    /** What the narrator is told about how everybody in the scene actually looks right now. */
    fun render(characters: List<CharacterEntity>, nowMinute: Long): String {
        val relevant = characters.filter { it.outfit.isNotBlank() || it.temporaryLook.isNotBlank() }
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("## WHAT PEOPLE ARE WEARING RIGHT NOW")
            appendLine(
                "This is current state, not the description they were created with. A person's " +
                    "appearance - their build, their face, their hair - is permanent. Their " +
                    "clothes are not, and neither is anything that washes off."
            )
            relevant.forEach { character ->
                val who = if (character.isPlayer) "${character.name} (the player)" else character.name
                val details = current(character, nowMinute)
                val gone = expired(character, nowMinute)
                append("- $who: ${character.outfit.truncate(160)}")
                append(" [${character.outfitContext.lowercase()}")
                if (character.outfitSetAt > 0) {
                    append(", on since ${WorldClock.describeGap(nowMinute - character.outfitSetAt)} ago")
                }
                append("]")
                if (details.isNotEmpty()) append(" Also: ${details.joinToString("; ") { it.text }}.")
                if (gone.isNotEmpty()) {
                    append(" NO LONGER TRUE, do not mention: ${gone.joinToString("; ") { it.text }}.")
                }
                stale(character, nowMinute)?.let { append(" They are $it - they would have changed by now.") }
                appendLine()
            }
            appendLine(
                "When somebody changes clothes, record it in \"outfits\". Do not invent a new " +
                    "outfit every turn: people wear the same thing all day, and change at " +
                    "sensible moments - getting home, sleeping, showering, starting a shift, " +
                    "going out, getting soaked."
            )
        }
    }
}
