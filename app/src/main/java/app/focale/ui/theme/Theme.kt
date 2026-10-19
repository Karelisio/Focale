package app.focale.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.animateColorScheme

/** Couleur de marque, utilisée quand les couleurs dynamiques ne sont pas disponibles. */
val FocaleSeed = Color(0xFFE8603C)

/** Paramètres de thème résolus, transmis aux thèmes imbriqués (photo, visionneuse). */
@Immutable
data class ThemeConfig(
    val darkTheme: Boolean,
    val amoled: Boolean,
    val colorFromPhoto: Boolean,
)

val LocalThemeConfig = staticCompositionLocalOf { ThemeConfig(darkTheme = false, amoled = false, colorFromPhoto = true) }

/** Thème racine : Material You dynamique + Expressive (formes, typo, mouvement à ressorts). */
@Composable
fun FocaleTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val amoled = darkTheme && settings.amoled
    val context = LocalContext.current
    val useDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = remember(darkTheme, amoled, useDynamic, context) {
        val base = when {
            useDynamic && darkTheme -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> seededColorScheme(FocaleSeed, darkTheme, PaletteStyle.TonalSpot)
        }
        if (amoled) base.toAmoled() else base
    }

    CompositionLocalProvider(
        LocalThemeConfig provides ThemeConfig(darkTheme, amoled, settings.colorFromPhoto),
    ) {
        MaterialExpressiveTheme(
            colorScheme = animateColorScheme(colorScheme),
            motionScheme = MotionScheme.expressive(),
            shapes = FocaleShapes,
            typography = FocaleTypography,
            content = content,
        )
    }
}

/**
 * Thème « couleur depuis la photo » : régénère un schéma complet à partir de la
 * couleur dominante [seed] et l'anime à chaque changement de photo.
 *
 * @param forceDark la visionneuse reste sombre quel que soit le thème de l'application.
 */
@Composable
fun PhotoTheme(
    seed: Color?,
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val config = LocalThemeConfig.current
    val dark = forceDark || config.darkTheme
    val baseScheme = MaterialTheme.colorScheme
    val target = remember(seed, dark, config.amoled, config.colorFromPhoto, baseScheme) {
        when {
            seed != null && config.colorFromPhoto ->
                seededColorScheme(seed, dark, PaletteStyle.Vibrant).let { if (dark && config.amoled) it.toAmoled() else it }
            forceDark && !config.darkTheme -> seededColorScheme(FocaleSeed, true, PaletteStyle.TonalSpot)
            else -> baseScheme
        }
    }
    MaterialExpressiveTheme(
        colorScheme = animateColorScheme(target),
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

fun seededColorScheme(seed: Color, dark: Boolean, style: PaletteStyle): ColorScheme =
    dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        style = style,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )

/** Variante AMOLED : fonds noirs, conteneurs assombris en gardant leur teinte. */
fun ColorScheme.toAmoled(): ColorScheme {
    fun Color.deepen(fraction: Float) = lerp(this, Color.Black, fraction)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = surfaceContainerLow.deepen(0.7f),
        surfaceContainer = surfaceContainer.deepen(0.6f),
        surfaceContainerHigh = surfaceContainerHigh.deepen(0.5f),
        surfaceContainerHighest = surfaceContainerHighest.deepen(0.4f),
        surfaceBright = surfaceBright.deepen(0.3f),
    )
}
