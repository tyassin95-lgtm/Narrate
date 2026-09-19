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
        val knowledge = repo.knowledgeDao.all(worldId)
        val events = repo.eventDao.all(worldId)
        val memories = repo.memoryDao.all(worldId).groupBy { it.turnIndex }
        val chapters = repo.chapterDao.all(worldId).sortedBy { it.fromTurn }

        return buildString {
            appendLine("# ${world.name}")
            appendLine()
            appendLine("_Narrate transcript, exported ${stamp(at)}._")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Turns played | ${world.turnCount} |")
            appendLine("| Story time | ${WorldClock.of(world).full} |")
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
                append(renderTurn(turn, issues[turn.index].orEmpty(), memories[turn.index].orEmpty()))
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
                locations.sortedBy { it.name }.forEach { location ->
                    val within = locations.firstOrNull { it.id == location.parentId }?.name
                    appendLine(
                        "- **${location.name}** (${location.type})" +
                            (within?.let { ", inside $it" }.orEmpty()) +
                            (if (!location.discovered) " - undiscovered by the player" else "")
                    )
                }
                appendLine()
            }
            if (items.isNotEmpty()) {
                appendLine("### Objects")
                appendLine()
                items.forEach { item ->
                    appendLine(
                        "- **${item.name}** [${item.possession.lowercase()}]: " +
                            Possession.describe(
                                item = item,
                                owner = characters.firstOrNull { it.id == item.ownerId },
                                holder = characters.firstOrNull { it.id == item.holderId },
                                placeName = item.locationId?.let { place(it) }
                            ) + item.state.takeIf { it.isNotBlank() }?.let { " State: $it." }.orEmpty()
                    )
                }
                appendLine()
            }
            // The whole point of the redesign, so the whole point of the diagnostic: what the
            // world holds against what the player has actually been given.
            if (knowledge.isNotEmpty()) {
                appendLine("### What the player's character knows")
                appendLine()
                knowledge.groupBy { it.subjectName.ifBlank { it.subjectId } }
                    .toSortedMap()
                    .forEach { (subject, rows) ->
                        appendLine(
                            "- **$subject**: " + rows.sortedBy { it.turnIndex }.joinToString("; ") {
                                "${it.field} (${it.source.lowercase()}" +
                                    it.sourceDetail.takeIf { detail -> detail.isNotBlank() }
                                        ?.let { detail -> ", $detail" }.orEmpty() +
                                    ", turn ${it.turnIndex})"
                            }
                        )
                    }
                appendLine()
                val hidden = characters.filter { character ->
                    !character.isPlayer && knowledge.none {
                        it.subjectId == character.id && it.field == PlayerKnowledge.EXISTS
                    }
                }
                if (hidden.isNotEmpty()) {
                    appendLine("Never met: " + hidden.joinToString(", ") { it.name } + ".")
                    appendLine()
                }
            }
            if (events.isNotEmpty()) {
                appendLine("### The calendar")
                appendLine()
                events.sortedBy { it.startMinute }.forEach { event ->
                    val at = WorldClock.stamp(event.startMinute, world)
                    appendLine(
                        "- **${event.title}** [${event.kind.lowercase()}]: ${at.full}" +
                            event.recurrence.takeIf { it.isNotBlank() }?.let { ", repeating $it" }.orEmpty() +
                            event.withNames.takeIf { it.isNotBlank() }?.let { ", with $it" }.orEmpty() +
                            " (${event.status.lowercase()})"
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

    private fun renderTurn(
        turn: TurnEntity,
        issues: List<ContinuityIssueEntity>,
        memories: List<com.narrate.app.data.entity.MemoryEntity>
    ): String = buildString {
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
                // The label is what the player sends; the intent tag is a hint on the card and
                // is never part of it. They are printed apart so they cannot be read as one line.
                appendLine("${index + 1}. [${choice.kind}] ${choice.label}")
                choice.detail.takeIf { it.isNotBlank() }?.let { appendLine("   - intent tag (never sent): $it") }
            }
        }
        appendLine()

        if (memories.isNotEmpty()) {
            appendLine("**What the world recorded:**")
            appendLine()
            memories.forEach {
                appendLine("- `${it.kind}` ${it.text}${if (it.pinned) " _(pinned)_" else ""}")
            }
            appendLine()
        }

        val world = issues.filterNot { ContinuityGuard.isGenerationNote(it.category) }
        val caught = issues.filter { ContinuityGuard.isGenerationNote(it.category) }
        if (world.isNotEmpty()) {
            appendLine("**Continuity notes for this turn (things that happened in the world):**")
            appendLine()
            world.forEach { appendLine("- `${it.severity}/${it.category}` ${it.description} → ${it.resolution}") }
            appendLine()
        }
        if (caught.isNotEmpty()) {
            appendLine("**Caught before it reached the player (never happened in the world):**")
            appendLine()
            caught.forEach { appendLine("- `${it.category}` ${it.description} → ${it.resolution}") }
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
