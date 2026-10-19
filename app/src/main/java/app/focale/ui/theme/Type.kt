package app.focale.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.focale.R

/**
 * Google Sans Flex (OFL), police variable embarquée : axes de graisse, de largeur et
 * d'arrondi (`ROND`). Les titres utilisent la variante arrondie, signature de
 * Material 3 Expressive ; le texte courant reste droit pour la lisibilité.
 */
@OptIn(ExperimentalTextApi::class)
private fun googleSansFlex(roundness: Float): FontFamily = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map { weight ->
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.Setting("ROND", roundness),
            ),
        )
    },
)

val FocaleDisplayFamily: FontFamily = googleSansFlex(roundness = 100f)
val FocaleTextFamily: FontFamily = googleSansFlex(roundness = 0f)

private fun TextStyle.display(weight: FontWeight? = null) =
    copy(fontFamily = FocaleDisplayFamily, fontWeight = weight ?: fontWeight)

private fun TextStyle.text() = copy(fontFamily = FocaleTextFamily)

private val Base = Typography()

val FocaleTypography: Typography = Typography(
    displayLarge = Base.displayLarge.display(FontWeight.Medium),
    displayMedium = Base.displayMedium.display(FontWeight.Medium),
    displaySmall = Base.displaySmall.display(FontWeight.Medium),
    headlineLarge = Base.headlineLarge.display(FontWeight.Medium),
    headlineMedium = Base.headlineMedium.display(FontWeight.Medium),
    headlineSmall = Base.headlineSmall.display(FontWeight.Medium),
    titleLarge = Base.titleLarge.display(FontWeight.Medium),
    titleMedium = Base.titleMedium.display(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.display(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.text(),
    bodyMedium = Base.bodyMedium.text(),
    bodySmall = Base.bodySmall.text(),
    labelLarge = Base.labelLarge.display(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.display(FontWeight.SemiBold),
    labelSmall = Base.labelSmall.display(FontWeight.SemiBold),
    displayLargeEmphasized = Base.displayLargeEmphasized.display(FontWeight.Bold),
    displayMediumEmphasized = Base.displayMediumEmphasized.display(FontWeight.Bold),
    displaySmallEmphasized = Base.displaySmallEmphasized.display(FontWeight.Bold),
    headlineLargeEmphasized = Base.headlineLargeEmphasized.display(FontWeight.Bold),
    headlineMediumEmphasized = Base.headlineMediumEmphasized.display(FontWeight.Bold),
    headlineSmallEmphasized = Base.headlineSmallEmphasized.display(FontWeight.Bold),
    titleLargeEmphasized = Base.titleLargeEmphasized.display(FontWeight.Bold),
    titleMediumEmphasized = Base.titleMediumEmphasized.display(FontWeight.Bold),
    titleSmallEmphasized = Base.titleSmallEmphasized.display(FontWeight.Bold),
    bodyLargeEmphasized = Base.bodyLargeEmphasized.text(),
    bodyMediumEmphasized = Base.bodyMediumEmphasized.text(),
    bodySmallEmphasized = Base.bodySmallEmphasized.text(),
    labelLargeEmphasized = Base.labelLargeEmphasized.display(FontWeight.Bold),
    labelMediumEmphasized = Base.labelMediumEmphasized.display(FontWeight.Bold),
    labelSmallEmphasized = Base.labelSmallEmphasized.display(FontWeight.Bold),
)
