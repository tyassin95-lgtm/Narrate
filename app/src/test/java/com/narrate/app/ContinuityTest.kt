package com.narrate.app

import com.narrate.app.data.entity.*
import com.narrate.app.data.repo.WorldSnapshot
import com.narrate.app.engine.ContinuityGuard
import com.narrate.app.engine.Labeler
import com.narrate.app.engine.MemoryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityTest {

    private val harbour = LocationEntity(id = "loc-harbour", worldId = "w", name = "Old Harbour", type = "DISTRICT")
    private val warehouse = LocationEntity(id = "loc-warehouse", worldId = "w", name = "Warehouse 9", parentId = "loc-harbour")
    private val mountain = LocationEntity(id = "loc-mountain", worldId = "w", name = "Cairn Pass", type = "WILDERNESS")

    private val player = CharacterEntity(
        id = "pc", worldId = "w", name = "Vale", isPlayer = true, currentLocationId = "loc-harbour"
    )
    private val elena = CharacterEntity(
        id = "npc-elena", worldId = "w", name = "Elena Vasquez", currentLocationId = "loc-harbour", lastSeenTurn = 3
    )
    private val marcus = CharacterEntity(
        id = "npc-marcus", worldId = "w", name = "Marcus Reyes", currentLocationId = "loc-mountain"
    )
    private val ghost = CharacterEntity(
        id = "npc-ghost", worldId = "w", name = "Tomas Lund", currentLocationId = "loc-harbour", status = "DEAD"
    )

    private fun snapshot(
        characters: List<CharacterEntity> = listOf(player, elena, marcus, ghost),
        memories: List<MemoryEntity> = emptyList(),
        turnCount: Int = 8
    ) = WorldSnapshot(
        world = WorldEntity(id = "w", name = "Tidewater", currentLocationId = "loc-harbour", turnCount = turnCount),
        player = characters.firstOrNull { it.isPlayer },
        characters = characters,
        locations = listOf(harbour, warehouse, mountain),
        links = listOf(LocationLinkEntity(id = "l1", worldId = "w", fromId = "loc-harbour", toId = "loc-warehouse")),
        items = emptyList(),
        factions = emptyList(),
        relationships = emptyList(),
        threads = emptyList(),
        chapters = emptyList(),
        recentTurns = emptyList(),
        memories = memories,
        visualIdentities = emptyList()
    )

    @Test
    fun `near-duplicate names resolve to the existing character`() {
        val existing = ContinuityGuard.findExisting(listOf(elena, marcus), "Elena Vasquez ")
        assertEquals(elena.id, existing?.id)
        assertEquals(elena.id, ContinuityGuard.findExisting(listOf(elena, marcus), "elena vasquez")?.id)
        assertNull("a genuinely new name must not be merged", ContinuityGuard.findExisting(listOf(elena), "Harriet Oyelaran"))
    }

    @Test
    fun `movement along a mapped route is allowed silently`() {
        assertNull(ContinuityGuard.checkMovement(snapshot(), elena, "loc-warehouse", null))
    }

    @Test
    fun `movement into a containing location is allowed`() {
        val inside = elena.copy(currentLocationId = "loc-warehouse")
        assertNull(ContinuityGuard.checkMovement(snapshot(), inside, "loc-harbour", null))
    }

    @Test
    fun `an unexplained jump across the map is flagged`() {
        val issue = ContinuityGuard.checkMovement(snapshot(), elena, "loc-mountain", null)
        assertNotNull(issue)
        assertEquals(ContinuityGuard.SEVERITY_WARNING, issue!!.severity)
        assertEquals("movement", issue.category)
    }

    @Test
    fun `an explained jump is downgraded to a note`() {
        val issue = ContinuityGuard.checkMovement(snapshot(), elena, "loc-mountain", "she took the night train")
        assertEquals(ContinuityGuard.SEVERITY_INFO, issue?.severity)
    }

    @Test
    fun `a character acting far from where they stand is caught`() {
        val narration = "Marcus steps out of the fog and says, \"You are late.\""
        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = emptySet())
        assertTrue(issues.any { it.category == "presence" && it.description.contains("Marcus") })
    }

    @Test
    fun `no warning when the state block moved them`() {
        val narration = "Marcus steps out of the fog and says, \"You are late.\""
        val issues = ContinuityGuard.auditNarration(snapshot(), narration, movedNames = setOf("Marcus Reyes"))
        assertTrue(issues.none { it.category == "presence" })
    }

    @Test
    fun `the dead do not speak unremarked`() {
        val issues = ContinuityGuard.auditNarration(snapshot(), "Tomas nodded and said nothing.", emptySet())
        assertTrue(issues.any { it.category == "dead-character" })
    }

    @Test
    fun `merely mentioning an absent character is not an error`() {
        val issues = ContinuityGuard.auditNarration(snapshot(), "You wonder where Marcus went after the fire.", emptySet())
        assertTrue(issues.none { it.category == "presence" })
    }

    @Test
    fun `pinned memories are always retrieved and recency is respected`() {
        val memories = (1..40).map { index ->
            MemoryEntity(
                id = "m$index", worldId = "w", text = "Routine event number $index",
                importance = 2, turnIndex = index, keywords = "routine event"
            )
        } + MemoryEntity(
            id = "pinned", worldId = "w", text = "The tide runs on a timetable nobody set.",
            importance = 5, turnIndex = 1, pinned = true, keywords = "tide timetable"
        ) + MemoryEntity(
            id = "elena", worldId = "w", text = "Elena Vasquez forged the harbour manifest.",
            importance = 4, turnIndex = 3, subjectNames = "Elena Vasquez", keywords = "elena forged manifest"
        )

        val retrieved = MemoryIndex.retrieve(snapshot(memories = memories, turnCount = 41), "ask about the manifest", 12)
        assertTrue("pinned canon must always be present", retrieved.any { it.id == "pinned" })
        assertTrue("memories about who is present should surface", retrieved.any { it.id == "elena" })
        assertTrue(retrieved.size <= 13)
        assertEquals("retrieved memories read in story order", retrieved.map { it.turnIndex }.sorted(), retrieved.map { it.turnIndex })
    }

    @Test
    fun `album labels read like a human wrote them`() {
        assertEquals("Marcus Reyes - First Appearance", Labeler.portrait("Marcus Reyes", "Cairn Pass", "night", isFirst = true))
        assertEquals("Elena - Apartment - Night", Labeler.portrait("Elena", "Apartment", "night", isFirst = false))
        assertEquals("Old Harbour - After the storm", Labeler.location("Old Harbour", "after the storm", "dawn"))
        assertTrue(
            Labeler.moment("The confrontation at Blackwood Station ended badly.", "Blackwood Station", "Day 4, night")
                .startsWith("The confrontation at Blackwood Station")
        )
    }

    @Test
    fun `present and nearby characters are derived from the map`() {
        val state = snapshot()
        assertEquals(listOf("Elena Vasquez"), state.presentNpcs().map { it.name })
        assertTrue(state.nearbyNpcs().isEmpty())

        val inWarehouse = marcus.copy(currentLocationId = "loc-warehouse")
        val moved = snapshot(characters = listOf(player, elena, inWarehouse))
        assertEquals(listOf("Marcus Reyes"), moved.nearbyNpcs().map { it.name })
    }
}
