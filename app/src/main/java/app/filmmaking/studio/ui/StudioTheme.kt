package app.filmmaking.studio.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dualframe.camera.R

/**
 * The web app's single cinematic dark theme (screenshot-match `src/styles.css`, DESIGN_SYSTEM.md):
 * graphite surfaces, warm ivory text, muted amber accent. Values are the web's oklch tokens converted to sRGB.
 */
object StudioColors {
    val Background = Color(0xFF110F0D)
    val Foreground = Color(0xFFF1EADC)
    val Surface = Color(0xFF1A1614)
    val SurfaceRaised = Color(0xFF221E1C)
    val Card = Color(0xFF1C1916)
    val Popover = Color(0xFF201C19)
    val Primary = Color(0xFFDAA75D)        // also --gold
    val PrimaryForeground = Color(0xFF15110D)
    val GoldSoft = Color(0x24DAA75D)
    val Secondary = Color(0xFF292623)
    val SecondaryForeground = Color(0xFFE4DDCF)
    val Muted = Color(0xFF272321)
    val MutedForeground = Color(0xFF9E978E)
    val Accent = Color(0xFF302A25)
    val Destructive = Color(0xFFD95544)
    val DestructiveForeground = Color(0xFFF5F1EA)
    val Success = Color(0xFF75B683)
    val Border = Color(0x17F1EADC)
    val Input = Color(0x1FF1EADC)
    val Ring = Color(0x99DAA75D)
    val GoldGradient = Brush.linearGradient(listOf(Color(0xFFE3B667), Color(0xFFC77F3E)))
    val Scrim = Brush.verticalGradient(0.3f to Color(0x00110F0D), 1f to Color(0xEB110F0D))
}

object StudioFonts {
    /** Titles and the writing editor. */
    val Display = FontFamily(
        Font(R.font.studio_fraunces_400, FontWeight.Normal),
        Font(R.font.studio_fraunces_500, FontWeight.Medium),
        Font(R.font.studio_fraunces_600, FontWeight.SemiBold),
    )
    /** Everything in the interface. */
    val Sans = FontFamily(
        Font(R.font.studio_manrope_400, FontWeight.Normal),
        Font(R.font.studio_manrope_500, FontWeight.Medium),
        Font(R.font.studio_manrope_600, FontWeight.SemiBold),
        Font(R.font.studio_manrope_700, FontWeight.Bold),
    )
    /** Metadata, codes and the eyebrow labels. */
    val Mono = FontFamily(
        Font(R.font.studio_jetbrainsmono_400, FontWeight.Normal),
        Font(R.font.studio_jetbrainsmono_500, FontWeight.Medium),
    )
}

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = StudioFonts.Display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.01).em)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = StudioFonts.Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val StudioTypography = Typography(
    displayLarge = display(36, 42), displayMedium = display(32, 38), displaySmall = display(30, 36),
    headlineLarge = display(30, 36), headlineMedium = display(26, 32), headlineSmall = display(22, 28),
    titleLarge = display(20, 25),
    titleMedium = sans(16, 22, FontWeight.SemiBold), titleSmall = sans(14, 20, FontWeight.SemiBold),
    bodyLarge = sans(16, 24), bodyMedium = sans(14, 20), bodySmall = sans(12, 16),
    labelLarge = sans(14, 20, FontWeight.SemiBold), labelMedium = sans(12, 16, FontWeight.Medium), labelSmall = sans(11, 16, FontWeight.Medium),
)

/** The web's `eyebrow` utility: mono, 0.66rem, uppercase, 0.14em tracking, muted. Uppercase the text at the call site. */
val EyebrowStyle = TextStyle(fontFamily = StudioFonts.Mono, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.14.em, color = StudioColors.MutedForeground)
val MonoStyle = TextStyle(fontFamily = StudioFonts.Mono, fontSize = 14.sp, lineHeight = 20.sp)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),   // --radius-sm
    medium = RoundedCornerShape(12.dp),  // --radius-md (fields)
    large = RoundedCornerShape(18.dp),   // rounded-xl (cards)
    extraLarge = RoundedCornerShape(24.dp), // rounded-3xl (sheets)
)

private val StudioScheme = darkColorScheme(
    primary = StudioColors.Primary, onPrimary = StudioColors.PrimaryForeground,
    primaryContainer = StudioColors.GoldSoft, onPrimaryContainer = StudioColors.Primary,
    secondary = StudioColors.SecondaryForeground, onSecondary = StudioColors.Background,
    secondaryContainer = StudioColors.GoldSoft, onSecondaryContainer = StudioColors.Primary,
    tertiary = StudioColors.Success, onTertiary = StudioColors.PrimaryForeground,
    tertiaryContainer = StudioColors.Accent, onTertiaryContainer = StudioColors.Foreground,
    background = StudioColors.Background, onBackground = StudioColors.Foreground,
    surface = StudioColors.Background, onSurface = StudioColors.Foreground,
    surfaceVariant = StudioColors.Muted, onSurfaceVariant = StudioColors.MutedForeground,
    surfaceTint = Color.Transparent,
    inverseSurface = StudioColors.Foreground, inverseOnSurface = StudioColors.Background, inversePrimary = StudioColors.PrimaryForeground,
    error = StudioColors.Destructive, onError = StudioColors.DestructiveForeground,
    errorContainer = Color(0x26D95544), onErrorContainer = StudioColors.Destructive,
    outline = Color(0x33F1EADC), outlineVariant = StudioColors.Border, scrim = Color(0xB3110F0D),
    surfaceBright = StudioColors.SurfaceRaised, surfaceDim = StudioColors.Background,
    surfaceContainerLowest = StudioColors.Background, surfaceContainerLow = StudioColors.Surface,
    surfaceContainer = StudioColors.Popover, surfaceContainerHigh = StudioColors.Popover,
    surfaceContainerHighest = StudioColors.Card,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = StudioScheme, typography = StudioTypography, shapes = StudioShapes) {
        // Ivory text by default everywhere, not only inside a Surface (Material's default content colour is black).
        CompositionLocalProvider(LocalContentColor provides StudioColors.Foreground, content = content)
    }
