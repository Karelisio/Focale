package app.focale.edit

import androidx.compose.runtime.Immutable

/**
 * Rectangle normalisé (0..1) dans le cadre de l'image après rotation et miroir ; le
 * redressement fait pivoter l'image dans ce cadre, qui ne change pas.
 */
@Immutable
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val Full = NormRect(0f, 0f, 1f, 1f)
    }
}

/** Format de recadrage ; [ratio] = largeur / hauteur en pixels, null = libre. */
enum class CropAspect(val ratio: Float?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_THREE(4f / 3f),
    THREE_TWO(3f / 2f),
    SIXTEEN_NINE(16f / 9f),
    NINE_SIXTEEN(9f / 16f),
}

/** Préréglages de filtre (matrices dans [ColorMatrices.preset]). */
enum class FilterPreset { NONE, VIVID, MONO, SEPIA, WARM, COOL, VINTAGE, DRAMATIC, FADE, NOIR }

/** Réglages fins, chacun de -1 à 1 (0 = neutre). */
enum class Adjustment { BRIGHTNESS, CONTRAST, SATURATION, WARMTH }

/**
 * État complet d'une édition, appliqué dans cet ordre : rotation par quarts de tour,
 * miroir horizontal, redressement, recadrage, filtre puis réglages. Le redressement
 * ([straighten], en degrés, sens horaire, de -45 à 45) fait pivoter l'image autour du
 * centre de son cadre ; [crop] reste dans l'image redressée ([StraightenMath]). Les traits
 * de dessin ([strokes]), exprimés comme [crop] dans ce cadre, sont tracés par-dessus, sans
 * filtre.
 */
@Immutable
data class EditState(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val straighten: Float = 0f,
    val crop: NormRect = NormRect.Full,
    val aspect: CropAspect = CropAspect.FREE,
    val filter: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val strokes: List<DrawingStroke> = emptyList(),
) {
    val isIdentity: Boolean get() = this == EditState(aspect = aspect)

    val hasGeometry: Boolean get() = quarterTurns % 4 != 0 || flipHorizontal || straighten != 0f || crop != NormRect.Full

    fun value(adjustment: Adjustment): Float = when (adjustment) {
        Adjustment.BRIGHTNESS -> brightness
        Adjustment.CONTRAST -> contrast
        Adjustment.SATURATION -> saturation
        Adjustment.WARMTH -> warmth
    }

    fun with(adjustment: Adjustment, value: Float): EditState {
        val v = value.coerceIn(-1f, 1f)
        return when (adjustment) {
            Adjustment.BRIGHTNESS -> copy(brightness = v)
            Adjustment.CONTRAST -> copy(contrast = v)
            Adjustment.SATURATION -> copy(saturation = v)
            Adjustment.WARMTH -> copy(warmth = v)
        }
    }

    /** Matrice de couleur finale (filtre puis réglages), au format Android 4 × 5. */
    fun colorMatrix(): FloatArray = ColorMatrices.compose(
        ColorMatrices.adjustments(brightness, contrast, saturation, warmth),
        ColorMatrices.preset(filter),
    )
}

/** Mode d'enregistrement. */
enum class SaveMode { COPY, REPLACE }
