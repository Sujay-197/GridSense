package com.gridsense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.gridsense.core.QualityLevel

// Every colour in the app is defined in this file. Screens use MaterialTheme roles, the
// quality tokens or the plan palette below, never their own literals.

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4F616E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF63597C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF1F1635),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAFE),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFE),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainer = Color(0xFFEAEEF2),
    surfaceContainerHigh = Color(0xFFE4E9ED),
    surfaceContainerHighest = Color(0xFFDFE3E7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84CFFF),
    onPrimary = Color(0xFF00344B),
    primaryContainer = Color(0xFF004C6C),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB6C9D8),
    onSecondary = Color(0xFF21333F),
    secondaryContainer = Color(0xFF374955),
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFFCDC0E9),
    onTertiary = Color(0xFF342B4B),
    tertiaryContainer = Color(0xFF4B4263),
    onTertiaryContainer = Color(0xFFE9DDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDEE3E7),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDEE3E7),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF252B2E),
    surfaceContainerHighest = Color(0xFF303539)
)

/** 4/8/12/16/24 dp spacing. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// --- signal quality ----------------------------------------------------------------------

/** Semantic quality colours, the same in light and dark so a map reads the same in both. */
object QualityColors {
    val excellent = Color(0xFF1B7F3B)
    val veryGood = Color(0xFF43A047)
    val good = Color(0xFF9CCC65)
    val fair = Color(0xFFFDD835)
    val weak = Color(0xFFFB8C00)
    val poor = Color(0xFFE53935)
    val noData = Color(0xFF9E9E9E)

    /** Worst first, as the relative ramp runs from 0 to 1. */
    val ramp = listOf(poor, weak, fair, good, veryGood, excellent)
}

fun qualityColor(level: QualityLevel?): Color = when (level) {
    QualityLevel.EXCELLENT -> QualityColors.excellent
    QualityLevel.VERY_GOOD -> QualityColors.veryGood
    QualityLevel.GOOD -> QualityColors.good
    QualityLevel.FAIR -> QualityColors.fair
    QualityLevel.WEAK -> QualityColors.weak
    QualityLevel.POOR -> QualityColors.poor
    null -> QualityColors.noData
}

/** Packed ARGB of a level's colour, for raster pixels. */
fun qualityArgb(level: QualityLevel?, alpha: Int = 255): Int =
    qualityColor(level).copy(alpha = alpha / 255f).toArgb()

/** The relative-scale ramp: 0 (worst in this room) through the quality colours to 1 (best). */
fun qualityRampColor(q: Double): Color {
    val stops = QualityColors.ramp
    val t = q.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = t.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], (t - i).toFloat())
}

fun qualityRampArgb(q: Double, alpha: Int = 255): Int =
    qualityRampColor(q).copy(alpha = alpha / 255f).toArgb()

/**
 * Diverging scale for difference maps: orange for worse in B, blue for better, a neutral grey at
 * zero. Blue and orange stay distinct for the common forms of colour blindness.
 */
object DiffColors {
    val worse = Color(0xFFD95F02)
    val neutral = Color(0xFFE6E6E6)
    val better = Color(0xFF2166AC)
}

/** [t] from -1 (much worse in B) through 0 to 1 (much better in B). */
fun diffColor(t: Double): Color {
    val c = t.coerceIn(-1.0, 1.0).toFloat()
    return if (c < 0) lerp(DiffColors.neutral, DiffColors.worse, -c) else lerp(DiffColors.neutral, DiffColors.better, c)
}

fun diffArgb(t: Double, alpha: Int = 255): Int = diffColor(t).copy(alpha = alpha / 255f).toArgb()

// --- plan drawing ------------------------------------------------------------------------

/** Colours for the room plan, grid and heatmap canvases. */
data class PlanColors(
    val background: Color,
    val outline: Color,
    val gridLine: Color,
    val pending: Color,
    val disabled: Color,
    val logging: Color,
    val loggingHalo: Color,
    val doneRing: Color,
    val start: Color,
    val router: Color,
    val routerEdge: Color,
    val routerLabel: Color,
    val label: Color,
    val pointDot: Color,
    val pointRing: Color
)

/** Also used for every exported PNG, so exports look the same whatever the phone's theme. */
val LightPlanColors = PlanColors(
    background = Color(0xFFFFFFFF),
    outline = Color(0xFF263238),
    gridLine = Color(0x33263238),
    pending = Color(0xFF9E9E9E),
    disabled = Color(0xFFB0BEC5),
    logging = Color(0xFF1976D2),
    loggingHalo = Color(0x442196F3),
    doneRing = Color(0xFF263238),
    start = Color(0xFF2E7D32),
    router = Color(0xFF1565C0),
    routerEdge = Color(0xFFFFFFFF),
    routerLabel = Color(0xFF0D47A1),
    label = Color(0xFF263238),
    pointDot = Color(0xFF263238),
    pointRing = Color(0xFFFFFFFF)
)

val DarkPlanColors = PlanColors(
    background = Color(0xFF171C1F),
    outline = Color(0xFFDEE3E7),
    gridLine = Color(0x40DEE3E7),
    pending = Color(0xFF8B9198),
    disabled = Color(0xFF4F5559),
    logging = Color(0xFF84CFFF),
    loggingHalo = Color(0x5584CFFF),
    doneRing = Color(0xFFDEE3E7),
    start = Color(0xFF81C784),
    router = Color(0xFF64B5F6),
    routerEdge = Color(0xFF0F1417),
    routerLabel = Color(0xFFBBDEFB),
    label = Color(0xFFDEE3E7),
    pointDot = Color(0xFF0F1417),
    pointRing = Color(0xFFDEE3E7)
)

private val LocalPlanColors = staticCompositionLocalOf { LightPlanColors }

object GridSenseTheme {
    val plan: PlanColors
        @Composable get() = LocalPlanColors.current
}

@Composable
fun GridSenseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = AppShapes
    ) {
        CompositionLocalProvider(
            LocalPlanColors provides if (darkTheme) DarkPlanColors else LightPlanColors,
            content = content
        )
    }
}
