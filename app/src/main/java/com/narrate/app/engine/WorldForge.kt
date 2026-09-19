package com.narrate.app.engine

import com.narrate.app.ai.ChatMessage
import com.narrate.app.ai.EmptyResponseException
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.LlmResponse
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.core.AppJson
import com.narrate.app.core.nameSimilarity
import com.narrate.app.core.newId
import com.narrate.app.data.entity.*
import com.narrate.app.data.entity.LocationEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class WorldConcept(
    val name: String = "",
    val tagline: String = "",
    val genre: String = "",
    val tone: String = "",
    val premise: String = "",
    val history: String = "",
    val rules: String = "",
    val themes: String = "",
    @SerialName("art_style") val artStyle: String = "",
    @SerialName("opening_situation") val openingSituation: String = ""
)

@Serializable
private data class WorldBuild(
    val world: WorldConcept = WorldConcept(),
    val locations: List<NewLocation> = emptyList(),
    val characters: List<NewCharacter> = emptyList(),
    val factions: List<FactionDelta> = emptyList(),
    val threads: List<ThreadDelta> = emptyList(),
    @SerialName("starting_location") val startingLocation: String = "",
    @SerialName("starting_time") val startingTime: String = "Day 1, morning",
    /** Where the protagonist lives. Not the same place as where the story opens. */
    @SerialName("player_home") val playerHome: String = "",
    /** The shape of the protagonist's week: shifts, classes, standing arrangements. */
    val schedule: List<EventDelta> = emptyList(),
    @SerialName("established_facts") val establishedFacts: List<MemoryDelta> = emptyList()
)

@Serializable
data class CharacterConcept(
    val name: String = "",
    val role: String = "",
    val summary: String = "",
    val personality: String = "",
    val backstory: String = "",
    val appearance: String = "",
    val outfit: String = "",
    val voice: String = "",
    val goals: String = "",
    val fears: String = "",
    val secrets: String = "",
    @SerialName("starting_items") val startingItems: List<String> = emptyList(),
    @SerialName("ties_to_world") val tiesToWorld: String = ""
)

/**
 * World and character creation.
 *
 * The player's own words are always the spine: generated concepts are offered as material,
 * never imposed, and anything the player writes survives verbatim into the saved world.
 */
class WorldForge(
    private val repo: WorldRepository,
    private val settings: SettingsStore
) {

    private val usage = UsageRecorder(repo)

    private suspend fun ask(
        system: String,
        user: String,
        maxTokens: Int = 4000,
        temperature: Double = 1.0,
        worldId: String = ""
    ): String {
        val choice = settings.current.narration
        if (!choice.isSet) {
            throw ProviderException(choice.provider, "No narration model selected. Choose one in Settings.")
        }
        val provider = ProviderRegistry.get(choice.provider)
        val apiKey = settings.apiKey(choice.provider)

        suspend fun attempt(budget: Int): LlmResponse = provider.chat(
            LlmRequest(
                model = choice.model,
                system = system,
                messages = listOf(ChatMessage.user(user)),
                maxTokens = budget,
                temperature = temperature,
                // Creation is a single long call, so it gets more room than a turn does.
                timeoutSeconds = CREATION_TIMEOUT_SECONDS,
                // Structured JSON does not need deep deliberation, and a reasoning model that
                // spends its whole budget thinking returns an empty message and looks hung.
                reasoningEffort = "low"
            ),
            apiKey
        )

        val response = try {
            attempt(maxTokens)
        } catch (empty: EmptyResponseException) {
            // The model wrote nothing, usually because reasoning consumed the budget.
            // One more attempt with real room before giving up on it.
            attempt((maxTokens * 2).coerceAtMost(MAX_CREATION_TOKENS))
        }
        usage.recordChat(worldId, -1, UsageRecorder.PURPOSE_CREATION, response, system.length + user.length)
        return response.text
    }

    /**
     * Asks for one JSON object and insists on getting something usable back.
     *
     * Three things go wrong here in practice: the model wraps its answer in prose, it runs out
     * of room mid-object, or it writes nothing at all. The first two are recoverable from the
     * text already in hand; only the third needs asking again, with real room the second time.
     */
    private suspend fun askObject(
        system: String,
        user: String,
        maxTokens: Int = 4000,
        temperature: Double = 1.0,
        worldId: String = ""
    ): String {
        val raw = ask(system, user, maxTokens, temperature, worldId)
        TurnParser.salvageJsonObject(raw)?.let { return it }
        // Nothing parseable came back at all, which usually means the reply was cut off before
        // the first brace. One retry with a doubled budget, then the caller is told honestly.
        val retry = ask(system, user, (maxTokens * 2).coerceAtMost(MAX_CREATION_TOKENS), temperature, worldId)
        return TurnParser.salvageJsonObject(retry)
            ?: throw IllegalStateException("The model did not return a usable answer.")
    }

    /**
     * Fills in whatever the world generation left blank.
     *
     * A model asked for nine fields sometimes answers with seven, and which two it drops varies
     * from run to run - which is exactly the randomness the player sees. The gaps are named and
     * asked for on their own, and merged in without touching a field that already has a value,
     * so nothing the player wrote and nothing already generated can be overwritten here.
     */
    suspend fun completeWorld(
        concept: WorldConcept,
        authored: String,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): WorldConcept {
        val missing = ConceptCompleteness.missingWorldFields(concept)
        if (missing.isEmpty()) return concept
        val patch = runCatching {
            val json = askObject(
                system = "You complete a partly written world bible. You fill only the gaps you are " +
                    "asked for, you never contradict what is already written, and you reply with JSON only.",
                user = """
                    ${AuthoredCanon.brief("THE PLAYER'S OWN WORLD TEXT", authored)}

                    THE WORLD SO FAR - all of this is settled canon and may not be changed:
                    ${ConceptCompleteness.describeWorld(concept)}

                    These fields were left empty: ${missing.joinToString(", ")}

                    Write only those fields, inferred from the world above and the player's text.
                    Each must be consistent with everything already written and specific to this
                    world - never a generic line that would fit any story. Do not restate another
                    field, and do not write filler simply to fill a space.

                    The experience the player asked for is ${playStyle.label}: ${playStyle.blurb}

                    Reply with ONE JSON object containing exactly these keys: ${missing.joinToString(", ")}
                """.trimIndent(),
                maxTokens = 2000,
                temperature = 0.5
            )
            AppJson.decodeFromString(WorldConcept.serializer(), json)
        }.getOrElse { failure ->
            // Being cancelled is the player leaving, not a failure to absorb.
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // A failed top-up is not a failed world: what was generated still stands.
            return concept
        }
        return ConceptCompleteness.mergeWorld(concept, AuthoredCanon.enforceWorld(authored, patch).first)
    }

    /** The same top-up for a character. */
    suspend fun completeCharacter(
        concept: CharacterConcept,
        world: WorldEntity,
        authored: String
    ): CharacterConcept {
        val missing = ConceptCompleteness.missingCharacterFields(concept)
        if (missing.isEmpty()) return concept
        val patch = runCatching {
            val json = askObject(
                system = "You complete a partly written character sheet. You fill only the gaps you " +
                    "are asked for, you never contradict what is already written, and you reply with JSON only.",
                user = """
                    ${AuthoredCanon.brief("THE PLAYER'S OWN CHARACTER TEXT", authored)}

                    THE CHARACTER SO FAR - all of this is settled canon and may not be changed:
                    ${ConceptCompleteness.describeCharacter(concept)}

                    THE WORLD THEY LIVE IN
                    ${world.name}. ${world.genre}. ${world.tone}
                    ${world.premise}
                    ${world.rules.takeIf { it.isNotBlank() }?.let { "Rules: " + it }.orEmpty()}

                    These fields were left empty: ${missing.joinToString(", ")}

                    Write only those fields, inferred from the character above, their world, and
                    the player's text. Keep them modest and consistent: do not invent a dramatic
                    past, a secret or a fear that contradicts anything already written, and do not
                    write filler simply to fill a space.
                    ${if ("appearance" in missing) "\"appearance\" becomes this character's permanent visual reference, so describe face, build, hair, colouring and age concretely." else ""}

                    Reply with ONE JSON object containing exactly these keys: ${missing.joinToString(", ")}
                """.trimIndent(),
                maxTokens = 2000,
                temperature = 0.5
            )
            AppJson.decodeFromString(CharacterConcept.serializer(), json)
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return concept
        }
        return ConceptCompleteness.mergeCharacter(concept, AuthoredCanon.enforceCharacter(authored, patch).first)
    }

    /** Several distinct starting points for a world, shaped by whatever the player typed. */
    suspend fun worldConcepts(
        direction: String,
        count: Int = 3,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): Result<List<WorldConcept>> = runCatching {
        val brief = direction.ifBlank { "Surprise the player. Pick something with a strong, specific point of view." }
        val raw = ask(
            system = "You invent settings for a persistent interactive world. You are specific and concrete. " +
                "You avoid generic fantasy filler, and you never describe a world in vague marketing language. " +
                "You reply with JSON only, no commentary and no code fences.",
            user = """
                Propose $count genuinely different worlds based on this direction from the player:

                "$brief"

                Each must be distinct in setting, tone and premise - not three flavours of one idea.
                Honour the player's direction exactly; it outranks your own taste.

                The player wants this kind of experience: ${playStyle.label} - ${playStyle.blurb}
                ${playStyle.buildGuidance}
                The premise and opening situation must suit that, so a sandbox world opens on an
                ordinary day rather than on a crisis.

                Reply with a JSON array of exactly $count objects:
                [{
                  "name": "the world's title",
                  "tagline": "one line that sells it",
                  "genre": "",
                  "tone": "",
                  "premise": "3-5 sentences on the situation the player is dropped into",
                  "history": "2-4 sentences of established past that shapes the present",
                  "rules": "the hard rules of this world: physics, magic, technology, taboos",
                  "themes": "what the world is about underneath",
                  "art_style": "a visual direction for generated imagery",
                  "opening_situation": "where and how the player's first scene begins"
                }]
            """.trimIndent(),
            temperature = 1.05
        )
        parseList(raw, WorldConcept.serializer())
    }

    /**
     * Turns what the player wrote about their character into structured fields without
     * changing any of it.
     *
     * This is the path taken whenever the player has written something: their text is the
     * character, not a brief for inventing one. The name and every stated detail are then
     * re-checked in code, because a prompt is a request and this is a guarantee.
     */
    suspend fun expandCharacter(world: WorldEntity, authored: String): Result<CharacterConcept> =
        runCatching {
            require(authored.isNotBlank()) { "Nothing was written to expand." }
            val raw = ask(
                system = "You organise a player's own words about their character into structured " +
                    "fields. You are a scribe, not an author: you never rename, replace, contradict or " +
                    "quietly improve what you are given. You reply with JSON only.",
                user = """
                    ${AuthoredCanon.brief("THE PLAYER'S CHARACTER", authored)}

                    THE WORLD THEY WILL INHABIT
                    Name: ${world.name}
                    Genre: ${world.genre}. Tone: ${world.tone}.
                    Premise: ${world.premise}
                    ${world.history.takeIf { it.isNotBlank() }?.let { "History: $it" }.orEmpty()}
                    ${world.rules.takeIf { it.isNotBlank() }?.let { "Rules: $it" }.orEmpty()}

                    Put their character into the fields below.

                    - Copy every detail they gave into the field it belongs in, in their own words
                      wherever the wording still reads naturally there.
                    - "name" is exactly the name they used. If they gave a full name, use the full name.
                    - Fill a field they left empty with something that fits everything they did say,
                      and keep it modest. Do not invent a dramatic past they did not ask for.
                    - "appearance" must contain every physical detail they gave, word for word where
                      possible, because it becomes the permanent visual reference for this character.
                      You may add neutral specifics they omitted, such as build or age, but you may not
                      alter one they gave.
                    - "ties_to_world" connects them to this world without contradicting their text.

                    Reply with ONE JSON object:
                    {
                      "name": "", "role": "", "summary": "2-3 sentences",
                      "personality": "", "backstory": "", "appearance": "", "outfit": "",
                      "voice": "", "goals": "", "fears": "", "secrets": "",
                      "starting_items": [""], "ties_to_world": ""
                    }
                """.trimIndent(),
                temperature = 0.4
            )
            val json = TurnParser.salvageJsonObject(raw)
                ?: throw IllegalStateException("The model did not return a usable character.")
            val concept = AppJson.decodeFromString(CharacterConcept.serializer(), json)
            completeCharacter(AuthoredCanon.enforceCharacter(authored, concept).first, world, authored)
        }

    /**
     * The same for a world: the player's text becomes the world, organised rather than replaced.
     */
    suspend fun expandWorld(
        authored: String,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): Result<WorldConcept> = runCatching {
        require(authored.isNotBlank()) { "Nothing was written to expand." }
        val raw = ask(
            system = "You organise a player's own words about their world into structured fields. " +
                "You are a scribe, not an author: you never rename, replace, contradict or quietly " +
                "improve what you are given. You reply with JSON only.",
            user = """
                ${AuthoredCanon.brief("THE PLAYER'S WORLD", authored)}

                Put their world into the fields below.

                - Every name they used - of the world, its places, its people, its factions, its
                  terminology - appears exactly as they wrote it.
                - Copy their facts into the fields where they belong. Where they wrote about
                  history, that is "history"; where they wrote a rule, that is "rules".
                - Fill only what they left empty, and keep those additions compatible with
                  everything they did say.
                - "name" is the name they gave the world. Only invent a title if they gave none.
                - If they described where or how the story starts - an arrival, a meeting, a place,
                  a moment - that is "opening_situation", copied faithfully with its details intact.
                  It is the first scene, not a future event, and must not be rewritten into one.

                The experience they asked for is ${playStyle.label}: ${playStyle.blurb}
                ${playStyle.buildGuidance}

                Reply with ONE JSON object:
                {
                  "name": "", "tagline": "", "genre": "", "tone": "", "premise": "",
                  "history": "", "rules": "", "themes": "", "art_style": "",
                  "opening_situation": ""
                }
            """.trimIndent(),
            temperature = 0.4
        )
        val json = TurnParser.salvageJsonObject(raw)
            ?: throw IllegalStateException("The model did not return a usable world.")
        val concept = AppJson.decodeFromString(WorldConcept.serializer(), json)
        completeWorld(AuthoredCanon.enforceWorld(authored, concept).first, authored, playStyle)
    }

    /** Character concepts that already have hooks into this specific world. */
    suspend fun characterConcepts(
        world: WorldEntity,
        direction: String,
        count: Int = 3
    ): Result<List<CharacterConcept>> = runCatching {
        val brief = direction.ifBlank { "Surprise the player, but make them belong to this world." }
        val raw = ask(
            system = "You create protagonists for interactive worlds. They are specific people with " +
                "contradictions, histories and obligations, not archetypes. You reply with JSON only.",
            user = """
                THE WORLD
                Name: ${world.name}
                Genre: ${world.genre}. Tone: ${world.tone}.
                Premise: ${world.premise}
                History: ${world.history}
                Rules: ${world.rules}
                ${AuthoredCanon.brief("THE PLAYER'S OWN WORLD TEXT", world.authoredCanon)}

                THE PLAYER'S DIRECTION FOR THEIR CHARACTER
                "$brief"

                The player has asked to see alternatives, so these may be different people from
                anyone described above - but nothing here may contradict the world text they wrote.

                Propose $count protagonists who could only exist in this world. Each needs a real
                connection to it: a debt, a job, a family, a crime, a duty, a wound.

                The appearance field is important: it becomes the permanent visual reference used for
                every image of this character, so describe face, build, hair, colouring, age and
                distinguishing features concretely and without metaphor.

                Reply with a JSON array of exactly $count objects:
                [{
                  "name": "", "role": "their place in the world", "summary": "2-3 sentences",
                  "personality": "", "backstory": "3-5 sentences",
                  "appearance": "concrete physical description for image generation",
                  "outfit": "what they are wearing when the story opens",
                  "voice": "how they speak", "goals": "", "fears": "", "secrets": "",
                  "starting_items": ["what they carry"],
                  "ties_to_world": "how they are bound to this specific place and its people"
                }]
            """.trimIndent(),
            temperature = 1.05
        )
        parseList(raw, CharacterConcept.serializer())
    }

    /**
     * Builds the world the player will actually inhabit: geography, a cast with positions and
     * routines, factions, and the threads already in motion before turn one.
     */
    suspend fun buildWorld(
        concept: WorldConcept,
        customPrompt: String,
        character: CharacterConcept,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): Result<WorldBuildOutcome> =
        runCatching {
            val raw = ask(
                system = "You are the architect of a persistent simulated world. Everything you produce becomes " +
                    "permanent canon that a game master will be held to for hundreds of turns, so it must be " +
                    "concrete, internally consistent and specific. You reply with JSON only.",
                user = """
                    Build the starting state of this world.

                    ${AuthoredCanon.brief("THE PLAYER'S OWN WORLD TEXT", customPrompt)}

                    WORLD
                    Name: ${concept.name}
                    Tagline: ${concept.tagline}
                    Genre: ${concept.genre}. Tone: ${concept.tone}.
                    Premise: ${concept.premise}
                    History: ${concept.history}
                    Rules: ${concept.rules}
                    Themes: ${concept.themes}
                    THE FIRST SCENE - this is where the story opens, and it is happening now:
                    ${concept.openingSituation}

                    Everything that scene names must exist in what you produce: the place it happens
                    in, the people in it, anything it refers to. "starting_location" must be the place
                    it happens. Do not turn it into a thread - a thread is something still to come, and
                    this has already begun. Do not invent a different starting point.

                    At most two characters are in that opening scene with the player, and only if
                    the scene itself puts them there. A world does not begin with eight people
                    standing on the same pavement. Everyone else is somewhere in their own life -
                    at work, asleep, across town - and each one's "location" must be the exact name
                    of a place you defined in "locations".

                    THE PROTAGONIST - this person already exists and is the player. Never rename them,
                    never create another character with their name, and never cast them as an NPC.
                    ${character.name}, ${character.role}. ${character.summary}
                    Backstory: ${character.backstory}
                    Ties to the world: ${character.tiesToWorld}
                    Goals: ${character.goals}

                    Produce:
                    - 8-14 locations forming a real geography: a containing region or city, the districts or
                      areas within it, and the specific buildings and rooms that matter. Every location must
                      connect to at least one other. Include where the protagonist lives or sleeps.
                    - 6-10 named characters who already have lives here. Each needs a current location, a
                      home, a daily routine, goals of their own, and a concrete appearance for image
                      generation. At least two should already know the protagonist.
                    - 2-4 factions or groups with real interests in conflict.
                    - 3-5 threads already in motion before the player's first turn, each with a next beat
                      that will happen whether or not the player engages.
                      ${playStyle.buildGuidance}
                    - Somewhere the protagonist lives, named in "player_home", and on a real street
                      with a number if this setting has streets. They have lived there for a while.
                    - The shape of the protagonist's week in "schedule": the shifts, classes or
                      standing commitments their life already has, with days and times. This is a
                      calendar, so "Fridays off" is expressed as the days they DO work.
                    - Streets. A town is made of them: name the roads that its buildings stand on
                      and give the buildings numbers on those roads, so the map is a map.
                    - 5-10 established facts that must never be contradicted.

                    Reply with one JSON object:
                    {
                      "starting_location": "exact name of a location you defined, where the first scene opens",
                      the opening must match the requested pacing above,
                      "starting_time": "Day 1, morning",
                      "player_home": "the exact name of the location the protagonist lives in - a real
                        place in your list, with a street address if the setting has streets. They
                        live somewhere from the first turn, even if the story opens elsewhere",
                      "schedule": [{"title":"ER shift","kind":"SHIFT|CLASS|APPOINTMENT|DEADLINE|MEETING",
                        "when":"Monday 8 AM","duration_minutes":720,"location":"","with":"",
                        "recurrence":"WEEKLY:MON,TUE,WED,THU","for":"player"}],
                      "locations": [{"name":"","type":"REGION|SETTLEMENT|DISTRICT|BUILDING|ROOM|LANDMARK|WILDERNESS",
                        "parent":"containing location name or empty","description":"","atmosphere":"",
                        "notable_features":"","connects_to":["other location names"],"travel_time":"","controlled_by":""}],
                      "characters": [{"name":"","role":"","summary":"","personality":"","appearance":"",
                        "outfit":"","voice":"","goals":"","secrets":"","faction":"","relationship_to_player":"",
                        "location":"where they are right now","home_location":"","routine":"where they are at each
                        part of the day","importance":3}],
                      "factions": [{"name":"","description":"","goals":"","leader":"","territory":"","standing_delta":0}],
                      "threads": [{"title":"","description":"","status":"ACTIVE","urgency":3,"involved":[""],
                        "next_beat":"what happens next on its own","deadline":""}],
                      "established_facts": [{"text":"","kind":"FACT","importance":4,"subjects":[""]}]
                    }
                """.trimIndent(),
                maxTokens = 12_000,
                temperature = 0.95
            )
            val json = TurnParser.salvageJsonObject(raw)
                ?: throw IllegalStateException("The world builder returned no readable JSON.")
            val build = AppJson.decodeFromString(WorldBuild.serializer(), json)
            WorldBuildOutcome(
                concept, build.locations, build.characters, build.factions, build.threads,
                build.startingLocation, build.startingTime, build.establishedFacts,
                build.playerHome, build.schedule
            )
        }

    data class WorldBuildOutcome(
        val concept: WorldConcept,
        val locations: List<NewLocation>,
        val characters: List<NewCharacter>,
        val factions: List<FactionDelta>,
        val threads: List<ThreadDelta>,
        val startingLocation: String,
        val startingTime: String,
        val facts: List<MemoryDelta>,
        /** Where the protagonist lives, which the world must know from turn one. */
        val playerHome: String = "",
        /** Their week, as calendar records rather than as a sentence in a biography. */
        val schedule: List<EventDelta> = emptyList()
    )

    /**
     * Commits a built world to disk as a save the player can leave and come back to.
     *
     * Once the first row is written the rest must follow. A world half-written - saved, but
     * with no protagonist and no map - would sit on the shelf looking playable and open onto
     * nothing, so leaving the screen mid-write finishes the write rather than abandoning it.
     */
    suspend fun persist(
        concept: WorldConcept,
        customPrompt: String,
        narrationLength: String,
        contentGuidelines: String,
        character: CharacterConcept,
        characterPrompt: String,
        build: WorldBuildOutcome?,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): WorldEntity = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        writeWorld(
            concept, customPrompt, narrationLength, contentGuidelines,
            character, characterPrompt, build, playStyle
        )
    }

    private suspend fun writeWorld(
        concept: WorldConcept,
        customPrompt: String,
        narrationLength: String,
        contentGuidelines: String,
        character: CharacterConcept,
        characterPrompt: String,
        build: WorldBuildOutcome?,
        playStyle: PlayStyle = PlayStyle.BALANCED
    ): WorldEntity {
        val worldId = newId()
        // Last line of defence: whatever the model returned, the player's own names win.
        val enforcedWorld = AuthoredCanon.enforceWorld(customPrompt, concept).first
        val enforcedCharacter = AuthoredCanon.enforceCharacter(characterPrompt, character).first
        var world = WorldEntity(
            id = worldId,
            name = enforcedWorld.name.ifBlank { AuthoredCanon.worldName(customPrompt) ?: "Untitled World" },
            tagline = enforcedWorld.tagline,
            genre = enforcedWorld.genre,
            tone = enforcedWorld.tone,
            premise = enforcedWorld.premise,
            history = enforcedWorld.history,
            rules = enforcedWorld.rules,
            themes = enforcedWorld.themes,
            customPrompt = listOf(customPrompt, characterPrompt).filter { it.isNotBlank() }.joinToString("\n\n"),
            authoredCanon = customPrompt,
            narrationLength = narrationLength,
            playStyle = playStyle.id,
            contentGuidelines = contentGuidelines,
            artStyle = enforcedWorld.artStyle.ifBlank { "Cinematic, film still, natural lighting, high detail" },
            openingNarration = enforcedWorld.openingSituation
        )
        // The world starts on a real date at a real hour, so that "Friday" is somewhere the
        // calendar can point to rather than a word in a sentence.
        val openingText = listOf(enforcedWorld.openingSituation, build?.startingTime.orEmpty())
            .joinToString(" ")
        world = WorldClock.applyTo(
            world.copy(calendarEpoch = WorldClock.startingEpoch(openingText).toString()),
            startingMinuteOf(build?.startingTime, enforcedWorld.openingSituation)
        )
        repo.saveWorld(world)

        val locationIds = mutableMapOf<String, String>()
        val locations = mutableListOf<LocationEntity>()
        build?.locations
            ?.filter { it.name.isNotBlank() }
            // The builder sometimes declares the same address twice under two spellings. Two
            // rows for one place means half the map points at one of them and half at the other.
            ?.distinctBy { it.name.trim().lowercase() }
            ?.forEachIndexed { index, incoming ->
            val entity = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                type = PlaceIdentity.typeFromName(incoming.name, incoming.type),
                description = incoming.description,
                atmosphere = incoming.atmosphere,
                notableFeatures = incoming.notableFeatures,
                controlledBy = incoming.controlledBy,
                // The world is built whole; the player's map is not. Nothing is on it until
                // they have stood in it, been told about it, or found it - see the knowledge
                // seeding at the end of this method.
                discovered = false,
                mapX = (index % 4) * 0.24f + 0.14f,
                mapY = (index / 4) * 0.2f + 0.12f
            )
            locations += entity
            locationIds[incoming.name.trim().lowercase()] = entity.id
        }
        // Parents resolve in a second pass so order in the model's output does not matter.
        val parented = locations.map { location ->
            val incoming = build?.locations?.firstOrNull { it.name.trim().equals(location.name, true) }
            val parentId = incoming?.parent?.trim()?.lowercase()?.let { locationIds[it] }
            location.copy(parentId = parentId?.takeIf { it != location.id })
        }
        // And once the hierarchy is known, a room sits beside the building it is in rather than
        // wherever the grid happened to put it. The map is the player's picture of the town.
        // Shallowest first, so a parent is already where it belongs before its children go
        // looking for it.
        fun depth(location: LocationEntity): Int {
            var current = location
            var steps = 0
            while (steps < 8) {
                current = parented.firstOrNull { it.id == current.parentId } ?: break
                steps++
            }
            return steps
        }
        val settled = mutableMapOf<String, LocationEntity>()
        parented.sortedBy { depth(it) }.forEach { location ->
            val parent = location.parentId?.let { settled[it] }
            settled[location.id] = if (parent == null) location else {
                val (x, y) = MapPlacement.place(listOf(parent), settled.values.toList(), settled.size)
                location.copy(mapX = x, mapY = y)
            }
        }
        val withParents = parented.map { settled[it.id] ?: it }
        if (withParents.isNotEmpty()) repo.saveLocations(withParents)

        val links = mutableListOf<LocationLinkEntity>()
        build?.locations?.forEach { incoming ->
            val fromId = locationIds[incoming.name.trim().lowercase()] ?: return@forEach
            incoming.connectsTo.forEach { target ->
                val toId = locationIds[target.trim().lowercase()] ?: return@forEach
                if (fromId == toId) return@forEach
                val exists = links.any {
                    (it.fromId == fromId && it.toId == toId) || (it.fromId == toId && it.toId == fromId)
                }
                if (!exists) {
                    links += LocationLinkEntity(
                        id = newId(), worldId = worldId, fromId = fromId, toId = toId,
                        travelTime = incoming.travelTime, mode = "on foot"
                    )
                }
            }
        }
        if (links.isNotEmpty()) repo.saveLinks(links)

        val startId = startingLocation(
            opening = enforcedWorld.openingSituation,
            declared = locationIds[build?.startingLocation?.trim()?.lowercase()],
            locations = withParents
        )
            ?: withParents.firstOrNull { it.type == "ROOM" || it.type == "BUILDING" }?.id
            ?: withParents.firstOrNull()?.id

        val playerName = enforcedCharacter.name
            .ifBlank { AuthoredCanon.characterName(characterPrompt).orEmpty() }
            .ifBlank { "The Traveller" }
        // Somewhere to live, from turn one.
        //
        // The last playthrough had the protagonist's flat come into existence on turn 24,
        // because he had been given the pavement he was standing on as his home address. A
        // person has somewhere they live before the story starts, the map should show it, and
        // "go home" needs somewhere to go.
        val homeId = ensurePlayerHome(
            worldId = worldId,
            playerName = playerName,
            declared = build?.playerHome,
            startId = startId,
            locations = withParents.toMutableList().also { list ->
                // Anything added here is saved by ensurePlayerHome itself.
            }
        )

        val playerCharacter = CharacterEntity(
            id = newId(),
            worldId = worldId,
            name = playerName,
            isPlayer = true,
            role = enforcedCharacter.role,
            summary = enforcedCharacter.summary,
            personality = enforcedCharacter.personality,
            backstory = enforcedCharacter.backstory,
            appearance = enforcedCharacter.appearance,
            outfit = enforcedCharacter.outfit,
            voice = enforcedCharacter.voice,
            goals = enforcedCharacter.goals,
            fears = enforcedCharacter.fears,
            secrets = enforcedCharacter.secrets,
            authoredCanon = characterPrompt,
            currentLocationId = startId,
            homeLocationId = homeId ?: startId,
            importance = 5
        )
        repo.saveCharacter(playerCharacter)
        if (playerCharacter.appearance.isNotBlank()) {
            repo.saveVisualIdentity(
                VisualIdentityEntity(
                    id = newId(), worldId = worldId, subjectId = playerCharacter.id,
                    subjectType = "CHARACTER", subjectName = playerCharacter.name,
                    canonicalDescription = playerCharacter.appearance, currentVariant = playerCharacter.outfit
                )
            )
        }
        enforcedCharacter.startingItems.filter { it.isNotBlank() }.forEach { name ->
            repo.saveItems(
                listOf(
                    ItemEntity(
                        id = newId(), worldId = worldId, name = name.trim(),
                        ownerId = playerCharacter.id, holderId = playerCharacter.id,
                        significance = "Carried from the beginning."
                    )
                )
            )
        }

        // Where the builder puts people, resolved the same way the game resolves places later.
        // An exact string lookup misses "the Gallery" against "The Gallery" and misses anywhere
        // it named without declaring - and every miss used to land on the player's own doorstep.
        val mapped = withParents.toMutableList()
        val invented = mutableListOf<LocationEntity>()
        fun placeNamed(name: String?): String? {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            PlaceIdentity.match(trimmed, mapped) { it.name }?.let { return it.id }
            // It named somewhere real to the world it just built. It exists; it was simply
            // never written down. Writing it down beats moving the person to the player.
            // Everything belongs somewhere: an NPC's home with no parent is a node floating off
            // the edge of the map the moment the player finds out it exists.
            val within = mapped.filter { candidate ->
                candidate.name.length >= 4 && trimmed.lowercase().contains(candidate.name.lowercase())
            }.maxByOrNull { it.name.length }
                ?: mapped.firstOrNull { it.type == "DISTRICT" }
                ?: mapped.firstOrNull { it.type == "SETTLEMENT" || it.type == "REGION" }

            val (x, y) = MapPlacement.place(listOfNotNull(within), mapped, mapped.size)
            val created = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = trimmed,
                type = PlaceIdentity.typeFromName(trimmed, "BUILDING"),
                parentId = within?.id,
                description = "Named when the world was built.",
                discovered = false,
                mapX = x,
                mapY = y
            )
            mapped += created
            invented += created
            return created.id
        }

        val npcs = build?.characters
            ?.filter { it.name.isNotBlank() }
            // The world builder sometimes casts the player as one of the locals. They are not.
            ?.filter { nameSimilarity(it.name, playerCharacter.name) < 0.85 }
            ?.map { incoming ->
            val entity = CharacterEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                role = incoming.role,
                summary = incoming.summary,
                personality = incoming.personality,
                appearance = incoming.appearance,
                outfit = incoming.outfit,
                voice = incoming.voice,
                goals = incoming.goals,
                secrets = incoming.secrets,
                faction = incoming.faction,
                relationshipToPlayer = incoming.relationshipToPlayer,
                currentLocationId = placeNamed(incoming.location)
                    ?: placeNamed(incoming.homeLocation)
                    ?: startId,
                homeLocationId = placeNamed(incoming.homeLocation),
                routine = incoming.routine,
                importance = incoming.importance.coerceIn(1, 5)
            )
            entity
        }.orEmpty()
        if (invented.isNotEmpty()) repo.saveLocations(invented)

        // A world does not open with eight people standing on the pavement with you. Whoever
        // the opening scene actually names belongs in it; everyone else is somewhere in their
        // own life, which is what the rest of the map is for.
        val opening = enforcedWorld.openingSituation.lowercase()
        val elsewhere = mapped.filter { it.id != startId }
        val cast = npcs.filter { it.currentLocationId == startId }
        val crowd = if (cast.size <= OPENING_COMPANY) emptyList() else {
            val named = cast.filter { npc ->
                npc.name.split(' ').first().lowercase().let { first ->
                    first.length >= 3 && Regex("\\b${Regex.escape(first)}\\b").containsMatchIn(opening)
                }
            }
            val keep = (named + cast.sortedByDescending { it.importance }).distinct().take(OPENING_COMPANY)
            cast - keep.toSet()
        }
        val placedNpcs = npcs.map { npc ->
            if (npc !in crowd) npc else {
                val away = npc.homeLocationId?.takeIf { it != startId }
                    ?: elsewhere.firstOrNull { it.id == npc.homeLocationId }?.id
                    ?: elsewhere.getOrNull(npc.name.length % elsewhere.size.coerceAtLeast(1))?.id
                    ?: npc.currentLocationId
                npc.copy(currentLocationId = away, homeLocationId = npc.homeLocationId ?: away)
            }
        }
        if (placedNpcs.isNotEmpty()) {
            repo.saveCharacters(placedNpcs)
            placedNpcs.filter { it.appearance.isNotBlank() }.forEach { npc ->
                repo.saveVisualIdentity(
                    VisualIdentityEntity(
                        id = newId(), worldId = worldId, subjectId = npc.id, subjectType = "CHARACTER",
                        subjectName = npc.name, canonicalDescription = npc.appearance, currentVariant = npc.outfit
                    )
                )
            }
        }

        build?.factions?.filter { it.name.isNotBlank() }?.map { incoming ->
            FactionEntity(
                id = newId(), worldId = worldId, name = incoming.name.trim(),
                description = incoming.description, goals = incoming.goals,
                leaderId = npcs.firstOrNull { it.name.equals(incoming.leader, true) }?.id,
                territory = incoming.territory, standingWithPlayer = incoming.standingDelta.coerceIn(-100, 100)
            )
        }?.takeIf { it.isNotEmpty() }?.let { repo.saveFactions(it) }

        build?.threads
            ?.filter { it.title.isNotBlank() }
            // The opening is not a thread. A thread is something still to come, and the opening
            // has already happened - filing it as one is how a stated first scene got replaced
            // by a different beginning and stored as a loose plot to reach later.
            ?.filterNot { OpeningScene.restatesOpening(it, enforcedWorld.openingSituation) }
            ?.map { incoming ->
            ThreadEntity(
                id = newId(), worldId = worldId, title = incoming.title.trim(),
                description = incoming.description, status = incoming.status.uppercase().ifBlank { "ACTIVE" },
                urgency = incoming.urgency.coerceIn(1, 5), involvedNames = incoming.involved.joinToString(", "),
                nextBeat = incoming.nextBeat, deadline = incoming.deadline
            )
        }?.takeIf { it.isNotEmpty() }?.let { repo.saveThreads(it) }

        val facts = mutableListOf<MemoryEntity>()
        build?.facts?.filter { it.text.isNotBlank() }?.forEach { incoming ->
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = incoming.kind.uppercase().ifBlank { "FACT" },
                text = incoming.text, importance = incoming.importance.coerceIn(1, 5),
                subjectNames = incoming.subjects.joinToString(", "),
                keywords = MemoryIndex.keywords(incoming.text).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0,
                // Background the builder invented is remembered, never pinned. Pinning is for
                // what the player wrote and what the story actually established: "Eastgate
                // formed in the 1980s from university expansion" is colour, and it was
                // outranking the world's real history because a model rated it a five.
                provenance = "SPECULATIVE",
                pinned = false
            )
        }
        // The player's own words are canon of the highest order, and are always pinned.
        if (customPrompt.isNotBlank()) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "CANON",
                text = "The player wrote this world themselves, and it is fact: $customPrompt",
                importance = 5, keywords = MemoryIndex.keywords(customPrompt).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0,
                provenance = "PLAYER_CANON", pinned = true
            )
        }
        if (enforcedWorld.openingSituation.isNotBlank()) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "CANON",
                text = "This world opened on: ${enforcedWorld.openingSituation}",
                importance = 5,
                keywords = MemoryIndex.keywords(enforcedWorld.openingSituation).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0,
                provenance = "PLAYER_CANON", pinned = true
            )
        }
        if (playStyle.quietWorld) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "RULE",
                text = "The player chose a ${playStyle.label.lowercase()} world: " +
                    "events emerge from their actions and the ordinary life of the place, " +
                    "and the world does not manufacture drama to hold their attention.",
                importance = 5, keywords = "pacing tone sandbox quiet",
                storyTime = world.storyTime, turnIndex = 0,
                provenance = "WORLD_CANON", pinned = true
            )
        }
        if (characterPrompt.isNotBlank()) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "CANON",
                text = "The player wrote their own character, and it is fact: $characterPrompt",
                importance = 5, keywords = MemoryIndex.keywords(characterPrompt).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0,
                provenance = "PLAYER_CANON", pinned = true
            )
        }
        if (facts.isNotEmpty()) repo.saveMemories(facts)

        // What the player's character knows on turn one.
        //
        // This is the whole difference between a world you are inhabiting and a world you have
        // been handed the notes for. They know the place they are standing in and whatever
        // contains it, the people the opening scene actually puts in front of them, their own
        // home, and what is in their pockets. The rest of the city, and everybody in it, they
        // will have to find out about.
        // The protagonist's week goes in the calendar, so time can be skipped to it and the
        // world knows which evenings they are free.
        val schedule = build?.schedule.orEmpty().filter { it.title.isNotBlank() }.mapNotNull { incoming ->
            Schedule.fromDelta(
                worldId = worldId,
                title = incoming.title,
                description = incoming.description,
                kind = incoming.kind,
                whenText = incoming.whenText,
                durationMinutes = incoming.durationMinutes,
                locationName = incoming.location,
                withNames = incoming.withNames,
                recurrence = incoming.recurrence,
                forPlayer = incoming.forWhom.isBlank() || incoming.forWhom.equals("player", true),
                turnIndex = 0,
                nowMinute = world.clockMinute,
                stamp = WorldClock.of(world)
            )?.copy(
                locationId = PlaceIdentity.match(incoming.location, mapped) { it.name }?.id,
                status = "CONFIRMED"
            )
        }
        if (schedule.isNotEmpty()) repo.saveEvents(schedule)

        seedKnowledge(
            worldId = worldId,
            world = world,
            player = playerCharacter,
            startId = startId,
            locations = mapped,
            npcs = placedNpcs,
            openingCast = placedNpcs.filter { it.currentLocationId == startId }
        )

        val finished = world.copy(playerCharacterId = playerCharacter.id, currentLocationId = startId)
        repo.saveWorld(finished)
        return finished
    }

    /**
     * Where the protagonist lives, found or made.
     *
     * The builder is asked for it, and usually gives one. When it does not - or names
     * somewhere that does not exist - a home is created rather than deferred, because
     * "somewhere he lives" is not a detail to be improvised on turn twenty-four: it is where
     * Go Home goes, where he sleeps, and the first pin on his own map.
     */
    private suspend fun ensurePlayerHome(
        worldId: String,
        playerName: String,
        declared: String?,
        startId: String?,
        locations: MutableList<LocationEntity>
    ): String? {
        PlaceIdentity.match(declared, locations) { it.name }?.let { return it.id }

        // Anything already named after them counts: "Adrian's Apartment" is his apartment.
        val firstName = playerName.split(' ').firstOrNull()?.takeIf { it.length >= 3 }
        if (firstName != null) {
            locations.firstOrNull { it.name.contains("$firstName's", true) }?.let { return it.id }
        }

        val start = locations.firstOrNull { it.id == startId }
        val district = generateSequence(start) { child ->
            child.parentId?.let { id -> locations.firstOrNull { it.id == id } }
        }.firstOrNull { it.type == "DISTRICT" }
            ?: locations.firstOrNull { it.type == "DISTRICT" }
            ?: locations.firstOrNull { it.type == "SETTLEMENT" }

        val name = declared?.trim().takeUnless { it.isNullOrBlank() }
            ?: "${firstName ?: playerName}'s Apartment"
        val (x, y) = Geography.looseNear(district?.name, name, locations)
        val home = LocationEntity(
            id = newId(),
            worldId = worldId,
            name = name,
            type = "BUILDING",
            parentId = district?.id,
            description = "Where ${playerName} lives.",
            discovered = true,
            visited = true,
            mapX = x,
            mapY = y,
            addressNumber = Geography.addressNumberIn(name) ?: 0
        )
        locations += home
        repo.saveLocation(home)
        return home.id
    }

    /**
     * The player's starting knowledge, and the map that follows from it.
     *
     * Deliberately small. A place is on the map because they are standing in it or it contains
     * the place they are standing in; a person is in the cast because they are in the opening
     * scene. Their own home is theirs to know. Everything else in the generated world exists
     * so that people have somewhere to be, and stays out of sight until the story shows it.
     */
    private suspend fun seedKnowledge(
        worldId: String,
        world: WorldEntity,
        player: CharacterEntity,
        startId: String?,
        locations: List<LocationEntity>,
        npcs: List<CharacterEntity>,
        openingCast: List<CharacterEntity>
    ) {
        val rows = mutableListOf<KnowledgeEntity>()
        val seen = mutableSetOf<String>()

        fun place(id: String?, source: String, detail: String) {
            val location = locations.firstOrNull { it.id == id } ?: return
            if (!seen.add(location.id)) return
            rows += PlayerKnowledge.row(
                worldId, PlayerKnowledge.LOCATION, location.id, location.name,
                PlayerKnowledge.EXISTS, source = source, sourceDetail = detail,
                storyTime = world.storyTime
            )
        }

        // Where they are, and everything it sits inside.
        var current = locations.firstOrNull { it.id == startId }
        var depth = 0
        while (current != null && depth < 6) {
            place(current.id, PlayerKnowledge.VISITED, "where the story opens")
            current = current.parentId?.let { id -> locations.firstOrNull { it.id == id } }
            depth++
        }
        // And home, and whatever contains it: a person knows the way to their own front door.
        var home = locations.firstOrNull { it.id == player.homeLocationId }
        var homeDepth = 0
        while (home != null && homeDepth < 6) {
            place(home.id, PlayerKnowledge.VISITED, "where they live")
            home = home.parentId?.let { id -> locations.firstOrNull { it.id == id } }
            homeDepth++
        }

        // The opening scene introduces whoever is in it by name, so the player has met them.
        openingCast.forEach { npc ->
            rows += PlayerKnowledge.onMeeting(worldId, npc, 0, world.storyTime, emptySet(), named = true)
        }

        if (rows.isNotEmpty()) repo.saveKnowledge(rows)

        val known = rows.filter { it.subjectType == PlayerKnowledge.LOCATION }.map { it.subjectId }.toSet()
        val corrected = locations.filter { it.discovered != (it.id in known) }
            .map { it.copy(discovered = it.id in known) }
        if (corrected.isNotEmpty()) repo.saveLocations(corrected)
    }

    /**
     * What time the world opens at, from whatever the builder said about it.
     *
     * The builder writes phrases, not clocks - "late evening", "Day 1, morning" - so the
     * phrase is read once, here, and never again: from this point the world has a number.
     */
    private fun startingMinuteOf(startingTime: String?, opening: String): Long {
        val text = (startingTime.orEmpty() + " " + opening).lowercase()
        Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(text)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: 0
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (match.groupValues[3].lowercase() == "pm" && hour < 12) hour += 12
            if (match.groupValues[3].lowercase() == "am" && hour == 12) hour = 0
            return hour * 60L + minute
        }
        return when {
            text.contains("midnight") -> 0L
            text.contains("dawn") || text.contains("sunrise") -> 6 * 60L
            text.contains("late morning") -> 11 * 60L
            text.contains("morning") -> 8 * 60L
            text.contains("midday") || text.contains("noon") -> 12 * 60L
            text.contains("afternoon") -> 15 * 60L
            text.contains("dusk") || text.contains("sunset") -> 19 * 60L
            text.contains("late evening") -> 22 * 60L
            text.contains("evening") -> 20 * 60L
            text.contains("late night") -> 1 * 60L
            text.contains("night") -> 23 * 60L
            else -> 9 * 60L
        }
    }

    private companion object {
        /** How many people may be standing with the player when the story opens. */
        const val OPENING_COMPANY = 2
        /** World building is legitimately slow; it is not the same budget as a turn. */
        const val CREATION_TIMEOUT_SECONDS = 600
        const val MAX_CREATION_TOKENS = 32_000
    }

    /**
     * Where the story actually opens.
     *
     * The builder is asked to name the starting location, but it sometimes names somewhere
     * else entirely. When the opening scene plainly happens in a place that exists, that place
     * wins - the player said where their story begins.
     */
    private fun startingLocation(
        opening: String,
        declared: String?,
        locations: List<LocationEntity>
    ): String? {
        if (opening.isBlank()) return declared
        val named = locations.filter { location ->
            location.name.length >= 3 &&
                Regex("\\b${Regex.escape(location.name.lowercase())}\\b").containsMatchIn(opening.lowercase())
        }
        if (named.isEmpty()) return declared
        if (declared != null && named.any { it.id == declared }) return declared
        // The most specific place mentioned: a ward inside a hospital, not the hospital.
        return named.maxByOrNull { it.name.length }?.id ?: declared
    }

    private fun <T> parseList(raw: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching {
                return AppJson.decodeFromString(ListSerializer(serializer), raw.substring(start, end + 1))
            }
        }
        // An array that was cut off still holds every suggestion the model finished writing, so
        // they are read out one at a time rather than lost with the one it never completed.
        val salvaged = objectsIn(raw).mapNotNull { json ->
            runCatching { AppJson.decodeFromString(serializer, json) }.getOrNull()
        }
        if (salvaged.isNotEmpty()) return salvaged
        throw IllegalStateException("The model did not return usable JSON.")
    }

    /** Every complete JSON object in a reply, plus the half-written last one if it can be closed. */
    private fun objectsIn(raw: String): List<String> = buildList {
        var cursor = raw.indexOf('{')
        while (cursor >= 0) {
            val rest = raw.substring(cursor)
            val complete = TurnParser.extractJsonObject(rest)
            if (complete != null) {
                add(complete)
                cursor = raw.indexOf('{', cursor + complete.length)
            } else {
                TurnParser.salvageJsonObject(rest)?.let { add(it) }
                return@buildList
            }
        }
    }
}
