package app.focale.edit

/**
 * Matrices de couleur 4 × 5 (convention `android.graphics.ColorMatrix`, valeurs 0..255 pour
 * les translations). Les mêmes matrices servent à l'aperçu Compose et au rendu final, ce
 * qui garantit un résultat enregistré identique à ce qui est affiché.
 */
object ColorMatrices {

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    val identity: FloatArray
        get() = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

    /** Composition `a ∘ b` : applique d'abord [b], puis [a]. */
    fun compose(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
                if (col == 4) sum += a[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    fun saturation(amount: Float): FloatArray {
        val inv = 1f - amount
        val r = LR * inv
        val g = LG * inv
        val b = LB * inv
        return floatArrayOf(
            r + amount, g, b, 0f, 0f,
            r, g + amount, b, 0f, 0f,
            r, g, b + amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Contraste autour du gris moyen : [scale] 1 = neutre. */
    fun contrast(scale: Float): FloatArray {
        val t = 128f * (1f - scale)
        return floatArrayOf(
            scale, 0f, 0f, 0f, t,
            0f, scale, 0f, 0f, t,
            0f, 0f, scale, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun brightness(offset: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, offset,
        0f, 1f, 0f, 0f, offset,
        0f, 0f, 1f, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Remonte les noirs (aspect « délavé »). */
    fun lift(amount: Float): FloatArray {
        val s = 1f - amount / 255f
        return floatArrayOf(
            s, 0f, 0f, 0f, amount,
            0f, s, 0f, 0f, amount,
            0f, 0f, s, 0f, amount,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private val sepia: FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Réglages fins, chacun de -1 à 1. */
    fun adjustments(brightness: Float, contrast: Float, saturation: Float, warmth: Float): FloatArray {
        var m = identity
        if (warmth != 0f) m = compose(scale(1f + 0.12f * warmth, 1f + 0.02f * warmth, 1f - 0.12f * warmth), m)
        if (saturation != 0f) m = compose(saturation(1f + saturation), m)
        if (contrast != 0f) m = compose(contrast(1f + 0.6f * contrast), m)
        if (brightness != 0f) m = compose(brightness(brightness * 70f), m)
        return m
    }

    fun preset(filter: FilterPreset): FloatArray = when (filter) {
        FilterPreset.NONE -> identity
        FilterPreset.VIVID -> compose(contrast(1.08f), saturation(1.35f))
        FilterPreset.MONO -> compose(contrast(1.1f), saturation(0f))
        FilterPreset.SEPIA -> compose(contrast(1.05f), sepia)
        FilterPreset.WARM -> compose(scale(1.1f, 1.02f, 0.88f), saturation(1.1f))
        FilterPreset.COOL -> compose(scale(0.92f, 1.0f, 1.1f), saturation(0.95f))
        FilterPreset.VINTAGE -> compose(lift(28f), compose(scale(1.06f, 1.0f, 0.86f), compose(contrast(0.88f), saturation(0.75f))))
        FilterPreset.DRAMATIC -> compose(contrast(1.35f), saturation(0.8f))
        FilterPreset.FADE -> compose(lift(40f), compose(contrast(0.85f), saturation(0.85f)))
        FilterPreset.NOIR -> compose(contrast(1.45f), saturation(0f))
    }

    /** Applique la matrice à une couleur RGB 0..255 (tests, aperçu de pixels). */
    fun apply(matrix: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun row(i: Int) = (matrix[i * 5] * r + matrix[i * 5 + 1] * g + matrix[i * 5 + 2] * b + matrix[i * 5 + 4]).coerceIn(0f, 255f)
        return Triple(row(0), row(1), row(2))
    }
}
