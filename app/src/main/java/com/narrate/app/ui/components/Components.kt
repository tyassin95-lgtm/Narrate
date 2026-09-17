package com.narrate.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.narrate.app.ui.theme.NarrateColors
import java.io.File

/** A poster-style tile. The building block of the browse experience. */
@Composable
fun PosterCard(
    title: String,
    subtitle: String,
    imagePath: String?,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    height: Dp = 216.dp,
    badge: String? = null,
    onClick: () -> Unit
) {
    Column(modifier = modifier.width(width).clickable(onClick = onClick)) {
        Box(
            Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .background(NarrateColors.SurfaceElevated)
        ) {
            if (imagePath != null && File(imagePath).exists()) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MonogramArt(title, Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            )
            badge?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(NarrateColors.Accent, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NarrateColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Deterministic art for anything not yet illustrated, so no tile is ever empty. */
@Composable
fun MonogramArt(seed: String, modifier: Modifier = Modifier) {
    val hash = seed.hashCode()
    val hue = ((hash % 360) + 360) % 360
    val top = Color.hsv(hue.toFloat(), 0.45f, 0.30f)
    val bottom = Color.hsv(((hue + 40) % 360).toFloat(), 0.55f, 0.12f)
    Box(
        modifier.background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            seed.trim().take(1).uppercase(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp),
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary)
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = NarrateColors.Accent,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

@Composable
fun <T> CardRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    if (items.isEmpty()) return
    Column(modifier) {
        SectionHeader(title, action = action, onAction = onAction)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items) { content(it) }
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) NarrateColors.Accent else NarrateColors.SurfaceHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else NarrateColors.TextSecondary
        )
    }
}

@Composable
fun NarrateTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(NarrateColors.Background)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NarrateColors.TextPrimary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = NarrateColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NarrateColors.Accent,
            contentColor = Color.White,
            disabledContainerColor = NarrateColors.SurfaceHigh,
            disabledContentColor = NarrateColors.TextMuted
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, NarrateColors.Divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NarrateColors.TextPrimary)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun NarrateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = 12,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = NarrateColors.TextMuted) },
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        minLines = minLines,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NarrateColors.Accent,
            unfocusedBorderColor = NarrateColors.Divider,
            focusedTextColor = NarrateColors.TextPrimary,
            unfocusedTextColor = NarrateColors.TextPrimary,
            focusedLabelColor = NarrateColors.Accent,
            unfocusedLabelColor = NarrateColors.TextMuted,
            cursorColor = NarrateColors.Accent,
            focusedContainerColor = NarrateColors.Surface,
            unfocusedContainerColor = NarrateColors.Surface
        )
    )
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = NarrateColors.TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PrimaryButton(actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun Avatar(name: String, imagePath: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(NarrateColors.SurfaceHigh), Alignment.Center) {
        if (imagePath != null && File(imagePath).exists()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = NarrateColors.TextSecondary
            )
        }
    }
}

/**
 * A square thumbnail for a world entity: its generated image if one exists, and its own
 * generated artwork if not, so nothing in the world is ever only a line of text.
 */
@Composable
fun Thumbnail(
    name: String,
    imagePath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    corner: Dp = 8.dp
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(NarrateColors.SurfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        if (imagePath != null && File(imagePath).exists()) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MonogramArt(name, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(NarrateColors.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, NarrateColors.Divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = NarrateColors.TextMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = NarrateColors.TextPrimary)
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = NarrateColors.Accent)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = NarrateColors.TextSecondary)
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message.isNullOrBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(NarrateColors.Accent.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .border(1.dp, NarrateColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = NarrateColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "DISMISS",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = NarrateColors.Accent,
            modifier = Modifier.clickable(onClick = onDismiss).padding(start = 10.dp)
        )
    }
}
