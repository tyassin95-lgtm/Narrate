package com.narrate.app.engine

/**
 * How much pressure the world puts on the player.
 *
 * A world can be a thriller or it can be a place to spend an afternoon. This is part of the
 * world's permanent configuration, and it reaches the narrator, the choices offered, the
 * offscreen simulation and the way the world is first built.
 */
enum class PlayStyle(
    val id: String,
    val label: String,
    val blurb: String
) {
    SANDBOX(
        "SANDBOX",
        "Sandbox",
        "Just live here. The world is yours to wander; nothing dramatic happens unless you cause it."
    ),
    GENTLE(
        "GENTLE",
        "Slice of life",
        "Small, human stakes. Relationships, work, weather and routine, with the occasional complication."
    ),
    BALANCED(
        "BALANCED",
        "Balanced",
        "Ordinary life and real consequence in turn. Pressure builds, but it leaves room to breathe."
    ),
    DRAMATIC(
        "DRAMATIC",
        "Dramatic",
        "Momentum, danger and escalation. Something is always moving, and rarely in your favour."
    );

    /** Injected into the narrator's system prompt. */
    val narratorGuidance: String
        get() = when (this) {
            SANDBOX -> """
                PACING: SANDBOX

                This world is not a plot. It is a place, and the player is living in it.

                - Nothing dramatic is owed to any turn. A turn in which the player eats a meal, repairs
                  something, walks somewhere, or talks to a neighbour about nothing in particular is a
                  complete and successful turn. Write it with the same care you would give a crisis.
                - Do not manufacture emergencies, ambushes, mysterious strangers, sudden messages,
                  ominous portents or cliffhangers to keep the player engaged. Their interest is the
                  world itself.
                - Do not escalate. If the player is calm, let the scene stay calm. Never end a quiet turn
                  with a knock at the door, a scream, a hand on the shoulder or a phone ringing unless
                  the world's established state genuinely leads there.
                - Events arise only from three sources: something the player did, something already in
                  motion in the world state, or the ordinary business of the place - a delivery arriving,
                  a shop closing, rain starting, someone finishing their shift.
                - Put your energy into texture and presence: how the light falls, what the work feels
                  like in the hands, what the neighbours are arguing about, what is growing, what smells
                  of what, who nods at the player and who does not.
                - NPCs have their own ordinary lives and are glad to have small conversations that go
                  nowhere. Not every character wants something from the player.
                - Silence is allowed. If nothing is happening, say so beautifully rather than filling it.
            """.trimIndent()
            GENTLE -> """
                PACING: SLICE OF LIFE

                Keep the stakes human and close to the ground.

                - Conflict is interpersonal and small: a misunderstanding, a debt, an awkward favour, a
                  missed shift, a rivalry over something minor. Violence and catastrophe are rare and
                  land hard when they come.
                - Let scenes breathe. A conversation can be the whole turn.
                - Complications should be the kind that can be solved by talking, working or waiting.
                - The world's larger machinery exists, but it reaches the player slowly and indirectly.
            """.trimIndent()
            BALANCED -> """
                PACING: BALANCED

                Alternate pressure and rest.

                - Consequential turns should be earned, not constant. After something significant
                  happens, let the next turn settle before the world pushes again.
                - Quiet turns are legitimate and should be written richly rather than rushed through.
                - Escalate when the world state genuinely warrants it, not to hold attention.
            """.trimIndent()
            DRAMATIC -> """
                PACING: DRAMATIC

                Keep the world in motion and the player under pressure.

                - Something is always developing, and the player's choices have teeth.
                - End turns on live, unresolved situations. Let threats arrive and deadlines close.
                - Even here, consequence must follow from the established state. Drama is not licence to
                  invent events that contradict the world.
            """.trimIndent()
        }

    /** Shapes the kind of options offered each turn. */
    val choiceGuidance: String
        get() = when (this) {
            SANDBOX -> "Offer ordinary, concrete possibilities suited to the moment: places to go, " +
                "work to do, people to talk to, things to examine, ways to pass the time. At least one " +
                "option should be small and unhurried. Never present the options as a dilemma."
            GENTLE -> "Offer options about people and daily life: conversations to have, small tasks, " +
                "places to visit, things to notice. Keep at least one option low-stakes."
            BALANCED -> "Offer options that differ in kind - act, speak, observe, withdraw, improvise - " +
                "and include at least one that does not advance any plot."
            DRAMATIC -> "Offer options that differ in risk and approach, including one that is bold and " +
                "one that buys time."
        }

    /** How the very first scene should open. */
    val openingGuidance: String
        get() = when (this) {
            SANDBOX -> "Open quietly, in the middle of an ordinary moment. Establish the place, the " +
                "hour and the texture of the player's life here. Do not introduce a problem, a stranger, " +
                "a summons or a mystery. Give them somewhere to be and something they could do next."
            GENTLE -> "Open on an ordinary moment with one small, human thread already present - " +
                "someone waiting, a task half done, a conversation due."
            BALANCED -> "Open on an ordinary moment that has one live thing in it the player can engage " +
                "with or ignore."
            DRAMATIC -> "Open in motion, with something already underway that demands a response."
        }

    /** How the world is populated at creation time. */
    val buildGuidance: String
        get() = when (this) {
            SANDBOX -> "Threads must be ordinary, low-urgency business of the place - a harvest, a " +
                "repair, a festival being prepared, a rivalry simmering, someone's slow illness. Set " +
                "urgency to 1 or 2. No crises, no countdowns, no impending disasters, and nothing that " +
                "demands the player's involvement. The opening situation must be calm and everyday."
            GENTLE -> "Threads should be human-scale and personal, with urgency 1 to 3. Avoid " +
                "catastrophes; favour relationships, obligations and small local troubles."
            BALANCED -> "Mix ordinary business with one or two threads that carry real weight."
            DRAMATIC -> "At least two threads should be urgent and consequential, with deadlines."
        }

    /** Whether the simulator is allowed to press the player between turns. */
    val simulationPressure: String
        get() = when (this) {
            SANDBOX -> "These are the ordinary movements of a place going about its business. Use them " +
                "as texture and background, not as hooks. Do not turn any of them into an event that " +
                "demands the player's attention, and do not deliver them as news or interruptions."
            GENTLE -> "Let these surface gently, through people and routine rather than through alarm."
            BALANCED -> "Apply the ones that make sense and let the rest stay in the background."
            DRAMATIC -> "Press with these. The world should feel like it is moving faster than the player."
        }

    /** How hard the simulator pushes ongoing threads. */
    val maxThreadTicks: Int
        get() = when (this) {
            SANDBOX -> 2
            GENTLE -> 3
            BALANCED -> 5
            DRAMATIC -> 6
        }

    val quietWorld: Boolean get() = this == SANDBOX || this == GENTLE

    companion object {
        fun from(value: String?): PlayStyle =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: BALANCED
    }
}
