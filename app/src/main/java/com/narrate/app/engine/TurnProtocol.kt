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
    @SerialName("held_by") val heldBy: String = "",
    val location: String = ""
)

@Serializable
data class ItemUpdate(
    val name: String = "",
    @SerialName("held_by") val heldBy: String? = null,
    val location: String? = null,
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

/** A recorded change to how a subject looks, so future images stay right. */
@Serializable
data class VisualUpdate(
    val subject: String = "",
    @SerialName("subject_type") val subjectType: String = "CHARACTER",
    val change: String = "",
    val permanent: Boolean = false
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
    @SerialName("image_suggestion") val imageSuggestion: String? = null
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
    val parseNotes: List<String>
)

/**
 * Tolerant parser. Models drift: they fence JSON, drop markers, number their choices
 * differently, or add commentary. Nothing here throws - the narration always survives.
 */
object TurnParser {

    fun parse(raw: String): ParsedTurn {
        val notes = mutableListOf<String>()
        val text = raw.trim()

        val narrationStart = text.indexOf(TurnProtocol.NARRATION)
        val choicesStart = text.indexOf(TurnProtocol.CHOICES)
        val stateStart = text.indexOf(TurnProtocol.STATE)

        val narration = when {
            narrationStart >= 0 -> {
                val from = narrationStart + TurnProtocol.NARRATION.length
                val to = listOf(choicesStart, stateStart).filter { it > from }.minOrNull() ?: text.length
                text.substring(from, to)
            }
            choicesStart > 0 -> text.substring(0, choicesStart)
            stateStart > 0 -> text.substring(0, stateStart)
            else -> {
                notes += "No section markers found; treated the whole reply as narration."
                stripStateBlock(text)
            }
        }.trim().removeSuffix(TurnProtocol.END).trim()

        val choicesBlock = if (choicesStart >= 0) {
            val from = choicesStart + TurnProtocol.CHOICES.length
            val to = if (stateStart > from) stateStart else text.length
            text.substring(from, to)
        } else ""

        val choices = parseChoices(choicesBlock)
        if (choicesStart >= 0 && choices.isEmpty()) notes += "Choice block was present but empty."

        val stateBlock = if (stateStart >= 0) text.substring(stateStart + TurnProtocol.STATE.length) else text
        val json = extractJsonObject(stateBlock)
        var parsed = false
        val delta = if (json == null) {
            if (stateStart >= 0) notes += "State block contained no readable JSON object."
            StateDelta.EMPTY
        } else {
            runCatching { AppJson.decodeFromString(StateDelta.serializer(), json) }
                .onSuccess { parsed = true }
                .getOrElse {
                    // Second chance: repair the most common model JSON mistakes and retry.
                    runCatching { AppJson.decodeFromString(StateDelta.serializer(), repairJson(json)) }
                        .onSuccess { parsed = true; notes += "State JSON needed repair before it parsed." }
                        .getOrElse {
                            notes += "State JSON could not be parsed: ${it.message?.take(160)}"
                            StateDelta.EMPTY
                        }
                }
        }

        return ParsedTurn(
            narration = narration.ifBlank { raw.trim() },
            choices = choices,
            delta = delta,
            stateParsed = parsed,
            rawResponse = raw,
            parseNotes = notes
        )
    }

    private fun stripStateBlock(text: String): String {
        val fence = Regex("```(?:json)?\\s*\\{[\\s\\S]*?}\\s*```")
        return fence.replace(text, "").trim()
    }

    private fun parseChoices(block: String): List<Choice> {
        if (block.isBlank()) return emptyList()
        val cleaned = block.replace("```", "").trim()
        return cleaned.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != TurnProtocol.END }
            .map { line -> line.removePrefix("-").removePrefix("*").trim() }
            .map { line -> line.replace(Regex("^\\d+[.):]\\s*"), "") }
            .filter { it.length > 1 }
            .take(6)
            .mapIndexed { index, line ->
                val kind = when {
                    line.startsWith("\"") || line.contains(Regex("^(Say|Ask|Tell|Reply|Answer|Whisper|Shout)\\b")) -> "SPEECH"
                    line.contains(Regex("^(Wait|Observe|Watch|Listen|Study|Examine|Look)\\b")) -> "OBSERVE"
                    else -> "ACTION"
                }
                val parts = line.split(" -- ", " | ", limit = 2)
                Choice(
                    id = "c$index",
                    label = parts[0].trim().removeSurrounding("**").trim(),
                    detail = parts.getOrNull(1)?.trim().orEmpty(),
                    kind = kind
                )
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
