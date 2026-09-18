package com.narrate.app.engine

/**
 * Which parts of a generated world or character are still missing.
 *
 * A model asked for eleven fields sometimes returns nine, and the two it dropped are silently
 * empty because every field defaults to a blank string. Rather than leave that to chance, the
 * gaps are named so they can be asked for specifically - and merged in without ever writing
 * over something that already has a value, which is what keeps authored canon safe.
 */
object ConceptCompleteness {

    /** Fields a finished world is expected to have. Anything absent here is optional. */
    val worldFields = listOf(
        "tagline", "genre", "tone", "premise", "history", "rules", "themes",
        "art_style", "opening_situation"
    )

    /**
     * Fields a finished character is expected to have.
     *
     * `secrets` and `fears` are deliberately not among them, and neither is `starting_items`. A
     * character sheet that must have a secret gets one invented, and what comes back is usually
     * a personality trait wearing a hat: "he keeps everyone at arm's length until he wants
     * something" is not a secret, it is the line the player already wrote about him.
     */
    val characterFields = listOf(
        "role", "summary", "personality", "backstory", "appearance", "outfit",
        "voice", "goals", "ties_to_world"
    )

    fun missingWorldFields(concept: WorldConcept): List<String> = buildList {
        if (concept.tagline.isBlank()) add("tagline")
        if (concept.genre.isBlank()) add("genre")
        if (concept.tone.isBlank()) add("tone")
        if (concept.premise.isBlank()) add("premise")
        if (concept.history.isBlank()) add("history")
        if (concept.rules.isBlank()) add("rules")
        if (concept.themes.isBlank()) add("themes")
        if (concept.artStyle.isBlank()) add("art_style")
        if (concept.openingSituation.isBlank()) add("opening_situation")
    }

    fun missingCharacterFields(concept: CharacterConcept): List<String> = buildList {
        if (concept.role.isBlank()) add("role")
        if (concept.summary.isBlank()) add("summary")
        if (concept.personality.isBlank()) add("personality")
        if (concept.backstory.isBlank()) add("backstory")
        if (concept.appearance.isBlank()) add("appearance")
        if (concept.outfit.isBlank()) add("outfit")
        if (concept.voice.isBlank()) add("voice")
        if (concept.goals.isBlank()) add("goals")
        // Not fears, not secrets: an invented one is worse than an empty one.
        if (concept.tiesToWorld.isBlank()) add("ties_to_world")
    }

    /** What is already settled, written out for a model that is only allowed to fill the rest. */
    fun describeWorld(concept: WorldConcept): String = listOf(
        "name" to concept.name,
        "tagline" to concept.tagline,
        "genre" to concept.genre,
        "tone" to concept.tone,
        "premise" to concept.premise,
        "history" to concept.history,
        "rules" to concept.rules,
        "themes" to concept.themes,
        "art_style" to concept.artStyle,
        "opening_situation" to concept.openingSituation
    ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }

    fun describeCharacter(concept: CharacterConcept): String = (
        listOf(
            "name" to concept.name,
            "role" to concept.role,
            "summary" to concept.summary,
            "personality" to concept.personality,
            "backstory" to concept.backstory,
            "appearance" to concept.appearance,
            "outfit" to concept.outfit,
            "voice" to concept.voice,
            "goals" to concept.goals,
            "fears" to concept.fears,
            "secrets" to concept.secrets,
            "ties_to_world" to concept.tiesToWorld
        ) + listOf("starting_items" to concept.startingItems.joinToString(", "))
        ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }

    /** Fills blanks from [patch]. A field that already has a value is never overwritten. */
    fun mergeWorld(base: WorldConcept, patch: WorldConcept): WorldConcept = base.copy(
        name = base.name.ifBlank { patch.name },
        tagline = base.tagline.ifBlank { patch.tagline },
        genre = base.genre.ifBlank { patch.genre },
        tone = base.tone.ifBlank { patch.tone },
        premise = base.premise.ifBlank { patch.premise },
        history = base.history.ifBlank { patch.history },
        rules = base.rules.ifBlank { patch.rules },
        themes = base.themes.ifBlank { patch.themes },
        artStyle = base.artStyle.ifBlank { patch.artStyle },
        openingSituation = base.openingSituation.ifBlank { patch.openingSituation }
    )

    fun mergeCharacter(base: CharacterConcept, patch: CharacterConcept): CharacterConcept = base.copy(
        name = base.name.ifBlank { patch.name },
        role = base.role.ifBlank { patch.role },
        summary = base.summary.ifBlank { patch.summary },
        personality = base.personality.ifBlank { patch.personality },
        backstory = base.backstory.ifBlank { patch.backstory },
        appearance = base.appearance.ifBlank { patch.appearance },
        outfit = base.outfit.ifBlank { patch.outfit },
        voice = base.voice.ifBlank { patch.voice },
        goals = base.goals.ifBlank { patch.goals },
        fears = base.fears.ifBlank { patch.fears },
        secrets = base.secrets.ifBlank { patch.secrets },
        startingItems = base.startingItems.ifEmpty { patch.startingItems },
        tiesToWorld = base.tiesToWorld.ifBlank { patch.tiesToWorld }
    )
}
