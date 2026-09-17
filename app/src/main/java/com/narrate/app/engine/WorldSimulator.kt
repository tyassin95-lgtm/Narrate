package com.narrate.app.engine

import com.narrate.app.core.truncate
import com.narrate.app.data.entity.CharacterEntity
import com.narrate.app.data.repo.WorldSnapshot
import kotlin.math.abs

/**
 * The part of the world the player is not looking at.
 *
 * Before each turn this produces concrete proposals - who has moved along their routine, which
 * threads are due a development, which unresolved promises are aging - and hands them to the
 * narrator as pressure to work with. The narrator decides what actually happens; the simulator
 * only guarantees that the world never sits still.
 */
object WorldSimulator {

    private val dayPhases = listOf("dawn", "morning", "midday", "afternoon", "evening", "night")

    data class Tick(val kind: String, val text: String)

    fun simulate(snapshot: WorldSnapshot, lastStoryTime: String): List<Tick> {
        val style = PlayStyle.from(snapshot.world.playStyle)
        val ticks = mutableListOf<Tick>()
        val elapsed = phaseDistance(lastStoryTime, snapshot.world.storyTime) +
            abs(snapshot.world.dayNumber - (parseDay(lastStoryTime) ?: snapshot.world.dayNumber)) * dayPhases.size

        ticks += routineTicks(snapshot, elapsed)
        ticks += threadTicks(snapshot, style)
        ticks += relationshipTicks(snapshot, style)
        ticks += pressureTicks(snapshot)
        return ticks.take(if (style.quietWorld) 8 else 14)
    }

    /** NPCs keep their own schedules, whether or not anyone is watching. */
    private fun routineTicks(snapshot: WorldSnapshot, elapsedPhases: Int): List<Tick> {
        if (elapsedPhases <= 0) return emptyList()
        val here = snapshot.currentLocation?.id
        return snapshot.npcs
            .filter { it.status == "ALIVE" && it.currentLocationId != here && it.routine.isNotBlank() }
            .sortedByDescending { it.importance }
            .take(6)
            .map { npc ->
                Tick(
                    "routine",
                    "${npc.name} has had ${phaseWord(elapsedPhases)} to follow their routine " +
                        "(${npc.routine.truncate(120)}) from ${snapshot.locationName(npc.currentLocationId)}. " +
                        "Move them if their routine says they would have moved."
                )
            }
    }

    /** Threads develop on their own, as hard or as softly as the world's style allows. */
    private fun threadTicks(snapshot: WorldSnapshot, style: PlayStyle): List<Tick> =
        snapshot.threads
            .filter { it.status == "ACTIVE" }
            .sortedByDescending { it.urgency * 10 + (snapshot.world.turnCount - it.updatedTurn) }
            .take(style.maxThreadTicks)
            .map { thread ->
                val stale = snapshot.world.turnCount - thread.updatedTurn
                val pressure = when {
                    style.quietWorld -> "This moves at its own pace in the background. Do not bring it to the player."
                    thread.urgency >= 4 -> "This is urgent and should visibly advance now."
                    stale >= 6 -> "This has been quiet for $stale turns; it should resurface."
                    else -> "This continues in the background."
                }
                Tick(
                    "thread",
                    "Thread \"${thread.title}\": ${thread.nextBeat.ifBlank { thread.description }.truncate(180)} $pressure"
                )
            }

    /** People do not sit still emotionally either. */
    private fun relationshipTicks(snapshot: WorldSnapshot, style: PlayStyle): List<Tick> {
        val turn = snapshot.world.turnCount
        if (style == PlayStyle.SANDBOX) return emptyList()
        return snapshot.npcs
            .filter { it.status == "ALIVE" && abs(it.affinity) >= 40 && turn - it.lastSeenTurn in 3..40 }
            .sortedByDescending { abs(it.affinity) }
            .take(3)
            .map { npc ->
                val feeling = if (npc.affinity > 0) {
                    "cares about the player and has not seen them in ${turn - npc.lastSeenTurn} turns; they may seek them out, send word, or worry."
                } else {
                    "resents the player and has had ${turn - npc.lastSeenTurn} turns to act on it; their hostility should have gone somewhere."
                }
                Tick("relationship", "${npc.name} $feeling")
            }
    }

    /** Unfinished business the world should not let the player forget. */
    private fun pressureTicks(snapshot: WorldSnapshot): List<Tick> {
        val ticks = mutableListOf<Tick>()
        val unresolvedPromises = snapshot.memories
            .filter { it.kind == "PROMISE" && !it.superseded }
            .sortedByDescending { it.importance }
            .take(2)
        unresolvedPromises.forEach {
            ticks += Tick("promise", "Outstanding promise from turn ${it.turnIndex}: ${it.text.truncate(160)}")
        }
        snapshot.npcs.firstOrNull { it.status == "INJURED" }?.let {
            ticks += Tick("condition", "${it.name} is still injured; their condition should be better or worse by now, not identical.")
        }
        val player = snapshot.player
        if (player != null && player.physicalState.isNotBlank()) {
            ticks += Tick("condition", "The player's condition (${player.physicalState.truncate(100)}) should keep affecting what they can do.")
        }
        return ticks
    }

    fun render(ticks: List<Tick>, style: PlayStyle): String {
        if (ticks.isEmpty()) return ""
        return buildString {
            appendLine("## OFFSCREEN WORLD (what has been happening while the player was elsewhere)")
            appendLine("These are possibilities, not scripts. Apply the ones that make sense, ignore the rest,")
            appendLine("and record whatever you apply in the state block. The player only learns of them")
            appendLine("through evidence, never through narration telling them directly.")
            appendLine(style.simulationPressure)
            ticks.forEach { appendLine("- [${it.kind}] ${it.text}") }
        }
    }

    /** Fallback schedule movement for background NPCs when the narrator says nothing about them. */
    fun idleDrift(snapshot: WorldSnapshot, character: CharacterEntity): String? {
        if (character.routine.isBlank()) return null
        val phase = snapshot.world.timeOfDay
        val match = character.routine.split(Regex("[;,\n]"))
            .map { it.trim() }
            .firstOrNull { it.contains(phase, ignoreCase = true) } ?: return null
        return match
    }

    private fun parseDay(storyTime: String): Int? =
        Regex("day\\s+(\\d+)", RegexOption.IGNORE_CASE).find(storyTime)?.groupValues?.get(1)?.toIntOrNull()

    private fun phaseIndex(storyTime: String): Int {
        val lower = storyTime.lowercase()
        val index = dayPhases.indexOfFirst { lower.contains(it) }
        return if (index >= 0) index else 1
    }

    private fun phaseDistance(from: String, to: String): Int {
        val delta = phaseIndex(to) - phaseIndex(from)
        return if (delta < 0) delta + dayPhases.size else delta
    }

    private fun phaseWord(phases: Int): String = when {
        phases <= 1 -> "a short while"
        phases <= 3 -> "several hours"
        phases <= 6 -> "most of a day"
        else -> "days"
    }
}
