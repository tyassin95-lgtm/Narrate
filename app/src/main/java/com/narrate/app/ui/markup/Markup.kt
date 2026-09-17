package com.narrate.app.ui.markup

/**
 * The narrator's markup, parsed into blocks the renderer can style.
 *
 * Prose is prose, but a text message is not an email and neither is a letter - so each
 * in-world format becomes its own block and gets its own typography on screen.
 */
sealed class Block {
    data class Prose(val text: String) : Block()
    data object SceneBreak : Block()
    data class Comm(
        val kind: CommKind,
        val attributes: Map<String, String>,
        val body: String
    ) : Block()
}

enum class CommKind(val tag: String) {
    SMS("sms"),
    EMAIL("email"),
    CALL("call"),
    LETTER("letter"),
    DOCUMENT("document"),
    SIGN("sign"),
    BROADCAST("broadcast"),
    SYSTEM("system"),
    THOUGHT("thought"),
    JOURNAL("journal"),
    WHISPER("whisper");

    companion object {
        fun fromTag(tag: String): CommKind? = entries.firstOrNull { it.tag == tag.lowercase() }
    }
}

object MarkupParser {

    private val blockPattern = Regex(
        "\\[\\[\\s*(\\w+)([^\\]]*)]]([\\s\\S]*?)\\[\\[\\s*/\\s*\\1\\s*]]",
        RegexOption.IGNORE_CASE
    )
    private val attributePattern = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")

    private val anyTag = Regex("\\[\\[\\s*(/?)\\s*(\\w+)([^\\]]*)]]", RegexOption.IGNORE_CASE)

    /**
     * Repairs the two ways a model gets a block wrong before anything tries to read it.
     *
     * It opens a message and then, instead of closing it, opens the same block again -
     * `[[sms from="me"]] on my way [[sms from="me"]]` - or it opens one and never closes it at
     * all. Both used to fall through the block pattern entirely, and the text was flattened
     * into ordinary prose: the message the narrator wrote lost its phone.
     */
    fun repair(markup: String): String {
        if (!markup.contains("[[")) return markup
        val tags = anyTag.findAll(markup).toList()
        if (tags.isEmpty()) return markup
        val result = StringBuilder()
        var cursor = 0
        var openKind: String? = null

        tags.forEach { tag ->
            val closing = tag.groupValues[1] == "/"
            val kind = tag.groupValues[2].lowercase()
            if (CommKind.fromTag(kind) == null) return@forEach
            // A block starts on its own line. A tag dropped into the middle of a sentence is a
            // stray marker, not a message, and repairing it would turn half a sentence into a
            // text message - so it is left to be stripped as the noise it is.
            val startsLine = openKind != null || markup.take(tag.range.first).let {
                it.isBlank() || it.trimEnd(' ', '\t').endsWith("\n")
            }
            if (!startsLine) return@forEach
            result.append(markup, cursor, tag.range.first)
            cursor = tag.range.last + 1
            when {
                closing && kind == openKind -> {
                    openKind = null
                    result.append(tag.value)
                }
                closing -> Unit // A closer for something that was never opened: drop it.
                openKind == kind -> {
                    // The same block opened twice: the second one is where the first ended.
                    openKind = null
                    result.append("[[/$kind]]")
                }
                openKind != null -> {
                    // A different block opened inside an unclosed one. Close the old one first.
                    result.append("[[/$openKind]]")
                    openKind = kind
                    result.append(tag.value)
                }
                else -> {
                    openKind = kind
                    result.append(tag.value)
                }
            }
        }
        result.append(markup, cursor, markup.length)
        // Something still open at the end is closed where the text runs out.
        openKind?.let { result.append("[[/$it]]") }
        return result.toString()
    }

    fun parse(raw: String): List<Block> {
        if (raw.isBlank()) return emptyList()
        val markup = repair(raw)
        val blocks = mutableListOf<Block>()
        var cursor = 0

        blockPattern.findAll(markup).forEach { match ->
            val kind = CommKind.fromTag(match.groupValues[1]) ?: return@forEach
            if (match.range.first > cursor) {
                blocks += splitProse(markup.substring(cursor, match.range.first))
            }
            val attributes = attributePattern.findAll(match.groupValues[2])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            blocks += Block.Comm(kind, attributes, match.groupValues[3].trim())
            cursor = match.range.last + 1
        }
        if (cursor < markup.length) blocks += splitProse(markup.substring(cursor))
        return blocks.filterNot { it is Block.Prose && it.text.isBlank() }
    }

    /**
     * Paragraphs and scene breaks. Unclosed markup is stripped rather than shown raw.
     *
     * Models disagree about newlines: some separate paragraphs with a blank line, some with a
     * single one, and some hard-wrap their prose at a fixed width. Lines that look like a
     * wrapped continuation are rejoined, so real paragraph breaks are the only breaks left and
     * the renderer can space them generously.
     */
    private fun splitProse(raw: String): List<Block> {
        val cleaned = raw.replace(Regex("\\[\\[/?\\s*\\w+[^\\]]*]]"), "").trim()
        if (cleaned.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        cleaned.split(Regex("\n\\s*\n")).forEach { chunk ->
            if (chunk.isBlank()) return@forEach
            val paragraph = StringBuilder()

            fun flush() {
                val text = paragraph.toString().trim()
                if (text.isNotEmpty()) blocks += Block.Prose(text)
                paragraph.clear()
            }

            chunk.lines().forEach { line ->
                val text = line.trim()
                when {
                    text.isBlank() -> flush()
                    text.matches(sceneBreak) -> {
                        flush()
                        blocks += Block.SceneBreak
                    }
                    paragraph.isEmpty() -> paragraph.append(text)
                    continuesParagraph(paragraph.toString()) -> paragraph.append(' ').append(text)
                    else -> {
                        flush()
                        paragraph.append(text)
                    }
                }
            }
            flush()
        }
        return blocks
    }

    private val sceneBreak = Regex("^([-*_=]\\s*){3,}$")

    /** A long line that stops mid-sentence is a wrap, not the end of a paragraph. */
    private fun continuesParagraph(soFar: String): Boolean {
        val tail = soFar.trimEnd()
        if (tail.length < WRAP_WIDTH) return false
        val lastLineLength = tail.length - (tail.lastIndexOf('\n') + 1)
        if (lastLineLength < WRAP_WIDTH) return false
        return tail.last() !in sentenceEnders
    }

    // The typographic marks belong here for the same reason the straight ones do: a wrapped
    // line that ends on a closing curly quote has finished its sentence.
    private val sentenceEnders = setOf(
        '.', '!', '?', '"', ':', ';', ')', ']', '*', '_',
        '\u201d', '\u2019', '\u00bb', '\u2026'
    )

    private const val WRAP_WIDTH = 60

    /** Plain text for previews, search and image prompts. */
    fun stripMarkup(markup: String): String = markup
        .replace(Regex("\\[\\[/?\\s*\\w+[^\\]]*]]"), " ")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}
