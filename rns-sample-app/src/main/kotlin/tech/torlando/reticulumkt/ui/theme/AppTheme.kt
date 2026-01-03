package tech.torlando.reticulumkt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Preset built-in themes for the Reticulum sample app.
 */
enum class PresetTheme(
    val displayName: String,
    val description: String,
) {
    VIBRANT(
        displayName = "Vibrant",
        description = "Purple-pink gradient matching Columba",
    ),
    DYNAMIC(
        displayName = "Dynamic",
        description = "Extracts colors from your device wallpaper",
    ),
    OCEAN(
        displayName = "Ocean",
        description = "Cool blue tones for clarity and focus",
    ),
    FOREST(
        displayName = "Forest",
        description = "Natural green tones, easy on the eyes",
    ),
    SUNSET(
        displayName = "Sunset",
        description = "Warm orange-red tones for energy",
    ),
    MONOCHROME(
        displayName = "Monochrome",
        description = "Grayscale minimalism for professionals",
    ),
    OLED_BLACK(
        displayName = "OLED Black",
        description = "True black for OLED screens - maximum battery savings",
    );

    fun getColorScheme(isDarkTheme: Boolean): ColorScheme {
        return when (this) {
            VIBRANT -> if (isDarkTheme) vibrantDarkScheme else vibrantLightScheme
            OCEAN -> if (isDarkTheme) oceanDarkScheme else oceanLightScheme
            FOREST -> if (isDarkTheme) forestDarkScheme else forestLightScheme
            SUNSET -> if (isDarkTheme) sunsetDarkScheme else sunsetLightScheme
            MONOCHROME -> if (isDarkTheme) monochromeDarkScheme else monochromeLightScheme
            OLED_BLACK -> if (isDarkTheme) oledBlackDarkScheme else oledBlackLightScheme
            DYNAMIC -> if (isDarkTheme) vibrantDarkScheme else vibrantLightScheme // Fallback
        }
    }

    fun getPreviewColors(isDarkTheme: Boolean): Triple<Color, Color, Color> {
        return when (this) {
            VIBRANT -> if (isDarkTheme) Triple(Purple80, PurpleGrey80, Pink80) else Triple(Purple40, PurpleGrey40, Pink40)
            OCEAN -> if (isDarkTheme) Triple(OceanBlue80, OceanTeal80, OceanCyan80) else Triple(OceanBlue40, OceanTeal40, OceanCyan40)
            FOREST -> if (isDarkTheme) Triple(ForestGreen80, ForestEmerald80, ForestSage80) else Triple(ForestGreen40, ForestEmerald40, ForestSage40)
            SUNSET -> if (isDarkTheme) Triple(SunsetOrange80, SunsetRed80, SunsetCoral80) else Triple(SunsetOrange40, SunsetRed40, SunsetCoral40)
            MONOCHROME -> if (isDarkTheme) Triple(MonoGray80, MonoSlate80, MonoCharcoal80) else Triple(MonoGray40, MonoSlate40, MonoCharcoal40)
            OLED_BLACK -> if (isDarkTheme) Triple(OledPrimary80, OledSecondary80, OledTertiary80) else Triple(OledPrimary40, OledSecondary40, OledTertiary40)
            DYNAMIC -> if (isDarkTheme) Triple(DynamicPrimary80, DynamicSecondary80, DynamicTertiary80) else Triple(DynamicPrimary40, DynamicSecondary40, DynamicTertiary40)
        }
    }
}

// Vibrant theme (default)
private val vibrantDarkScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = VibrantOnPrimary80,
    primaryContainer = VibrantContainer80,
    onPrimaryContainer = VibrantOnContainer80,
    secondary = PurpleGrey80,
    onSecondary = VibrantOnSecondary80,
    tertiary = Pink80,
    onTertiary = VibrantOnTertiary80,
    surfaceVariant = VibrantSurface80,
    outline = VibrantOutline80,
    outlineVariant = VibrantOutline80,
)

private val vibrantLightScheme = lightColorScheme(
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = VibrantContainer40,
    onPrimaryContainer = VibrantOnContainer40,
    secondary = PurpleGrey40,
    onSecondary = Color.White,
    tertiary = Pink40,
    onTertiary = Color.White,
    surfaceVariant = VibrantSurface40,
    outline = VibrantOutline40,
    outlineVariant = VibrantOutline40,
)

// Ocean theme
private val oceanDarkScheme = darkColorScheme(
    primary = OceanBlue80,
    onPrimary = OceanOnPrimary80,
    primaryContainer = OceanContainer80,
    onPrimaryContainer = OceanOnContainer80,
    secondary = OceanTeal80,
    onSecondary = OceanOnSecondary80,
    tertiary = OceanCyan80,
    onTertiary = OceanOnTertiary80,
    surfaceVariant = OceanSurface80,
    outline = OceanOutline80,
    outlineVariant = OceanOutline80,
)

private val oceanLightScheme = lightColorScheme(
    primary = OceanBlue40,
    onPrimary = Color.White,
    primaryContainer = OceanContainer40,
    onPrimaryContainer = OceanOnContainer40,
    secondary = OceanTeal40,
    onSecondary = Color.White,
    tertiary = OceanCyan40,
    onTertiary = Color.White,
    surfaceVariant = OceanSurface40,
    outline = OceanOutline40,
    outlineVariant = OceanOutline40,
)

// Forest theme
private val forestDarkScheme = darkColorScheme(
    primary = ForestGreen80,
    onPrimary = ForestOnPrimary80,
    primaryContainer = ForestContainer80,
    onPrimaryContainer = ForestOnContainer80,
    secondary = ForestEmerald80,
    onSecondary = ForestOnSecondary80,
    tertiary = ForestSage80,
    onTertiary = ForestOnTertiary80,
    surfaceVariant = ForestSurface80,
    outline = ForestOutline80,
    outlineVariant = ForestOutline80,
)

private val forestLightScheme = lightColorScheme(
    primary = ForestGreen40,
    onPrimary = Color.White,
    primaryContainer = ForestContainer40,
    onPrimaryContainer = ForestOnContainer40,
    secondary = ForestEmerald40,
    onSecondary = Color.White,
    tertiary = ForestSage40,
    onTertiary = Color.White,
    surfaceVariant = ForestSurface40,
    outline = ForestOutline40,
    outlineVariant = ForestOutline40,
)

// Sunset theme
private val sunsetDarkScheme = darkColorScheme(
    primary = SunsetOrange80,
    onPrimary = SunsetOnPrimary80,
    primaryContainer = SunsetContainer80,
    onPrimaryContainer = SunsetOnContainer80,
    secondary = SunsetRed80,
    onSecondary = SunsetOnSecondary80,
    tertiary = SunsetCoral80,
    onTertiary = SunsetOnTertiary80,
    surfaceVariant = SunsetSurface80,
    outline = SunsetOutline80,
    outlineVariant = SunsetOutline80,
)

private val sunsetLightScheme = lightColorScheme(
    primary = SunsetOrange40,
    onPrimary = Color.White,
    primaryContainer = SunsetContainer40,
    onPrimaryContainer = SunsetOnContainer40,
    secondary = SunsetRed40,
    onSecondary = Color.White,
    tertiary = SunsetCoral40,
    onTertiary = Color.White,
    surfaceVariant = SunsetSurface40,
    outline = SunsetOutline40,
    outlineVariant = SunsetOutline40,
)

// Monochrome theme
private val monochromeDarkScheme = darkColorScheme(
    primary = MonoGray80,
    onPrimary = MonoOnPrimary80,
    primaryContainer = MonoContainer80,
    onPrimaryContainer = MonoOnContainer80,
    secondary = MonoSlate80,
    onSecondary = MonoOnSecondary80,
    tertiary = MonoCharcoal80,
    onTertiary = MonoOnTertiary80,
    surfaceVariant = MonoSurface80,
    outline = MonoOutline80,
    outlineVariant = MonoOutline80,
)

private val monochromeLightScheme = lightColorScheme(
    primary = MonoGray40,
    onPrimary = Color.White,
    primaryContainer = MonoContainer40,
    onPrimaryContainer = MonoOnContainer40,
    secondary = MonoSlate40,
    onSecondary = Color.White,
    tertiary = MonoCharcoal40,
    onTertiary = Color.White,
    surfaceVariant = MonoSurface40,
    outline = MonoOutline40,
    outlineVariant = MonoOutline40,
)

// OLED Black theme - True black for OLED screens
private val oledBlackDarkScheme = darkColorScheme(
    primary = OledPrimary80,
    onPrimary = OledOnPrimary80,
    primaryContainer = OledContainer80,
    onPrimaryContainer = OledOnContainer80,
    secondary = OledSecondary80,
    onSecondary = OledOnSecondary80,
    tertiary = OledTertiary80,
    onTertiary = OledOnTertiary80,
    background = OledSurfaceDim80,
    onBackground = OledOnSurface80,
    surface = OledSurfaceDim80,
    onSurface = OledOnSurface80,
    surfaceVariant = OledSurface80,
    onSurfaceVariant = OledOnSurface80,
    surfaceTint = OledPrimary80,
    surfaceDim = OledSurfaceDim80,
    surfaceBright = OledSurfaceBright80,
    surfaceContainerLowest = OledSurfaceContainerLowest80,
    surfaceContainerLow = OledSurfaceContainerLow80,
    surfaceContainer = OledSurfaceContainer80,
    surfaceContainerHigh = OledSurfaceContainerHigh80,
    surfaceContainerHighest = OledSurfaceContainerHighest80,
)

private val oledBlackLightScheme = lightColorScheme(
    primary = OledPrimary40,
    onPrimary = Color.White,
    primaryContainer = OledContainer40,
    onPrimaryContainer = OledOnContainer40,
    secondary = OledSecondary40,
    onSecondary = Color.White,
    secondaryContainer = OledSecondaryContainer40,
    onSecondaryContainer = OledOnSecondaryContainer40,
    tertiary = OledTertiary40,
    onTertiary = Color.White,
    background = OledSurface40,
    onBackground = OledOnContainer40,
    surface = OledSurface40,
    onSurface = OledOnContainer40,
    surfaceVariant = OledContainer40,
    onSurfaceVariant = OledOnContainer40,
    outline = OledOutline40,
)
