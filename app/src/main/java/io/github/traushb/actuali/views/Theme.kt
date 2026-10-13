package io.github.traushb.actuali.views

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.traushb.actuali.models.AppearanceMode
import io.github.traushb.actuali.services.BudgetStore

/** The app's single source of truth, as SwiftUI's `@EnvironmentObject BudgetStore`. */
val LocalBudgetStore = staticCompositionLocalOf<BudgetStore> { error("No BudgetStore provided") }

/**
 * Material's Vibrant scheme (the 2025 Expressive spec) from a coral-orange
 * seed, as the Material color utilities generate it — every role from its
 * tonal palettes, nothing picked by hand. One deviation: `background`, which
 * Scaffold paints, takes surfaceContainer's tone, the page Expressive's
 * segmented lists sit on; their items take `surface`.
 */
private val WarmLight = lightColorScheme(
    primary = Color(0xFFA13900),
    onPrimary = Color(0xFFFFEFEA),
    primaryContainer = Color(0xFFFF793E),
    onPrimaryContainer = Color(0xFF431300),
    inversePrimary = Color(0xFFF3631C),
    secondary = Color(0xFF944500),
    onSecondary = Color(0xFFFFF0E9),
    secondaryContainer = Color(0xFFFFC5A4),
    onSecondaryContainer = Color(0xFF753500),
    tertiary = Color(0xFF7D5300),
    onTertiary = Color(0xFFFFF0E1),
    tertiaryContainer = Color(0xFFFAAC21),
    onTertiaryContainer = Color(0xFF4F3300),
    background = Color(0xFFFFE3D4),
    onBackground = Color(0xFF4B2409),
    surface = Color(0xFFFFF4F0),
    onSurface = Color(0xFF4B2409),
    surfaceVariant = Color(0xFFFFD4BC),
    onSurfaceVariant = Color(0xFF805031),
    surfaceTint = Color(0xFFA13900),
    inverseSurface = Color(0xFF1D0800),
    inverseOnSurface = Color(0xFFCB906C),
    error = Color(0xFFB31B25),
    onError = Color(0xFFFFEFEE),
    errorContainer = Color(0xFFFB5151),
    onErrorContainer = Color(0xFF570008),
    outline = Color(0xFFA06B4A),
    outlineVariant = Color(0xFFDDA07B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF4F0),
    surfaceContainer = Color(0xFFFFE3D4),
    surfaceContainerHigh = Color(0xFFFFDBC8),
    surfaceContainerHighest = Color(0xFFFFD4BC),
    surfaceContainerLow = Color(0xFFFFEDE5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFFFC9AA),
    primaryFixed = Color(0xFFFF793E),
    primaryFixedDim = Color(0xFFF7661F),
    onPrimaryFixed = Color(0xFF000000),
    onPrimaryFixedVariant = Color(0xFF521900),
    secondaryFixed = Color(0xFFFFC5A4),
    secondaryFixedDim = Color(0xFFFFB284),
    onSecondaryFixed = Color(0xFF582600),
    onSecondaryFixedVariant = Color(0xFF833C00),
    tertiaryFixed = Color(0xFFFAAC21),
    tertiaryFixedDim = Color(0xFFEA9F0C),
    onTertiaryFixed = Color(0xFF331F00),
    onTertiaryFixedVariant = Color(0xFF5B3B00),
)

private val WarmDark = darkColorScheme(
    primary = Color(0xFFFF9062),
    onPrimary = Color(0xFF561B00),
    primaryContainer = Color(0xFFFF793E),
    onPrimaryContainer = Color(0xFF431300),
    inversePrimary = Color(0xFFA73C00),
    secondary = Color(0xFFFE8E41),
    onSecondary = Color(0xFF4C2000),
    secondaryContainer = Color(0xFF994700),
    onSecondaryContainer = Color(0xFFFFF6F2),
    tertiary = Color(0xFFFFBD59),
    onTertiary = Color(0xFF5C3B00),
    tertiaryContainer = Color(0xFFFAAC21),
    onTertiaryContainer = Color(0xFF4F3300),
    background = Color(0xFF2F1100),
    onBackground = Color(0xFFFFDFCE),
    surface = Color(0xFF1D0800),
    onSurface = Color(0xFFFFDFCE),
    surfaceVariant = Color(0xFF421B00),
    onSurfaceVariant = Color(0xFFDA9E79),
    surfaceTint = Color(0xFFFF9062),
    inverseSurface = Color(0xFFFFF8F5),
    inverseOnSurface = Color(0xFF794A2B),
    error = Color(0xFFFF716C),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF9F0519),
    onErrorContainer = Color(0xFFFFA8A3),
    outline = Color(0xFF9E6948),
    outlineVariant = Color(0xFF693D20),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF4B2000),
    surfaceContainer = Color(0xFF2F1100),
    surfaceContainerHigh = Color(0xFF381600),
    surfaceContainerHighest = Color(0xFF421B00),
    surfaceContainerLow = Color(0xFF250C00),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF1D0800),
    primaryFixed = Color(0xFFFF793E),
    primaryFixedDim = Color(0xFFF7661F),
    onPrimaryFixed = Color(0xFF000000),
    onPrimaryFixedVariant = Color(0xFF521900),
    secondaryFixed = Color(0xFFFFC5A4),
    secondaryFixedDim = Color(0xFFFFB284),
    onSecondaryFixed = Color(0xFF582600),
    onSecondaryFixedVariant = Color(0xFF833C00),
    tertiaryFixed = Color(0xFFFAAC21),
    tertiaryFixedDim = Color(0xFFEA9F0C),
    onTertiaryFixed = Color(0xFF331F00),
    onTertiaryFixedVariant = Color(0xFF5B3B00),
)

/** Material 3 Expressive: its motion and shapes, in the warm scheme. */
@Composable
fun ActualiTheme(appearance: AppearanceMode = AppearanceMode.System, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppearanceMode.System -> isSystemInDarkTheme()
        AppearanceMode.Light -> false
        AppearanceMode.Dark -> true
    }
    MaterialExpressiveTheme(colorScheme = if (dark) WarmDark else WarmLight, motionScheme = MotionScheme.expressive()) {
        SystemBarsAppearance()
        content()
    }
}

/**
 * Dark system-bar icons over the light theme, light over the dark — for the
 * activity's window, or a sheet's own when called inside one. The app's
 * appearance setting can differ from the system's, so this follows the theme.
 */
@Composable
fun SystemBarsAppearance() {
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.isDark
    val window = (view.parent as? DialogWindowProvider)?.window ?: (view.context as? Activity)?.window
    SideEffect {
        window ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f

// Status colors. Negative is the scheme's own error role; positive, warning
// and info are Material custom colors — tonal palettes at the custom-color
// tones (40/100/90/10 light, 80/20/30/90 dark). Green and blue are harmonized
// toward the seed; amber isn't, or it would drift into the orange primary.

/** Money in, available, on track. */
val ColorScheme.positive: Color get() = if (isDark) Color(0xFF96D866) else Color(0xFF336B00)

/** Glyphs on a [positive] fill. */
val ColorScheme.onPositive: Color get() = if (isDark) Color(0xFF173800) else Color.White

/** Overspent, money out of a negative balance. */
val ColorScheme.negative: Color get() = error

/** Due soon, needs attention. */
val ColorScheme.warning: Color get() = if (isDark) Color(0xFFFFB957) else Color(0xFF835400)

/** In progress, informational. */
val ColorScheme.info: Color get() = if (isDark) Color(0xFFBCC2FF) else Color(0xFF3E50D0)

val ColorScheme.positiveContainer: Color get() = if (isDark) Color(0xFF255100) else Color(0xFFB1F57F)
val ColorScheme.onPositiveContainer: Color get() = if (isDark) Color(0xFFB1F57F) else Color(0xFF0B2000)
val ColorScheme.warningContainer: Color get() = if (isDark) Color(0xFF643F00) else Color(0xFFFFDDB5)
val ColorScheme.onWarningContainer: Color get() = if (isDark) Color(0xFFFFDDB5) else Color(0xFF2A1800)
val ColorScheme.negativeContainer: Color get() = errorContainer
val ColorScheme.onNegativeContainer: Color get() = onErrorContainer
