package com.narrate.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** A cinema-dark palette: near-black canvas, one hot accent, everything else recedes. */
object NarrateColors {
    val Background = Color(0xFF07070A)
    val Surface = Color(0xFF121218)
    val SurfaceElevated = Color(0xFF1B1B23)
    val SurfaceHigh = Color(0xFF24242E)
    val Accent = Color(0xFFE50914)
    val AccentSoft = Color(0xFFFF3B47)
    val Gold = Color(0xFFE8B84B)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFA9A9B4)
    val TextMuted = Color(0xFF6E6E7A)
    val Divider = Color(0xFF2A2A34)

    // Each in-world communication format gets its own colour signature.
    val SmsIncoming = Color(0xFF26262F)
    val SmsOutgoing = Color(0xFF0B6E4F)
    val Email = Color(0xFF16202B)
    val EmailAccent = Color(0xFF5AA9E6)
    val Call = Color(0xFF11202A)
    val CallAccent = Color(0xFF4ED8A0)
    val Letter = Color(0xFF2A241A)
    val LetterAccent = Color(0xFFD9C08C)
    val Document = Color(0xFF1A1A1E)
    val DocumentAccent = Color(0xFFBFC3C9)
    val System = Color(0xFF13181F)
    val SystemAccent = Color(0xFF7BE0F5)
    val Broadcast = Color(0xFF241522)
    val BroadcastAccent = Color(0xFFE86AB0)
    val Journal = Color(0xFF1E1B16)
    val Thought = Color(0xFF15131C)
    val ThoughtAccent = Color(0xFF9B8CF0)
}

private val DarkScheme = darkColorScheme(
    primary = NarrateColors.Accent,
    onPrimary = Color.White,
    secondary = NarrateColors.Gold,
    background = NarrateColors.Background,
    onBackground = NarrateColors.TextPrimary,
    surface = NarrateColors.Surface,
    onSurface = NarrateColors.TextPrimary,
    surfaceVariant = NarrateColors.SurfaceElevated,
    onSurfaceVariant = NarrateColors.TextSecondary,
    outline = NarrateColors.Divider,
    error = NarrateColors.AccentSoft
)

private val NarrateTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 17.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp)
)

@Composable
fun NarrateTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NarrateColors.Background.toArgb()
            window.navigationBarColor = NarrateColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = DarkScheme, typography = NarrateTypography, content = content)
}
