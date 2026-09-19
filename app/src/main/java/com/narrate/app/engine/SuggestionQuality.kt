package com.narrate.app.engine

import com.narrate.app.core.AppJson
import com.narrate.app.data.repo.WorldSnapshot
import kotlinx.serialization.builtins.ListSerializer

/**
 * Whether a suggested action is worth a player's turn, and whether they have already had it.
 *
 * Two separate failures, with the same cause: the app judged suggestions by their wording.
 * A forty-one turn playthrough offered "Say goodnight and start back toward the grid" six
 * times in six different phrasings, asked three times in three phrasings what Liv had in mind
 * for Friday, and complimented the same glitter on the same cheek twice. Not one pair was a
 * string duplicate, so nothing caught any of it. Meanwhile half the menu was things nobody
 * would choose on purpose - sit down, stay quiet, check the time - offered because the app
 * insisted on four options whether or not the moment contained four decisions.
 *
 * So suggestions are judged by what they *mean*: which kind of move they are, and whether the
 * move changes anything. An option that would leave the world exactly as it found it is not a
 * choice, and a move the player has just made is not a new one however it is worded.
 */
object SuggestionQuality {

    /**
     * What kind of move an option is.
     *
     * Deliberately coarse. The question it has to answer is "have they just done this", and
     * for that, six ways of saying goodnight are one thing.
     */
    private val categories: List<Pair<String, Regex>> = listOf(
        "FAREWELL" to Regex(
            "\\b(say|says|saying)\\s+(goodnight|good night|goodbye|bye)\\b|\\bhead (?:back |off )?home\\b|" +
                "\\b(?:start|begin) (?:the )?(?:walk|walking) back\\b|\\bcall it a night\\b|\\bleave\\b.*\\bfor the night\\b",
            RegexOption.IGNORE_CASE
        ),
        "EXCHANGE_CONTACT" to Regex(
            "\\b(number|numbers|phone number|contact details)\\b.*\\b(ask|swap|exchange|give|get)\\b|" +
                "\\b(ask|swap|exchange|give|get)\\b.*\\b(number|numbers|phone number|contact details)\\b",
            RegexOption.IGNORE_CASE
        ),
        "MAKE_PLAN" to Regex(
            "\\b(meet|see you|see her|see him|get together|grab (?:a )?(?:coffee|drink|dinner)|go out)\\b" +
                ".*\\b(friday|saturday|sunday|monday|tuesday|wednesday|thursday|tomorrow|next week|again|sometime)\\b|" +
                "\\b(friday|tomorrow|next week)\\b.*\\b(work|works|good|suits|free)\\b|\\bmake a plan\\b",
            RegexOption.IGNORE_CASE
        ),
        "ASK_PLANS" to Regex(
            "\\bask\\b.*\\b(what|where|when)\\b.*\\b(in mind|planning|plans?|doing|do on|plan to)\\b|" +
                "\\bwhat (?:she|he|they) (?:usually )?(?:does|do|has in mind)\\b",
            RegexOption.IGNORE_CASE
        ),
        "ASK_WEEK" to Regex(
            "\\b(week|day|classes|shift|shifts|work|term|semester)\\b.*\\b(been|was|look|looked|like|going)\\b|" +
                "\\bask\\b.*\\babout (?:her|his|their) (?:week|day|classes|shift|job|work)\\b",
            RegexOption.IGNORE_CASE
        ),
        "ASK_MESSAGE" to Regex(
            "\\b(message|text|texts|phone|screen|notification)\\b.*\\b(about|bad news|worrying|who|what|everything (?:all )?right)\\b|" +
                "\\bask\\b.*\\b(message|text)\\b",
            RegexOption.IGNORE_CASE
        ),
        "COMPLIMENT" to Regex(
            "\\b(compliment|tell (?:her|him|them))\\b.*\\b(looks?|suits?|better|good|nice|beautiful|pretty|great)\\b|" +
                "\\b(glitter|dress|hair|smile|eyes|jacket|coat)\\b.*\\b(suits?|looks? (?:good|better|nice))\\b",
            RegexOption.IGNORE_CASE
        ),
        "SILENCE" to Regex(
            "\\b(say|saying) nothing\\b|\\bstay (?:quiet|silent)\\b|\\bin silence\\b|\\bwithout speaking\\b|" +
                "\\blet the silence\\b|\\bwait for (?:her|him|them) to (?:speak|fill)\\b|\\bdon'?t answer\\b",
            RegexOption.IGNORE_CASE
        ),
        "SIT" to Regex("\\b(sit|sits|sitting|sit down|take a seat)\\b", RegexOption.IGNORE_CASE),
        "STAND_STILL" to Regex(
            "\\b(stay|remain|keep) (?:standing|where|put|still)\\b|\\bstay a moment longer\\b|\\blinger\\b",
            RegexOption.IGNORE_CASE
        ),
        "WAIT" to Regex("^\\s*wait\\b|\\bwait (?:a|another|for|until|while)\\b", RegexOption.IGNORE_CASE),
        "CHECK_TIME" to Regex("\\b(check|glance at|look at)\\b.*\\b(time|clock|watch|phone)\\b", RegexOption.IGNORE_CASE),
        // Buying somebody a drink is a social move, not a drink. Checked before CONSUME,
        // because the words are the same and the meaning is the opposite.
        "SHARE" to Regex(
            "\\b(offer|offers|buy|buys|get|gets|bring|brings|pour|pours|make|makes|order|orders)\\b" +
                "[^.?!]{0,24}\\b(her|him|them|you|us|everyone)\\b|" +
                "\\boffer to (?:buy|get|bring|make)\\b",
            RegexOption.IGNORE_CASE
        ),
        "CONSUME" to Regex(
            "\\b(drink|sip|sips|taste|eat|eats|order|orders|buy)\\b.*\\b(coffee|tea|water|drink|beer|food|something)\\b|" +
                "\\btake a sip\\b|\\bmake (?:another |a )?(?:cup of )?coffee\\b",
            RegexOption.IGNORE_CASE
        ),
        "OBSERVE" to Regex(
            "\\b(look|glance|gaze|stare|watch|study|survey|take in)\\b\\s+(?:around|out|at the (?:room|street|sky|window|view)|the (?:room|street|surroundings))|" +
                "\\b(?:keep|continue|carry on|go on)\\s+(?:watching|looking|staring|listening|reading)\\b",
            RegexOption.IGNORE_CASE
        ),
        "OFFER_ITEM" to Regex(
            "\\b(offer|offers|give|gives|hand|hands|lend|lends|pass|put)\\b.*\\b(jacket|coat|scarf|umbrella|blanket|keys|phone|book)\\b",
            RegexOption.IGNORE_CASE
        ),
        "ITEM_BACK" to Regex("\\b(jacket|coat|scarf|keys|book|money)\\b.*\\bback\\b", RegexOption.IGNORE_CASE),
        "AFFECTION" to Regex(
            "\\b(kiss|kisses|hold her hand|hold his hand|take her hand|take his hand|hug|embrace|touch her|pull (?:her|him) close)\\b",
            RegexOption.IGNORE_CASE
        ),
        "PROPOSE_OUTING" to Regex(
            "\\b(ask|suggest|propose|invite)\\b.*\\b(walk|go|come|stop|head|grab|somewhere|inside|upstairs|out)\\b",
            RegexOption.IGNORE_CASE
        ),
        "TRAVEL" to Regex(
            "\\b(walk|head|go|drive|take the bus|set off|leave for|make (?:your|his) way)\\s+(?:to|toward|towards|over to|back to|into|down to)\\b",
            RegexOption.IGNORE_CASE
        ),
        "REPLY_MESSAGE" to Regex("\\b(reply|answer|respond|text back|write back|call (?:her|him|them) back)\\b", RegexOption.IGNORE_CASE),
        "CONFESS" to Regex(
            "\\b(tell (?:her|him|them) the truth|admit|confess|own up|come clean|tell (?:her|him|them) about)\\b",
            RegexOption.IGNORE_CASE
        ),
        "CONFRONT" to Regex("\\b(confront|accuse|press (?:her|him|them)|demand|push back|call (?:her|him|them) out)\\b", RegexOption.IGNORE_CASE),
        "SEARCH" to Regex("\\b(search|go through|look through|open the|rifle|read the|check the drawer)\\b", RegexOption.IGNORE_CASE),
        "WORK" to Regex("\\b(shift|patient|ward|rounds|study|revise|notes|textbook|assignment)\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * Which kind of move this is.
     *
     * Anything the list above does not recognise falls back to its own verb and object, so two
     * genuinely different actions never collide just because neither was anticipated.
     */
    fun categoryOf(label: String): String {
        val text = label.trim()
        categories.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
        val words = text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 3 && it !in stopWords }
        return "OTHER:" + words.take(2).sorted().joinToString("+").ifBlank { text.lowercase().take(12) }
    }

    private val stopWords = setOf(
        "tell", "them", "that", "with", "from", "this", "your", "about", "what", "when",
        "then", "just", "some", "like", "into", "over", "back", "here", "there", "they",
        "have", "been", "want", "would", "could", "still", "while", "after", "before"
    )

    /**
     * Things the player never has to be asked about, because the prose should simply do them.
     *
     * Kept as categories rather than phrasings: this is the list that was being worked around
     * by rewording, turn after turn.
     */
    private val chores = setOf(
        "SILENCE", "SIT", "STAND_STILL", "WAIT", "CHECK_TIME", "CONSUME", "OBSERVE"
    )

    /**
     * Whether an option would actually change anything.
     *
     * The gate is deliberately about consequence rather than about interest: an option earns
     * its place by moving the situation, a relationship, what somebody knows, where they are,
     * what they own, or what is going to happen. Anything that leaves the world as it found it
     * belongs in the narration.
     */
    fun meaningful(label: String, snapshot: WorldSnapshot): Boolean {
        // Anything the player would actually say out loud is a move. "Do you want to sit
        // down?" has the word "sit" in it and is an offer, not a chore.
        if (label.contains('"') || label.contains('\u201c')) return label.trim().length >= 8
        val category = categoryOf(label)
        if (category in chores) return consequential(label, snapshot)
        if (label.trim().length < 6) return false
        return true
    }

    /**
     * A chore with a reason behind it is not a chore.
     *
     * "Wait" is nothing. "Wait until his shift ends" is a decision about the evening, and
     * "sit down with her" is how a scene changes shape. What separates them is whether the
     * line names something the world is actually tracking.
     */
    private fun consequential(label: String, snapshot: WorldSnapshot): Boolean {
        val text = label.lowercase()
        if (Regex("\\buntil\\b|\\bso (?:that|you)\\b|\\bwhile\\b|\\bbefore (?:she|he|they|it)\\b").containsMatchIn(text)) {
            return true
        }
        val people = snapshot.npcs.mapNotNull { it.name.split(' ').firstOrNull()?.lowercase() }
            .filter { it.length >= 3 }
        return people.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) } &&
            Regex("\\b(with|beside|next to|and)\\b").containsMatchIn(text)
    }

    /** The categories of what the player has actually been doing lately. */
    fun recentCategories(snapshot: WorldSnapshot, turns: Int = 4): Set<String> =
        snapshot.recentTurns.takeLast(turns)
            .flatMap { turn ->
                val offered = runCatching {
                    AppJson.decodeFromString(ListSerializer(Choice.serializer()), turn.choicesJson)
                }.getOrDefault(emptyList())
                // What they did, plus what they were shown: an option refused twice running is
                // still an option they have seen twice running.
                listOfNotNull(turn.playerInput.takeIf { it.isNotBlank() }) + offered.map { it.label }
            }
            .map { categoryOf(it) }
            .toSet()

    /** Just what they chose, which is the stronger signal of "you have already done this". */
    fun recentlyDone(snapshot: WorldSnapshot, turns: Int = 6): Set<String> =
        snapshot.recentTurns.takeLast(turns)
            .map { it.playerInput }
            .filter { it.isNotBlank() }
            .map { categoryOf(it) }
            .toSet()
}
