package com.narrate.app.engine

import com.narrate.app.ai.ImageReference
import com.narrate.app.ai.ImageRequest
import com.narrate.app.ai.ModelTaxonomy
import com.narrate.app.ai.ProviderException
import com.narrate.app.ai.ProviderRegistry
import com.narrate.app.core.newId
import com.narrate.app.core.truncate
import com.narrate.app.data.entity.ImageEntity
import com.narrate.app.data.entity.VisualIdentityEntity
import com.narrate.app.data.prefs.SettingsStore
import com.narrate.app.data.repo.WorldRepository
import com.narrate.app.data.repo.WorldSnapshot

/** What the player asked to see. */
sealed class ImageSubject {
    data object CurrentScene : ImageSubject()
    data class Character(val characterId: String) : ImageSubject()
    data class Location(val locationId: String) : ImageSubject()
    data class Item(val itemId: String) : ImageSubject()
    data class Moment(val description: String) : ImageSubject()
    data class Custom(val description: String) : ImageSubject()
}

/**
 * Turns world state into pictures, and pictures back into world state.
 *
 * Nothing here is a standalone generation: every prompt is assembled from the live world -
 * who is present, what they are wearing, what the light is doing - and every recurring
 * subject is seeded with the images already generated for it, so a face stays the same face.
 */
class ImageDirector(
    private val repo: WorldRepository,
    private val settings: SettingsStore
) {

    private val usage = UsageRecorder(repo)

    suspend fun generate(
        snapshot: WorldSnapshot,
        subject: ImageSubject,
        extraDirection: String = ""
    ): Result<ImageEntity> = runCatching {
        val choice = settings.current.image
        if (!choice.isSet) throw ProviderException(choice.provider, "No image model selected. Choose one in Settings.")
        val apiKey = settings.apiKey(choice.provider)
        val provider = ProviderRegistry.get(choice.provider)

        val plan = plan(snapshot, subject, extraDirection)
        // Asked of the taxonomy rather than the catalog, so a model the provider listed but
        // Narrate has never heard of is still classified correctly.
        val supportsReferences = ModelTaxonomy.supportsImageReferences(choice.provider, choice.model)
        val references = if (settings.current.useReferenceImages && supportsReferences) {
            loadReferences(plan.referenceImageIds)
        } else {
            emptyList()
        }

        val result = provider.generateImage(
            ImageRequest(
                model = choice.model,
                prompt = plan.prompt,
                references = references,
                size = plan.size
            ),
            apiKey
        )

        val extension = if (result.mimeType.contains("jpeg")) "jpg" else "png"
        val file = repo.writeImageFile(snapshot.world.id, result.bytes, extension)
        val image = ImageEntity(
            id = newId(),
            worldId = snapshot.world.id,
            filePath = file.absolutePath,
            label = plan.label,
            caption = plan.caption,
            type = plan.type,
            subjectIds = plan.subjectIds.joinToString(","),
            subjectNames = plan.subjectNames.joinToString(", "),
            locationName = plan.locationName,
            storyTime = snapshot.world.storyTime,
            turnIndex = snapshot.world.turnCount,
            prompt = plan.prompt,
            model = choice.model,
            provider = choice.provider.name,
            usedReferences = references.joinToString(", ") { it.label }
        )
        repo.saveImage(image)
        bindToSubjects(snapshot, plan, image)
        usage.recordImage(snapshot.world.id, snapshot.world.turnCount, choice.provider, choice.model)
        image
    }

    private data class Plan(
        val prompt: String,
        val label: String,
        val caption: String,
        val type: String,
        val subjectIds: List<String>,
        val subjectNames: List<String>,
        val locationName: String,
        val referenceImageIds: List<String>,
        val size: String
    )

    private suspend fun plan(snapshot: WorldSnapshot, subject: ImageSubject, extraDirection: String): Plan {
        val world = snapshot.world
        val style = world.artStyle.ifBlank { "Cinematic, film still, natural lighting, high detail" }
        val here = snapshot.currentLocation
        val timeOfDay = world.timeOfDay

        return when (subject) {
            is ImageSubject.Character -> {
                val character = repo.character(subject.characterId)
                    ?: throw IllegalStateException("That character is no longer in this world.")
                val identity = repo.visualForSubject(character.id)
                val place = snapshot.locationById(character.currentLocationId)
                val prompt = buildString {
                    appendLine("A portrait of a single person from the world of ${world.name}.")
                    appendLine("Art direction: $style. Genre: ${world.genre}. Mood: ${world.tone}.")
                    appendLine()
                    appendLine("WHO THIS IS (their appearance is fixed and must be reproduced exactly):")
                    appendLine(identity?.canonicalDescription?.ifBlank { character.appearance } ?: character.appearance)
                    if (character.outfit.isNotBlank()) appendLine("Currently wearing: ${character.outfit}")
                    if (character.physicalState.isNotBlank()) appendLine("Current condition: ${character.physicalState}")
                    if (identity?.currentVariant?.isNotBlank() == true) appendLine("Recent change: ${identity.currentVariant}")
                    if (character.personality.isNotBlank()) {
                        appendLine("Their bearing should read as: ${character.personality.truncate(200)}")
                    }
                    if (place != null) {
                        appendLine("Setting: ${place.name}. ${place.description.truncate(220)} ${place.atmosphere.truncate(140)}")
                    }
                    appendLine("Time of day: $timeOfDay.")
                    if (extraDirection.isNotBlank()) appendLine("Additional direction: $extraDirection")
                    appendReferenceNote(identity)
                    appendLine("No text, captions, watermarks or borders in the image.")
                }
                Plan(
                    prompt = prompt,
                    label = Labeler.portrait(character.name, place?.name, timeOfDay, isFirst = identity?.primaryImageId == null),
                    caption = listOfNotNull(
                        character.role.takeIf { it.isNotBlank() },
                        place?.name,
                        world.storyTime
                    ).joinToString(" - "),
                    type = "PORTRAIT",
                    subjectIds = listOf(character.id),
                    subjectNames = listOf(character.name),
                    locationName = place?.name.orEmpty(),
                    referenceImageIds = identity?.let { referenceIdsOf(it) }.orEmpty(),
                    size = "1024x1024"
                )
            }

            is ImageSubject.Location -> {
                val location = repo.location(subject.locationId)
                    ?: throw IllegalStateException("That location is no longer in this world.")
                val identity = repo.visualForSubject(location.id)
                val prompt = buildString {
                    appendLine("An establishing view of a place in the world of ${world.name}. No people in frame unless unavoidable.")
                    appendLine("Art direction: $style. Genre: ${world.genre}. Mood: ${world.tone}.")
                    appendLine()
                    appendLine("THE PLACE (its architecture and layout are established and must be preserved):")
                    appendLine(identity?.canonicalDescription?.ifBlank { location.description } ?: location.description)
                    if (location.atmosphere.isNotBlank()) appendLine("Atmosphere: ${location.atmosphere}")
                    if (location.notableFeatures.isNotBlank()) appendLine("Must include: ${location.notableFeatures}")
                    if (location.currentState.isNotBlank()) appendLine("Its current condition: ${location.currentState}")
                    appendLine("Time of day: $timeOfDay. Day ${world.dayNumber}.")
                    if (extraDirection.isNotBlank()) appendLine("Additional direction: $extraDirection")
                    appendReferenceNote(identity)
                    appendLine("No text, captions, watermarks or borders in the image.")
                }
                Plan(
                    prompt = prompt,
                    label = Labeler.location(location.name, location.currentState, timeOfDay),
                    caption = location.description.truncate(160),
                    type = "LOCATION",
                    subjectIds = listOf(location.id),
                    subjectNames = listOf(location.name),
                    locationName = location.name,
                    referenceImageIds = identity?.let { referenceIdsOf(it) }.orEmpty(),
                    size = "1792x1024"
                )
            }

            is ImageSubject.Item -> {
                val item = snapshot.items.firstOrNull { it.id == subject.itemId }
                    ?: throw IllegalStateException("That object is no longer in this world.")
                val identity = repo.visualForSubject(item.id)
                val prompt = buildString {
                    appendLine("A close study of a single object from the world of ${world.name}.")
                    appendLine("Art direction: $style. Genre: ${world.genre}.")
                    appendLine("THE OBJECT: ${item.appearance.ifBlank { item.description }}")
                    if (item.state.isNotBlank()) appendLine("Its current condition: ${item.state}")
                    if (item.significance.isNotBlank()) appendLine("It matters because: ${item.significance.truncate(200)}")
                    if (extraDirection.isNotBlank()) appendLine("Additional direction: $extraDirection")
                    appendReferenceNote(identity)
                    appendLine("No text, captions, watermarks or borders in the image.")
                }
                Plan(
                    prompt = prompt,
                    label = Labeler.simple(item.name, item.state.ifBlank { world.storyTime }),
                    caption = item.significance.truncate(160),
                    type = "ITEM",
                    subjectIds = listOf(item.id),
                    subjectNames = listOf(item.name),
                    locationName = snapshot.locationName(item.locationId),
                    referenceImageIds = identity?.let { referenceIdsOf(it) }.orEmpty(),
                    size = "1024x1024"
                )
            }

            is ImageSubject.Moment, is ImageSubject.Custom, ImageSubject.CurrentScene -> {
                val direction = when (subject) {
                    is ImageSubject.Moment -> subject.description
                    is ImageSubject.Custom -> subject.description
                    else -> ""
                }
                val lastTurn = snapshot.recentTurns.lastOrNull()
                val present = snapshot.presentNpcs().take(4)
                val player = snapshot.player
                val identities = mutableListOf<VisualIdentityEntity>()
                val subjectIds = mutableListOf<String>()
                val subjectNames = mutableListOf<String>()

                val prompt = buildString {
                    appendLine("A single frame from the world of ${world.name}, showing this exact moment.")
                    appendLine("Art direction: $style. Genre: ${world.genre}. Mood: ${world.tone}.")
                    appendLine()
                    if (here != null) {
                        appendLine("WHERE: ${here.name}. ${here.description.truncate(300)}")
                        if (here.atmosphere.isNotBlank()) appendLine("Atmosphere: ${here.atmosphere.truncate(200)}")
                        if (here.currentState.isNotBlank()) appendLine("Current condition of the place: ${here.currentState.truncate(200)}")
                        repo.visualForSubject(here.id)?.let { identities += it }
                        subjectIds += here.id
                    }
                    appendLine("WHEN: ${world.storyTime}.")
                    if (player != null) {
                        appendLine()
                        appendLine("THE PROTAGONIST (appearance fixed - reproduce exactly): ${player.appearance}")
                        if (player.outfit.isNotBlank()) appendLine("Wearing: ${player.outfit}")
                        if (player.physicalState.isNotBlank()) appendLine("Condition: ${player.physicalState}")
                        repo.visualForSubject(player.id)?.let { identities += it }
                        subjectIds += player.id
                        subjectNames += player.name
                    }
                    present.forEach { npc ->
                        appendLine()
                        appendLine("ALSO PRESENT - ${npc.name} (appearance fixed - reproduce exactly): ${npc.appearance}")
                        if (npc.outfit.isNotBlank()) appendLine("Wearing: ${npc.outfit}")
                        if (npc.physicalState.isNotBlank()) appendLine("Condition: ${npc.physicalState}")
                        repo.visualForSubject(npc.id)?.let { identities += it }
                        subjectIds += npc.id
                        subjectNames += npc.name
                    }
                    appendLine()
                    val beat = direction.ifBlank {
                        lastTurn?.summary?.ifBlank { null } ?: lastTurn?.narration?.truncate(500).orEmpty()
                    }
                    if (beat.isNotBlank()) {
                        appendLine("WHAT IS HAPPENING: ${beat.truncate(700)}")
                    }
                    appendLine("Compose it as a still from a film: specific, grounded, and true to the description above.")
                    appendLine("Do not invent characters who are not listed here.")
                    if (identities.isNotEmpty()) {
                        appendLine("Reference images are attached for the people and place; keep their faces, builds and architecture identical.")
                    }
                    appendLine("No text, captions, watermarks or borders in the image.")
                }

                val label = when {
                    direction.isNotBlank() -> Labeler.moment(direction, here?.name, world.storyTime)
                    lastTurn?.summary?.isNotBlank() == true -> Labeler.moment(lastTurn.summary, here?.name, world.storyTime)
                    else -> Labeler.simple(here?.name ?: world.name, world.storyTime)
                }
                Plan(
                    prompt = prompt,
                    label = label,
                    caption = (direction.ifBlank { lastTurn?.summary.orEmpty() }).truncate(180),
                    type = if (subject is ImageSubject.Moment) "EVENT" else "SCENE",
                    subjectIds = subjectIds,
                    subjectNames = subjectNames,
                    locationName = here?.name.orEmpty(),
                    referenceImageIds = identities.flatMap { referenceIdsOf(it) }.distinct().take(3),
                    size = "1792x1024"
                )
            }
        }
    }

    private fun StringBuilder.appendReferenceNote(identity: VisualIdentityEntity?) {
        if (identity?.primaryImageId != null) {
            appendLine(
                "A reference image of this subject is attached. Keep the same identity - face, build, " +
                    "distinguishing features, architecture - and change only what the description says has changed."
            )
        }
    }

    private fun referenceIdsOf(identity: VisualIdentityEntity): List<String> =
        (listOfNotNull(identity.primaryImageId) + identity.referenceImageIds.split(",").filter { it.isNotBlank() })
            .distinct()
            .take(2)

    private suspend fun loadReferences(imageIds: List<String>): List<ImageReference> =
        imageIds.mapNotNull { id ->
            val image = repo.image(id) ?: return@mapNotNull null
            val bytes = repo.readImageBytes(image.filePath) ?: return@mapNotNull null
            ImageReference(bytes, image.label, if (image.filePath.endsWith("jpg")) "image/jpeg" else "image/png")
        }

    /**
     * Files the new image against its subjects: the first picture of anyone becomes their
     * reference, and portraits become the profile image shown throughout the app.
     */
    private suspend fun bindToSubjects(snapshot: WorldSnapshot, plan: Plan, image: ImageEntity) {
        // A picture of one subject defines how that subject looks. A crowded scene does not:
        // it is filed as a secondary reference so it can never overwrite an established face.
        val isDefiningPortrait = plan.subjectIds.size == 1 &&
            plan.type in setOf("PORTRAIT", "LOCATION", "ITEM", "CREATURE")

        plan.subjectIds.forEach { subjectId ->
            val character = repo.character(subjectId)
            val location = if (character == null) repo.location(subjectId) else null
            val item = if (character == null && location == null) {
                snapshot.items.firstOrNull { it.id == subjectId }
            } else null
            val name = character?.name ?: location?.name ?: item?.name ?: return@forEach
            val type = when {
                character != null -> "CHARACTER"
                location != null -> "LOCATION"
                else -> "ITEM"
            }
            val canonical = character?.appearance ?: location?.description ?: item?.appearance.orEmpty()
            val existing = repo.visualForSubject(subjectId)

            val identity = (existing ?: VisualIdentityEntity(
                id = newId(),
                worldId = snapshot.world.id,
                subjectId = subjectId,
                subjectType = type,
                subjectName = name,
                canonicalDescription = canonical
            )).let { current ->
                val references = (listOf(image.id) + current.referenceImageIds.split(",").filter { it.isNotBlank() })
                    .distinct()
                    .take(4)
                current.copy(
                    subjectName = name,
                    canonicalDescription = current.canonicalDescription.ifBlank { canonical },
                    primaryImageId = if (isDefiningPortrait) image.id else current.primaryImageId,
                    referenceImageIds = references.joinToString(","),
                    updatedTurn = snapshot.world.turnCount
                )
            }
            repo.saveVisualIdentity(identity)

            // Profile art only ever comes from a portrait of that one subject.
            if (character != null && plan.type == "PORTRAIT" && plan.subjectIds.size == 1) {
                repo.saveCharacter(character.copy(portraitImageId = image.id))
            }
            if (location != null && (location.imageId == null || plan.type == "LOCATION")) {
                repo.saveLocation(location.copy(imageId = image.id))
            }
            if (item != null && item.imageId == null) {
                repo.saveItems(listOf(item.copy(imageId = image.id)))
            }
        }
        if (snapshot.world.coverImageId == null && (plan.type == "SCENE" || plan.type == "LOCATION")) {
            repo.world(snapshot.world.id)?.let { repo.saveWorld(it.copy(coverImageId = image.id)) }
        }
    }
}

/** Human-readable album labels, built from what the picture actually is. */
object Labeler {

    fun portrait(name: String, place: String?, timeOfDay: String, isFirst: Boolean): String = when {
        isFirst -> "$name - First Appearance"
        !place.isNullOrBlank() -> "$name - $place - ${timeOfDay.replaceFirstChar { it.uppercase() }}"
        else -> "$name - ${timeOfDay.replaceFirstChar { it.uppercase() }}"
    }

    fun location(name: String, state: String, timeOfDay: String): String = when {
        state.isNotBlank() -> "$name - ${state.truncate(40).replaceFirstChar { it.uppercase() }}"
        else -> "$name - ${timeOfDay.replaceFirstChar { it.uppercase() }}"
    }

    fun simple(name: String, qualifier: String): String =
        if (qualifier.isBlank()) name else "$name - ${qualifier.truncate(40)}"

    /** Condense a sentence of narration into a title like "The Confrontation at Blackwood Station". */
    fun moment(description: String, place: String?, storyTime: String): String {
        val cleaned = description
            .replace(Regex("[*_#\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(".")
        val words = cleaned.split(' ').filter { it.isNotBlank() }
        val head = words.take(9).joinToString(" ")
        val title = head.ifBlank { "A Moment" }.replaceFirstChar { it.uppercase() }
        return when {
            !place.isNullOrBlank() && !title.contains(place, ignoreCase = true) -> "$title - $place"
            else -> "$title - $storyTime"
        }.truncate(80)
    }
}
