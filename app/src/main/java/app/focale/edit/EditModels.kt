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
 * centre de son cadre ; [crop] reste dans l'image redressée ([StraightenMath]). Les zones
 * floutées ([blurs]) remplacent ensuite des pixels de la photo : attachées à elle, elles
 * pivotent avec son redressement ([BlurArea]) et prennent ses couleurs. Les traits de
 * dessin ([strokes]) puis les textes ([texts]), exprimés comme [crop] dans le cadre, sont
 * tracés par-dessus, sans filtre : un texte posé sur une zone floutée reste net.
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
    val texts: List<TextItem> = emptyList(),
    val blurs: List<BlurRegion> = emptyList(),
) {
    val isIdentity: Boolean get() = this == EditState(aspect = aspect)

    val hasGeometry: Boolean get() = quarterTurns % 4 != 0 || flipHorizontal || straighten != 0f || crop != NormRect.Full

    /**
     * Modifications par rapport à l'original, que « Tout annuler » retire : rotation et miroir,
     * redressement, recadrage, filtre et chaque réglage comptent pour une, chaque trait, texte et
     * zone floutée aussi. Nul si et seulement si [isIdentity].
     */
    val changeCount: Int
        get() = listOf(
            quarterTurns % 4 != 0 || flipHorizontal,
            straighten != 0f,
            crop != NormRect.Full,
            filter != FilterPreset.NONE,
            brightness != 0f,
            contrast != 0f,
            saturation != 0f,
            warmth != 0f,
        ).count { it } + strokes.size + texts.size + blurs.size

    /**
     * Quart de tour horaire de l'image affichée. Rotation et miroir ne commutent pas : avec
     * miroir, il correspond à un quart de tour antihoraire de l'original. Recadrage, traits,
     * textes et zones, exprimés dans le repère affiché, tournent avec lui ; le redressement,
     * rotation lui aussi, ne change pas.
     */
    fun rotated(): EditState = copy(
        quarterTurns = Math.floorMod(if (flipHorizontal) quarterTurns - 1 else quarterTurns + 1, 4),
        crop = CropMath.rotateClockwise(crop),
        strokes = strokes.map(DrawingStroke::rotateClockwise),
        texts = texts.map(TextItem::rotateClockwise),
        blurs = blurs.map(BlurRegion::rotateClockwise),
    )

    /**
     * Miroir horizontal de l'image affichée : le redressement tourne alors dans l'autre sens ;
     * les lettres des textes, elles, ne sont pas retournées ([TextItem.flipHorizontal]).
     */
    fun mirrored(): EditState = copy(
        flipHorizontal = !flipHorizontal,
        straighten = StraightenMath.clamp(-straighten),
        crop = CropMath.flipHorizontal(crop),
        strokes = strokes.map(DrawingStroke::flipHorizontal),
        texts = texts.map(TextItem::flipHorizontal),
        blurs = blurs.map(BlurRegion::flipHorizontal),
    )

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
