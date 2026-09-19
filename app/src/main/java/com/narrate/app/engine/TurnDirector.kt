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
import com.narrate.app.engine.ChoiceGuard.Verdict
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

    /**
     * One of the time controls, with the moment it lands on.
     *
     * The option carries the target, so the turn cannot quietly do nothing: the clock is put
     * where the button said it would go whatever the narrator writes.
     */
    suspend fun takeTimeControl(
        worldId: String,
        kind: String,
        option: WorldActions.TimeOption?
    ): Result<TurnResult> = run(worldId, "", kind, option)

    private suspend fun run(
        worldId: String,
        input: String,
        kind: String,
        timeOption: WorldActions.TimeOption? = null
    ): Result<TurnResult> = runCatching {
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
        val playerInput = if (WorldActions.isWorldAction(kind) && input.isBlank()) {
            WorldActions.playerInput(kind, snapshot, timeOption)
        } else {
            input
        }
        val context = buildContext(snapshot, playerInput, kind, turnIndex, timeOption)
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

        // A model that answered the player instead of the world has not written a turn. Ask
        // once more with the fiction restated, and if it still will not, say so honestly
        // rather than filing its refusal in the player's story as though it had happened.
        var declined = false
        if (Refusal.looksLikeRefusal(parsed)) {
            val second = runCatching {
                provider.chat(
                    LlmRequest(
                        model = choice.model,
                        system = system,
                        messages = messages + ChatMessage.assistant(response.text) +
                            ChatMessage.user(Refusal.reframe(input, kind)),
                        maxTokens = tokenBudget(snapshot.world, config),
                        temperature = config.temperature.toDouble()
                    ),
                    apiKey
                )
            }.getOrNull()
            if (second != null) {
                usage.recordChat(worldId, turnIndex, UsageRecorder.PURPOSE_REPAIR, second, 0)
                val retry = TurnParser.parse(second.text)
                if (Refusal.looksLikeRefusal(retry)) declined = true else parsed = retry
            } else {
                declined = true
            }
        }
        if (declined) {
            // The world is left exactly as it was: no turn, no state, no history. The player's
            // input was never the problem and is not recorded as one.
            throw ProviderException(choice.provider, Refusal.NOTICE)
        }

        // Suggestions are held to the same standard as the prose: one that offers something the
        // player is not carrying, or hands back a coat that was never theirs, is a continuity
        // error the player would be invited to commit.
        var verdict = ChoiceGuard.vet(snapshot, parsed.choices, parsed)
        parsed = parsed.copy(choices = verdict.kept)

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
                choiceProblems = verdict.problems(),
                choice = choice,
                apiKey = apiKey,
                config = config
            )
            if (completion != null) {
                val secondLook = ChoiceGuard.vet(snapshot, completion.choices, completion)
                parsed = completion.copy(
                    choices = secondLook.kept.ifEmpty { parsed.choices }
                )
                verdict = Verdict(parsed.choices, verdict.rejected + secondLook.rejected)
                repaired = true
            }
        }

        if (verdict.rejected.isNotEmpty()) {
            repo.saveIssues(
                ContinuityGuard.Report().apply {
                    verdict.rejected.forEach { rejection ->
                        add(
                            ContinuityGuard.SEVERITY_WARNING,
                            "suggested-action",
                            "A suggested action contradicted the world: ${rejection.reason}.",
                            "It was not offered to the player."
                        )
                    }
                }.toEntities(worldId, turnIndex)
            )
        }

        // The opening scene has already happened by the time it is narrated, so a thread that
        // just retells it would leave the world waiting for something it is standing in.
        val delta = if (turnIndex == 0) {
            OpeningScene.withoutOpeningThreads(parsed.delta, snapshot.world.openingNarration)
        } else {
            parsed.delta
        }
        var applied = applier.apply(snapshot, delta, turnIndex, parsed.narration, kind)

        // A time control names the moment it lands on, and the app puts the clock there.
        // Before this the narrator was asked to advance time and then checked afterwards,
        // which produced a "skip" that moved the world forward by one minute.
        if (WorldActions.isWorldAction(kind)) {
            val target = WorldActions.targetMinute(kind, snapshot, timeOption)
            if (applied.world.clockMinute != target) {
                val world = WorldClock.applyTo(applied.world, target)
                repo.saveWorld(world)
                applied = applied.copy(world = world)
            }
        }

        // A turn that ended by asking the player his own name left the character mute. The
        // narrator hears about it on the next turn, the same way it hears about a teleport.
        val mute = PlayerVoice.unanswered(parsed.narration, snapshot)
        if (mute.isNotEmpty()) {
            val who = snapshot.player?.name ?: "The player character"
            repo.saveIssues(
                ContinuityGuard.Report().apply {
                    mute.forEach { question ->
                        add(
                            ContinuityGuard.SEVERITY_WARNING,
                            "player-voice",
                            "$who was asked \"$question\" and said nothing, though the answer is " +
                                "already established about him.",
                            "He answers ordinary questions about himself in the same scene they are " +
                                "asked. Only a question that decides something waits for the player."
                        )
                    }
                }.toEntities(worldId, turnIndex)
            )
        }

        // The clock is the app's now, so it cannot run backwards or stand still. What can
        // still go wrong is the prose disagreeing with it, and that is worth catching: a turn
        // that says "twenty minutes later" while the beat was three minutes long has told the
        // player something the world does not believe.
        val moved = applied.world.clockMinute - snapshot.world.clockMinute
        val claimed = StoryClock.statedElapsed(parsed.narration)
        if (claimed != null && moved >= 0) {
            val slack = maxOf(10, claimed / 2)
            if (kotlin.math.abs(moved - claimed) > slack) {
                repo.saveIssues(
                    ContinuityGuard.Report().apply {
                        add(
                            ContinuityGuard.SEVERITY_WARNING,
                            "clock",
                            "The narration says about $claimed minutes went by; the beat was " +
                                "recorded as $moved.",
                            "The clock moved by what \"scene\".\"minutes\" said. Put the real " +
                                "length of the beat there and the prose will match it."
                        )
                    }.toEntities(worldId, turnIndex)
                )
            }
        }

        // Four in the morning does not have sunlight in it, whatever the sentence wanted.
        val landed = WorldClock.of(applied.world)
        val lightWrong = when {
            landed.minuteOfDay < 4 * 60 ->
                Regex("\\b(sunlight|sunshine|broad daylight|morning light)\\b", RegexOption.IGNORE_CASE)
                    .find(parsed.narration)?.value
            landed.minuteOfDay in 10 * 60 until 15 * 60 ->
                Regex("\\b(pitch dark|moonlight|starlight|middle of the night)\\b", RegexOption.IGNORE_CASE)
                    .find(parsed.narration)?.value
            else -> null
        }
        lightWrong?.let { phrase ->
            repo.saveIssues(
                ContinuityGuard.Report().apply {
                    add(
                        ContinuityGuard.SEVERITY_WARNING,
                        "clock",
                        "The narration describes \"$phrase\" at ${landed.full}.",
                        "Light and dark follow the world clock, which is given to you at the " +
                            "top of every turn."
                    )
                }.toEntities(worldId, turnIndex)
            )
        }

        // A beat that covered no time at all is the old failure in its purest form.
        if (moved == 0L && turnIndex > 0 && !WorldActions.isWorldAction(kind)) {
            repo.saveIssues(
                ContinuityGuard.Report().apply {
                    add(
                        ContinuityGuard.SEVERITY_WARNING,
                        "clock",
                        "The turn recorded no elapsed time at all.",
                        "Every beat takes some time. Put its real length in " +
                            "\"scene\".\"minutes\" - a conversation is ten or twenty."
                    )
                }.toEntities(worldId, turnIndex)
            )
        }

        val turn = TurnEntity(
            id = newId(),
            worldId = worldId,
            index = turnIndex,
            inputType = kind,
            playerInput = playerInput,
            narration = parsed.narration,
            summary = parsed.delta.summary?.trim().orEmpty().ifBlank { parsed.narration.truncate(220) },
            choicesJson = AppJson.encodeToString(ListSerializer(Choice.serializer()), parsed.choices),
            storyTime = WorldClock.of(applied.world).full,
            locationId = applied.world.currentLocationId,
            locationName = applied.currentLocationName
                .ifBlank { snapshot.locationName(applied.world.currentLocationId) },
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
        choiceProblems: List<String>,
        choice: com.narrate.app.data.prefs.ModelChoice,
        apiKey: String,
        config: com.narrate.app.data.prefs.AppSettings
    ): ParsedTurn? {
        val needsChoices = parsed.choices.size < MIN_CHOICES
        val needsState = !parsed.stateParsed
        if (!needsChoices && !needsState && !wasCutOff) return null

        val instruction = Prompts.repairInstruction(wasCutOff, needsChoices, needsState, choiceProblems)
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
        turnIndex: Int,
        timeOption: WorldActions.TimeOption? = null
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

        Schedule.render(snapshot).takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }

        Wardrobe.render(snapshot.characters, snapshot.world.clockMinute).takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }

        if (!WorldActions.isWorldAction(kind)) {
            appendLine()
            append(SceneDirector.render(snapshot))
        }

        PlayerKnowledge.render(snapshot).takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }

        SceneMomentum.render(snapshot).takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }

        StyleWatch.render(snapshot).takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }

        appendLine()
        appendLine(SceneBrief.render(snapshot, input, kind))
        appendLine()
        appendLine("# THIS TURN")
        // The first turn is the world's opening even when the player typed something first -
        // which is what happens when the opening call failed and they tried again by acting.
        // Losing the scene they wrote because of a network error is not acceptable.
        if (kind == "OPENING" || turnIndex == 0) {
            appendLine(Prompts.openingInstruction(snapshot.world))
            if (input.isNotBlank()) {
                appendLine()
                appendLine(Prompts.playerInputInstruction(input, kind))
            }
        } else if (WorldActions.isWorldAction(kind)) {
            appendLine(WorldActions.instruction(kind, snapshot, timeOption))
        } else {
            appendLine(Prompts.playerInputInstruction(input, kind))
        }
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
        val waiting = compactUpTo - lastCompacted
        // A full chapter is the target. But waiting for ten more turns every time meant the
        // journal stopped at "turns 0-9" for another eighteen turns while everything since sat
        // in the log unsummarised, so a shorter chapter is written rather than none at all.
        if (waiting < MIN_CHAPTER_SIZE) return

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
                    system = "You are the archivist of a persistent fictional world. You write the " +
                        "chapter of the story these turns amount to: what happened that will still " +
                        "matter, in the voice of a record rather than a retelling. You never invent " +
                        "anything that is not in the transcript, and you never list actions that " +
                        "changed nothing.",
                    messages = listOf(
                        ChatMessage.user(
                            """
                            Summarise these turns of "${world.name}" as one chapter of permanent record.

                            Requirements:
                            - Open with a short evocative chapter title on its own first line.
                            - Then 150-300 words of prose in past tense, about what actually happened.
                            - Keep: decisions, discoveries, what was promised or arranged and when, who
                              was met and what they turned out to be, how a relationship moved, what
                              changed hands, where everybody ended up, and anything still outstanding.
                            - Cut everything else. Do not write that somebody stood still, said
                              nothing, kept walking, or that nothing was agreed - a chapter that says
                              "no promises were exchanged and no threats were issued" is a chapter
                              that should have been three sentences long.
                            - Never write a list of small physical actions. "He checked the time, sat
                              down and drank his coffee" is not a record of anything.
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
        // Models title a chapter with whatever heading markers they feel like. All of them go:
        // the journal adds its own, and "## ## The Long Nights" is what happens when it does not.
        val title = lines.firstOrNull()
            ?.trim()
            ?.trim('#', '*', '_', ' ')
            ?.removeSurrounding("\"")
            ?.trim()
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
        /** Below this a chapter is too thin to be worth a call; above it the journal keeps up. */
        const val MIN_CHAPTER_SIZE = 3
        /** Fewer than this is not a menu, and is worth a second call to put right. */
        /**
         * The floor for asking again, which is now one.
         *
         * Four suggestions was a UI requirement pretending to be a gameplay rule, and it was
         * met by inventing chores. A moment with one real decision in it gets one option; a
         * moment with none gets none, and the scene carries on.
         */
        const val MIN_CHOICES = 1
        /** Enough for choices and a state block, not enough to pay for a second narration. */
        const val REPAIR_TOKENS = 3500
        /** The tail of the first reply is all the model needs to pick up where it stopped. */
        const val MAX_ECHOED_REPLY = 6000
    }
}
