package com.narrate.app.ui.markup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.narrate.app.ui.theme.NarrateColors

/**
 * Renders narration. Prose reads like a novel; every in-world format is visually
 * unmistakable at a glance, which is most of what makes the world feel like a place.
 */
@Composable
fun RichNarration(
    markup: String,
    modifier: Modifier = Modifier,
    proseColor: Color = NarrateColors.TextPrimary
) {
    val blocks = remember(markup) { MarkupParser.parse(markup) }
    // Long-form prose needs air: paragraphs are set well apart, and an in-world block
    // (a text message, a letter) gets more still, so it reads as an interruption.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            if (block is Block.Comm || previous is Block.Comm) Spacer(Modifier.height(6.dp))
            when (block) {
                is Block.Prose -> Text(
                    text = inlineStyled(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = proseColor
                )
                Block.SceneBreak -> SceneBreak()
                is Block.Comm -> CommBlock(block)
            }
        }
    }
}

@Composable
private fun SceneBreak() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(NarrateColors.Divider))
        Text(
            "* * *",
            modifier = Modifier.padding(horizontal = 14.dp),
            color = NarrateColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
        Box(Modifier.weight(1f).height(1.dp).background(NarrateColors.Divider))
    }
}

/** **bold**, *italic*, and "spoken dialogue" all get their own weight on the page. */
fun inlineStyled(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
    val quote = Regex("\"([^\"]{1,400})\"")

    data class Mark(val range: IntRange, val style: SpanStyle, val content: String)

    val marks = mutableListOf<Mark>()
    bold.findAll(text).forEach {
        marks += Mark(it.range, SpanStyle(fontWeight = FontWeight.Bold, color = NarrateColors.TextPrimary), it.groupValues[1])
    }
    italic.findAll(text).forEach { match ->
        if (marks.none { match.range.first in it.range }) {
            marks += Mark(match.range, SpanStyle(fontStyle = FontStyle.Italic), match.groupValues[1])
        }
    }
    quote.findAll(text).forEach { match ->
        if (marks.none { match.range.first in it.range }) {
            marks += Mark(
                match.range,
                SpanStyle(color = NarrateColors.Gold, fontWeight = FontWeight.Medium),
                "\"" + match.groupValues[1] + "\""
            )
        }
    }
    marks.sortBy { it.range.first }

    marks.forEach { mark ->
        if (mark.range.first < index) return@forEach
        append(text.substring(index, mark.range.first))
        withStyle(mark.style) { append(mark.content) }
        index = mark.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

@Composable
private fun CommBlock(block: Block.Comm) {
    when (block.kind) {
        CommKind.SMS -> SmsBubble(block)
        CommKind.EMAIL -> EmailCard(block)
        CommKind.CALL -> CallCard(block)
        CommKind.LETTER -> LetterCard(block)
        CommKind.DOCUMENT -> DocumentCard(block)
        CommKind.SIGN -> SignCard(block)
        CommKind.BROADCAST -> BroadcastCard(block)
        CommKind.SYSTEM -> SystemCard(block)
        CommKind.THOUGHT -> ThoughtBlock(block)
        CommKind.JOURNAL -> JournalCard(block)
        CommKind.WHISPER -> WhisperBlock(block)
    }
}

@Composable
private fun SmsBubble(block: Block.Comm) {
    val sender = block.attributes["from"].orEmpty()
    val outgoing = sender.equals("me", true) || sender.equals("you", true) || sender.isBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            if (!outgoing && sender.isNotBlank()) {
                Text(
                    sender.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NarrateColors.TextMuted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 3.dp)
                )
            }
            Box(
                Modifier
                    .background(
                        if (outgoing) NarrateColors.SmsOutgoing else NarrateColors.SmsIncoming,
                        RoundedCornerShape(
                            topStart = 18.dp, topEnd = 18.dp,
                            bottomStart = if (outgoing) 18.dp else 4.dp,
                            bottomEnd = if (outgoing) 4.dp else 18.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    block.body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = NarrateColors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun EmailCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Email, RoundedCornerShape(10.dp))
            .border(1.dp, NarrateColors.EmailAccent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(NarrateColors.EmailAccent.copy(alpha = 0.12f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Email, null, tint = NarrateColors.EmailAccent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                block.attributes["subject"].orEmpty().ifBlank { "(no subject)" },
                style = MaterialTheme.typography.titleMedium,
                color = NarrateColors.TextPrimary
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            MonoLine("From: ${block.attributes["from"].orEmpty()}")
            block.attributes["to"]?.let { MonoLine("To: $it") }
            block.attributes["date"]?.let { MonoLine("Date: $it") }
            Spacer(Modifier.height(8.dp))
            Text(
                block.body,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = NarrateColors.TextSecondary
            )
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = NarrateColors.TextMuted
    )
}

@Composable
private fun CallCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Call, RoundedCornerShape(14.dp))
            .border(1.dp, NarrateColors.CallAccent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Call, null, tint = NarrateColors.CallAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                (block.attributes["status"]?.uppercase() ?: "CALL") + " - " + block.attributes["with"].orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.CallAccent
            )
        }
        Spacer(Modifier.height(10.dp))
        block.body.lines().filter { it.isNotBlank() }.forEach { line ->
            Text(
                inlineStyled(line.trim()),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = NarrateColors.TextPrimary,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun LetterCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Letter, RoundedCornerShape(4.dp))
            .border(1.dp, NarrateColors.LetterAccent.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(18.dp)
    ) {
        Text(
            block.body,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 16.sp,
                lineHeight = 26.sp
            ),
            color = NarrateColors.LetterAccent
        )
        block.attributes["from"]?.takeIf { it.isNotBlank() }?.let { from ->
            Spacer(Modifier.height(12.dp))
            Text(
                "- $from",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Cursive, fontSize = 17.sp),
                color = NarrateColors.LetterAccent,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun DocumentCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Document, RoundedCornerShape(6.dp))
            .border(1.dp, NarrateColors.DocumentAccent.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = NarrateColors.DocumentAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                block.attributes["title"].orEmpty().ifBlank { "DOCUMENT" }.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = NarrateColors.DocumentAccent
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            block.body,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
            color = NarrateColors.TextSecondary
        )
    }
}

@Composable
private fun SignCard(block: Block.Comm) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(2.dp, NarrateColors.TextSecondary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            block.body.uppercase(),
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Black),
            color = NarrateColors.TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BroadcastCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Broadcast, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Campaign, null, tint = NarrateColors.BroadcastAccent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                block.attributes["source"].orEmpty().ifBlank { "BROADCAST" }.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                color = NarrateColors.BroadcastAccent
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            block.body,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic),
            color = NarrateColors.TextPrimary
        )
    }
}

@Composable
private fun SystemCard(block: Block.Comm) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.System, RoundedCornerShape(6.dp))
            .border(1.dp, NarrateColors.SystemAccent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Icon(Icons.Default.Terminal, null, tint = NarrateColors.SystemAccent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            block.body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            color = NarrateColors.SystemAccent
        )
    }
}

@Composable
private fun ThoughtBlock(block: Block.Comm) {
    Row(Modifier.fillMaxWidth().background(NarrateColors.Thought, RoundedCornerShape(8.dp)).padding(2.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(NarrateColors.ThoughtAccent))
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 12.dp)) {
            Icon(Icons.Default.Psychology, null, tint = NarrateColors.ThoughtAccent.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                block.body,
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 25.sp),
                color = NarrateColors.ThoughtAccent.copy(alpha = 0.92f)
            )
        }
    }
}

@Composable
private fun JournalCard(block: Block.Comm) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Journal, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MenuBook, null, tint = NarrateColors.LetterAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                block.attributes["title"].orEmpty().ifBlank { "ENTRY" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NarrateColors.LetterAccent.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            block.body,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp),
            color = NarrateColors.TextSecondary
        )
    }
}

@Composable
private fun WhisperBlock(block: Block.Comm) {
    Text(
        block.body,
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, fontSize = 13.sp, letterSpacing = 0.5.sp),
        color = NarrateColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    )
}
