package com.narrate.app.engine

import com.narrate.app.core.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire format between the app and whichever model the player chose.
 *
 * Narration is emitted as raw text (never inside JSON) so long prose is never mangled by
 * escaping, while the machine-readable world updates arrive as a small JSON object.
 */
object TurnProtocol {
    const val NARRATION = "===NARRATION==="
    const val CHOICES = "===CHOICES==="
    const val STATE = "===STATE==="
    const val END = "===END==="
}

@Serializable
data class PlayerDelta(
    @SerialName("location") val location: String? = null,
    @SerialName("condition") val condition: String? = null,
    @SerialName("outfit") val outfit: String? = null,
    @SerialName("appearance_change") val appearanceChange: String? = null,
    @SerialName("knowledge_add") val knowledgeAdd: List<String> = emptyList(),
    @SerialName("items_gained") val itemsGained: List<String> = emptyList(),
    @SerialName("items_lost") val itemsLost: List<String> = emptyList()
)

@Serializable
data class NewCharacter(
    val name: String = "",
    val role: String = "",
    val summary: String = "",
    val personality: String = "",
    val appearance: String = "",
    val outfit: String = "",
    val voice: String = "",
    val goals: String = "",
    val secrets: String = "",
    val faction: String = "",
    @SerialName("relationship_to_player") val relationshipToPlayer: String = "",
    val location: String = "",
    @SerialName("home_location") val homeLocation: String = "",
    val routine: String = "",
    val importance: Int = 3
)

@Serializable
data class CharacterUpdate(
    val name: String = "",
    val location: String? = null,
    @SerialName("movement_reason") val movementReason: String? = null,
    val status: String? = null,
    val outfit: String? = null,
    @SerialName("physical_state") val physicalState: String? = null,
    val goals: String? = null,
    @SerialName("affinity_delta") val affinityDelta: Int = 0,
    @SerialName("trust_delta") val trustDelta: Int = 0,
    @SerialName("knowledge_add") val knowledgeAdd: List<String> = emptyList(),
    @SerialName("relationship_to_player") val relationshipToPlayer: String? = null,
    val note: String? = null
)

@Serializable
data class NewLocation(
    val name: String = "",
    val type: String = "BUILDING",
    val parent: String = "",
    val description: String = "",
    val atmosphere: String = "",
    @SerialName("notable_features") val notableFeatures: String = "",
    @SerialName("connects_to") val connectsTo: List<String> = emptyList(),
    @SerialName("travel_time") val travelTime: String = "",
    @SerialName("controlled_by") val controlledBy: String = ""
)

@Serializable
data class LocationUpdate(
    val name: String = "",
    @SerialName("state_change") val stateChange: String? = null,
    val description: String? = null,
    val atmosphere: String? = null,
    @SerialName("controlled_by") val controlledBy: String? = null,
    val discovered: Boolean? = null
)

@Serializable
data class NewLink(
    val from: String = "",
    val to: String = "",
    @SerialName("travel_time") val travelTime: String = "",
    val mode: String = "on foot",
    val description: String = ""
)

@Serializable
data class NewItem(
    val name: String = "",
    val description: String = "",
    val appearance: String = "",
    val significance: String = "",
    /** Whose it is. Defaults to whoever is holding it when it first appears. */
    val owner: String = "",
    @SerialName("held_by") val heldBy: String = "",
    val location: String = ""
)

@Serializable
data class ItemUpdate(
    val name: String = "",
    /** Only set when the object genuinely changes hands for good. Lending does not. */
    val owner: String? = null,
    @SerialName("held_by") val heldBy: String? = null,
    val location: String? = null,
    /**
     * What kind of change this is: lend, give, return, take, drop, store, lose, destroy.
     *
     * Owner and holder alone cannot tell lending from giving, and the difference decides
     * whether the player can ask for their own coat back or is asking for a gift back.
     */
    val transfer: String? = null,
    val state: String? = null
)

@Serializable
data class FactionDelta(
    val name: String = "",
    val description: String = "",
    val goals: String = "",
    val leader: String = "",
    val territory: String = "",
    @SerialName("standing_delta") val standingDelta: Int = 0,
    val status: String? = null
)

@Serializable
data class RelationshipDelta(
    val from: String = "",
    val to: String = "",
    val type: String = "",
    val descriptor: String = "",
    @SerialName("strength_delta") val strengthDelta: Int = 0,
    val note: String = ""
)

@Serializable
data class MemoryDelta(
    val text: String = "",
    val kind: String = "EVENT",
    val importance: Int = 3,
    val subjects: List<String> = emptyList()
)

@Serializable
data class ThreadDelta(
    val title: String = "",
    val description: String = "",
    val status: String = "ACTIVE",
    val urgency: Int = 2,
    val involved: List<String> = emptyList(),
    @SerialName("next_beat") val nextBeat: String = "",
    val deadline: String = ""
)

/**
 * An exchange of contact details, recorded the turn it happens.
 *
 * Without this there is no way for the player to reach anyone, which is the point: a number
 * exists in this world only once somebody has handed it over in a scene.
 */
@Serializable
data class ContactDelta(
    val character: String = "",
    /** PHONE, EMAIL, SOCIAL, RADIO, LETTER - or the words the model used, which are normalised. */
    val channel: String = "PHONE",
    /** False when a channel is lost: a blocked number, a changed address, a burned phone. */
    val established: Boolean = true,
    val note: String = ""
)

/** A recorded change to how a subject looks, so future images stay right. */
@Serializable
data class VisualUpdate(
    val subject: String = "",
    @SerialName("subject_type") val subjectType: String = "CHARACTER",
    val change: String = "",
    val permanent: Boolean = false
)

/**
 * Something that crossed from the world's knowledge into the player character's this turn.
 *
 * This is how anything gets into the codex, the map or the player's own mouth. A field the
 * narrator never reveals stays hidden however long the world has known it.
 */
@Serializable
data class RevealDelta(
    /** Who or what it is about: a character, a place, an object, a faction. */
    val about: String = "",
    @SerialName("subject_type") val subjectType: String = "CHARACTER",
    /** Which part of them: role, goals, home, secrets, exists... */
    val field: String = "",
    /** What the player now knows, in the terms they learned it. */
    val value: String = "",
    /** How: she told them, they read it, they saw it, they worked it out. */
    val how: String = "",
    /** Who or what it came from. */
    val from: String = ""
)

/**
 * What the narrator did with the beat: how far it got, how long it took, why it stopped.
 *
 * The clock moves by [minutes] and by nothing else. The narrator no longer writes a time at
 * all - it reports a duration, and the app does the arithmetic, which is the only arrangement
 * in which the two can never disagree.
 */
@Serializable
data class SceneDelta(
    /** CONTINUING while the player is still in the scene; RESOLVED once it is finished. */
    val status: String = "CONTINUING",
    /** How long this beat took on the world clock. */
    val minutes: Int = 0,
    @SerialName("ended_because") val endedBecause: String = ""
)

/** Something that is going to happen, with a day and a time rather than a word like "Friday". */
@Serializable
data class EventDelta(
    val title: String = "",
    val description: String = "",
    /** SHIFT, CLASS, MEETING, APPOINTMENT, DEADLINE, PLAN. */
    val kind: String = "PLAN",
    /** When, in the words people arrange things in: "Friday 8 PM", "tomorrow morning". */
    @SerialName("when") val whenText: String = "",
    @SerialName("duration_minutes") val durationMinutes: Int = 60,
    val location: String = "",
    @SerialName("with") val withNames: String = "",
    /** Blank for a one-off, or "WEEKLY:MON,WED" / "DAILY" for something that repeats. */
    val recurrence: String = "",
    /** "player" for the player's own commitment, otherwise whose it is. */
    @SerialName("for") val forWhom: String = "player",
    /** CONFIRMED, TENTATIVE, CANCELLED, DONE, MISSED. */
    val status: String = "CONFIRMED"
)

/**
 * What somebody is wearing now, and for how long the temporary parts of it last.
 *
 * Clothing was a sentence with no time attached, so a party dress and glitter on one cheek
 * were still being described four days later.
 */
@Serializable
data class OutfitDelta(
    val character: String = "",
    val wearing: String = "",
    /** PARTY, WORK, CLASS, HOME, SLEEP, GOING_OUT, OUTDOORS, CASUAL. */
    val context: String = "CASUAL",
    /** Glitter, makeup, wet hair, a hand stamp: true now, not true tomorrow. */
    val temporary: String = "",
    @SerialName("temporary_hours") val temporaryHours: Int = 8
)

@Serializable
data class StateDelta(
    @SerialName("story_time") val storyTime: String? = null,
    @SerialName("time_passed") val timePassed: String? = null,
    val summary: String? = null,
    val player: PlayerDelta? = null,
    @SerialName("characters_new") val charactersNew: List<NewCharacter> = emptyList(),
    @SerialName("characters_update") val charactersUpdate: List<CharacterUpdate> = emptyList(),
    @SerialName("locations_new") val locationsNew: List<NewLocation> = emptyList(),
    @SerialName("locations_update") val locationsUpdate: List<LocationUpdate> = emptyList(),
    @SerialName("links_new") val linksNew: List<NewLink> = emptyList(),
    @SerialName("items_new") val itemsNew: List<NewItem> = emptyList(),
    @SerialName("items_update") val itemsUpdate: List<ItemUpdate> = emptyList(),
    @SerialName("factions") val factions: List<FactionDelta> = emptyList(),
    val relationships: List<RelationshipDelta> = emptyList(),
    val memories: List<MemoryDelta> = emptyList(),
    val threads: List<ThreadDelta> = emptyList(),
    @SerialName("visual_updates") val visualUpdates: List<VisualUpdate> = emptyList(),
    /** Contact details exchanged this turn, in either direction. */
    val contacts: List<ContactDelta> = emptyList(),
    /** What the player's character learned this turn, and how. */
    val revealed: List<RevealDelta> = emptyList(),
    /** How far this beat got, how long it took, and why it stopped there. */
    val scene: SceneDelta? = null,
    /** Anything arranged, scheduled or due. */
    val events: List<EventDelta> = emptyList(),
    /** Who changed what they are wearing, and into what. */
    val outfits: List<OutfitDelta> = emptyList(),
    @SerialName("image_suggestion") val imageSuggestion: String? = null,
    /** Some models put the choices in the state block instead. Accepted rather than lost. */
    val choices: List<String> = emptyList()
) {
    companion object {
        val EMPTY = StateDelta()
    }
}

/** A suggested action offered to the player. Suggestions only: the input box always wins. */
@Serializable
data class Choice(
    val id: String,
    val label: String,
    val detail: String = "",
    val kind: String = "ACTION"
)

data class ParsedTurn(
    val narration: String,
    val choices: List<Choice>,
    val delta: StateDelta,
    val stateParsed: Boolean,
    val rawResponse: String,
    val parseNotes: List<String>,
    /** The prose stops mid-sentence, which usually means the reply hit the token ceiling. */
    val looksUnfinished: Boolean = false,
    /**
     * Whether the reply actually carried prose. A completion that was asked for choices alone
     * has none, and must never have its choices and JSON mistaken for narration.
     */
    val hasNarration: Boolean = true
) {
    /**
     * A turn is complete when the world was updated and the reply is not cut off.
     *
     * Having something to do is no longer part of it. A scene that has resolved - they got
     * home, they said goodnight, the shift ended - offers nothing to choose, and asking the
     * narrator for options anyway is what filled menus with sitting down and checking the
     * time. What it may not do is end mid-sentence or forget the state block.
     */
    val isComplete: Boolean
        get() = stateParsed && !looksUnfinished &&
            (choices.isNotEmpty() || delta.scene?.status?.uppercase() == "RESOLVED")
}

/**
 * Tolerant parser. Models drift: they fence JSON, drop markers, number their choices
 * differently, or add commentary. Nothing here throws - the narration always survives.
 */
object TurnParser {

    /**
     * Section headers, in the order of how much they can be trusted. Models drift away from
     * the exact markers - they drop the equals signs, bold the word, or write "Choices:" -
     * and a reply that is perfectly good otherwise should not lose its choices to that.
     */
    private fun sectionRegex(name: String) = Regex(
        "(?:^|\\n)\\s*(?:={2,}\\s*$name\\s*={2,}|\\*{0,2}#{0,4}\\s*$name\\s*:?\\s*\\*{0,2})\\s*(?=\\n|$)",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val narrationHeader = sectionRegex("NARRATION")
    private val choicesHeader = sectionRegex("(?:CHOICES|OPTIONS|ACTIONS)")
    private val stateHeader = sectionRegex("(?:STATE|WORLD STATE|STATE UPDATE)")
    private val endHeader = sectionRegex("END")

    private data class Section(val start: Int, val contentStart: Int)

    private fun find(regex: Regex, text: String, from: Int = 0): Section? =
        regex.find(text, from)?.let { Section(it.range.first, it.range.last + 1) }

    fun parse(raw: String): ParsedTurn {
        val notes = mutableListOf<String>()
        val text = raw.trim()

        val narrationSection = find(narrationHeader, text)
        val choicesSection = find(choicesHeader, text)
        val stateSection = find(stateHeader, text)
        val endSection = find(endHeader, text)

        val boundariesAfterNarration = listOfNotNull(choicesSection?.start, stateSection?.start, endSection?.start)
        val firstOtherHeader = boundariesAfterNarration.minOrNull()

        var narration = when {
            narrationSection != null -> {
                val from = narrationSection.contentStart
                val to = boundariesAfterNarration.filter { it > from }.minOrNull() ?: text.length
                text.substring(from, to)
            }
            // Prose before the first header is narration; a reply that opens on a header has none.
            firstOtherHeader != null -> text.substring(0, firstOtherHeader)
            else -> {
                notes += "No section markers found; treated the whole reply as narration."
                stripStateBlock(text)
            }
        }.trim()

        val choicesBlock = choicesSection?.let { section ->
            // A section header consumes the newline before it, so the next header can begin
            // one character *before* this one's content does. Comparing against the content
            // start therefore missed it entirely when a section was empty - and an empty
            // choices section is now a legitimate turn, so the whole state block was being
            // read back as a list of options.
            val to = listOfNotNull(stateSection?.start, endSection?.start)
                .filter { it >= section.start }
                .minOrNull()
                ?.coerceAtLeast(section.contentStart)
                ?: text.length
            text.substring(section.contentStart, to)
        }.orEmpty()

        var choices = parseChoices(choicesBlock)
        if (choicesSection != null && choices.isEmpty()) notes += "The choice section was present but empty."

        // Some models never write the header and simply end the prose with a list of options.
        if (choices.isEmpty()) {
            val salvaged = salvageTrailingChoices(narration)
            if (salvaged != null) {
                choices = salvaged.choices
                narration = salvaged.narration
                notes += "Choices were recovered from the end of the narration."
            }
        }

        val stateBlock = stateSection?.let { text.substring(it.contentStart) } ?: text
        val json = extractJsonObject(stateBlock)
        var parsed = false
        var delta = if (json == null) {
            if (stateSection != null) notes += "The state section contained no readable JSON object."
            StateDelta.EMPTY
        } else {
            runCatching { AppJson.decodeFromString(StateDelta.serializer(), json) }
                .onSuccess { parsed = true }
                .getOrElse {
                    // Second chance: repair the most common model JSON mistakes and retry.
                    runCatching { AppJson.decodeFromString(StateDelta.serializer(), repairJson(json)) }
                        .onSuccess { parsed = true; notes += "The state JSON needed repair before it parsed." }
                        .getOrElse {
                            notes += "The state JSON could not be parsed: ${it.message?.take(160)}"
                            StateDelta.EMPTY
                        }
                }
        }

        // A model that answered in the state block rather than the choices section still answered.
        if (choices.isEmpty() && delta.choices.isNotEmpty()) {
            choices = parseChoices(delta.choices.joinToString("\n"))
            if (choices.isNotEmpty()) notes += "Choices were taken from the state block."
        }
        if (delta.choices.isNotEmpty()) delta = delta.copy(choices = emptyList())

        val cleanedNarration = narration.trim()
        return ParsedTurn(
            narration = cleanedNarration,
            choices = choices,
            delta = delta,
            stateParsed = parsed,
            rawResponse = raw,
            parseNotes = notes,
            looksUnfinished = endsMidSentence(cleanedNarration),
            hasNarration = cleanedNarration.isNotBlank()
        )
    }

    /**
     * A reply that stops mid-sentence was cut off, whether or not the provider said so.
     * Deliberate endings land on punctuation, a closing quote or a closed markup block.
     */
    fun endsMidSentence(narration: String): Boolean {
        val trimmed = narration.trimEnd()
        if (trimmed.length < 40) return false
        if (trimmed.endsWith("]]")) return false
        val last = trimmed.last()
        // Typographic punctuation ends a sentence just as straight punctuation does. Treating
        // a line that closes on a curly quote as unfinished would buy a repair call, and its
        // cost, for every turn that ends on someone speaking.
        return last !in setOf(
            '.', '!', '?', '"', '\'', ')', ':', '-', '*', '_', ']', '}',
            '\u201d', '\u2019', '\u00bb', '\u2026', '\u2014'
        )
    }

    private data class Salvage(val narration: String, val choices: List<Choice>)

    /**
     * Lifts a trailing list out of the prose. Only a run of short list-shaped lines at the
     * very end qualifies, so a bulleted letter in the middle of a scene is never eaten.
     */
    private fun salvageTrailingChoices(narration: String): Salvage? {
        val lines = narration.lines()
        if (lines.size < 3) return null
        val listShaped = Regex("^\\s*(?:[-*\\u2022]|\\d+[.):])\\s+\\S.*")
        var index = lines.size - 1
        val collected = mutableListOf<String>()
        while (index >= 0) {
            val line = lines[index]
            if (line.isBlank()) {
                if (collected.isEmpty()) { index--; continue } else break
            }
            if (!listShaped.matches(line) || line.length > 220 || line.contains("[[")) break
            collected += line
            index--
        }
        if (collected.size < 2) return null
        // Drop an immediately preceding prompt line such as "What do you do?" or "Choices:".
        var cut = index + 1
        while (cut > 0 && lines[cut - 1].isBlank()) cut--
        val lead = lines.getOrNull(cut - 1)?.trim().orEmpty()
        if (lead.length <= 60 && Regex("(choice|option|what (do|will) you|you (can|could)|next)", RegexOption.IGNORE_CASE)
                .containsMatchIn(lead)
        ) {
            cut -= 1
        }
        val choices = parseChoices(collected.reversed().joinToString("\n"))
        if (choices.isEmpty()) return null
        return Salvage(lines.take(cut).joinToString("\n").trim(), choices)
    }

    private fun stripStateBlock(text: String): String {
        val fence = Regex("```(?:json)?\\s*\\{[\\s\\S]*?}\\s*```")
        return fence.replace(text, "").trim()
    }

    private val bulletMarker = Regex("^(?:[-\u2022]|\\*(?!\\*)|\\d+[.):])\\s*")

    private fun parseChoices(block: String): List<Choice> {
        if (block.isBlank()) return emptyList()
        val cleaned = block.replace("```", "").trim()
        val headerish = Regex(
            "^(choices|options|actions|what (do|will) you do|you (can|could)|next)\\b.{0,40}$",
            RegexOption.IGNORE_CASE
        )
        return cleaned.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { endHeader.containsMatchIn(it) || headerish.matches(it) }
            .map { line -> line.removePrefix(">").trim() }
            // Strip a bullet or number, but only when it is really a list marker: a leading
            // "*" belongs to **bold** far more often than it belongs to a bullet.
            .map { line -> line.replace(bulletMarker, "") }
            .map { line -> line.trim().removeSurrounding("**").trim() }
            .filter { it.length > 1 }
            .distinctBy { it.lowercase().take(60) }
            .take(6)
            .mapIndexed { index, line ->
                val kind = when {
                    line.startsWith("\"") || line.startsWith("\u201c") ||
                        line.contains(Regex("^(Say|Ask|Tell|Reply|Answer|Whisper|Shout)\\b")) -> "SPEECH"
                    line.contains(Regex("^(Wait|Observe|Watch|Listen|Study|Examine|Look)\\b")) -> "OBSERVE"
                    else -> "ACTION"
                }
                val parts = line.split(" -- ", " | ", limit = 2)
                // What the player sends must be their action alone, with any predicted
                // outcome the narrator tacked on removed before it can reach the input box.
                val label = ChoiceSanitizer.clean(parts[0].trim().removeSurrounding("**").trim()).take(320)
                val detail = parts.getOrNull(1)?.trim().orEmpty()
                    .let(ChoiceSanitizer::clean)
                    .let { if (ChoiceSanitizer.looksLikeCommentary(it)) "" else it }
                    .take(160)
                Choice(id = "c$index", label = label, detail = detail, kind = kind)
            }
            .toList()
    }

    /** Finds the first balanced JSON object, ignoring braces that appear inside strings. */
    fun extractJsonObject(text: String): String? {
        val cleaned = text.replace("```json", "```")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until cleaned.length) {
            val char = cleaned[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return cleaned.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /**
     * The same, but tolerant of a reply that was cut off mid-object.
     *
     * A generation that ran out of budget halfway through still contains everything the model
     * wrote before it stopped. Throwing all of it away is how a world ends up with four of its
     * nine fields filled for no reason the player can see, so the unfinished structure is closed
     * and, if that still will not parse, walked back one field at a time until it does.
     */
    fun salvageJsonObject(text: String): String? {
        extractJsonObject(text)?.let { return it }
        val cleaned = text.replace("```json", "```")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var body = cleaned.substring(start).trimEnd()
        repeat(MAX_SALVAGE_STEPS) {
            val closed = closeOpenStructures(body)
            if (closed != null && parsesAsObject(closed)) return closed
            val cut = body.lastIndexOf(',')
            if (cut <= 0) return null
            body = body.substring(0, cut).trimEnd()
        }
        return null
    }

    /** Adds the quote and the brackets the model never got to write. */
    private fun closeOpenStructures(body: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (char in body) {
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> stack.addLast('}')
                char == '[' -> stack.addLast(']')
                char == '}' || char == ']' -> stack.removeLastOrNull()
            }
        }
        if (stack.isEmpty()) return null
        val tail = buildString {
            append(body)
            if (escaped) setLength(length - 1)
            if (inString) append('"')
        }.trimEnd().trimEnd(',', ':')
        return tail + stack.reversed().joinToString("")
    }

    private fun parsesAsObject(json: String): Boolean =
        runCatching { AppJson.parseToJsonElement(json) as? JsonObject }.getOrNull() != null

    private const val MAX_SALVAGE_STEPS = 24

    /** Trailing commas and stray comments are the two mistakes models make most often. */
    private fun repairJson(json: String): String = json
        .replace(Regex("//[^\n]*"), "")
        .replace(Regex(",\\s*([}\\]])"), "$1")
        .replace(Regex("}\\s*\\{"), "},{")
}

/** Helpers for reading loosely-typed JSON the generators return. */
fun JsonElement?.stringOr(default: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull() ?: default

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

fun JsonObject.str(key: String, default: String = ""): String = this[key].stringOr(default)

fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.jsonPrimitive?.content } ?: emptyList()
