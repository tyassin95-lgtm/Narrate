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
import com.narrate.app.data.entity.WorldEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot
import kotlinx.serialization.builtins.ListSerializer

data class TurnResult(
    val turn: TurnEntity,
    val parsed: ParsedTurn,
    val issues: List<ContinuityGuard.Issue>,
    /** True when the first reply came back incomplete and a second call completed it. */
    val wasRepaired: Boolean = false
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
    private val usage = UsageRecorder(repo)

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
        val messages = history + ChatMessage.user(context)
        val promptCharacters = system.length + messages.sumOf { it.content.length }

        val provider = ProviderRegistry.get(choice.provider)
        val apiKey = settings.apiKey(choice.provider)
        val response = provider.chat(
            LlmRequest(
                model = choice.model,
                system = system,
                messages = messages,
                maxTokens = tokenBudget(snapshot.world, config),
                temperature = config.temperature.toDouble()
            ),
            apiKey
        )
        usage.recordChat(worldId, turnIndex, UsageRecorder.PURPOSE_NARRATION, response, promptCharacters)

        var parsed = TurnParser.parse(response.text)
        var repaired = false

        // A turn that arrived without choices or without a state block is not finished. Rather
        // than papering over it in the UI, ask the model to complete what it started.
        if (!parsed.isComplete) {
            val completion = complete(
                worldId = worldId,
                turnIndex = turnIndex,
                system = system,
                messages = messages,
                firstReply = response.text,
                parsed = parsed,
                wasCutOff = response.truncated || parsed.looksUnfinished,
                choice = choice,
                apiKey = apiKey,
                config = config
            )
            if (completion != null) {
                parsed = completion
                repaired = true
            }
        }

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

        TurnResult(turn, parsed, applied.report.issues, repaired)
    }

    /**
     * Second call for an incomplete turn. It asks only for the missing pieces - a continuation
     * of prose that stopped mid-sentence, the choices, the state block - and merges them into
     * the turn the player is already reading, so nothing is rewritten underneath them.
     */
    private suspend fun complete(
        worldId: String,
        turnIndex: Int,
        system: String,
        messages: List<ChatMessage>,
        firstReply: String,
        parsed: ParsedTurn,
        wasCutOff: Boolean,
        choice: com.narrate.app.data.prefs.ModelChoice,
        apiKey: String,
        config: com.narrate.app.data.prefs.AppSettings
    ): ParsedTurn? {
        val needsChoices = parsed.choices.isEmpty()
        val needsState = !parsed.stateParsed
        if (!needsChoices && !needsState && !wasCutOff) return null

        val instruction = Prompts.repairInstruction(wasCutOff, needsChoices, needsState)
        val repairMessages = messages +
            ChatMessage.assistant(firstReply.takeLast(MAX_ECHOED_REPLY)) +
            ChatMessage.user(instruction)

        val response = runCatching {
            ProviderRegistry.get(choice.provider).chat(
                LlmRequest(
                    model = choice.model,
                    system = system,
                    messages = repairMessages,
                    maxTokens = REPAIR_TOKENS,
                    temperature = (config.temperature * 0.8).toDouble().coerceIn(0.1, 1.0)
                ),
                apiKey
            )
        }.getOrNull() ?: return null

        usage.recordChat(
            worldId, turnIndex, UsageRecorder.PURPOSE_REPAIR, response,
            system.length + repairMessages.sumOf { it.content.length }
        )

        val completion = TurnParser.parse(response.text)

        // Continue the prose only when it really was cut off, the completion actually contained
        // prose, and that prose is a continuation rather than the model starting over.
        val isContinuation = wasCutOff &&
            completion.hasNarration &&
            !completion.narration.startsWith(parsed.narration.take(80))
        val narration = if (isContinuation) {
            joinContinuation(parsed.narration, completion.narration)
        } else {
            parsed.narration
        }

        return parsed.copy(
            narration = narration,
            choices = completion.choices.ifEmpty { parsed.choices },
            // The first reply's state block wins when it parsed: re-applying a second one
            // would double-count memories and relationship changes.
            delta = if (parsed.stateParsed) parsed.delta else completion.delta,
            stateParsed = parsed.stateParsed || completion.stateParsed,
            looksUnfinished = TurnParser.endsMidSentence(narration),
            hasNarration = narration.isNotBlank(),
            parseNotes = parsed.parseNotes + completion.parseNotes.map { "on completion: $it" }
        )
    }

    /** Joins a continuation onto prose that stopped mid-word or mid-sentence. */
    private fun joinContinuation(original: String, continuation: String): String {
        val head = original.trimEnd()
        val tail = continuation.trimStart()
        val needsSpace = head.isNotEmpty() && tail.isNotEmpty() &&
            !head.last().isWhitespace() && tail.first().isLetterOrDigit() &&
            head.last() !in setOf('-', '\u2014')
        return if (needsSpace) "$head $tail" else head + tail
    }

    /**
     * Long narration plus a full state block plus choices does not fit in a small budget, and
     * running out of room is exactly how a turn loses its choices. The player's setting is a
     * floor, never a ceiling that the format cannot fit inside.
     */
    private fun tokenBudget(world: WorldEntity, config: com.narrate.app.data.prefs.AppSettings): Int {
        val floor = when (world.narrationLength) {
            "SHORT" -> 4000
            "EPIC" -> 9000
            else -> 6500
        }
        return maxOf(config.maxTokens, floor)
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
                appendLine(WorldSimulator.render(ticks, PlayStyle.from(snapshot.world.playStyle)))
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
        appendLine(
            if (kind == "OPENING") Prompts.openingInstruction(snapshot.world)
            else Prompts.playerInputInstruction(input, kind)
        )
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
        val summaryResponse = runCatching {
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
            )
        }.getOrNull() ?: return
        usage.recordChat(worldId, world.turnCount, UsageRecorder.PURPOSE_CHAPTER, summaryResponse, transcript.length)
        val summary = summaryResponse.text

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
        /** Enough for choices and a state block, not enough to pay for a second narration. */
        const val REPAIR_TOKENS = 3500
        /** The tail of the first reply is all the model needs to pick up where it stopped. */
        const val MAX_ECHOED_REPLY = 6000
    }
}
