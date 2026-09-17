package com.narrate.app.engine

import com.narrate.app.ai.ChatMessage
import com.narrate.app.ai.LlmRequest
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.core.AppJson
import com.narrate.app.core.nameSimilarity
import com.narrate.app.core.newId
import com.narrate.app.data.entity.*
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
        val response = ProviderRegistry.get(choice.provider).chat(
            LlmRequest(
                model = choice.model,
                system = system,
                messages = listOf(ChatMessage.user(user)),
                maxTokens = maxTokens,
                temperature = temperature
            ),
            settings.apiKey(choice.provider)
        )
        usage.recordChat(worldId, -1, UsageRecorder.PURPOSE_CREATION, response, system.length + user.length)
        return response.text
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
            val json = TurnParser.extractJsonObject(raw)
                ?: throw IllegalStateException("The model did not return a usable character.")
            val concept = AppJson.decodeFromString(CharacterConcept.serializer(), json)
            AuthoredCanon.enforceCharacter(authored, concept).first
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
        val json = TurnParser.extractJsonObject(raw)
            ?: throw IllegalStateException("The model did not return a usable world.")
        val concept = AppJson.decodeFromString(WorldConcept.serializer(), json)
        AuthoredCanon.enforceWorld(authored, concept).first
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
                    Opening situation: ${concept.openingSituation}

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
                    - 5-10 established facts that must never be contradicted.

                    Reply with one JSON object:
                    {
                      "starting_location": "exact name of a location you defined, where the first scene opens",
                      the opening must match the requested pacing above,
                      "starting_time": "Day 1, morning",
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
                maxTokens = 8000,
                temperature = 0.95
            )
            val json = TurnParser.extractJsonObject(raw)
                ?: throw IllegalStateException("The world builder returned no readable JSON.")
            val build = AppJson.decodeFromString(WorldBuild.serializer(), json)
            WorldBuildOutcome(concept, build.locations, build.characters, build.factions, build.threads, build.startingLocation, build.startingTime, build.establishedFacts)
        }

    data class WorldBuildOutcome(
        val concept: WorldConcept,
        val locations: List<NewLocation>,
        val characters: List<NewCharacter>,
        val factions: List<FactionDelta>,
        val threads: List<ThreadDelta>,
        val startingLocation: String,
        val startingTime: String,
        val facts: List<MemoryDelta>
    )

    /** Commits a built world to disk as a save the player can leave and come back to. */
    suspend fun persist(
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
        val world = WorldEntity(
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
            storyTime = build?.startingTime?.ifBlank { "Day 1, morning" } ?: "Day 1, morning",
            openingNarration = enforcedWorld.openingSituation
        )
        repo.saveWorld(world)

        val locationIds = mutableMapOf<String, String>()
        val locations = mutableListOf<LocationEntity>()
        build?.locations?.filter { it.name.isNotBlank() }?.forEachIndexed { index, incoming ->
            val entity = LocationEntity(
                id = newId(),
                worldId = worldId,
                name = incoming.name.trim(),
                type = incoming.type.uppercase().ifBlank { "BUILDING" },
                description = incoming.description,
                atmosphere = incoming.atmosphere,
                notableFeatures = incoming.notableFeatures,
                controlledBy = incoming.controlledBy,
                discovered = true,
                mapX = (index % 4) * 0.24f + 0.14f,
                mapY = (index / 4) * 0.2f + 0.12f
            )
            locations += entity
            locationIds[incoming.name.trim().lowercase()] = entity.id
        }
        // Parents resolve in a second pass so order in the model's output does not matter.
        val withParents = locations.map { location ->
            val incoming = build?.locations?.firstOrNull { it.name.trim().equals(location.name, true) }
            val parentId = incoming?.parent?.trim()?.lowercase()?.let { locationIds[it] }
            location.copy(parentId = parentId?.takeIf { it != location.id })
        }
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

        val startId = locationIds[build?.startingLocation?.trim()?.lowercase()]
            ?: withParents.firstOrNull { it.type == "ROOM" || it.type == "BUILDING" }?.id
            ?: withParents.firstOrNull()?.id

        val playerName = enforcedCharacter.name
            .ifBlank { AuthoredCanon.characterName(characterPrompt).orEmpty() }
            .ifBlank { "The Traveller" }
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
            homeLocationId = startId,
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
                        holderId = playerCharacter.id, significance = "Carried from the beginning."
                    )
                )
            )
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
                currentLocationId = locationIds[incoming.location.trim().lowercase()] ?: startId,
                homeLocationId = locationIds[incoming.homeLocation.trim().lowercase()],
                routine = incoming.routine,
                importance = incoming.importance.coerceIn(1, 5)
            )
            entity
        }.orEmpty()
        if (npcs.isNotEmpty()) {
            repo.saveCharacters(npcs)
            npcs.filter { it.appearance.isNotBlank() }.forEach { npc ->
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

        build?.threads?.filter { it.title.isNotBlank() }?.map { incoming ->
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
                storyTime = world.storyTime, turnIndex = 0, pinned = incoming.importance >= 4
            )
        }
        // The player's own words are canon of the highest order, and are always pinned.
        if (customPrompt.isNotBlank()) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "CANON",
                text = "The player wrote this world themselves, and it is fact: $customPrompt",
                importance = 5, keywords = MemoryIndex.keywords(customPrompt).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0, pinned = true
            )
        }
        if (playStyle.quietWorld) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "RULE",
                text = "The player chose a ${playStyle.label.lowercase()} world: " +
                    "events emerge from their actions and the ordinary life of the place, " +
                    "and the world does not manufacture drama to hold their attention.",
                importance = 5, keywords = "pacing tone sandbox quiet",
                storyTime = world.storyTime, turnIndex = 0, pinned = true
            )
        }
        if (characterPrompt.isNotBlank()) {
            facts += MemoryEntity(
                id = newId(), worldId = worldId, kind = "CANON",
                text = "The player wrote their own character, and it is fact: $characterPrompt",
                importance = 5, keywords = MemoryIndex.keywords(characterPrompt).joinToString(" "),
                storyTime = world.storyTime, turnIndex = 0, pinned = true
            )
        }
        if (facts.isNotEmpty()) repo.saveMemories(facts)

        val finished = world.copy(playerCharacterId = playerCharacter.id, currentLocationId = startId)
        repo.saveWorld(finished)
        return finished
    }

    private fun <T> parseList(raw: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching {
                return AppJson.decodeFromString(ListSerializer(serializer), raw.substring(start, end + 1))
            }
        }
        // A single object is an acceptable answer too.
        TurnParser.extractJsonObject(raw)?.let { json ->
            runCatching { return listOf(AppJson.decodeFromString(serializer, json)) }
        }
        throw IllegalStateException("The model did not return usable JSON.")
    }
}
