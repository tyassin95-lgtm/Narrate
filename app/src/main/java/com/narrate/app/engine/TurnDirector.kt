package com.narrate.app.engine

import com.narrate.app.ai.ChatMessage
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.core.AppJson
import com.narrate.app.core.newId
import com.narrate.app.core.truncate
import com.narrate.app.data.entity.ChapterEntity
import com.narrate.app.data.entity.TurnEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import kotlinx.serialization.builtins.ListSerializer

data class TurnResult(
    val turn: TurnEntity,
    val parsed: ParsedTurn,
    val issues: List<ContinuityGuard.Issue>
)

/**
 * Runs one exchange with the world.
 *
 * Assembles the prompt from persisted state rather than conversation history, calls the
 * player's chosen model, parses the reply, puts it through the continuity guard, commits
 * the result, and folds old turns into chapter summaries so history never falls out of context.
 */
class TurnDirector(
    private val repo: WorldRepository,
    private val settings: SettingsStore
) {

    private val applier = StateApplier(repo)

    suspend fun opening(worldId: String): Result<TurnResult> =
        run(worldId, input = "", kind = "OPENING")

    suspend fun take(worldId: String, input: String, kind: String): Result<TurnResult> =
        run(worldId, input, kind)

    private suspend fun run(worldId: String, input: String, kind: String): Result<TurnResult> = runCatching {
        val config = settings.current
        val choice = config.narration
        if (!choice.isSet) {
            throw ProviderException(choice.provider, "No narration model selected. Choose one in Settings.")
        }
        val snapshot = repo.snapshot(worldId, config.recentTurnWindow)
            ?: throw IllegalStateException("That world could not be loaded.")
        val turnIndex = snapshot.world.turnCount

        val system = Prompts.gameMaster(snapshot.world)
        val history = conversationHistory(snapshot)
        val context = buildContext(snapshot, input, kind, turnIndex)

        val response = ProviderRegistry.get(choice.provider).chat(
            LlmRequest(
                model = choice.model,
                system = system,
                messages = history + ChatMessage.user(context),
                maxTokens = config.maxTokens,
                temperature = config.temperature.toDouble()
            ),
            settings.apiKey(choice.provider)
        )

        val parsed = TurnParser.parse(response.text)
        val applied = applier.apply(snapshot, parsed.delta, turnIndex, parsed.narration)

        if (!parsed.stateParsed && parsed.narration.isNotBlank()) {
            repo.saveIssues(
                ContinuityGuard.Report().apply {
                    add(
                        ContinuityGuard.SEVERITY_WARNING,
                        "state-block",
                        "The narrator returned prose but no readable state update for turn $turnIndex.",
                        "The narration was kept; world state was left unchanged for this turn."
                    )
                }.toEntities(worldId, turnIndex)
            )
        }

        val turn = TurnEntity(
            id = newId(),
            worldId = worldId,
            index = turnIndex,
            inputType = kind,
            playerInput = input,
            narration = parsed.narration,
            summary = parsed.delta.summary?.trim().orEmpty().ifBlank { parsed.narration.truncate(220) },
            choicesJson = AppJson.encodeToString(ListSerializer(Choice.serializer()), parsed.choices),
            storyTime = applied.world.storyTime,
            locationId = applied.world.currentLocationId,
            locationName = snapshot.locationName(applied.world.currentLocationId),
            presentCharacterIds = applied.presentCharacterIds.joinToString(","),
            model = response.model,
            provider = choice.provider.name
        )
        repo.saveTurn(turn)
        repo.touchWorld(worldId)

        compactIfNeeded(worldId)

        TurnResult(turn, parsed, applied.report.issues)
    }

    /** Recent turns replayed as a real conversation, so the model feels the rhythm of the scene. */
    private fun conversationHistory(snapshot: WorldSnapshot): List<ChatMessage> {
        val turns = snapshot.recentTurns
        if (turns.isEmpty()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        val verbatimFrom = (turns.size - 3).coerceAtLeast(0)
        turns.forEachIndexed { index, turn ->
            val playerText = when {
                turn.inputType == "OPENING" -> "(the world opens)"
                turn.playerInput.isBlank() -> "(the player waits)"
                else -> turn.playerInput
            }
            messages += ChatMessage.user(playerText)
            val narration = if (index >= verbatimFrom) turn.narration else turn.summary.ifBlank { turn.narration.truncate(400) }
            messages += ChatMessage.assistant(narration)
        }
        return messages
    }

    /** The whole of the current truth, rebuilt from the database every single turn. */
    private suspend fun buildContext(
        snapshot: WorldSnapshot,
        input: String,
        kind: String,
        turnIndex: Int
    ): String = buildString {
        appendLine(WorldDigest.worldBible(snapshot))
        appendLine()
        appendLine(WorldDigest.playerDossier(snapshot))
        appendLine()
        appendLine(WorldDigest.history(snapshot))
        appendLine()
        val memories = MemoryIndex.retrieve(snapshot, input, settings.current.memoryRetrievalCount)
        appendLine(MemoryIndex.render(memories))
        appendLine()
        appendLine(WorldDigest.currentState(snapshot))
        appendLine()
        appendLine(WorldDigest.relationshipDigest(snapshot))
        appendLine(WorldDigest.threadDigest(snapshot))
        appendLine(WorldDigest.visualDigest(snapshot))

        if (settings.current.simulateOffscreenWorld && turnIndex > 0) {
            val lastTime = snapshot.recentTurns.lastOrNull()?.storyTime ?: snapshot.world.storyTime
            val ticks = WorldSimulator.simulate(snapshot, lastTime)
            if (ticks.isNotEmpty()) {
                appendLine()
                appendLine(WorldSimulator.render(ticks))
            }
        }

        if (settings.current.strictContinuity && turnIndex > 0) {
            val previous = repo.issueDao.forTurn(snapshot.world.id, turnIndex - 1)
                .filter { it.severity != ContinuityGuard.SEVERITY_INFO }
                .map { "${it.description} (${it.resolution})" }
            if (previous.isNotEmpty()) {
                appendLine()
                appendLine(ContinuityGuard.renderCorrections(previous))
            }
        }

        appendLine()
        appendLine("# THIS TURN")
        appendLine(if (kind == "OPENING") Prompts.openingInstruction() else Prompts.playerInputInstruction(input, kind))
        appendLine()
        appendLine(
            "Respond with the four sections in order: ${TurnProtocol.NARRATION}, ${TurnProtocol.CHOICES}, " +
                "${TurnProtocol.STATE}, ${TurnProtocol.END}. The state block is not optional - anything you " +
                "narrate but fail to record there will be lost to this world forever."
        )
    }

    /**
     * Chapter compaction. Once enough turns have gone by, the oldest ones are summarised into
     * a chapter so that early history stays available forever at a fraction of the context cost.
     */
    private suspend fun compactIfNeeded(worldId: String) {
        val config = settings.current
        val world = repo.world(worldId) ?: return
        val lastCompacted = repo.lastCompactedTurn(worldId)
        val keepRecent = config.recentTurnWindow.coerceAtLeast(6)
        val compactUpTo = world.turnCount - keepRecent - 1
        if (compactUpTo - lastCompacted < CHAPTER_SIZE) return

        val from = lastCompacted + 1
        val to = (from + CHAPTER_SIZE - 1).coerceAtMost(compactUpTo)
        val turns = repo.turnsBetween(worldId, from, to)
        if (turns.isEmpty()) return

        val transcript = turns.joinToString("\n\n") { turn ->
            buildString {
                appendLine("--- Turn ${turn.index} (${turn.storyTime}, ${turn.locationName}) ---")
                if (turn.playerInput.isNotBlank()) appendLine("PLAYER: ${turn.playerInput}")
                appendLine(turn.narration.truncate(2600))
            }
        }

        val choice = settings.simulationOrNarration()
        val summary = runCatching {
            ProviderRegistry.get(choice.provider).chat(
                LlmRequest(
                    model = choice.model,
                    system = "You are the archivist of a persistent fictional world. You write dense, factual " +
                        "chapter summaries that preserve every consequence, promise, relationship change, " +
                        "discovery and movement. You never invent anything that is not in the transcript.",
                    messages = listOf(
                        ChatMessage.user(
                            """
                            Summarise these turns of "${world.name}" as one chapter of permanent record.

                            Requirements:
                            - Open with a short evocative chapter title on its own first line.
                            - Then 200-400 words of dense prose in past tense.
                            - Preserve: what the player did and decided, who they met, what was promised or
                              threatened, what was learned, what changed in the world, where everyone ended up,
                              and any consequence still outstanding.
                            - Omit atmosphere and description. This is a record, not a retelling.

                            TRANSCRIPT:
                            $transcript
                            """.trimIndent()
                        )
                    ),
                    maxTokens = 1200,
                    temperature = 0.3
                ),
                settings.apiKey(choice.provider)
            ).text
        }.getOrNull() ?: return

        val lines = summary.trim().lines()
        val title = lines.firstOrNull()?.trim()?.removePrefix("#")?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it.length < 90 } ?: "Turns $from-$to"
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else summary.trim()

        repo.saveChapter(
            ChapterEntity(
                id = newId(),
                worldId = worldId,
                title = title,
                summary = body,
                fromTurn = from,
                toTurn = to,
                storyTime = turns.last().storyTime
            )
        )
    }

    private companion object {
        const val CHAPTER_SIZE = 10
    }
}
