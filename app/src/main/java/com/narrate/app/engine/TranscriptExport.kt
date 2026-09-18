package com.narrate.app.engine

import com.narrate.app.core.AppJson
import com.narrate.app.data.entity.ContinuityIssueEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.repo.WorldRepository
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A whole playthrough written out as one markdown file.
 *
 * Diagnosing how a world is actually going from screenshots and remembered paraphrase is slow
 * and lossy. This is the same material the app itself works from - every turn's prose, what
 * the player typed, the options they were offered, what the continuity guard noticed, and the
 * state the world ended up in - in a file that can simply be handed over.
 *
 * It contains world content only. API keys are never read here, and nothing is sent anywhere:
 * the file goes where the player puts it.
 */
object TranscriptExport {

    /** A filename that sorts by date and says which world it came from. */
    fun fileName(world: WorldEntity, at: Long = System.currentTimeMillis()): String {
        val slug = world.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "world" }
            .take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        return "narrate-$slug-$stamp.md"
    }

    suspend fun build(repo: WorldRepository, worldId: String, at: Long = System.currentTimeMillis()): String? {
        val world = repo.world(worldId) ?: return null
        val turns = repo.turnDao.all(worldId).sortedBy { it.index }
        val issues = repo.issueDao.all(worldId).groupBy { it.turnIndex }
        val characters = repo.characterDao.all(worldId)
        val player = characters.firstOrNull { it.isPlayer }
        val locations = repo.locationDao.all(worldId)
        val items = repo.itemDao.all(worldId)
        val threads = repo.threadDao.all(worldId)
        val chapters = repo.chapterDao.all(worldId).sortedBy { it.fromTurn }

        return buildString {
            appendLine("# ${world.name}")
            appendLine()
            appendLine("_Narrate transcript, exported ${stamp(at)}._")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Turns played | ${world.turnCount} |")
            appendLine("| Story time | ${world.storyTime} |")
            appendLine("| Pacing | ${PlayStyle.from(world.playStyle).label} |")
            appendLine("| Narration length | ${world.narrationLength} |")
            if (world.genre.isNotBlank()) appendLine("| Genre | ${world.genre} |")
            if (world.tone.isNotBlank()) appendLine("| Tone | ${world.tone} |")
            turns.lastOrNull()?.let { appendLine("| Model on the last turn | ${it.provider} ${it.model} |") }
            appendLine()

            if (world.premise.isNotBlank()) {
                appendLine("## Premise")
                appendLine()
                appendLine(world.premise)
                appendLine()
            }
            if (world.authoredCanon.isNotBlank()) {
                appendLine("## What the player wrote about this world")
                appendLine()
                appendLine(quote(world.authoredCanon))
                appendLine()
            }
            if (world.openingNarration.isNotBlank()) {
                appendLine("## The opening scene they asked for")
                appendLine()
                appendLine(quote(world.openingNarration))
                appendLine()
            }
            player?.let {
                appendLine("## The player character")
                appendLine()
                appendLine("**${it.name}**${it.role.takeIf { r -> r.isNotBlank() }?.let { r -> " - $r" }.orEmpty()}")
                appendLine()
                if (it.authoredCanon.isNotBlank()) {
                    appendLine(quote(it.authoredCanon))
                    appendLine()
                }
                listOf(
                    "Summary" to it.summary,
                    "Personality" to it.personality,
                    "Backstory" to it.backstory,
                    "Appearance" to it.appearance,
                    "Voice" to it.voice,
                    "Goals" to it.goals,
                    "Fears" to it.fears,
                    "Secrets" to it.secrets
                ).filter { field -> field.second.isNotBlank() }
                    .forEach { field -> appendLine("- **${field.first}:** ${field.second}") }
                appendLine()
            }

            appendLine("## Turns")
            appendLine()
            if (turns.isEmpty()) {
                appendLine("_This world has not been played yet._")
                appendLine()
            }
            turns.forEach { turn ->
                append(renderTurn(turn, issues[turn.index].orEmpty()))
            }

            if (chapters.isNotEmpty()) {
                appendLine("## Journal chapters")
                appendLine()
                chapters.forEach { chapter ->
                    appendLine("### ${chapter.title} (turns ${chapter.fromTurn}-${chapter.toTurn})")
                    appendLine()
                    appendLine(chapter.summary)
                    appendLine()
                }
            }

            appendLine("## World state at export")
            appendLine()
            appendLine("### Where everyone is")
            appendLine()
            fun place(id: String?) = locations.firstOrNull { it.id == id }?.name ?: "unrecorded"
            appendLine("- **${player?.name ?: "The player"}** (player): ${place(world.currentLocationId)}")
            characters.filterNot { it.isPlayer }.sortedByDescending { it.importance }.forEach { npc ->
                val reach = if (ContactChannels.canReach(npc)) {
                    ContactChannels.parse(npc.playerContact).joinToString(", ") { ContactChannels.describe(it) }
                } else {
                    "no way to contact them"
                }
                appendLine(
                    "- **${npc.name}**${npc.role.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}: " +
                        "${place(npc.currentLocationId)}; affinity ${npc.affinity}, trust ${npc.trust}; $reach" +
                        (if (npc.status != "ALIVE") "; ${npc.status}" else "")
                )
            }
            appendLine()

            if (locations.isNotEmpty()) {
                appendLine("### Places on the map")
                appendLine()
                locations.sortedBy { it.name }.forEach {
                    appendLine("- **${it.name}** (${it.type})${if (!it.discovered) " - undiscovered" else ""}")
                }
                appendLine()
            }
            if (items.isNotEmpty()) {
                appendLine("### Objects")
                appendLine()
                items.forEach { item ->
                    val owner = characters.firstOrNull { it.id == item.ownerId }?.name
                    val holder = characters.firstOrNull { it.id == item.holderId }?.name
                    appendLine(
                        "- **${item.name}**: " +
                            listOfNotNull(
                                owner?.let { "owned by $it" },
                                holder?.let { "held by $it" },
                                item.locationId?.let { "at ${place(it)}" },
                                item.state.takeIf { it.isNotBlank() }
                            ).joinToString("; ").ifBlank { "unplaced" }
                    )
                }
                appendLine()
            }
            if (threads.isNotEmpty()) {
                appendLine("### Threads")
                appendLine()
                threads.forEach {
                    appendLine("- **${it.title}** [${it.status}, urgency ${it.urgency}]: ${it.description}")
                }
                appendLine()
            }
        }
    }

    private fun renderTurn(turn: TurnEntity, issues: List<ContinuityIssueEntity>): String = buildString {
        val heading = listOfNotNull(
            turn.storyTime.takeIf { it.isNotBlank() },
            turn.locationName.takeIf { it.isNotBlank() }
        ).joinToString(" - ")
        appendLine("### Turn ${turn.index}${if (heading.isBlank()) "" else " - $heading"}")
        appendLine()

        if (turn.inputType == "OPENING") {
            appendLine("**The world opens.**")
        } else {
            appendLine("**Player (${turn.inputType.lowercase()}):** ${turn.playerInput.ifBlank { "(nothing typed)" }}")
        }
        appendLine()
        appendLine("**Narration:**")
        appendLine()
        appendLine(turn.narration.ifBlank { "_(no narration came back)_" })
        appendLine()

        val choices = runCatching {
            AppJson.decodeFromString(ListSerializer(Choice.serializer()), turn.choicesJson)
        }.getOrDefault(emptyList())
        appendLine("**Suggested actions:**")
        appendLine()
        if (choices.isEmpty()) {
            appendLine("_(none were offered this turn)_")
        } else {
            choices.forEachIndexed { index, choice ->
                val tag = choice.detail.takeIf { it.isNotBlank() }?.let { " _(— $it)_" }.orEmpty()
                appendLine("${index + 1}. [${choice.kind}] ${choice.label}$tag")
            }
        }
        appendLine()

        if (issues.isNotEmpty()) {
            appendLine("**Continuity notes for this turn:**")
            appendLine()
            issues.forEach { appendLine("- `${it.severity}/${it.category}` ${it.description} → ${it.resolution}") }
            appendLine()
        }
        appendLine("---")
        appendLine()
    }

    private fun quote(text: String): String =
        text.trim().lines().joinToString("\n") { "> $it" }

    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))
}
